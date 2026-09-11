package com.smartisan.music.ui.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineMusicHome
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineBanner
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudMusicBanner
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCard
import com.smartisan.music.ui.cloud.components.CloudMusicCoverCardSection
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudPageBackgroundColor
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 首页顶部栏高度。 */
private val CloudMusicHomeTopBarHeight = 50.dp

/** 顶部搜索入口的高度。 */
private val CloudMusicHomeSearchEntryHeight = 32.dp

/** 顶部搜索入口圆角。 */
private val CloudMusicHomeSearchEntryCornerRadius = 16.dp

/** 搜索入口底色。 */
private val CloudMusicHomeSearchEntryColor = Color(0xFFF0F0F0)

/** “我的”按钮文字。 */
private val CloudMusicMineButtonColor = CloudAccentColor

/** 首页推荐页状态机：请求 / 空态 / 错误 / 成功，错误态可递增 revision 触发重试。 */
private sealed interface CloudMusicHomeState {
    object Loading : CloudMusicHomeState
    object Empty : CloudMusicHomeState
    object Error : CloudMusicHomeState
    data class Success(
        val banners: List<OnlineBanner>,
        val home: OnlineMusicHome,
        val dailyTracks: List<OnlineTrack>,
    ) : CloudMusicHomeState
}

/**
 * 云音乐首页推荐页。
 *
 * 无 ViewModel：用 [androidx.compose.runtime.produceState] 发起 `featuredHome()` +
 * `featuredBanners()` 并行请求，状态机 loading/empty/error/success，错误态可重试。
 *
 * 布局：[LazyColumn] 垂直滚动，从上到下依次为 Banner 轮播、每日推荐、推荐歌单、
 * 排行榜、新碟上架、热门艺人（空区块跳过）。底部为播放条预留 padding。
 */
@Composable
internal fun CloudMusicHomePage(
    repository: OnlineMusicProviderRepository,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onOpenSearch: () -> Unit,
    onOpenMine: () -> Unit,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onOpenArtist: (id: String, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    val scope = rememberCoroutineScope()
    // 错误态重试时递增，触发 produceState 重新加载。
    var revision by remember { mutableIntStateOf(0) }

    val state by produceState<CloudMusicHomeState>(
        initialValue = CloudMusicHomeState.Loading,
        repository,
        revision,
    ) {
        value = runSuspendCatching {
            coroutineScope {
                val homeAsync = async { repository.featuredHome() }
                val bannersAsync = async { repository.featuredBanners() }
                val dailyAsync = async {
                    runSuspendCatching { repository.currentUserDailyRecommendedTracks() }
                        .getOrNull().orEmpty()
                }
                val home = homeAsync.await()
                val banners = bannersAsync.await()
                val accountDaily = dailyAsync.await()
                if (banners.isEmpty() &&
                    home.tracks.isEmpty() &&
                    home.playlists.isEmpty() &&
                    home.charts.isEmpty() &&
                    home.albums.isEmpty() &&
                    home.artists.isEmpty()
                ) {
                    CloudMusicHomeState.Empty
                } else {
                    CloudMusicHomeState.Success(
                        banners = banners,
                        home = home,
                        dailyTracks = accountDaily.ifEmpty { home.tracks },
                    )
                }
            }
        }.fold(
            onSuccess = { it },
            onFailure = { CloudMusicHomeState.Error },
        )
    }

    Column(modifier = modifier.fillMaxSize().background(CloudPageBackgroundColor)) {
        CloudMusicHomeTopBar(
            onOpenSearch = onOpenSearch,
            onOpenMine = onOpenMine,
        )
        when (val current = state) {
            CloudMusicHomeState.Loading -> CloudMusicDelayedLoadingState(
                title = stringResource(R.string.cloud_music_home_loading),
                modifier = Modifier.fillMaxSize(),
            )
            CloudMusicHomeState.Empty -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_empty_title),
                subtitle = stringResource(R.string.cloud_music_empty_subtitle),
                modifier = Modifier.fillMaxSize(),
            )
            CloudMusicHomeState.Error -> CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_home_error),
                subtitle = null,
                actionText = stringResource(R.string.cloud_music_retry),
                onActionClick = { revision += 1 },
                modifier = Modifier.fillMaxSize(),
            )
            is CloudMusicHomeState.Success -> CloudMusicHomeContent(
                state = current,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                onOpenPlaylist = onOpenPlaylist,
                onOpenAlbum = onOpenAlbum,
                onOpenArtist = onOpenArtist,
                onPlayBannerTrack = { trackId ->
                    scope.launch {
                        val track = runSuspendCatching { repository.track(trackId) }.getOrNull()
                            ?: return@launch
                        playbackBrowser?.replaceQueueAndPlay(
                            mediaItems = listOf(track.toMediaItem().withOnlinePlaybackPlaceholderUri()),
                            startIndex = 0,
                        )
                    }
                },
                onPlayDailyTracks = { tracks, index ->
                    val items = tracks.map { it.toMediaItem().withOnlinePlaybackPlaceholderUri() }
                    playbackBrowser?.replaceQueueAndPlay(
                        mediaItems = items,
                        startIndex = index,
                    )
                },
            )
        }
    }
}

/** 首页滚动内容：Banner + 各横滑区块。空区块跳过，列表 key 用 `${id}:$index`。 */
@Composable
private fun CloudMusicHomeContent(
    state: CloudMusicHomeState.Success,
    playbackBarOverlayHeight: Dp,
    onOpenPlaylist: (id: String, title: String) -> Unit,
    onOpenAlbum: (id: String, title: String) -> Unit,
    onOpenArtist: (id: String, name: String) -> Unit,
    onPlayBannerTrack: (trackId: String) -> Unit,
    onPlayDailyTracks: (tracks: List<OnlineTrack>, index: Int) -> Unit,
) {
    val home = state.home
    val banners = state.banners
    val dailyTracks = state.dailyTracks
    val dailyTracksTitle = stringResource(R.string.cloud_music_section_daily_tracks)
    val playlistsTitle = stringResource(R.string.cloud_music_section_playlists)
    val chartsTitle = stringResource(R.string.cloud_music_section_charts)
    val albumsTitle = stringResource(R.string.cloud_music_section_albums)
    val artistsTitle = stringResource(R.string.cloud_music_section_artists)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        if (banners.isNotEmpty()) {
            item(key = "cloud-home-banner") {
                CloudMusicBanner(
                    banners = banners,
                    onOpenPlaylist = onOpenPlaylist,
                    onOpenAlbum = onOpenAlbum,
                    onPlayTrack = onPlayBannerTrack,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (dailyTracks.isNotEmpty()) {
            item(key = "cloud-home-section-daily") {
                val tracks = dailyTracks
                CloudMusicCoverCardSection(title = dailyTracksTitle) {
                    itemsIndexed(
                        items = tracks,
                        key = { index, track -> "${track.mediaId}:$index" },
                    ) { index, track ->
                        CloudMusicCoverCard(
                            imageUrl = track.artworkUrl,
                            title = track.title,
                            subtitle = track.artist,
                            onClick = { onPlayDailyTracks(tracks, index) },
                        )
                    }
                }
            }
        }
        if (home.playlists.isNotEmpty()) {
            item(key = "cloud-home-section-playlists") {
                val playlists = home.playlists
                CloudMusicCoverCardSection(title = playlistsTitle) {
                    itemsIndexed(
                        items = playlists,
                        key = { index, playlist -> "${playlist.playlistId}:$index" },
                    ) { _, playlist ->
                        CloudMusicCoverCard(
                            imageUrl = playlist.artworkUrl,
                            title = playlist.title,
                            subtitle = playlist.subtitle,
                            onClick = { onOpenPlaylist(playlist.playlistId, playlist.title) },
                        )
                    }
                }
            }
        }
        if (home.charts.isNotEmpty()) {
            item(key = "cloud-home-section-charts") {
                val charts = home.charts
                CloudMusicCoverCardSection(title = chartsTitle) {
                    itemsIndexed(
                        items = charts,
                        key = { index, chart -> "${chart.playlistId}:$index" },
                    ) { _, chart ->
                        CloudMusicCoverCard(
                            imageUrl = chart.artworkUrl,
                            title = chart.title,
                            subtitle = chart.subtitle,
                            onClick = { onOpenPlaylist(chart.playlistId, chart.title) },
                        )
                    }
                }
            }
        }
        if (home.albums.isNotEmpty()) {
            item(key = "cloud-home-section-albums") {
                val albums = home.albums
                CloudMusicCoverCardSection(title = albumsTitle) {
                    itemsIndexed(
                        items = albums,
                        key = { index, album -> "${album.albumId}:$index" },
                    ) { _, album ->
                        CloudMusicCoverCard(
                            imageUrl = album.artworkUrl,
                            title = album.title,
                            subtitle = album.subtitle(),
                            onClick = { onOpenAlbum(album.albumId, album.title) },
                        )
                    }
                }
            }
        }
        if (home.artists.isNotEmpty()) {
            item(key = "cloud-home-section-artists") {
                val artists = home.artists
                CloudMusicCoverCardSection(title = artistsTitle) {
                    itemsIndexed(
                        items = artists,
                        key = { index, artist -> "${artist.artistId}:$index" },
                    ) { _, artist ->
                        CloudMusicCoverCard(
                            imageUrl = artist.artworkUrl,
                            title = artist.name,
                            subtitle = artist.subtitle,
                            onClick = { onOpenArtist(artist.artistId, artist.name) },
                        )
                    }
                }
            }
        }
    }
}

/** 专辑卡片副标题：优先艺人，其次“共 N 首”。 */
private fun OnlineAlbum.subtitle(): String? {
    artist?.takeIf(String::isNotBlank)?.let { return it }
    return trackCount.takeIf { it > 0 }?.let { "共 $it 首" }
}

/** 首页顶部栏：左侧搜索入口（点击进入搜索页）+ 右侧“我的”入口。 */
@Composable
private fun CloudMusicHomeTopBar(
    onOpenSearch: () -> Unit,
    onOpenMine: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(CloudMusicHomeTopBarHeight)
            .background(Color.White)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(CloudMusicHomeSearchEntryHeight)
                .clip(RoundedCornerShape(CloudMusicHomeSearchEntryCornerRadius))
                .background(CloudMusicHomeSearchEntryColor)
                .cloudMusicPressable(onClick = onOpenSearch)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.search_bar_left_icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(R.string.cloud_music_search_hint_netease),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Text(
            text = stringResource(R.string.cloud_music_mine_title),
            style = TextStyle(
                fontSize = 14.sp,
                color = CloudMusicMineButtonColor,
            ),
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp)
                .cloudMusicPressable(onClick = onOpenMine),
        )
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
