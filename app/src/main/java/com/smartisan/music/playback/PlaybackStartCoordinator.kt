package com.smartisan.music.playback

import android.os.Bundle
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.isOnlineMediaItem
import com.smartisan.music.data.online.shouldRefreshOnlinePlaybackUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 起播/替换队列的协调器：把会话命令里的条目解析成可播放队列并交给播放器，
 * 同时保证同一时刻只有一次起播在途（新请求作废旧请求）。
 */
internal class PlaybackStartCoordinator(
    private val scope: CoroutineScope,
    private val onlineMusicRepository: OnlineMusicRepositoryRouter,
    private val playerProvider: () -> Player?,
    private val loadLocalItemsByIds: (List<String>) -> List<MediaItem>,
    private val fadeController: PlaybackStartFadeController,
) {
    private var pendingPlaybackStartJob: Job? = null
    private var pendingPlaybackStartFuture: SettableFuture<SessionResult>? = null
    private val playbackStartRequestGeneration = AtomicLong()

    fun resolveSessionPlaybackMediaItems(mediaItems: List<MediaItem>): MutableList<MediaItem> {
        mediaItems.resolveDirectSessionPlaybackItemsOrNull()?.let { directItems ->
            return directItems
        }
        val localItemsById = loadLocalItemsByIds(
            mediaItems
                .filterNot(MediaItem::canResolveDirectSessionPlaybackItem)
                .map(MediaItem::mediaId),
        ).associateBy(MediaItem::mediaId)
        return mediaItems.mapNotNullTo(mutableListOf()) { item ->
            item.toDirectSessionPlaybackItemOrNull() ?: localItemsById[item.mediaId]
        }
    }

    /**
     * 起播前的解析：仅对「即将播放」的在线条目同步解析出真实 URL（15 分钟有效期），
     * 队列其余在线条目保持占位 URI，轮到播放时再按需解析，避免一次性打爆接口。
     */
    private suspend fun resolveSessionPlaybackMediaItemsForPlaybackStart(
        mediaItems: List<MediaItem>,
        startIndex: Int,
    ): MutableList<MediaItem> {
        val resolvedItems = resolveSessionPlaybackMediaItems(mediaItems)
        val startItem = resolvedItems.getOrNull(startIndex) ?: return resolvedItems
        if (!startItem.isOnlineMediaItem() || !startItem.shouldRefreshOnlinePlaybackUrl()) {
            return resolvedItems
        }
        val playableStartItem = onlineMusicRepository.resolvePlayableMediaItem(
            mediaItem = startItem,
            includeLyrics = false,
            forceRefresh = false,
        ) ?: return resolvedItems
        resolvedItems[startIndex] = playableStartItem
        return resolvedItems
    }

    private fun replaceResolvedQueueAndPlay(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        shuffleModeEnabled: Boolean,
    ): SessionResult {
        val safeStartIndex = playbackQueueStartIndex(
            itemCount = mediaItems.size,
            startIndex = startIndex,
        ) ?: return SessionResult(SessionError.ERROR_BAD_VALUE)
        val playbackPlayer = playerProvider() ?: return SessionResult(SessionError.ERROR_UNKNOWN)
        fadeController.protectNextPlayback(playbackPlayer)
        playbackPlayer.replaceQueueAndPlayDirect(
            mediaItems = mediaItems,
            startIndex = safeStartIndex,
            shuffleModeEnabled = shuffleModeEnabled,
        )
        return SessionResult(SessionResult.RESULT_SUCCESS)
    }

    fun replaceQueueAndPlayFromSessionCommand(args: Bundle): ListenableFuture<SessionResult> {
        val mediaItems = args.decodeReplaceQueueAndPlayMediaItems()
        val startIndex = args.getInt(ReplaceQueueStartIndexKey, 0)
        val shuffleModeEnabled = args.getBoolean(ReplaceQueueShuffleModeKey, false)
        val safeStartIndex = playbackQueueStartIndex(
            itemCount = mediaItems.size,
            startIndex = startIndex,
        ) ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val requestGeneration = playbackStartRequestGeneration.incrementAndGet()
        cancelPendingPlaybackStart()

        val resultFuture = SettableFuture.create<SessionResult>()
        pendingPlaybackStartFuture = resultFuture
        lateinit var startJob: Job
        startJob = scope.launch {
            val result = try {
                val resolvedItems = withContext(AppDispatchers.IO) {
                    resolveSessionPlaybackMediaItemsForPlaybackStart(
                        mediaItems = mediaItems,
                        startIndex = safeStartIndex,
                    )
                }
                if (playbackStartRequestGeneration.get() != requestGeneration) {
                    SessionResult(SessionResult.RESULT_SUCCESS)
                } else {
                    replaceResolvedQueueAndPlay(
                        mediaItems = resolvedItems,
                        startIndex = safeStartIndex,
                        shuffleModeEnabled = shuffleModeEnabled,
                    )
                }
            } catch (_: CancellationException) {
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch (error: Exception) {
                Log.w(
                    PlaybackDiagnosticsTag,
                    "Playback start resolution failed type=${error.javaClass.simpleName} " +
                        "message=${error.message}",
                )
                SessionResult(SessionError.ERROR_UNKNOWN)
            }
            if (!resultFuture.isDone) {
                resultFuture.set(result)
            }
            if (pendingPlaybackStartJob === startJob) {
                pendingPlaybackStartJob = null
                pendingPlaybackStartFuture = null
            }
        }
        pendingPlaybackStartJob = startJob
        resultFuture.addListener(
            {
                if (resultFuture.isCancelled) {
                    startJob.cancel()
                }
            },
            MoreExecutors.directExecutor(),
        )
        return resultFuture
    }

    fun cancelPendingPlaybackStart() {
        pendingPlaybackStartJob?.cancel()
        pendingPlaybackStartJob = null
        pendingPlaybackStartFuture
            ?.takeUnless(SettableFuture<SessionResult>::isDone)
            ?.set(SessionResult(SessionResult.RESULT_SUCCESS))
        pendingPlaybackStartFuture = null
    }
}
