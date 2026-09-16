package com.smartisan.music.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.ui.cloud.components.CloudMusicArtistList
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudMusicVerticalCoverList
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudPullRefresh
import com.smartisan.music.ui.cloud.components.CloudSurfaceColor
import com.smartisan.music.ui.cloud.components.cloudAlbumSubtitle
import com.smartisan.music.ui.cloud.components.cloudPlaylistSubtitle

/**
 * 首页各分区「全部」对应的完整列表页。
 *
 * 不含日推：「每日推荐」有独立的 [CloudMusicDailyPage]（默认/风格双 tab）。
 */
internal enum class CloudFeaturedPage(val titleRes: Int) {
    Playlists(R.string.cloud_music_section_playlists),
    Charts(R.string.cloud_music_section_charts),
    Albums(R.string.cloud_music_section_albums),
    Artists(R.string.cloud_music_section_artists),
}

/**
 * 「查看全部」整页：顶栏 + 竖排列表，按分区取 [CloudHomeBundle.home] 的对应列表渲染。
 * 与旧版五个 Featured* 路由一一对应，点击条目沿用宿主的详情/播放回调。
 *
 * 数据复用首页的 [CloudMusicDataStore.home] 槽——两者打的是同一个 featuredHome 端点，
 * 从首页进入整页时结果已在缓存里，不必再拉一次。
 */
@Composable
internal fun CloudMusicFeaturedPage(
    page: CloudFeaturedPage,
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeSlot = data.home
    val listState = scrollStates.featured(page)

    LaunchedEffect(homeSlot, active) {
        if (active) {
            homeSlot.ensureLoaded(Unit)
        }
    }

    // 下拉刷新：请求发出后等槽位离开 Loading 即视为完成。
    var refreshRequested by remember { mutableStateOf(false) }
    var homeRefreshVersion by remember { mutableIntStateOf(-1) }
    val homeState = homeSlot.state(Unit)
    val homeSlotVersion = homeSlot.version
    LaunchedEffect(homeSlotVersion) {
        if (refreshRequested && homeSlotVersion != homeRefreshVersion) {
            refreshRequested = false
        }
    }

    CloudPullRefresh(
        refreshing = refreshRequested,
        onRefresh = {
            refreshRequested = true
            homeRefreshVersion = homeSlot.version
            data.refreshHome()
        },
        canChildScrollUp = {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicSectionTitle(
            title = stringResource(page.titleRes),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val current = homeState) {
                CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_featured_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSlotState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_featured_error),
                    subtitle = null,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { homeSlot.reload(Unit) },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSlotState.Success -> when (page) {
                    CloudFeaturedPage.Playlists -> CloudFeaturedPlaylistList(
                        playlists = current.data.home.playlists,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = listState,
                        onPlaylistClick = onOpenPlaylist,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Charts -> CloudFeaturedPlaylistList(
                        playlists = current.data.home.charts,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = listState,
                        onPlaylistClick = onOpenPlaylist,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Albums -> CloudMusicVerticalCoverList(
                        items = current.data.home.albums,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        listState = listState,
                        title = OnlineAlbum::title,
                        subtitle = { album -> cloudAlbumSubtitle(album) },
                        imageUrl = OnlineAlbum::artworkUrl,
                        onItemClick = onOpenAlbum,
                        itemKey = OnlineAlbum::albumId,
                        modifier = Modifier.fillMaxSize(),
                    )
                    CloudFeaturedPage.Artists -> if (current.data.home.artists.isEmpty()) {
                        CloudMusicBlankState(
                            title = stringResource(R.string.cloud_music_artists_empty),
                            subtitle = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CloudMusicArtistList(
                            artists = current.data.home.artists,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            listState = listState,
                            onArtistClick = onOpenArtist,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun CloudFeaturedPlaylistList(
    playlists: List<OnlinePlaylist>,
    playbackBarOverlayHeight: Dp,
    listState: LazyListState,
    onPlaylistClick: (OnlinePlaylist) -> Unit,
    modifier: Modifier = Modifier,
) {
    CloudMusicVerticalCoverList(
        items = playlists,
        playbackBarOverlayHeight = playbackBarOverlayHeight,
        listState = listState,
        title = OnlinePlaylist::title,
        subtitle = { playlist -> cloudPlaylistSubtitle(playlist) },
        imageUrl = OnlinePlaylist::artworkUrl,
        onItemClick = onPlaylistClick,
        itemKey = OnlinePlaylist::playlistId,
        modifier = modifier,
    )
}
