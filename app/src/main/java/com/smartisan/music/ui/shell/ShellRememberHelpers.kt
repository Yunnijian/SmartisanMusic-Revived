package com.smartisan.music.ui.shell

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.smartisan.music.MusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongRecord
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistSummary
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.ArtistSettingsStore
import com.smartisan.music.data.settings.LibraryDisplaySettings
import com.smartisan.music.data.settings.LibraryDisplaySettingsStore
import com.smartisan.music.data.settings.NavigationSettings
import com.smartisan.music.data.settings.NavigationSettingsStore
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.data.settings.PlaybackSettingsStore
import com.smartisan.music.data.settings.ThemeMode
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.parentTarget
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import com.smartisan.music.ui.navigation.MusicDestination
import com.smartisan.music.ui.navigation.NavigationLayout
import com.smartisan.music.ui.shell.playback.PlaybackBarHost
import com.smartisan.music.ui.shell.playback.loadArtworkBitmap
import com.smartisan.music.ui.shell.playback.peekArtworkBitmap
import kotlinx.coroutines.CoroutineScope

/** 主壳从各 Store 采集的只读状态快照。 */
internal class ShellCollectedState(
    val favoriteIds: Set<String>,
    val libraryExclusions: LibraryExclusions,
    val playbackSettings: PlaybackSettings,
    val artistSettings: ArtistSettings,
    val persistedNavigationSettings: NavigationSettings?,
    val navigationSettings: NavigationSettings,
    val themeMode: ThemeMode,
    val albumViewMode: AlbumViewMode,
    val artistAlbumViewMode: AlbumViewMode,
    val unknownSongTitle: String,
    val favoriteRecords: List<FavoriteSongRecord>,
    val playlists: List<UserPlaylistSummary>,
)

@Composable
internal fun rememberShellCollectedState(
    container: MusicAppContainer,
    favoriteRepository: FavoriteSongsRepository,
    playlistRepository: PlaylistRepository,
): ShellCollectedState {
    val favoriteIds by favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val libraryExclusions by
        container.libraryExclusionsStore.exclusions.collectAsState(initial = LibraryExclusions())
    val playbackSettings by
        container.playbackSettingsStore.settings.collectAsState(initial = PlaybackSettings())
    val artistSettings by
        container.artistSettingsStore.settings.collectAsState(initial = ArtistSettings())
    val libraryDisplaySettings by
        container.libraryDisplaySettingsStore.settings.collectAsState(initial = LibraryDisplaySettings())
    val persistedNavigationSettings: NavigationSettings? by
        container.navigationSettingsStore.settings.collectAsState(initial = null)
    val navigationSettings = persistedNavigationSettings ?: NavigationSettings()
    val themeMode = remember(container.themeSettingsStore) { container.themeSettingsStore.currentMode() }
    val albumViewMode = libraryDisplaySettings.albumViewMode
    val artistAlbumViewMode = libraryDisplaySettings.artistAlbumViewMode
    val unknownSongTitle = stringResource(R.string.unknown_song_title)
    val favoriteRecords by
        favoriteRepository.observeFavorites().collectAsState(initial = emptyList())
    val playlists by playlistRepository.playlists.collectAsState(initial = emptyList())
    return ShellCollectedState(
        favoriteIds = favoriteIds,
        libraryExclusions = libraryExclusions,
        playbackSettings = playbackSettings,
        artistSettings = artistSettings,
        persistedNavigationSettings = persistedNavigationSettings,
        navigationSettings = navigationSettings,
        themeMode = themeMode,
        albumViewMode = albumViewMode,
        artistAlbumViewMode = artistAlbumViewMode,
        unknownSongTitle = unknownSongTitle,
        favoriteRecords = favoriteRecords,
        playlists = playlists,
    )
}

@Composable
internal fun rememberShellArtwork(
    appContext: Context,
    playbackBar: PlaybackBarHost,
): State<Bitmap?> {
    val playbackBarMediaItem = playbackBar.contentSnapshot.mediaItem
    val artworkRequestKey = playbackBarMediaItem?.artworkRequestKey()
    return produceState<Bitmap?>(
        initialValue = playbackBarMediaItem?.let(::peekArtworkBitmap),
        artworkRequestKey,
    ) {
        val mediaItem = playbackBar.contentSnapshot.mediaItem
        if (mediaItem == null) {
            value = null
            return@produceState
        }
        value = peekArtworkBitmap(mediaItem) ?: value
        value = loadArtworkBitmap(appContext, mediaItem)
    }
}

@Composable
internal fun rememberShellActions(
    context: Context,
    controller: MediaController?,
    scope: CoroutineScope,
    uiState: MusicShellViewModel,
    favoriteRepository: FavoriteSongsRepository,
    playlistRepository: PlaylistRepository,
    playbackStatsRepository: PlaybackStatsRepository,
    libraryExclusionsStore: LibraryExclusionsStore,
    onlineRepositoryRouter: OnlineMusicRepositoryRouter,
    libraryDisplaySettingsStore: LibraryDisplaySettingsStore,
    currentArtistAlbumViewMode: State<AlbumViewMode>,
    libraryRefreshVersion: MutableState<Int>,
    libraryRefreshing: MutableState<Boolean>,
    onlineLovedRefreshVersion: MutableState<Int>,
): ShellActions {
    val actions =
        remember(controller, context) {
            ShellActions(
                context = context,
                controller = controller,
                scope = scope,
                uiState = uiState,
                favoriteRepository = favoriteRepository,
                playlistRepository = playlistRepository,
                playbackStatsRepository = playbackStatsRepository,
                libraryExclusionsStore = libraryExclusionsStore,
                onlineRepositoryRouter = onlineRepositoryRouter,
                libraryDisplaySettingsStore = libraryDisplaySettingsStore,
                artistAlbumViewModeProvider = { currentArtistAlbumViewMode.value },
                libraryRefreshVersion = libraryRefreshVersion,
                libraryRefreshing = libraryRefreshing,
                onlineLovedRefreshVersion = onlineLovedRefreshVersion,
            )
        }
    val deleteCoordinator =
        rememberMediaStoreDeleteCoordinator(
            onDeleted = { mediaIds ->
                actions.cleanupDeletedSongs(mediaIds, hideFromLibrary = false)
            },
            onNotDeleted = {
                Toast.makeText(context, R.string.playback_delete_failed, Toast.LENGTH_SHORT).show()
            },
        )
    actions.deleteCoordinator = deleteCoordinator
    return actions
}

@Composable
internal fun ShellBackHandler(uiState: MusicShellViewModel) {
    val backOwner =
        ShellBackNavigationState(
            destination = uiState.currentDestination,
            presentedFromMore = uiState.presentedFromMore,
            songsEditMode = uiState.songsEditMode,
            albumEditMode = uiState.albumEditMode,
            albumDetailOpen = uiState.selectedAlbumId != null,
            artistDetailOpen = uiState.selectedArtistTarget != null,
            artistDetailHasParent = uiState.selectedArtistTarget?.parentTarget() != null,
            visibleOverlay =
                shellBackOverlay(
                    playbackVisible = uiState.playbackVisible,
                    searchVisible = uiState.searchVisible,
                    playlistPickerVisible = uiState.playlistPickerVisible,
                    navigationEditorVisible = uiState.navigationEditorVisible,
                ),
        ).resolveOwner()
    BackHandler(enabled = backOwner.consumedByShell) {
        when (backOwner) {
            ShellBackOwner.CloseAlbumDetail -> uiState.closeAlbumDetail()
            ShellBackOwner.CloseArtistDetail -> uiState.closeArtistDetail()
            ShellBackOwner.ReturnToMore -> uiState.returnToMore()
            else -> Unit
        }
    }
}
