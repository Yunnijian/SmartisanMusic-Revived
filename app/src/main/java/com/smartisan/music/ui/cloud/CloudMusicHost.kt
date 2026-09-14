package com.smartisan.music.ui.cloud

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineAlbum
import com.smartisan.music.data.online.OnlineArtist
import com.smartisan.music.data.online.OnlineBanner
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlinePlaylist
import com.smartisan.music.data.online.OnlineRadio
import com.smartisan.music.ui.cloud.components.CloudHomeEntry
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicHomeEntryRow
import com.smartisan.music.ui.online.NeteaseWebLoginActivity
import com.smartisan.music.ui.shell.PageStackTransition

/** 云音乐 tab 内部一级页面。 */
internal enum class CloudSubPage {
    Home,
    Mine,
}

/** 详情页目标：歌单 / 专辑 / 艺人 / 电台四种，由宿主持有、PageStackTransition 驱动列表↔详情转场。
 *
 * 除 id/title 外携带展示元数据（封面、副标题来源、计数），跳转时一并传入，
 * 详情页头部不必再退化到"第一首歌的封面/种类硬编码"。
 */
internal sealed interface CloudDetailTarget {
    /** @param accountEditable 是否为当前账号可编辑的「我的歌单」（用于删除歌单/从歌单移除歌曲）。 */
    data class Playlist(
        val id: String,
        val title: String,
        val accountEditable: Boolean = false,
        val artworkUrl: String? = null,
        val subtitle: String? = null,
        val trackCount: Int = 0,
        val playCount: Long = 0L,
    ) : CloudDetailTarget

    data class Album(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val artist: String? = null,
        val trackCount: Int = 0,
    ) : CloudDetailTarget

    data class Artist(
        val id: String,
        val name: String,
        val artworkUrl: String? = null,
        val alias: String? = null,
        val trackCount: Int = 0,
        val albumCount: Int = 0,
    ) : CloudDetailTarget

    data class Radio(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val category: String? = null,
        val creator: String? = null,
        val programCount: Int = 0,
        val playCount: Long = 0L,
    ) : CloudDetailTarget

    /** Banner 指向的单曲：旧版 CloudMusicRoute.BannerTrack，进详情页而非直接播放。 */
    data class BannerTrack(
        val id: String,
        val title: String,
        val artworkUrl: String? = null,
        val subtitle: String? = null,
    ) : CloudDetailTarget
}

/**
 * 云音乐宿主页：未登录引导 + 已登录内容区。
 *
 * 状态全部集中在本宿主，子页面均为受控组件（数据/交互回调来自宿主），
 * 与作者新版框架的 [com.smartisan.music.ui.shell.MusicAppShell] 组织方式一致：
 * - 一级页面（首页/我的）用 [CloudSubPage] 状态切换；
 * - 整页数据（[CloudMusicDataStore]）与滚动位置（[CloudMusicScrollStates]）由宿主集中持有，
 *   子页面只读状态并触发加载，在入口层之间来回切换时不重新联网、不丢列表位置；
 * - 详情页用 [PageStackTransition] 驱动列表↔详情转场，secondaryKey = [CloudDetailTarget]；
 * - 搜索作为全页覆盖层（zIndex 分层），query 由宿主持有（受控）；
 * - 返回键用 [BackHandler] 按条件逐层关闭。
 */
@Composable
internal fun CloudMusicHost(
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    searchOpenRequest: Int = 0,
    onSearchOpenRequestHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val authStore = remember(appContext) { NeteaseAuthStore(appContext) }
    val repositoryRouter =
        remember(appContext) { OnlineMusicRepositoryRouter.getInstance(appContext) }
    val neteaseRepository =
        remember(repositoryRouter) {
            repositoryRouter.repositoryFor(OnlineMusicProvider.Netease)
        }
    var authState by remember { mutableStateOf(authStore.load()) }

    // 登录页返回后刷新登录态。
    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val cookieJson = result.data
            ?.getStringExtra(NeteaseWebLoginActivity.ExtraCookieJson)
            .orEmpty()
        if (cookieJson.isNotBlank() && authStore.saveCookieJson(cookieJson)) {
            authState = authStore.load()
            Toast.makeText(context, R.string.netease_login_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.netease_login_cookie_missing, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(active) {
        if (active) {
            authState = authStore.load()
        }
    }

    // ── 宿主状态（仿 MusicAppShell 的集中持有模式） ──
    // 整页数据与滚动位置一律由宿主持有：各页在入口层之间切换时会被移出组合，
    // 状态留在页内就会重新联网并弹回列表顶部（旧版同样是宿主集中持有）。
    val dataScope = rememberCoroutineScope()
    val data = rememberCloudMusicDataStore(neteaseRepository, dataScope)
    val scrollStates = remember { CloudMusicScrollStates() }

    var subPage by rememberSaveable { mutableStateOf(CloudSubPage.Home) }
    var mineFilter by rememberSaveable { mutableStateOf(CloudAccountLibraryFilter.All) }
    // 从「我的」打开的账号歌单：合并列表里把该行标题染成强调色（对齐旧版高亮）。
    var selectedAccountPlaylistId by remember { mutableStateOf<String?>(null) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchCategory by rememberSaveable { mutableStateOf(CloudSearchCategory.All) }
    var selectedDetail by remember { mutableStateOf<CloudDetailTarget?>(null) }
    // 「查看全部」整页：与详情页同属列表之上的推进层，返回时先退整页再退详情。
    var featuredPage by remember { mutableStateOf<CloudFeaturedPage?>(null) }
    // 电台模块（首页/热门播客/推荐节目三个子页）与歌手页/歌手专辑页，均由顶部入口行进入。
    var radioVisible by remember { mutableStateOf(false) }
    var radioSubPage by remember { mutableStateOf(CloudRadioSubPage.Home) }
    var artistsVisible by remember { mutableStateOf(false) }
    var artistAlbumsTarget by remember { mutableStateOf<CloudDetailTarget.Artist?>(null) }
    // 账号歌单库变更（加歌/移除/新建/删除）后由详情页回调递增，「我的」页订阅此值重拉。
    var accountLibraryRevision by remember { mutableStateOf(0) }
    val onAccountLibraryChanged = remember { { accountLibraryRevision += 1 } }

    // 标题栏搜索按钮在云页时由壳递增请求计数；这里展开页内搜索场并消费请求。
    LaunchedEffect(active, searchOpenRequest) {
        if (active && searchOpenRequest > 0) {
            searchVisible = true
            onSearchOpenRequestHandled()
        }
    }

    // 打开详情的回调在首页/整页之间共用，元数据组装只写一份。
    val openPlaylistDetail: (OnlinePlaylist) -> Unit = { playlist ->
        selectedDetail = CloudDetailTarget.Playlist(
            id = playlist.playlistId,
            title = playlist.title,
            artworkUrl = playlist.artworkUrl,
            subtitle = playlist.subtitle,
            trackCount = playlist.trackCount,
            playCount = playlist.playCount,
        )
    }
    val openAlbumDetail: (OnlineAlbum) -> Unit = { album ->
        selectedDetail = CloudDetailTarget.Album(
            id = album.albumId,
            title = album.title,
            artworkUrl = album.artworkUrl,
            artist = album.artist,
            trackCount = album.trackCount,
        )
    }
    val openArtistDetail: (OnlineArtist) -> Unit = { artist ->
        selectedDetail = CloudDetailTarget.Artist(
            id = artist.artistId,
            name = artist.name,
            artworkUrl = artist.artworkUrl,
            alias = artist.subtitle,
            trackCount = artist.trackCount,
            albumCount = artist.albumCount,
        )
    }
    val openRadioDetail: (OnlineRadio) -> Unit = { radio ->
        selectedDetail = CloudDetailTarget.Radio(
            id = radio.radioId,
            title = radio.title,
            artworkUrl = radio.artworkUrl,
            category = radio.category,
            creator = radio.creator,
            programCount = radio.programCount,
            playCount = radio.playCount,
        )
    }
    // 带歌曲目标的 Banner 进单曲详情页（旧版 CloudMusicRoute.BannerTrack），不再直接播放。
    val openBannerTrack: (OnlineBanner) -> Unit = { banner ->
        selectedDetail = CloudDetailTarget.BannerTrack(
            id = banner.targetTrackId.orEmpty(),
            title = banner.title,
            artworkUrl = banner.imageUrl,
            subtitle = banner.subtitle,
        )
    }
    /** 入口行切换：进入任一入口时清掉其他推进层，避免层叠残留。 */
    fun switchEntry(entry: CloudHomeEntry) {
        featuredPage = null
        radioVisible = false
        radioSubPage = CloudRadioSubPage.Home
        artistsVisible = false
        artistAlbumsTarget = null
        selectedAccountPlaylistId = null
        when (entry) {
            CloudHomeEntry.Mine -> subPage = CloudSubPage.Mine
            CloudHomeEntry.Recommend -> subPage = CloudSubPage.Home
            CloudHomeEntry.Radio -> radioVisible = true
            CloudHomeEntry.Collection -> featuredPage = CloudFeaturedPage.Playlists
            CloudHomeEntry.Artist -> artistsVisible = true
        }
    }

    // ── 返回键：逐层关闭（仿 shell 的 BackHandler 条件链） ──
    // 最外层：搜索覆盖层（从搜索进入详情后搜索层暂隐，返回键先退详情）
    BackHandler(enabled = active && searchVisible && selectedDetail == null) {
        searchVisible = false
    }
    // 中层：详情页（搜索暂隐时同样要能退回搜索层）
    BackHandler(enabled = active && selectedDetail != null) {
        selectedDetail = null
    }
    // 次中层：歌手专辑页
    BackHandler(
        enabled = active && !searchVisible && selectedDetail == null && artistAlbumsTarget != null,
    ) {
        artistAlbumsTarget = null
    }
    // 电台内部子页退回电台首页
    BackHandler(
        enabled = active && !searchVisible && selectedDetail == null &&
            artistAlbumsTarget == null && radioVisible && radioSubPage != CloudRadioSubPage.Home,
    ) {
        radioSubPage = CloudRadioSubPage.Home
    }
    // 入口层退出：电台首页 / 歌手页 / 「查看全部」整页 → 回首页
    BackHandler(
        enabled = active && !searchVisible && selectedDetail == null &&
            artistAlbumsTarget == null && radioSubPage == CloudRadioSubPage.Home &&
            (radioVisible || artistsVisible || featuredPage != null),
    ) {
        when {
            radioVisible -> radioVisible = false
            artistsVisible -> artistsVisible = false
            else -> featuredPage = null
        }
    }
    // 内层：我的页 → 首页
    BackHandler(
        enabled = active && !searchVisible && selectedDetail == null &&
            featuredPage == null && artistAlbumsTarget == null &&
            !radioVisible && !artistsVisible && subPage == CloudSubPage.Mine,
    ) {
        subPage = CloudSubPage.Home
        selectedAccountPlaylistId = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!authState.isLoggedIn) {
            CloudMusicBlankState(
                title = stringResource(R.string.cloud_music_empty_title),
                subtitle = stringResource(R.string.cloud_music_login_prompt),
                actionText = stringResource(R.string.cloud_music_login_action),
                onActionClick = {
                    loginLauncher.launch(NeteaseWebLoginActivity.createIntent(context))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            return@Column
        }

        // 顶部五段入口行：电台/歌单广场/艺术家页的唯一入口通道（对齐旧版 IA）。
        // 搜索场展开时整行隐藏，与旧版一致。
        if (!searchVisible) {
            CloudMusicHomeEntryRow(
                selectedEntry = when {
                    radioVisible -> CloudHomeEntry.Radio
                    artistsVisible || artistAlbumsTarget != null -> CloudHomeEntry.Artist
                    featuredPage == CloudFeaturedPage.Playlists ||
                        featuredPage == CloudFeaturedPage.Charts -> CloudHomeEntry.Collection
                    featuredPage == CloudFeaturedPage.Artists -> CloudHomeEntry.Artist
                    subPage == CloudSubPage.Mine -> CloudHomeEntry.Mine
                    else -> CloudHomeEntry.Recommend
                },
                onEntryClick = ::switchEntry,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 列表↔详情转场（仿 AlbumPage 的 PageStackTransition 用法）
            PageStackTransition(
                secondaryKey = selectedDetail,
                modifier = Modifier.fillMaxSize(),
                label = "cloud detail transition",
                primaryContent = {
                    val currentArtistAlbums = artistAlbumsTarget
                    when {
                        currentArtistAlbums != null -> CloudMusicArtistAlbumsPage(
                            data = data,
                            scrollStates = scrollStates,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            artist = currentArtistAlbums,
                            onOpenAlbum = openAlbumDetail,
                            modifier = Modifier.fillMaxSize(),
                        )
                        artistsVisible -> CloudMusicArtistsPage(
                            data = data,
                            scrollStates = scrollStates,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            onOpenArtist = openArtistDetail,
                            modifier = Modifier.fillMaxSize(),
                        )
                        radioVisible -> CloudMusicRadioPage(
                            data = data,
                            scrollStates = scrollStates,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            subPage = radioSubPage,
                            onSubPageChange = { radioSubPage = it },
                            onOpenRadio = openRadioDetail,
                            modifier = Modifier.fillMaxSize(),
                        )
                        featuredPage != null -> CloudMusicFeaturedPage(
                            page = featuredPage!!,
                            data = data,
                            scrollStates = scrollStates,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            onOpenPlaylist = openPlaylistDetail,
                            onOpenAlbum = openAlbumDetail,
                            onOpenArtist = openArtistDetail,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> when (subPage) {
                            CloudSubPage.Home -> CloudMusicHomePage(
                                data = data,
                                scrollStates = scrollStates,
                                active = active,
                                playbackBarOverlayHeight = playbackBarOverlayHeight,
                                onOpenPlaylist = openPlaylistDetail,
                                onOpenAlbum = openAlbumDetail,
                                onOpenArtist = openArtistDetail,
                                onOpenFeatured = { featuredPage = it },
                                onOpenBannerTrack = openBannerTrack,
                                modifier = Modifier.fillMaxSize(),
                            )
                            CloudSubPage.Mine -> CloudMusicMinePage(
                                data = data,
                                scrollStates = scrollStates,
                                authStore = authStore,
                                active = active,
                                libraryRevision = accountLibraryRevision,
                                selectedFilter = mineFilter,
                                onFilterChange = { mineFilter = it },
                                selectedPlaylistId = selectedAccountPlaylistId,
                                playbackBarOverlayHeight = playbackBarOverlayHeight,
                                onOpenPlaylist = { item ->
                                    selectedAccountPlaylistId = item.playlistId
                                    selectedDetail = CloudDetailTarget.Playlist(
                                        id = item.playlistId,
                                        title = item.title,
                                        accountEditable = item.isEditable,
                                        artworkUrl = item.artworkUrl,
                                        subtitle = item.subtitle,
                                        trackCount = item.trackCount,
                                    )
                                },
                                onOpenAlbum = openAlbumDetail,
                                onOpenRadio = openRadioDetail,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                },
                secondaryContent = { target ->
                    CloudMusicDetailPage(
                        data = data,
                        authStore = authStore,
                        active = active,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        target = target,
                        onBack = { selectedDetail = null },
                        onAccountLibraryChanged = onAccountLibraryChanged,
                        onOpenArtistAlbums = (target as? CloudDetailTarget.Artist)?.let { artist ->
                            { artistAlbumsTarget = artist }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )

            // 搜索覆盖层（zIndex 分层，仿 shell 的 SearchOverlay）；
            // 从搜索结果点进详情时暂隐，返回详情后搜索层带着原 query 恢复。
            // 展开/收起过渡对齐旧版搜索场的竖直伸缩 + 淡入淡出。
            androidx.compose.animation.AnimatedVisibility(
                visible = searchVisible && selectedDetail == null,
                enter = expandVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    fadeIn(animationSpec = tween(140)),
                exit = shrinkVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
                    fadeOut(animationSpec = tween(120)),
                modifier = Modifier.fillMaxSize().zIndex(1f),
            ) {
                CloudMusicSearchPage(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    data = data,
                    scrollStates = scrollStates,
                    active = active,
                    selectedCategory = searchCategory,
                    onCategoryChange = { searchCategory = it },
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    onOpenPlaylist = openPlaylistDetail,
                    onOpenAlbum = openAlbumDetail,
                    onOpenArtist = openArtistDetail,
                    onCancel = {
                        searchVisible = false
                        searchQuery = ""
                        searchCategory = CloudSearchCategory.All
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
