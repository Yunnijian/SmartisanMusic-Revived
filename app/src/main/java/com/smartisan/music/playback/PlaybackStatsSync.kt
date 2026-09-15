@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import androidx.media3.common.Player
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.smartisan.music.data.playback.PlaybackStatsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 播放统计/评分的写回与媒体库通知：评分写入串行落盘，随后去抖刷新曲库，
 * 让本地库的统计列在同一首歌连点多次时以最后一次意图为准。
 */
internal class PlaybackStatsSync(
    private val scope: CoroutineScope,
    private val libraryRefreshExecutor: ListeningExecutorService,
    private val statsRepository: PlaybackStatsRepository,
    private val localAudioLibrary: LocalAudioLibrary,
    private val playerProvider: () -> Player?,
    private val librarySessionProvider: () -> MediaLibraryService.MediaLibrarySession?,
) {
    private var pendingStatsLibraryRefreshJob: Job? = null
    private var pendingRatingLibraryRefreshJob: Job? = null

    /**
     * 评分写入仍在 `libraryRefreshExecutor` 上排队，保持迁移前的 FIFO 串行：连点评分时后一次的意图
     * 一定覆盖前一次。若把 Room 写丢给共享 IO 调度器，两条命令的提交顺序不确定，DB 终值可能停在
     * 上一次点击，而 UI 侧 `ratingOverrides` 只是乐观覆盖，进程重启后就再也兜不住。
     *
     * 这里去掉的只有「工作线程 `runBlocking(Dispatchers.Main.immediate)` 等主线程」这一跳：主线程在
     * onDestroy 里同步落盘、在 media3 的 OnHandler 上逐个执行 session 回调，两边交错会让整条刷新链
     * 停摆。改成把主线程那一跳交给 serviceScope，用 Future 的完成时机取代线程 park。
     */
    fun setTrackRatingFromSessionCommand(
        mediaId: String,
        score: Int,
    ): ListenableFuture<SessionResult> {
        val submitted = libraryRefreshExecutor.submit<ListenableFuture<SessionResult>> {
            val savedScore = runBlocking { statsRepository.setScore(mediaId, score) }
            if (savedScore == null) {
                Futures.immediateFuture(SessionResult(SessionError.ERROR_UNKNOWN))
            } else {
                val resultFuture = SettableFuture.create<SessionResult>()
                scope
                    .launch {
                        // 改队列 MediaItem 与去抖刷新都要碰 ExoPlayer，只能在主线程做。
                        val sessionResult = runCatching {
                            updateQueuedTrackRating(mediaId, savedScore)
                            scheduleRatingLibraryRefresh()
                            SessionResult(SessionResult.RESULT_SUCCESS)
                        }.getOrElse { SessionResult(SessionError.ERROR_UNKNOWN) }
                        resultFuture.set(sessionResult)
                    }
                    .invokeOnCompletion { cause ->
                        // 作用域已取消导致协程体根本没跑时，给出确定结果码而不是取消 Future。
                        if (cause != null) {
                            resultFuture.set(SessionResult(SessionError.ERROR_UNKNOWN))
                        }
                    }
                resultFuture
            }
        }
        return Futures.transformAsync(submitted, { it }, MoreExecutors.directExecutor())
    }

    fun scheduleStatsLibraryRefresh() {
        scope.launch(Dispatchers.Main.immediate) {
            pendingStatsLibraryRefreshJob?.cancel()
            pendingStatsLibraryRefreshJob = scope.launch(Dispatchers.Main.immediate) {
                delay(StatsLibraryRefreshDebounceMs)
                refreshStatsLibrary()
            }
        }
    }

    private fun scheduleRatingLibraryRefresh() {
        pendingRatingLibraryRefreshJob?.cancel()
        pendingRatingLibraryRefreshJob = scope.launch(Dispatchers.Main.immediate) {
            delay(RatingLibraryRefreshDebounceMs)
            pendingStatsLibraryRefreshJob?.cancel()
            pendingStatsLibraryRefreshJob = null
            refreshStatsLibrary()
        }
    }

    private fun refreshStatsLibrary() {
        localAudioLibrary.invalidateAudioItems()
        librarySessionProvider()?.notifyChildrenChanged(
            LocalAudioLibrary.ROOT_ID,
            Int.MAX_VALUE,
            null,
        )
    }

    private fun updateQueuedTrackRating(mediaId: String, score: Int) {
        val playbackPlayer = playerProvider() ?: return
        for (index in 0 until playbackPlayer.mediaItemCount) {
            val item = playbackPlayer.getMediaItemAt(index)
            if (item.mediaId == mediaId) {
                playbackPlayer.replaceMediaItem(index, item.withPlaybackRating(score))
            }
        }
    }

    fun cancelPendingRefreshes() {
        pendingStatsLibraryRefreshJob?.cancel()
        pendingStatsLibraryRefreshJob = null
        pendingRatingLibraryRefreshJob?.cancel()
        pendingRatingLibraryRefreshJob = null
    }

    private companion object {
        private const val StatsLibraryRefreshDebounceMs = 600L
        private const val RatingLibraryRefreshDebounceMs = 250L
    }
}
