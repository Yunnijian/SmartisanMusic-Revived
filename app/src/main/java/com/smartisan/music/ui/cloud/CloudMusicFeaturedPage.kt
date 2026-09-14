package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineMusicHome
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudMusicArtistList
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.cloudArtistSubtitle
import com.smartisan.music.ui.cloud.components.cloudPlaylistSubtitle
import kotlinx.coroutines.CancellationException

/** 首页五个分区「全部」对应的完整列表页。 */
internal enum class CloudFeaturedPage(val titleRes: Int) {
    Tracks(R.string.cloud_music_section_daily_tracks),
    Playlists(R.string.cloud_music_section_playlists),
    Charts(R.string.cloud_music_section_charts),
    Albums(R.string.cloud_music_section_albums),
    Artists(R.string.cloud_music_section_artists),
}

/** 整页共用一份 featuredHome 数据，按分区取对应列表渲染。 */
private sealed interface CloudFeaturedState {
    object Loading : CloudFeaturedState
    object Error : CloudFeaturedState
    data class Success(val home: OnlineMusicHome) : CloudFeaturedState
}

/**
 * 「查看全部」整页：顶栏 + 竖排列表，数据来自 [OnlineMusicProviderRepository.featuredHome]。
 * 与旧版五个 Featured* 路由一一对应，点击条目沿用宿主的详情/播放回调。
 */
@Composable
internal fun CloudMusicFeaturedPage(
    page: CloudFeaturedPage,
    repository: OnlineMusicProviderRepository,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    var revision by remember { mutableStateOf(0) }
    val state by produceState<CloudFeaturedState>(
        initialValue = CloudFeaturedState.Loading,
        page,
        revision,
        active,
    ) {
        if (!active) return@produceState
        value = CloudFeaturedState.Loading
        value = runSuspendCatching { repository.featuredHome() }.fold(
            onSuccess = { CloudFeaturedState.Success(it) },
            onFailure = { CloudFeaturedState.Error },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicPageTopBar(
            title = stringResource(page.titleRes),
            onBack = onBack,
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = state) {
                CloudFeaturedState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_featured_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudFeaturedState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_featured_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { revision += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudFeaturedState.Success -> when (page) {
                    CloudFeaturedPage.Tracks -> CloudFeaturedTrackList(
                        tracks = current.home.tracks,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        onTrackClick = { items, index ->
                            playbackBrowser?.replaceQueueAndPlay(
                                mediaItems = items,
                                startIndex = index,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Playlists -> CloudFeaturedPlaylistList(
                        playlists = current.home.playlists,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        onPlaylistClick = onOpenPlaylist,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Charts -> CloudFeaturedPlaylistList(
                        playlists = current.home.charts,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        onPlaylistClick = onOpenPlaylist,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Albums -> CloudMusicVerticalCoverList(
                        items = current.home.albums,
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
                    CloudFeaturedPage.Artists -> if (current.home.artists.isEmpty()) {
                        CloudMusicBlankState(
                            title = stringResource(R.string.cloud_music_artists_empty),
                            subtitle = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CloudMusicArtistList(
                            artists = current.home.artists,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            onArtistClick = onOpenArtist,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudFeaturedTrackList(
    tracks: List<OnlineTrack>,
    playbackBarOverlayHeight: Dp,
    onTrackClick: (List<androidx.media3.common.MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        CloudMusicBlankState(
            title = stringResource(R.string.cloud_music_empty_title),
            subtitle = stringResource(R.string.cloud_music_empty_subtitle),
            modifier = modifier,
        )
        return
    }
    val playableItems = remember(tracks) {
        tracks.map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
    }
    LazyColumn(
        modifier = modifier.background(CloudSurfaceColor),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { index, track -> "${track.mediaId}:$index" },
        ) { index, track ->
            Column {
                CloudMusicTrackRow(
                    track = track,
                    onClick = { onTrackClick(playableItems, index) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CloudMusicDivider()
            }
        }
    }
}

@Composable
private fun CloudFeaturedPlaylistList(
    playlists: List<OnlinePlaylist>,
    playbackBarOverlayHeight: Dp,
    onPlaylistClick: (OnlinePlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    CloudMusicVerticalCoverList(
        items = playlists,
        playbackBarOverlayHeight = playbackBarOverlayHeight,
        title = OnlinePlaylist::title,
        subtitle = { playlist -> cloudPlaylistSubtitle(playlist) },
        imageUrl = OnlinePlaylist::artworkUrl,
        onItemClick = onPlaylistClick,
        itemKey = OnlinePlaylist::playlistId,
        modifier = modifier,
    )
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
