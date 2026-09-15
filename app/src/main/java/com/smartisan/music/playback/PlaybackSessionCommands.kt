@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.os.Bundle
import androidx.core.os.BundleCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommand
import com.google.common.util.concurrent.ListenableFuture

internal const val ScratchSeekModeAction = "com.smartisan.music.action.SET_SCRATCH_SEEK_MODE"
internal const val ScratchSeekModeEnabledKey = "scratch_seek_mode_enabled"
internal const val StartSleepTimerAction = "com.smartisan.music.action.START_SLEEP_TIMER"
internal const val CancelSleepTimerAction = "com.smartisan.music.action.CANCEL_SLEEP_TIMER"
internal const val SleepTimerDurationMsKey = "sleep_timer_duration_ms"
internal const val RefreshLibraryAction = "com.smartisan.music.action.REFRESH_LIBRARY"
internal const val InvalidateLibraryAction = "com.smartisan.music.action.INVALIDATE_LIBRARY"
internal const val SetTrackRatingAction = "com.smartisan.music.action.SET_TRACK_RATING"
internal const val ReplaceQueueAndPlayAction = "com.smartisan.music.action.REPLACE_QUEUE_AND_PLAY"
internal const val TrackRatingMediaIdKey = "track_rating_media_id"
internal const val TrackRatingScoreKey = "track_rating_score"
internal const val TrackRatingMinScore = 0
internal const val TrackRatingMaxScore = 5
internal const val ReplaceQueueEntriesKey = "replace_queue_entries"
internal const val ReplaceQueueEntryIsFullKey = "replace_queue_entry_is_full"
internal const val ReplaceQueueEntryItemKey = "replace_queue_entry_item"
internal const val ReplaceQueueEntryMediaIdKey = "replace_queue_entry_media_id"
internal const val ReplaceQueueStartIndexKey = "replace_queue_start_index"
internal const val ReplaceQueueShuffleModeKey = "replace_queue_shuffle_mode"

internal val ScratchSeekModeCommand = SessionCommand(ScratchSeekModeAction, Bundle.EMPTY)
internal val StartSleepTimerCommand = SessionCommand(StartSleepTimerAction, Bundle.EMPTY)
internal val CancelSleepTimerCommand = SessionCommand(CancelSleepTimerAction, Bundle.EMPTY)
internal val RefreshLibraryCommand = SessionCommand(RefreshLibraryAction, Bundle.EMPTY)
internal val InvalidateLibraryCommand = SessionCommand(InvalidateLibraryAction, Bundle.EMPTY)
internal val SetTrackRatingCommand = SessionCommand(SetTrackRatingAction, Bundle.EMPTY)
internal val ReplaceQueueAndPlayCommand = SessionCommand(ReplaceQueueAndPlayAction, Bundle.EMPTY)

internal fun MediaController.setScratchSeekModeEnabled(enabled: Boolean) {
    val args = Bundle().apply {
        putBoolean(ScratchSeekModeEnabledKey, enabled)
    }
    sendCustomCommand(ScratchSeekModeCommand, args)
}

internal fun MediaController.startSleepTimer(durationMs: Long) {
    val args = Bundle().apply {
        putLong(SleepTimerDurationMsKey, durationMs)
    }
    sendCustomCommand(StartSleepTimerCommand, args)
}

internal fun MediaController.cancelSleepTimer() {
    sendCustomCommand(CancelSleepTimerCommand, Bundle.EMPTY)
}

internal fun MediaController.refreshLibrary() =
    sendCustomCommand(RefreshLibraryCommand, Bundle.EMPTY)

internal fun MediaController.invalidateLibrary() =
    sendCustomCommand(InvalidateLibraryCommand, Bundle.EMPTY)

internal fun MediaController.setTrackRating(mediaId: String, score: Int): ListenableFuture<SessionResult> {
    val args = Bundle().apply {
        putString(TrackRatingMediaIdKey, mediaId)
        putInt(TrackRatingScoreKey, score.coerceIn(TrackRatingMinScore, TrackRatingMaxScore))
    }
    return sendCustomCommand(SetTrackRatingCommand, args)
}

internal fun MediaController.sendReplaceQueueAndPlayCommand(
    mediaItems: List<MediaItem>,
    startIndex: Int,
    shuffleModeEnabled: Boolean,
): ListenableFuture<SessionResult> {
    return sendCustomCommand(
        ReplaceQueueAndPlayCommand,
        Bundle().apply {
            putParcelableArrayList(
                ReplaceQueueEntriesKey,
                ArrayList(
                    mediaItems.map { item ->
                        Bundle().apply {
                            // 本地条目接收侧只按 mediaId 回查本地库，传完整载荷纯属浪费，
                            // 上千首大队列还会把单个 Bundle 撑过 Binder 1MB 事务上限；
                            // 仅在线/外部条目随传输带来的完整 bundle 走 isFull 分支。
                            if (item.canResolveDirectSessionPlaybackItem()) {
                                putBoolean(ReplaceQueueEntryIsFullKey, true)
                                putBundle(
                                    ReplaceQueueEntryItemKey,
                                    item.toBundleIncludeLocalConfiguration(
                                        MediaLibraryInfo.INTERFACE_VERSION,
                                    ),
                                )
                            } else {
                                putBoolean(ReplaceQueueEntryIsFullKey, false)
                                putString(ReplaceQueueEntryMediaIdKey, item.mediaId)
                            }
                        }
                    },
                ),
            )
            putInt(ReplaceQueueStartIndexKey, startIndex)
            putBoolean(ReplaceQueueShuffleModeKey, shuffleModeEnabled)
        },
    )
}

internal fun Bundle.decodeReplaceQueueAndPlayMediaItems(): List<MediaItem> {
    val entryBundles = BundleCompat.getParcelableArrayList(
        this,
        ReplaceQueueEntriesKey,
        Bundle::class.java,
    )
        ?: return emptyList()
    return entryBundles.map { entryBundle ->
        if (entryBundle.getBoolean(ReplaceQueueEntryIsFullKey)) {
            val itemBundle = entryBundle.getBundle(ReplaceQueueEntryItemKey)
                ?: return@map MediaItem.Builder().build()
            MediaItem.fromBundle(itemBundle, MediaLibraryInfo.INTERFACE_VERSION)
        } else {
            MediaItem.Builder()
                .setMediaId(entryBundle.getString(ReplaceQueueEntryMediaIdKey).orEmpty())
                .build()
        }
    }
}
