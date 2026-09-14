package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.ui.cloud.components.CloudMusicArtistList
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.cloudAlbumSubtitle

/**
 * 歌手页：热门歌手完整竖排列表（featuredArtists 端点）。
 * 与「查看全部」的 FeaturedArtists（首页子集）不同，这里是独立端点的全量列表。
 *
 * 数据来自宿主级 [CloudMusicDataStore.artists]，进入歌手专辑页再返回时不重新联网。
 */
@Composable
internal fun CloudMusicArtistsPage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenArtist: (OnlineArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val artistsSlot = data.artists

    LaunchedEffect(artistsSlot, active) {
        if (active) {
            artistsSlot.ensureLoaded(Unit)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicSectionTitle(
            title = stringResource(R.string.cloud_music_section_artists),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = artistsSlot.state(Unit)) {
                CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_artists_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSlotState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_artists_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { artistsSlot.reload(Unit) },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSlotState.Success -> if (current.data.isEmpty()) {
                    CloudMusicBlankState(
                        title = stringResource(R.string.cloud_music_artists_empty),
                        subtitle = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CloudMusicArtistList(
                        artists = current.data,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = scrollStates.artists,
                        onArtistClick = onOpenArtist,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * 歌手专辑页：单个歌手的专辑完整竖排列表（artistAlbums 端点）。
 *
 * 槽位按歌手缓存，退回歌手页再进同一位歌手时直接复用结果。
 */
@Composable
internal fun CloudMusicArtistAlbumsPage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    artist: CloudDetailTarget.Artist,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    val albumsSlot = data.artistAlbums

    LaunchedEffect(albumsSlot, artist, active) {
        if (active) {
            albumsSlot.ensureLoaded(artist)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicSectionTitle(
            title = artist.name,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = albumsSlot.state(artist)) {
                CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_detail_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSlotState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_detail_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_detail_retry),
                    onActionClick = { albumsSlot.reload(artist) },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSlotState.Success -> if (current.data.isEmpty()) {
                    CloudMusicBlankState(
                        title = stringResource(R.string.cloud_music_detail_empty),
                        subtitle = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CloudMusicVerticalCoverList(
                        items = current.data,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = scrollStates.artistAlbums,
                        title = OnlineAlbum::title,
                        subtitle = { album -> cloudAlbumSubtitle(album) },
                        imageUrl = OnlineAlbum::artworkUrl,
                        onItemClick = onOpenAlbum,
                        itemKey = OnlineAlbum::albumId,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
