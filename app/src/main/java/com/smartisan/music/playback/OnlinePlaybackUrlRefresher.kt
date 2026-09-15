package com.smartisan.music.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.isNeteasePreviewDuration
import com.smartisan.music.data.online.isOnlineMediaItem
import com.smartisan.music.data.online.shouldRefreshOnlinePlaybackUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 在线条目 URL 的按需刷新：错误重解析、试听片段播完后重解析、切歌时预刷新相邻条目。
 * 所有刷新共用一条在途任务与 30 秒冷却，避免错误风暴打爆接口。
 */
internal class OnlinePlaybackUrlRefresher(
    private val scope: CoroutineScope,
    private val onlineMusicRepository: OnlineMusicRepositoryRouter,
    private val playerProvider: () -> Player?,
) {
    private var onlineMediaRefreshJob: Job? = null
    private var onlineMediaRefreshJobForceRefresh = false
    private var lastOnlineMediaRefreshKey: String? = null
    private var lastOnlineMediaRefreshAtMs: Long = 0L

    /**
     * 在线条目播放出错（URL 过期/解析失败）后强制重解析；
     * 解析仍失败则按队列情况自动跳下一首。
     */
    fun refreshCurrentOnlineMediaUrlAfterError() {
        val playbackPlayer = playerProvider() ?: return
        val currentItem = playbackPlayer.currentMediaItem ?: return
        if (!currentItem.isOnlineMediaItem()) {
            return
        }

        val refreshKey = currentItem.mediaId.takeIf(String::isNotBlank) ?: return
        if (!recordOnlineMediaRefreshAttempt(refreshKey)) {
            return
        }

        resolveOnlineMediaItemAt(
            item = currentItem,
            itemIndex = playbackPlayer.currentMediaItemIndex,
            resumePositionMs = playbackPlayer.currentPosition.coerceAtLeast(0L),
            resumePlayback = playbackPlayer.playWhenReady,
            prepareAfterReplace = true,
            forceRefresh = true,
            skipOnFailure = true,
        )
    }

    /** 试听片段播完时按完整曲目重新解析（试听时长命中网易云预览特征才触发）。 */
    fun refreshCurrentOnlineMediaUrlAfterPreviewEnd() {
        val playbackPlayer = playerProvider() ?: return
        val currentItem = playbackPlayer.currentMediaItem ?: return
        if (!currentItem.isOnlineMediaItem()) {
            return
        }
        val originalDurationMs = currentItem.mediaMetadata.durationMs ?: return
        val playedDurationMs = playbackPlayer.duration
            .takeIf { duration -> duration > 0L && duration != C.TIME_UNSET }
            ?: playbackPlayer.currentPosition.coerceAtLeast(0L)
        if (!isNeteasePreviewDuration(playedDurationMs, originalDurationMs)) {
            return
        }
        val refreshKey = currentItem.mediaId.takeIf(String::isNotBlank) ?: return
        if (!recordOnlineMediaRefreshAttempt(refreshKey)) {
            return
        }
        resolveOnlineMediaItemAt(
            item = currentItem,
            itemIndex = playbackPlayer.currentMediaItemIndex,
            resumePositionMs = 0L,
            resumePlayback = true,
            prepareAfterReplace = true,
            forceRefresh = true,
        )
    }

    /** 切歌时检查当前/下一首在线条目的 URL 是否已过期（15 分钟），过期则提前重新解析。 */
    fun resolveAdjacentOnlineMediaItem() {
        val playbackPlayer = playerProvider() ?: return
        val currentIndex = playbackPlayer.currentMediaItemIndex
        if (currentIndex == C.INDEX_UNSET) {
            return
        }
        val currentItem = playbackPlayer.currentMediaItem
        if (
            currentItem?.isOnlineMediaItem() == true &&
            currentItem.shouldRefreshOnlinePlaybackUrl()
        ) {
            if (currentItem.localConfiguration?.uri != null) {
                val refreshKey = currentItem.mediaId.takeIf(String::isNotBlank) ?: return
                if (!recordOnlineMediaRefreshAttempt(refreshKey)) {
                    return
                }
            }
            resolveOnlineMediaItemAt(
                item = currentItem,
                itemIndex = currentIndex,
                resumePositionMs = playbackPlayer.currentPosition.coerceAtLeast(0L),
                resumePlayback = playbackPlayer.playWhenReady,
                prepareAfterReplace = true,
                forceRefresh = false,
            )
            return
        }

        val nextIndex = currentIndex + 1
        if (nextIndex !in 0 until playbackPlayer.mediaItemCount) {
            return
        }
        val nextItem = playbackPlayer.getMediaItemAt(nextIndex)
        if (!nextItem.isOnlineMediaItem() || !nextItem.shouldRefreshOnlinePlaybackUrl()) {
            return
        }
        resolveOnlineMediaItemAt(
            item = nextItem,
            itemIndex = nextIndex,
            resumePositionMs = 0L,
            resumePlayback = false,
            prepareAfterReplace = false,
            forceRefresh = false,
        )
    }

    /**
     * 重新解析在线条目并原位替换队列里的 MediaItem；若是当前曲目则恢复进度并续播，
     * 完成后继续预解析下一首。带 30 秒冷却（[recordOnlineMediaRefreshAttempt]）防错误风暴。
     */
    private fun resolveOnlineMediaItemAt(
        item: MediaItem,
        itemIndex: Int,
        resumePositionMs: Long,
        resumePlayback: Boolean,
        prepareAfterReplace: Boolean,
        forceRefresh: Boolean,
        skipOnFailure: Boolean = false,
    ) {
        val activeRefreshJob = onlineMediaRefreshJob
        if (activeRefreshJob?.isActive == true) {
            if (!forceRefresh || onlineMediaRefreshJobForceRefresh) {
                return
            }
            activeRefreshJob.cancel()
        }
        if (!item.isOnlineMediaItem()) {
            return
        }
        if (!forceRefresh && !item.shouldRefreshOnlinePlaybackUrl()) {
            return
        }
        var resolveAdjacentAfterCompletion = false
        val refreshJob = scope.launch {
            val refreshedItem = try {
                onlineMusicRepository.resolvePlayableMediaItem(
                    mediaItem = item,
                    includeLyrics = false,
                    forceRefresh = forceRefresh,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(
                    PlaybackDiagnosticsTag,
                    "Online media refresh failed type=${error.javaClass.simpleName} " +
                        "message=${error.message}",
                )
                null
            }
            if (refreshedItem == null) {
                if (skipOnFailure) {
                    skipCurrentOnlineMediaItemAfterError(item.mediaId)
                }
                return@launch
            }
            val activePlayer = playerProvider() ?: return@launch
            val targetIndex = itemIndex.takeIf { it in 0 until activePlayer.mediaItemCount }
                ?: return@launch
            if (activePlayer.getMediaItemAt(targetIndex).mediaId != item.mediaId) {
                return@launch
            }
            activePlayer.replaceMediaItem(targetIndex, refreshedItem)
            if (activePlayer.currentMediaItemIndex == targetIndex) {
                val targetPositionMs = if (prepareAfterReplace) {
                    resumePositionMs
                } else {
                    activePlayer.currentPosition.coerceAtLeast(0L)
                }
                val targetPlayWhenReady = if (prepareAfterReplace) {
                    resumePlayback
                } else {
                    activePlayer.playWhenReady
                }
                activePlayer.seekTo(targetIndex, targetPositionMs)
                activePlayer.prepare()
                activePlayer.playWhenReady = targetPlayWhenReady
                if (targetPlayWhenReady) {
                    activePlayer.play()
                }
                resolveAdjacentAfterCompletion = true
            }
        }
        onlineMediaRefreshJob = refreshJob
        onlineMediaRefreshJobForceRefresh = forceRefresh
        refreshJob.invokeOnCompletion {
            if (onlineMediaRefreshJob === refreshJob) {
                onlineMediaRefreshJob = null
                onlineMediaRefreshJobForceRefresh = false
            }
            if (resolveAdjacentAfterCompletion) {
                scope.launch {
                    resolveAdjacentOnlineMediaItem()
                }
            }
        }
    }

    /** 在线条目重解析失败后自动跳到下一首（非单曲循环且有下一首时）。 */
    private fun skipCurrentOnlineMediaItemAfterError(mediaId: String) {
        val playbackPlayer = playerProvider() ?: return
        val currentItem = playbackPlayer.currentMediaItem ?: return
        if (currentItem.mediaId != mediaId) {
            return
        }
        if (
            !shouldSkipOnlinePlaybackError(
                isCurrentOnline = currentItem.isOnlineMediaItem(),
                hasNextMediaItem = playbackPlayer.hasNextMediaItem(),
                repeatMode = playbackPlayer.repeatMode,
            )
        ) {
            return
        }
        val resumePlayback = playbackPlayer.playWhenReady
        playbackPlayer.seekToNextMediaItem()
        playbackPlayer.prepare()
        if (resumePlayback) {
            playbackPlayer.play()
        }
    }

    private fun recordOnlineMediaRefreshAttempt(refreshKey: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (
            lastOnlineMediaRefreshKey == refreshKey &&
            now - lastOnlineMediaRefreshAtMs < OnlineMediaRefreshCooldownMs
        ) {
            return false
        }
        lastOnlineMediaRefreshKey = refreshKey
        lastOnlineMediaRefreshAtMs = now
        return true
    }

    fun cancel() {
        onlineMediaRefreshJob?.cancel()
        onlineMediaRefreshJob = null
    }

    private companion object {
        private const val OnlineMediaRefreshCooldownMs = 30_000L
    }
}
