package com.smartisan.music.ui.shell

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.zIndex
import androidx.media3.common.MediaItem
import com.smartisan.music.R
import com.smartisan.music.data.playlist.PlaylistCreateResult
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.playlist.UserPlaylistSummary
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.ui.album.AlbumViewMode
import com.smartisan.music.ui.artist.ArtistTarget
import com.smartisan.music.ui.playlist.PlaybackPlaylistPickerOverlay
import com.smartisan.music.ui.playlist.PlaylistNameDialogOverlay
import com.smartisan.music.ui.playlist.PlaylistNameDialogRequest
import com.smartisan.music.ui.search.SearchDrilldownTarget
import com.smartisan.music.ui.search.SearchOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 主壳顶层的整页覆盖层组：播放页、搜索、加歌单选择与新建歌单弹窗。
 *
 * 从 `MusicAppShell` 的 Box 里原样搬来，组合顺序、`zIndex` 与各 `visible` 表达式都不变；
 * 覆盖层自有的 `BackHandler` 仍然晚于主壳注册，所以返回优先级不变（见 ShellBackNavigation）。
 * 状态都读写主壳持有的 [MusicShellViewModel]（唯一状态源），[scope] 也仍是主壳的协程作用域，
 * 因此落库、Toast 与状态写入的时机与迁移前一致。
 */
@Composable
internal fun ShellOverlayLayer(
    uiState: MusicShellViewModel,
    scope: CoroutineScope,
    playlistRepository: PlaylistRepository,
    playbackSettings: PlaybackSettings,
    searchMediaItems: List<MediaItem>,
    hiddenMediaIds: Set<String>,
    libraryRefreshVersion: Int,
    artistAlbumViewMode: AlbumViewMode,
    artistSettings: ArtistSettings,
    playlists: List<UserPlaylistSummary>,
    onRequestAddToQueue: (List<MediaItem>) -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onFavoriteToggle: (MediaItem) -> Unit,
    onLibraryTrackMoreClick: (MediaItem) -> Unit,
    onToggleArtistAlbumViewMode: () -> Unit,
) {
    val context = LocalContext.current
    PlaybackOverlay(
        visible = uiState.playbackVisible,
        playbackSettings = playbackSettings,
        ratingOverrides = uiState.ratingOverrides,
        onRequestAddToPlaylist = uiState::requestPlaylistPicker,
        onRequestAddToQueue = onRequestAddToQueue,
        onScratchEnabledChange = onScratchEnabledChange,
        onTrackRatingChanged = uiState::addRatingOverride,
        onFavoriteToggle = onFavoriteToggle,
        onCollapse = uiState::hidePlaybackOverlay,
        modifier = Modifier.zIndex(3f),
    )
    SearchOverlay(
        visible = uiState.searchVisible,
        query = uiState.searchQuery,
        mediaItems = searchMediaItems,
        hiddenMediaIds = hiddenMediaIds,
        drilldownTarget = uiState.searchDrilldownTarget,
        libraryRefreshVersion = libraryRefreshVersion,
        artistAlbumViewMode = artistAlbumViewMode,
        artistSettings = artistSettings,
        onQueryChange = uiState::updateSearchQuery,
        onDismiss = uiState::closeSearch,
        onOpenPlayback = uiState::showPlaybackOverlay,
        onRequestAddToPlaylist = uiState::requestPlaylistPicker,
        onRequestAddToQueue = onRequestAddToQueue,
        onTrackMoreClick = onLibraryTrackMoreClick,
        onDrilldownTargetChanged = { target -> uiState.searchDrilldownTarget = target },
        onAlbumClick = { albumId, albumTitle ->
            uiState.searchDrilldownTarget =
                SearchDrilldownTarget.Album(
                    albumId = albumId,
                    albumTitle = albumTitle,
                )
        },
        onArtistClick = { artistId, artistName ->
            uiState.searchDrilldownTarget =
                SearchDrilldownTarget.Artist(
                    target =
                        ArtistTarget.Albums(
                            artistId = artistId,
                            artistName = artistName,
                        )
                )
        },
        onToggleArtistAlbumViewMode = onToggleArtistAlbumViewMode,
        modifier = Modifier.fillMaxSize().zIndex(2f),
    )
    PlaybackPlaylistPickerOverlay(
        visible = uiState.playlistPickerVisible,
        playlists = playlists,
        onDismiss = uiState::dismissPlaylistPicker,
        onCreateNewPlaylist = {
            scope.launch {
                uiState.openPlaylistCreateRequest(
                    PlaylistNameDialogRequest.Create(
                        initialName = playlistRepository.suggestNextUntitledName()
                    )
                )
            }
        },
        onPlaylistSelected = { playlistId ->
            val mediaIds =
                uiState.pendingPlaylistPickerMediaItems?.map(MediaItem::mediaId).orEmpty()
            scope.launch {
                val result = playlistRepository.addMediaIds(playlistId, mediaIds)
                when {
                    result.addedCount > 0 -> {
                        Toast.makeText(context, R.string.playlist_added, Toast.LENGTH_SHORT)
                            .show()
                    }
                    result.duplicateCount > 0 -> {
                        Toast.makeText(
                                context,
                                R.string.playlist_song_exists,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                }
                uiState.dismissPlaylistPicker()
            }
        },
        modifier = Modifier.fillMaxSize().zIndex(4f),
    )
    PlaylistNameDialogOverlay(
        request = uiState.playbackPlaylistCreateRequest,
        onDismiss = uiState::dismissPlaylistCreateRequest,
        onConfirm = { _, input ->
            val mediaIds =
                uiState.pendingPlaylistPickerMediaItems?.map(MediaItem::mediaId).orEmpty()
            scope.launch {
                when (playlistRepository.createPlaylist(input, mediaIds)) {
                    PlaylistCreateResult.EmptyName -> {
                        Toast.makeText(
                                context,
                                R.string.playlist_create_failed,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                    PlaylistCreateResult.DuplicateName -> {
                        Toast.makeText(
                                context,
                                R.string.playlist_duplicate_name,
                                Toast.LENGTH_SHORT,
                            )
                            .show()
                    }
                    is PlaylistCreateResult.Success -> {
                        uiState.finishPlaylistCreate()
                        Toast.makeText(context, R.string.playlist_added, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        },
    )
}
