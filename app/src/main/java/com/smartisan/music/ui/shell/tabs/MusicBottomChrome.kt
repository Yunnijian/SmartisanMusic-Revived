package com.smartisan.music.ui.shell.tabs

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.media3.common.MediaItem
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.shell.MusicShellUiState
import com.smartisan.music.ui.shell.playback.PlaybackBar
import com.smartisan.music.ui.shell.playback.PlaybackBarHost
import com.smartisan.music.ui.shell.retainedChromeVisibility

/**
 * 底部常驻 chrome：延迟组合的播放条 + 主导航底栏。
 *
 * 从 `MusicAppShell` 原样搬来的区域：播放条仍然只在真正组合过之后才挂上，退场动画收尾才撤回；
 * 底栏仍然沿用同一套「加歌模式 > 来自更多 > 当前目的地」的高亮判断，切 tab 与打开导航编辑的
 * 收敛动作都收在主壳的 [MusicShellUiState] 里。
 *
 * 作为 `BoxScope` 扩展存在：底栏仍然用同一个 `align(Alignment.BottomCenter)` 挂在主壳顶层 Box 上，
 * 修饰符链与迁移前逐字一致。
 */
@Composable
internal fun BoxScope.MusicBottomChrome(
    uiState: MusicShellUiState,
    playbackBar: PlaybackBarHost,
    favoriteIds: Set<String>,
    artworkBitmap: Bitmap?,
    playbackBarHeight: Dp,
    destinations: List<MusicDestination>,
    hideBottomChrome: Boolean,
    onToggleFavorite: (MediaItem) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .align(Alignment.BottomCenter)
                .retainedChromeVisibility(!hideBottomChrome)
    ) {
        if (playbackBar.composed) {
            PlaybackBar(
                snapshot = playbackBar.contentSnapshot,
                shown = playbackBar.requestedVisible,
                favoriteIds = favoriteIds,
                artworkBitmap = artworkBitmap,
                onHidden = playbackBar::onHidden,
                onOpenPlayback = uiState::showPlaybackOverlay,
                onToggleFavorite = onToggleFavorite,
                onPrevious = onPrevious,
                onPlayPause = onPlayPause,
                onNext = onNext,
                modifier = Modifier.fillMaxWidth().height(playbackBarHeight),
                bottomDividerVisible = true,
            )
        }
        MusicBottomBar(
            currentDestination =
                when {
                    uiState.playlistAddModeActive -> MusicDestination.Songs
                    else -> uiState.stackDestination
                },
            destinations = destinations,
            onDestinationSelected = uiState::selectTab,
            onEditRequested = uiState::showNavigationEditor,
            topChromeVisible = !playbackBar.composed,
        )
    }
}
