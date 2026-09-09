package com.smartisan.music.playback

import androidx.media3.common.Player

internal fun Player?.isPlaybackActiveForUi(): Boolean {
    val player = this ?: return false
    return playbackActiveForUiState(
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        playbackState = player.playbackState,
    )
}

internal fun playbackActiveForUiState(
    isPlaying: Boolean,
    playWhenReady: Boolean,
    playbackState: Int,
): Boolean {
    return isPlaying || (playWhenReady && playbackState == Player.STATE_BUFFERING)
}

/**
 * 在线条目解析/播放失败时是否自动跳到下一首：
 * 仅当当前正在播放在线条目、后面还有条目且不是单曲循环时才跳，避免把本地错误也吞掉。
 */
internal fun shouldSkipOnlinePlaybackError(
    isCurrentOnline: Boolean,
    hasNextMediaItem: Boolean,
    repeatMode: Int,
): Boolean {
    return isCurrentOnline &&
        hasNextMediaItem &&
        repeatMode != Player.REPEAT_MODE_ONE
}
