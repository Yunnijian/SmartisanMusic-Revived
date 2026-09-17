package com.smartisan.music.ui.shell

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.smartisan.music.AppDispatchers
import com.smartisan.music.ExternalAudioLaunchRequest
import com.smartisan.music.data.favorite.LovedSongsCloudSync
import com.smartisan.music.data.playlist.PlaylistOnlineItemResolver
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.restoredDestination
import com.smartisan.music.resolveExternalAudioArtist
import com.smartisan.music.resolveExternalAudioMediaStoreIds
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.shell.playback.toExternalAudioMediaItem
import kotlinx.coroutines.withContext

/**
 * 主壳的副作用集：导航恢复/持久化、我喜欢云端收敛、启动就绪、播放启动、
 * 目的地切换清理与外部音频入口。从 `MusicAppShellContent` 原样搬来，
 * LaunchedEffect 的 key 与执行时机逐字一致；跨重组保持的计数由主壳以 [MutableState] 注入。
 */
@Composable
internal fun ShellContentEffects(
    controller: MediaController?,
    appContext: Context,
    uiState: MusicShellViewModel,
    navigationSettingsStore: NavigationSettingsStore,
    persistedNavigationSettings: NavigationSettings?,
    navigationLayoutInitialized: MutableState<Boolean>,
    navigationStateRestored: MutableState<Boolean>,
    onlineLovedRefreshVersion: MutableState<Int>,
    onlineLovedMediaItems: MutableState<List<MediaItem>>,
    lovedSongsCloudSync: LovedSongsCloudSync,
    playlistOnlineItemResolver: PlaylistOnlineItemResolver,
    onlinePlaylistMediaIds: List<String>,
    onlinePlaylistMediaItems: MutableState<List<MediaItem>>,
    favoriteIds: Set<String>,
    libraryLoaded: Boolean,
    currentOnStartupReady: () -> Unit,
    playbackLaunchRequest: Int,
    externalAudioLaunchRequest: ExternalAudioLaunchRequest?,
    unknownSongTitle: String,
    onExternalAudioLaunchConsumed: (Int) -> Unit,
) {
    // 冷启动恢复上次一级板块；详情页和弹窗状态不进入持久化恢复范围。
    LaunchedEffect(persistedNavigationSettings, uiState.currentDestination) {
        val persistedLayout = persistedNavigationSettings?.layout ?: return@LaunchedEffect
        if (!navigationLayoutInitialized.value) {
            navigationLayoutInitialized.value = true
            val (restoredDestination, restoredFromMore) =
                persistedNavigationSettings?.restoredDestination()
                    ?: (persistedLayout.bottomDestinations.first() to false)
            uiState.currentDestination = restoredDestination
            uiState.presentedFromMore = restoredFromMore
            navigationStateRestored.value = true
        } else if (
            uiState.currentDestination != MusicDestination.More &&
                !persistedLayout.isPinned(uiState.currentDestination)
        ) {
            uiState.presentedFromMore = true
        }
    }

    LaunchedEffect(
        uiState.currentDestination,
        uiState.presentedFromMore,
        navigationStateRestored.value,
    ) {
        if (navigationStateRestored.value) {
            navigationSettingsStore.setLastDestination(
                uiState.currentDestination,
                uiState.presentedFromMore,
            )
        }
    }

    // 进入「我喜欢的歌曲」时与网易云账号收敛一次：策略（登录判定、差集、只补不删、失败降级）在数据层。
    LaunchedEffect(uiState.currentDestination, onlineLovedRefreshVersion.value) {
        onlineLovedMediaItems.value = lovedSongsCloudSync.mediaItemsForLovedSongsPage(
            lovedSongsPageActive = uiState.currentDestination == MusicDestination.LovedSongs,
            localFavoriteMediaIds = favoriteIds,
        )
    }

    // 本地歌单里的在线条目：进播放列表页且确有在线 mediaId 时反查物料。
    // 离开页面保留上次结果，避免返回时在线行先消失再重建；清空由数据变化驱动（见 onlinePlaylistMediaIds）。
    LaunchedEffect(
        uiState.currentDestination,
        onlinePlaylistMediaIds,
    ) {
        if (uiState.currentDestination != MusicDestination.Playlist) {
            return@LaunchedEffect
        }
        val resolved = playlistOnlineItemResolver.resolveOnlineMediaItems(onlinePlaylistMediaIds)
        onlinePlaylistMediaItems.value = resolved
    }

    LaunchedEffect(navigationStateRestored.value, libraryLoaded, controller) {
        if (!navigationStateRestored.value || !libraryLoaded || controller == null) {
            return@LaunchedEffect
        }
        // 资料库状态发布后再隐藏两个布局帧，让标题栏、专辑网格和首屏封面开始绑定。
        withFrameNanos {}
        withFrameNanos {}
        currentOnStartupReady()
    }

    LaunchedEffect(playbackLaunchRequest) {
        if (playbackLaunchRequest > 0) {
            uiState.playbackVisible = true
        }
    }

    LaunchedEffect(uiState.currentDestination) {
        if (uiState.currentDestination != MusicDestination.Songs) {
            uiState.songsEditMode = false
            uiState.selectedSongIds = emptySet()
            uiState.showSongDeleteConfirm = false
            uiState.pendingSongDeleteMediaIds = emptySet()
        }
        if (uiState.currentDestination != MusicDestination.Album) {
            uiState.albumEditMode = false
            uiState.selectedAlbumIds = emptySet()
            uiState.selectedAlbumId = null
            uiState.selectedAlbumTitle = null
        }
        if (uiState.currentDestination != MusicDestination.Artist) {
            uiState.selectedArtistTarget = null
        }
        if (uiState.currentDestination != MusicDestination.Playlist) {
            uiState.playlistAddModeActive = false
        }
        uiState.dismissTrackActions()
    }

    LaunchedEffect(externalAudioLaunchRequest, controller) {
        val request = externalAudioLaunchRequest ?: return@LaunchedEffect
        uiState.playbackVisible = true
        val playbackController = controller ?: return@LaunchedEffect
        val (artist, mediaStoreIds) =
            withContext(AppDispatchers.IO) {
                request.resolveExternalAudioArtist(appContext) to
                    request.resolveExternalAudioMediaStoreIds(appContext)
            }
        val mediaItem =
            request.toExternalAudioMediaItem(
                fallbackTitle = unknownSongTitle,
                artist = artist,
                mediaStoreId = mediaStoreIds.mediaStoreId,
                albumId = mediaStoreIds.albumId,
            )
        playbackController.setMediaItem(mediaItem)
        playbackController.prepare()
        playbackController.play()
        onExternalAudioLaunchConsumed(request.requestId)
    }

    LaunchedEffect(uiState.currentDestination) {
        if (uiState.currentDestination != MusicDestination.More) {
            uiState.moreSettingsPageActive = false
        }
    }
}
