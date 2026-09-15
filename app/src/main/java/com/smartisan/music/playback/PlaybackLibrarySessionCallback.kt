@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 媒体库会话回调：曲库浏览（root/children/item/addMediaItems）与自定义命令（搓碟、睡眠定时、
 * 刷新曲库、评分、整队列替换）。原先作为 [PlaybackService] 的 inner class，搬到顶层后
 * 服务侧能力一律以构造参数注入，逻辑保持不变。
 */
internal class PlaybackLibrarySessionCallback(
    private val packageName: String,
    private val localAudioLibrary: LocalAudioLibrary,
    private val libraryExecutor: ListeningExecutorService,
    private val libraryRefreshExecutor: ListeningExecutorService,
    private val serviceScope: CoroutineScope,
    private val currentPlayer: () -> ExoPlayer?,
    private val currentLibrarySession: () -> MediaLibraryService.MediaLibrarySession?,
    private val currentSessionStateCoordinator: () -> PlaybackSessionStateCoordinator?,
    private val audioItemsLoader: (Boolean) -> List<MediaItem>,
    private val audioPermissionChecker: () -> Boolean,
    private val audioItemsByIdsLoader: (List<String>) -> List<MediaItem>,
    private val onlineLibraryItemLoader: (OnlineTrackIdentity) -> ListenableFuture<LibraryResult<MediaItem>>,
    private val sessionPlaybackItemsResolver: (List<MediaItem>) -> MutableList<MediaItem>,
    private val replaceQueueAndPlayCommandHandler: (Bundle) -> ListenableFuture<SessionResult>,
    private val trackRatingCommandHandler: (String, Int) -> ListenableFuture<SessionResult>,
) : MediaLibraryService.MediaLibrarySession.Callback {

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        // 仅放行系统可信控制器（系统 UI/蓝牙/车机）与本应用自身，其余第三方一律拒绝，
        // 防止任意 App 遍历曲库并劫持播放。
        val isSelf = controller.packageName == packageName
        if (!controller.isTrusted && !isSelf) {
            return MediaSession.ConnectionResult.reject()
        }
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(ScratchSeekModeCommand)
            .add(StartSleepTimerCommand)
            .add(CancelSleepTimerCommand)
            .add(RefreshLibraryCommand)
            .add(InvalidateLibraryCommand)
            .add(SetTrackRatingCommand)
            .add(ReplaceQueueAndPlayCommand)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            .build()
    }

    override fun onGetLibraryRoot(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        return Futures.immediateFuture(
            LibraryResult.ofItem(localAudioLibrary.getRootItem(), params),
        )
    }

    override fun onGetChildren(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return libraryExecutor.submit<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId != LocalAudioLibrary.ROOT_ID) {
                return@submit LibraryResult.ofError(SessionError.ERROR_BAD_VALUE, params)
            }

            val items = audioItemsLoader(false)
            if (items.isEmpty() && !audioPermissionChecker()) {
                return@submit LibraryResult.ofError(
                    SessionError.ERROR_PERMISSION_DENIED,
                    params,
                )
            }

            val fromIndex = (page * pageSize).coerceAtMost(items.size)
            val toIndex = (fromIndex + pageSize).coerceAtMost(items.size)
            LibraryResult.ofItemList(items.subList(fromIndex, toIndex), params)
        }
    }

    override fun onGetItem(
        session: MediaLibraryService.MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // 在线条目不依赖本地媒体库权限，直接经 Router 拉取。
        // 它是一次网络调用，不能进 libraryExecutor：该 executor 只有一条线程，
        // 同步取详情会把排在后面的 onGetChildren/onAddMediaItems 全部堵住。
        mediaId.onlineTrackIdentityOrNull()?.let { identity ->
            return onlineLibraryItemLoader(identity)
        }
        return libraryExecutor.submit<LibraryResult<MediaItem>> {
            if (!audioPermissionChecker() && mediaId != LocalAudioLibrary.ROOT_ID) {
                return@submit LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED)
            }

            val item = if (mediaId == LocalAudioLibrary.ROOT_ID) {
                localAudioLibrary.getRootItem()
            } else {
                audioItemsByIdsLoader(listOf(mediaId)).firstOrNull()
            }
            if (item == null) {
                LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            } else {
                LibraryResult.ofItem(item, null)
            }
        }
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        mediaItems.resolveDirectSessionPlaybackItemsOrNull()?.let { resolvedItems ->
            return Futures.immediateFuture(resolvedItems)
        }
        return libraryExecutor.submit<MutableList<MediaItem>> {
            sessionPlaybackItemsResolver(mediaItems)
        }
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == ScratchSeekModeAction) {
            val enabled = args.getBoolean(ScratchSeekModeEnabledKey, false)
            currentPlayer()?.setSeekParameters(
                if (enabled) SeekParameters.EXACT else SeekParameters.DEFAULT,
            )
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (customCommand.customAction == StartSleepTimerAction) {
            val durationMs = args.getLong(SleepTimerDurationMsKey, 0L)
            if (durationMs <= 0L) {
                return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_BAD_VALUE),
                )
            }
            PlaybackSleepTimer.start(durationMs) {
                currentPlayer()?.pause()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (customCommand.customAction == CancelSleepTimerAction) {
            PlaybackSleepTimer.cancel()
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        if (customCommand.customAction == ReplaceQueueAndPlayAction) {
            return replaceQueueAndPlayCommandHandler(args)
        }
        if (customCommand.customAction == RefreshLibraryAction) {
            return libraryRefreshExecutor.submit<SessionResult> {
                if (!audioPermissionChecker()) {
                    return@submit SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                }

                val result = localAudioLibrary.refreshAudioItems()
                currentLibrarySession()?.notifyChildrenChanged(
                    LocalAudioLibrary.ROOT_ID,
                    result.items.size,
                    null,
                )
                serviceScope.launch {
                    currentSessionStateCoordinator()?.restoreIfQueueEmpty()
                }
                SessionResult(
                    if (result.successful) {
                        SessionResult.RESULT_SUCCESS
                    } else {
                        SessionError.ERROR_UNKNOWN
                    },
                )
            }
        }
        if (customCommand.customAction == InvalidateLibraryAction) {
            return libraryRefreshExecutor.submit<SessionResult> {
                if (!audioPermissionChecker()) {
                    return@submit SessionResult(SessionError.ERROR_PERMISSION_DENIED)
                }

                val items = audioItemsLoader(true)
                currentLibrarySession()?.notifyChildrenChanged(
                    LocalAudioLibrary.ROOT_ID,
                    items.size,
                    null,
                )
                serviceScope.launch {
                    currentSessionStateCoordinator()?.restoreIfQueueEmpty()
                }
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
        }
        if (customCommand.customAction == SetTrackRatingAction) {
            val mediaId = args.getString(TrackRatingMediaIdKey)?.trim().orEmpty()
            val score = args.getInt(TrackRatingScoreKey, -1)
            if (mediaId.isBlank() || score !in TrackRatingMinScore..TrackRatingMaxScore) {
                return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
            }
            return trackRatingCommandHandler(mediaId, score)
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }
}
