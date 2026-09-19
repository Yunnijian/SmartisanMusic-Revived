package com.smartisan.music.ui.cloud

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineBanner
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.ui.cloud.components.CloudHomeAnimatedSection
import com.smartisan.music.ui.cloud.components.CloudHomeSectionAnimation
import com.smartisan.music.ui.cloud.components.CloudMusicBanner
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCard
import com.smartisan.music.ui.cloud.components.CloudHomeDailyRecommendSection
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCardSection
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudPullRefresh

/**
 * 云音乐首页推荐页。
 *
 * 数据来自宿主级 [CloudMusicDataStore.home]：首页与「查看全部」整页共用同一份
 * featuredHome 结果，页面在入口层之间被销毁重建时不再重新联网。本页只负责触发加载
 * （[CloudDataSlot.ensureLoaded]，非活跃时不发起）、渲染三态与重试。
 *
 * 布局：[LazyColumn] 垂直滚动，从上到下依次为 Banner 轮播、每日推荐、推荐歌单、
 * 排行榜、新碟上架、热门艺人（空区块跳过）。底部为播放条预留 padding。
 */
@Composable
internal fun CloudMusicHomePage(
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    onOpenFeatured: (CloudFeaturedPage) -> Unit,
    onOpenBannerTrack: (OnlineBanner) -> Unit,
    onOpenDaily: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val homeSlot = data.home
    val sectionAnimation = data.homeSectionAnimation
    val homeLoaded = homeSlot.state(Unit) is CloudSlotState.Success

    // 下拉刷新：请求发出后等槽位离开 Loading 即视为完成，收尾动画由组件播放。
    var refreshRequested by remember { mutableStateOf(false) }
    var homeRefreshVersion by remember { mutableIntStateOf(-1) }
    val homeState = homeSlot.state(Unit)
    val homeSlotVersion = homeSlot.version
    LaunchedEffect(homeSlotVersion) {
        if (refreshRequested && homeSlotVersion != homeRefreshVersion) {
            refreshRequested = false
        }
    }

    // 切到非活跃 tab 时不发起请求；active 恢复后重新触发（已加载则直接复用缓存）。
    LaunchedEffect(homeSlot, active) {
        if (active) {
            homeSlot.ensureLoaded(Unit)
        }
    }
    // 入场动画只播一次：状态在仓库里，页面重建时区块保持展开。
    LaunchedEffect(sectionAnimation, homeLoaded) {
        if (homeLoaded) {
            sectionAnimation.requestPlay()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudPullRefresh(
            refreshing = refreshRequested,
            onRefresh = {
                refreshRequested = true
                homeRefreshVersion = homeSlot.version
                data.refreshHome()
            },
            canChildScrollUp = {
                scrollStates.home.firstVisibleItemIndex > 0 ||
                    scrollStates.home.firstVisibleItemScrollOffset > 0
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            when (val current = homeState) {
            CloudSlotState.Loading -> CloudMusicDelayedLoadingState(
                title = stringResource(R.string.cloud_music_home_loading),
                modifier = Modifier.fillMaxSize(),
            )
            CloudSlotState.Error -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_home_error),
                subtitle = null,
                actionText = stringResource(R.string.cloud_music_retry),
                onActionClick = { homeSlot.reload(Unit) },
                modifier = Modifier.fillMaxSize(),
            )
            is CloudSlotState.Success -> if (current.data.isEmpty) {
                CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_empty_title),
                    subtitle = stringResource(R.string.cloud_music_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CloudMusicHomeContent(
                    bundle = current.data,
                    listState = scrollStates.home,
                    sectionAnimation = sectionAnimation,
                    active = active,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onOpenFeatured = onOpenFeatured,
                    onOpenBannerTrack = onOpenBannerTrack,
                    onOpenDaily = onOpenDaily,
                )
            }
            }
        }
    }
}

/** 首页滚动内容：Banner + 各横滑区块。空区块跳过，列表 key 用 `${id}:$index`。 */
@Composable
private fun CloudMusicHomeContent(
    bundle: CloudHomeBundle,
    listState: LazyListState,
    sectionAnimation: CloudHomeSectionAnimation,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (OnlinePlaylist) -> Unit,
    onOpenAlbum: (OnlineAlbum) -> Unit,
    onOpenArtist: (OnlineArtist) -> Unit,
    onOpenFeatured: (CloudFeaturedPage) -> Unit,
    onOpenBannerTrack: (OnlineBanner) -> Unit,
    onOpenDaily: () -> Unit,
) {
    val home = bundle.home
    val banners = bundle.banners
    val dailyTracks = bundle.dailyTracks
    val dailyTracksTitle = stringResource(R.string.cloud_music_section_daily_tracks)
    val playlistsTitle = stringResource(R.string.cloud_music_section_playlists)
    val chartsTitle = stringResource(R.string.cloud_music_section_charts)
    val albumsTitle = stringResource(R.string.cloud_music_section_albums)
    val artistsTitle = stringResource(R.string.cloud_music_section_artists)
    val viewAllText = stringResource(R.string.cloud_music_section_view_all)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        // 区块索引按实际渲染顺序递增：空区块跳过时不占位，首个可见区块总是立即入场。
        var sectionIndex = 0
        if (banners.isNotEmpty()) {
            item(key = "cloud-home-banner") {
                CloudMusicBanner(
                    banners = banners,
                    active = active,
                    onOpenPlaylist = { id, title ->
                        onOpenPlaylist(
                            OnlinePlaylist(
                                provider = OnlineMusicProvider.Netease,
                                playlistId = id,
                                title = title,
                            ),
                        )
                    },
                    onOpenAlbum = { id, title ->
                        onOpenAlbum(
                            OnlineAlbum(
                                provider = OnlineMusicProvider.Netease,
                                albumId = id,
                                title = title,
                            ),
                        )
                    },
                    onTrackClick = onOpenBannerTrack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (dailyTracks.isNotEmpty()) {
            val animationIndex = sectionIndex++
            item(key = "cloud-home-section-daily") {
                val tracks = dailyTracks
                CloudHomeAnimatedSection(visibleState = sectionAnimation.stateAt(animationIndex)) {
                    CloudHomeDailyRecommendSection(
                        title = dailyTracksTitle,
                        tracks = tracks,
                        onClick = onOpenDaily,
                    )
                }
            }
        }
        if (home.playlists.isNotEmpty()) {
            val animationIndex = sectionIndex++
            item(key = "cloud-home-section-playlists") {
                val playlists = home.playlists
                CloudHomeAnimatedSection(visibleState = sectionAnimation.stateAt(animationIndex)) {
                    CloudMusicCoverCardSection(
                        title = playlistsTitle,
                        actionText = viewAllText,
                        onActionClick = { onOpenFeatured(CloudFeaturedPage.Playlists) },
                    ) {
                        itemsIndexed(
                            items = playlists,
                            key = { index, playlist -> "${playlist.playlistId}:$index" },
                        ) { _, playlist ->
                            CloudMusicCoverCard(
                                imageUrl = playlist.artworkUrl,
                                title = playlist.title,
                                subtitle = playlist.subtitle,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }
        }
        if (home.charts.isNotEmpty()) {
            val animationIndex = sectionIndex++
            item(key = "cloud-home-section-charts") {
                val charts = home.charts
                CloudHomeAnimatedSection(visibleState = sectionAnimation.stateAt(animationIndex)) {
                    CloudMusicCoverCardSection(
                        title = chartsTitle,
                        actionText = viewAllText,
                        onActionClick = { onOpenFeatured(CloudFeaturedPage.Charts) },
                    ) {
                        itemsIndexed(
                            items = charts,
                            key = { index, chart -> "${chart.playlistId}:$index" },
                        ) { _, chart ->
                            CloudMusicCoverCard(
                                imageUrl = chart.artworkUrl,
                                title = chart.title,
                                subtitle = chart.subtitle,
                                onClick = { onOpenPlaylist(chart) },
                            )
                        }
                    }
                }
            }
        }
        if (home.albums.isNotEmpty()) {
            val animationIndex = sectionIndex++
            item(key = "cloud-home-section-albums") {
                val albums = home.albums
                CloudHomeAnimatedSection(visibleState = sectionAnimation.stateAt(animationIndex)) {
                    CloudMusicCoverCardSection(
                        title = albumsTitle,
                        actionText = viewAllText,
                        onActionClick = { onOpenFeatured(CloudFeaturedPage.Albums) },
                    ) {
                        itemsIndexed(
                            items = albums,
                            key = { index, album -> "${album.albumId}:$index" },
                        ) { _, album ->
                            CloudMusicCoverCard(
                                imageUrl = album.artworkUrl,
                                title = album.title,
                                subtitle = album.subtitle(),
                                onClick = { onOpenAlbum(album) },
                            )
                        }
                    }
                }
            }
        }
        if (home.artists.isNotEmpty()) {
            val animationIndex = sectionIndex++
            item(key = "cloud-home-section-artists") {
                val artists = home.artists
                CloudHomeAnimatedSection(visibleState = sectionAnimation.stateAt(animationIndex)) {
                    CloudMusicCoverCardSection(
                        title = artistsTitle,
                        actionText = viewAllText,
                        onActionClick = { onOpenFeatured(CloudFeaturedPage.Artists) },
                    ) {
                        itemsIndexed(
                            items = artists,
                            key = { index, artist -> "${artist.artistId}:$index" },
                        ) { _, artist ->
                            CloudMusicCoverCard(
                                imageUrl = artist.artworkUrl,
                                title = artist.name,
                                subtitle = artist.subtitle,
                                onClick = { onOpenArtist(artist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 专辑卡片副标题：优先艺人，其次“共 N 首”。 */
@Composable
private fun OnlineAlbum.subtitle(): String? {
    artist?.takeIf(String::isNotBlank)?.let { return it }
    return trackCount.takeIf { it > 0 }?.let { stringResource(R.string.cloud_music_album_total_tracks, it) }
}
