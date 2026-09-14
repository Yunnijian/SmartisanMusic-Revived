package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.ui.cloud.components.CloudMusicArtistList
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import kotlinx.coroutines.CancellationException

private sealed interface CloudArtistListState {
    object Loading : CloudArtistListState
    object Error : CloudArtistListState
    object Empty : CloudArtistListState
    data class Success(val artists: List<OnlineArtist>) : CloudArtistListState
}

/**
 * 歌手页：热门歌手完整竖排列表（featuredArtists 端点）。
 * 与「查看全部」的 FeaturedArtists（首页子集）不同，这里是独立端点的全量列表。
 */
@Composable
internal fun CloudMusicArtistsPage(
    repository: OnlineMusicProviderRepository,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenArtist: (OnlineArtist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revision by remember { mutableStateOf(0) }
    val state by produceState<CloudArtistListState>(
        initialValue = CloudArtistListState.Loading,
        revision,
        active,
    ) {
        if (!active) return@produceState
        value = CloudArtistListState.Loading
        value = runSuspendCatching { repository.featuredArtists() }.fold(
            onSuccess = { artists ->
                if (artists.isEmpty()) CloudArtistListState.Empty
                else CloudArtistListState.Success(artists)
            },
            onFailure = { CloudArtistListState.Error },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicPageTopBar(
            title = stringResource(R.string.cloud_music_section_artists),
            onBack = onBack,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                CloudArtistListState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_artists_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudArtistListState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_artists_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { revision += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
                CloudArtistListState.Empty -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_artists_empty),
                    subtitle = null,
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudArtistListState.Success -> CloudMusicArtistList(
                    artists = current.artists,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    onArtistClick = onOpenArtist,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private sealed interface CloudArtistAlbumsState {
    object Loading : CloudArtistAlbumsState
    object Error : CloudArtistAlbumsState
    object Empty : CloudArtistAlbumsState
    data class Success(val albums: List<OnlineAlbum>) : CloudArtistAlbumsState
}

/** 歌手专辑页：单个歌手的专辑完整竖排列表（artistAlbums 端点）。 */
@Composable
internal fun CloudMusicArtistAlbumsPage(
    repository: OnlineMusicProviderRepository,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    artist: CloudDetailTarget.Artist,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revision by remember { mutableStateOf(0) }
    val state by produceState<CloudArtistAlbumsState>(
        initialValue = CloudArtistAlbumsState.Loading,
        artist,
        revision,
        active,
    ) {
        if (!active) return@produceState
        value = CloudArtistAlbumsState.Loading
        value = runSuspendCatching {
            repository.artistAlbums(
                OnlineArtist(
                    provider = OnlineMusicProvider.Netease,
                    artistId = artist.id,
                    name = artist.name,
                ),
            )
        }.fold(
            onSuccess = { albums ->
                if (albums.isEmpty()) CloudArtistAlbumsState.Empty
                else CloudArtistAlbumsState.Success(albums)
            },
            onFailure = { CloudArtistAlbumsState.Error },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicPageTopBar(
            title = artist.name,
            onBack = onBack,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                CloudArtistAlbumsState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_detail_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudArtistAlbumsState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_detail_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_detail_retry),
                    onActionClick = { revision += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
                CloudArtistAlbumsState.Empty -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_detail_empty),
                    subtitle = null,
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudArtistAlbumsState.Success -> CloudMusicVerticalCoverList(
                    items = current.albums,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    title = OnlineAlbum::title,
                    subtitle = { album ->
                        listOfNotNull(
                            album.artist?.takeIf(String::isNotBlank),
                            album.trackCount.takeIf { it > 0 }?.let { count ->
                                stringResource(R.string.cloud_music_album_total_tracks, count)
                            },
                        ).joinToString(" · ").ifBlank { null }
                    },
                    imageUrl = OnlineAlbum::artworkUrl,
                    onItemClick = onOpenAlbum,
                    itemKey = OnlineAlbum::albumId,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** 捕获非取消异常，避免网络错误直接打断协程作用域。 */
private suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
