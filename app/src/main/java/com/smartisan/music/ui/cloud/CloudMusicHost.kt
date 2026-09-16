package com.smartisan.music.ui.cloud

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.ui.cloud.components.CloudHomeEntry
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicHomeEntryRow
import com.smartisan.music.ui.online.NeteaseWebLoginActivity
import com.smartisan.music.ui.shell.PageStackTransition
import com.smartisan.music.ui.navigation.SmartisanNavigationDuration
import kotlin.math.cos

/** 一级页横向推入的缓动，与导航位移同款（cos 曲线）。 */
private val CloudPrimaryTransitionEasing = Easing { ((1.0 - cos(it * Math.PI)) / 2.0).toFloat() }

/**
 * 云音乐宿主页：未登录引导 + 已登录内容区。
 *
 * 界面状态集中在 [CloudMusicHostViewModel]（登录态、一级页、推进层、搜索场、详情目标），
 * 本组件只做编排与渲染：数据（[CloudMusicDataStore]）与滚动位置仍在宿主组合层持有，
 * 页面在入口层之间来回切换时不重新联网、不丢列表位置。
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
    val container = LocalMusicAppContainer.current
    val authStore = container.neteaseAuthStore
    val repositoryRouter = container.onlineRepositoryRouter
    val neteaseRepository =
        remember(repositoryRouter) {
            repositoryRouter.repositoryFor(OnlineMusicProvider.Netease)
        }
    val viewModel: CloudMusicHostViewModel =
        viewModel(factory = CloudMusicHostViewModel.factory(authStore))

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
        if (cookieJson.isNotBlank() && viewModel.saveLoginCookie(cookieJson)) {
            viewModel.reloadAuthState()
            Toast.makeText(context, R.string.netease_login_success, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, R.string.netease_login_cookie_missing, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(active) {
        if (active) {
            viewModel.reloadAuthState()
        }
    }

    val dataScope = rememberCoroutineScope()
    val data = rememberCloudMusicDataStore(neteaseRepository, dataScope)
    val scrollStates = remember { CloudMusicScrollStates() }

    // 标题栏搜索按钮在云页时由壳递增请求计数；这里展开页内搜索场并消费请求。
    LaunchedEffect(active, searchOpenRequest) {
        if (active && searchOpenRequest > 0) {
            viewModel.searchVisible = true
            onSearchOpenRequestHandled()
        }
    }

    // ── 返回键：逐层关闭（仿 shell 的 BackHandler 条件链） ──
    BackHandler(enabled = active && viewModel.searchVisible && viewModel.selectedDetail == null) {
        viewModel.searchVisible = false
    }
    BackHandler(enabled = active && viewModel.selectedDetail != null) {
        viewModel.selectedDetail = null
    }
    BackHandler(
        enabled = active && !viewModel.searchVisible && viewModel.selectedDetail == null &&
            viewModel.artistAlbumsTarget != null,
    ) {
        viewModel.artistAlbumsTarget = null
    }
    BackHandler(
        enabled = active && !viewModel.searchVisible && viewModel.selectedDetail == null &&
            viewModel.artistAlbumsTarget == null && viewModel.radioVisible &&
            viewModel.radioSubPage != CloudRadioSubPage.Home,
    ) {
        viewModel.radioSubPage = CloudRadioSubPage.Home
    }
    BackHandler(
        enabled = active && !viewModel.searchVisible && viewModel.selectedDetail == null &&
            viewModel.artistAlbumsTarget == null && viewModel.radioSubPage == CloudRadioSubPage.Home &&
            (viewModel.radioVisible || viewModel.artistsVisible ||
                viewModel.featuredPage != null || viewModel.dailyVisible),
    ) {
        when {
            viewModel.radioVisible -> viewModel.radioVisible = false
            viewModel.artistsVisible -> viewModel.artistsVisible = false
            viewModel.dailyVisible -> viewModel.dailyVisible = false
            else -> viewModel.featuredPage = null
        }
    }
    BackHandler(
        enabled = active && !viewModel.searchVisible && viewModel.selectedDetail == null &&
            viewModel.featuredPage == null && viewModel.artistAlbumsTarget == null &&
            !viewModel.radioVisible && !viewModel.artistsVisible && !viewModel.dailyVisible &&
            viewModel.subPage == CloudSubPage.Mine,
    ) {
        viewModel.subPage = CloudSubPage.Home
        viewModel.selectedAccountPlaylistId = null
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!viewModel.authState.isLoggedIn) {
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

        if (!viewModel.searchVisible) {
            CloudMusicHomeEntryRow(
                selectedEntry = when {
                    viewModel.radioVisible -> CloudHomeEntry.Radio
                    viewModel.artistsVisible || viewModel.artistAlbumsTarget != null ->
                        CloudHomeEntry.Artist
                    viewModel.featuredPage == CloudFeaturedPage.Playlists ||
                        viewModel.featuredPage == CloudFeaturedPage.Charts -> CloudHomeEntry.Collection
                    viewModel.featuredPage == CloudFeaturedPage.Artists -> CloudHomeEntry.Artist
                    viewModel.subPage == CloudSubPage.Mine && !viewModel.dailyVisible ->
                        CloudHomeEntry.Mine
                    else -> CloudHomeEntry.Recommend
                },
                onEntryClick = viewModel::switchEntry,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            PageStackTransition(
                secondaryKey = viewModel.selectedDetail,
                modifier = Modifier.fillMaxSize(),
                label = "cloud detail transition",
                primaryContent = {
                    AnimatedContent<CloudPrimaryPage>(
                        targetState = viewModel.primaryPage,
                        transitionSpec = {
                            val spec: FiniteAnimationSpec<IntOffset> = tween(
                                SmartisanNavigationDuration,
                                easing = CloudPrimaryTransitionEasing,
                            )
                            if (targetState.order >= initialState.order) {
                                slideInHorizontally(animationSpec = spec) { fullWidth -> fullWidth } togetherWith
                                    slideOutHorizontally(animationSpec = spec) { fullWidth -> -fullWidth }
                            } else {
                                slideInHorizontally(animationSpec = spec) { fullWidth -> -fullWidth } togetherWith
                                    slideOutHorizontally(animationSpec = spec) { fullWidth -> fullWidth }
                            }
                        },
                        label = "cloud primary transition",
                    ) { page ->
                        CloudMusicHostPrimaryContent(
                            page = page,
                            data = data,
                            scrollStates = scrollStates,
                            authStore = authStore,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            viewModel = viewModel,
                        )
                    }
                },
                secondaryContent = { target ->
                    CloudMusicDetailPage(
                        data = data,
                        authStore = authStore,
                        active = active,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        target = target,
                        onBack = { viewModel.selectedDetail = null },
                        onAccountLibraryChanged = viewModel.onAccountLibraryChanged,
                        onOpenArtistAlbums = (target as? CloudDetailTarget.Artist)?.let { artist ->
                            { viewModel.artistAlbumsTarget = artist }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )

            CloudMusicSearchOverlay(
                viewModel = viewModel,
                data = data,
                scrollStates = scrollStates,
                active = active,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
            )
        }
    }
}

/** 一级页与各推进层的内容分发（歌手/电台/「查看全部」/首页/我的）。 */
@Composable
private fun CloudMusicHostPrimaryContent(
    page: CloudPrimaryPage,
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    authStore: NeteaseAuthStore,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    viewModel: CloudMusicHostViewModel,
) {
    when (page) {
        CloudPrimaryPage.Daily -> CloudMusicDailyPage(
            data = data,
            scrollStates = scrollStates,
            active = active,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            tab = viewModel.dailyTab,
            onTabChange = { viewModel.dailyTab = it },
            modifier = Modifier.fillMaxSize(),
        )
        is CloudPrimaryPage.ArtistAlbums -> CloudMusicArtistAlbumsPage(
            data = data,
            scrollStates = scrollStates,
            active = active,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            artist = page.artist,
            onOpenAlbum = viewModel::openAlbumDetail,
            modifier = Modifier.fillMaxSize(),
        )
        CloudPrimaryPage.Artists -> CloudMusicArtistsPage(
            data = data,
            scrollStates = scrollStates,
            active = active,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            onOpenArtist = viewModel::openArtistDetail,
            modifier = Modifier.fillMaxSize(),
        )
        CloudPrimaryPage.Radio -> CloudMusicRadioPage(
            data = data,
            scrollStates = scrollStates,
            active = active,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            subPage = viewModel.radioSubPage,
            onSubPageChange = { viewModel.radioSubPage = it },
            onOpenRadio = viewModel::openRadioDetail,
            modifier = Modifier.fillMaxSize(),
        )
        is CloudPrimaryPage.Featured -> CloudMusicFeaturedPage(
            page = page.page,
            data = data,
            scrollStates = scrollStates,
            active = active,
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            onOpenPlaylist = viewModel::openPlaylistDetail,
            onOpenAlbum = viewModel::openAlbumDetail,
            onOpenArtist = viewModel::openArtistDetail,
            modifier = Modifier.fillMaxSize(),
        )
        is CloudPrimaryPage.Entry -> when (page.subPage) {
            CloudSubPage.Home -> CloudMusicHomePage(
                data = data,
                scrollStates = scrollStates,
                active = active,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                onOpenPlaylist = viewModel::openPlaylistDetail,
                onOpenAlbum = viewModel::openAlbumDetail,
                onOpenArtist = viewModel::openArtistDetail,
                onOpenFeatured = { viewModel.featuredPage = it },
                onOpenBannerTrack = viewModel::openBannerTrack,
                onOpenDaily = viewModel::openDaily,
                modifier = Modifier.fillMaxSize(),
            )
            CloudSubPage.Mine -> CloudMusicMinePage(
                data = data,
                scrollStates = scrollStates,
                authStore = authStore,
                active = active,
                libraryRevision = viewModel.accountLibraryRevision,
                selectedFilter = viewModel.mineFilter,
                onFilterChange = { viewModel.mineFilter = it },
                selectedPlaylistId = viewModel.selectedAccountPlaylistId,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                onOpenPlaylist = { item ->
                    viewModel.selectedAccountPlaylistId = item.playlistId
                    viewModel.selectedDetail = CloudDetailTarget.Playlist(
                        id = item.playlistId,
                        title = item.title,
                        accountEditable = item.isEditable,
                        artworkUrl = item.artworkUrl,
                        subtitle = item.subtitle,
                        trackCount = item.trackCount,
                    )
                },
                onOpenAlbum = viewModel::openAlbumDetail,
                onOpenRadio = viewModel::openRadioDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 搜索全页覆盖层：zIndex 分层，从结果进详情时暂隐、返回后带着原 query 恢复。 */
@Composable
private fun CloudMusicSearchOverlay(
    viewModel: CloudMusicHostViewModel,
    data: CloudMusicDataStore,
    scrollStates: CloudMusicScrollStates,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
) {
    AnimatedVisibility(
        visible = viewModel.searchVisible && viewModel.selectedDetail == null,
        enter = expandVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
            fadeIn(animationSpec = tween(140)),
        exit = shrinkVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
            fadeOut(animationSpec = tween(120)),
        modifier = Modifier.fillMaxSize().zIndex(1f),
    ) {
        CloudMusicSearchPage(
            query = viewModel.searchQuery,
            onQueryChange = { viewModel.searchQuery = it },
            data = data,
            scrollStates = scrollStates,
            active = active,
            selectedCategory = viewModel.searchCategory,
            onCategoryChange = { viewModel.searchCategory = it },
            playbackBarOverlayHeight = playbackBarOverlayHeight,
            onOpenPlaylist = viewModel::openPlaylistDetail,
            onOpenAlbum = viewModel::openAlbumDetail,
            onOpenArtist = viewModel::openArtistDetail,
            onCancel = {
                viewModel.searchVisible = false
                viewModel.searchQuery = ""
                viewModel.searchCategory = CloudSearchCategory.All
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
