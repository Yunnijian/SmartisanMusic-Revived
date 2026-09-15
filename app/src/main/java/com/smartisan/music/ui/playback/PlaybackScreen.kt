package com.smartisan.music.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.MediaItem
import com.smartisan.music.data.settings.PlaybackSettings

/**
 * 播放页入口：状态与交互由 [rememberPlaybackScreenHost] 承载，布局见 [PlaybackScreenLayout]。
 */
@Composable
fun PlaybackScreen(
    playbackSettings: PlaybackSettings,
    onScratchEnabledChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    onRequestAddToPlaylist: (List<MediaItem>) -> Unit = {},
    onRequestAddToQueue: (List<MediaItem>) -> Unit = {},
    onLibraryChanged: () -> Unit = {},
    onFavoriteToggle: ((MediaItem) -> Unit)? = null,
    showTopBar: Boolean = true,
) {
    val host =
        rememberPlaybackScreenHost(
            playbackSettings = playbackSettings,
            onScratchEnabledChange = onScratchEnabledChange,
            onCollapse = onCollapse,
            onRequestAddToPlaylist = onRequestAddToPlaylist,
            onRequestAddToQueue = onRequestAddToQueue,
            onLibraryChanged = onLibraryChanged,
            onFavoriteToggle = onFavoriteToggle,
        )
    PlaybackScreenLayout(
        host = host,
        playbackSettings = playbackSettings,
        showTopBar = showTopBar,
        modifier = modifier,
    )
}
