package com.smartisan.music.ui.shell.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.smartisan.music.data.favorite.FavoriteSongRecord
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.AudioFxPreset
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.ArtistTarget
import com.smartisan.music.ui.artist.ArtistTitleStack
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.shell.MusicShellViewModel
import com.smartisan.music.ui.shell.TrackActionSource
import com.smartisan.music.ui.shell.titlebar.MainTitleBar
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import com.smartisan.music.ui.shell.titlebar.TitleBarTransition

/**
 * 单个一级目的地的整页表面：固定高度标题区 + [MusicTabContent] 页面分发 + 详情页标题阴影。
 *
 * 从 `MusicAppShell` 的 `destinationSurface` 局部 lambda 原样搬来：组合顺序、`Modifier`、尺寸来源
 * 与所有回调表达式都不变。编辑态、选择态与待确认操作读写主壳持有的 [MusicShellViewModel]
 * （唯一状态源），需要落库的设置写回仍由主壳注入，页面层不碰存储。
 */
@Composable
internal fun MusicDestinationSurface(
    destination: MusicDestination,
    fromMore: Boolean,
    uiState: MusicShellViewModel,
    realTabContentBottomMargin: Dp,
    hideBottomChrome: Boolean,
    playbackBarOverlayHeight: Dp,
    titleAreaHeight: Dp,
    titleShadowHeight: Dp,
    overflowDestinations: List<MusicDestination>,
    mediaItems: List<MediaItem>,
    onlineLovedMediaItems: List<MediaItem>,
    favoriteRecords: List<FavoriteSongRecord>,
    libraryLoaded: Boolean,
    albumViewMode: AlbumViewMode,
    artistAlbumViewMode: AlbumViewMode,
    hiddenMediaIds: Set<String>,
    libraryRefreshVersion: Int,
    libraryRefreshing: Boolean,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    onToggleAlbumViewMode: () -> Unit,
    onToggleArtistAlbumViewMode: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onRequestAddToQueue: (List<MediaItem>) -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    onArtistSeparatorsChange: (Set<String>) -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onMediaIdsHidden: (Set<String>) -> Unit,
    onRequestDeleteMediaIds: (Set<String>) -> Unit,
    onRemoveFavoriteMediaIds: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier.fillMaxSize()
                .padding(
                    bottom = if (hideBottomChrome) 0.dp else realTabContentBottomMargin
                )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            val titleBarContent:
                @Composable
                (String?, ArtistTarget?, Modifier) -> Unit =
                { albumDetailTitle, artistTarget, titleModifier ->
                    MainTitleBar(
                        destination = destination,
                        songsEditMode =
                            destination == MusicDestination.Songs && uiState.songsEditMode,
                        selectedSongCount = uiState.selectedSongIds.size,
                        albumEditMode =
                            destination == MusicDestination.Album && uiState.albumEditMode,
                        selectedAlbumCount = uiState.selectedAlbumIds.size,
                        albumDetailTitle = albumDetailTitle,
                        albumViewMode = albumViewMode,
                        artistTarget = artistTarget,
                        artistAlbumViewMode = artistAlbumViewMode,
                        onEnterSongsEditMode = uiState::enterSongsEditMode,
                        onExitSongsEditMode = uiState::exitSongsEditMode,
                        onRequestDeleteSelected = uiState::requestDeleteSelectedSongs,
                        onEnterAlbumEditMode = uiState::enterAlbumEditMode,
                        onExitAlbumEditMode = uiState::exitAlbumEditMode,
                        onToggleAlbumViewMode = onToggleAlbumViewMode,
                        onAlbumDetailBack = uiState::closeAlbumDetail,
                        onArtistBack = uiState::closeArtistDetail,
                        onToggleArtistAlbumViewMode = onToggleArtistAlbumViewMode,
                        onRootBack = uiState::returnToMore.takeIf { fromMore },
                        onSearchClick = uiState::openSearch,
                        modifier = titleModifier,
                    )
                }
            if (destination !in DestinationsWithOwnedTitleBar) {
                when (destination) {
                    MusicDestination.Album ->
                        TitleBarTransition(
                            secondaryKey = uiState.selectedAlbumTitle,
                            modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                            label = "album title transition",
                            primaryContent = {
                                titleBarContent(null, null, Modifier.fillMaxSize())
                            },
                            secondaryContent = { detailTitle ->
                                titleBarContent(
                                    detailTitle,
                                    null,
                                    Modifier.fillMaxSize(),
                                )
                            },
                        )
                    MusicDestination.Artist ->
                        ArtistTitleStack(
                            selectedTarget = uiState.selectedArtistTarget,
                            modifier = Modifier.fillMaxWidth().height(titleAreaHeight),
                        ) { artistTarget, titleModifier ->
                            titleBarContent(null, artistTarget, titleModifier)
                        }
                    else -> titleBarContent(null, null, Modifier.fillMaxWidth())
                }
            }
            MusicTabContent(
                destination = destination,
                presentedFromMore = fromMore,
                overflowDestinations = overflowDestinations,
                mediaItems = mediaItems,
                onlineLovedMediaItems = onlineLovedMediaItems,
                favoriteRecords = favoriteRecords,
                libraryLoaded = libraryLoaded,
                songsEditMode = destination == MusicDestination.Songs && uiState.songsEditMode,
                selectedSongIds = uiState.selectedSongIds,
                albumViewMode = albumViewMode,
                albumEditMode = destination == MusicDestination.Album && uiState.albumEditMode,
                selectedAlbumId = uiState.selectedAlbumId,
                selectedAlbumIds = uiState.selectedAlbumIds,
                artistAlbumViewMode = artistAlbumViewMode,
                selectedArtistTarget = uiState.selectedArtistTarget,
                playbackBarOverlayHeight = if (hideBottomChrome) 0.dp else playbackBarOverlayHeight,
                hiddenMediaIds = hiddenMediaIds,
                libraryRefreshVersion = libraryRefreshVersion,
                libraryRefreshing = libraryRefreshing,
                playbackSettings = playbackSettings,
                artistSettings = artistSettings,
                onRefreshLibrary = onRefreshLibrary,
                onRequestAddToPlaylist = uiState::requestPlaylistPicker,
                onRequestAddToQueue = onRequestAddToQueue,
                onScratchEnabledChange = onScratchEnabledChange,
                onHidePlayerAxisEnabledChange = onHidePlayerAxisEnabledChange,
                onPopcornSoundEnabledChange = onPopcornSoundEnabledChange,
                onAudioFxEnabledChange = onAudioFxEnabledChange,
                onAudioFxPresetChange = onAudioFxPresetChange,
                onAudioFxCustomGainDbPointsChange = onAudioFxCustomGainDbPointsChange,
                onArtistSeparatorsChange = onArtistSeparatorsChange,
                navigationSettings = navigationSettings,
                themeMode = themeMode,
                onTabPinnedChange = onTabPinnedChange,
                onThemeModeChange = onThemeModeChange,
                onOverflowDestinationSelected = uiState::selectOverflow,
                onReturnToMore = uiState::returnToMore,
                onMediaIdsHidden = onMediaIdsHidden,
                onRequestDeleteMediaIds = onRequestDeleteMediaIds,
                onRequestSongDeleteConfirmation = uiState::requestSongDeleteConfirmation,
                onLibraryTrackMoreClick = { item ->
                    uiState.showTrackActions(item, TrackActionSource.Library)
                },
                onLovedSongsTrackMoreClick = { item ->
                    uiState.showTrackActions(item, TrackActionSource.Loved)
                },
                onPlaylistTrackMoreClick = { item ->
                    uiState.showTrackActions(item, TrackActionSource.Playlist)
                },
                onRemoveFavoriteMediaIds = onRemoveFavoriteMediaIds,
                onMoreSettingsPageActiveChanged = uiState::updateMoreSettingsPageActive,
                onSongSelectionChange = uiState::selectSongRow,
                onAlbumSelectionChange = uiState::selectAlbumRow,
                onAlbumSelected = uiState::openAlbumDetail,
                onArtistTargetChanged = uiState::setArtistTarget,
                onPlaylistAddModeActiveChanged = uiState::updatePlaylistAddModeActive,
                onSearchClick = uiState::openSearch,
                cloudSearchOpenRequest = uiState.cloudSearchOpenRequest,
                onCloudSearchOpenRequestHandled = uiState::consumeCloudSearchOpenRequest,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
        if (
            destination == MusicDestination.Artist ||
                destination == MusicDestination.Album
        ) {
            TitleBarShadow(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .offset(y = titleAreaHeight)
                        .fillMaxWidth()
                        .height(titleShadowHeight)
                        .zIndex(1f)
            )
        }
    }
}

/** 这些页面自带标题栏，主壳不再补一层。 */
private val DestinationsWithOwnedTitleBar =
    setOf(
        MusicDestination.Playlist,
        MusicDestination.More,
        MusicDestination.Genre,
        MusicDestination.LovedSongs,
        MusicDestination.Folder,
    )
