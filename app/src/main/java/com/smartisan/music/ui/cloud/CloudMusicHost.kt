package com.smartisan.music.ui.cloud

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.online.NeteaseWebLoginActivity
import com.smartisan.music.ui.shell.PageStackTransition

/** 云音乐 tab 内部一级页面。 */
internal enum class CloudSubPage {
    Home,
    Mine,
}

/** 详情页目标：歌单 / 专辑 / 艺人三种，由宿主持有、PageStackTransition 驱动列表↔详情转场。 */
internal sealed interface CloudDetailTarget {
    data class Playlist(val id: String, val title: String) : CloudDetailTarget
    data class Album(val id: String, val title: String) : CloudDetailTarget
    data class Artist(val id: String, val name: String) : CloudDetailTarget
}

/**
 * 云音乐宿主页：未登录引导 + 已登录内容区。
 *
 * 状态全部集中在本宿主，子页面均为受控组件（数据/交互回调来自宿主），
 * 与作者新版框架的 [com.smartisan.music.ui.shell.MusicAppShell] 组织方式一致：
 * - 一级页面（首页/我的）用 [CloudSubPage] 状态切换；
 * - 详情页用 [PageStackTransition] 驱动列表↔详情转场，secondaryKey = [CloudDetailTarget]；
 * - 搜索作为全页覆盖层（zIndex 分层），query 由宿主持有（受控）；
 * - 返回键用 [BackHandler] 按条件逐层关闭。
 */
@Composable
internal fun CloudMusicHost(
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val authStore = remember(appContext) { NeteaseAuthStore(appContext) }
    val repositoryRouter = remember(appContext) { OnlineMusicRepositoryRouter(appContext) }
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
    var subPage by rememberSaveable { mutableStateOf(CloudSubPage.Home) }
    var searchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedDetail by remember { mutableStateOf<CloudDetailTarget?>(null) }

    // ── 返回键：逐层关闭（仿 shell 的 BackHandler 条件链） ──
    // 最外层：搜索覆盖层
    BackHandler(enabled = active && searchVisible) {
        searchVisible = false
        searchQuery = ""
    }
    // 中层：详情页
    BackHandler(enabled = active && !searchVisible && selectedDetail != null) {
        selectedDetail = null
    }
    // 内层：我的页 → 首页
    BackHandler(enabled = active && !searchVisible && selectedDetail == null && subPage == CloudSubPage.Mine) {
        subPage = CloudSubPage.Home
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

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // 列表↔详情转场（仿 AlbumPage 的 PageStackTransition 用法）
            PageStackTransition(
                secondaryKey = selectedDetail,
                modifier = Modifier.fillMaxSize(),
                label = "cloud detail transition",
                primaryContent = {
                    when (subPage) {
                        CloudSubPage.Home -> CloudMusicHomePage(
                            repository = neteaseRepository,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            onOpenSearch = {
                                searchQuery = ""
                                searchVisible = true
                            },
                            onOpenMine = { subPage = CloudSubPage.Mine },
                            onOpenPlaylist = { id, title ->
                                selectedDetail = CloudDetailTarget.Playlist(id, title)
                            },
                            onOpenAlbum = { id, title ->
                                selectedDetail = CloudDetailTarget.Album(id, title)
                            },
                            onOpenArtist = { id, name ->
                                selectedDetail = CloudDetailTarget.Artist(id, name)
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        CloudSubPage.Mine -> CloudMusicMinePage(
                            repository = neteaseRepository,
                            authStore = authStore,
                            active = active,
                            playbackBarOverlayHeight = playbackBarOverlayHeight,
                            onOpenPlaylist = { id, title ->
                                selectedDetail = CloudDetailTarget.Playlist(id, title)
                            },
                            onOpenAlbum = { id, title ->
                                selectedDetail = CloudDetailTarget.Album(id, title)
                            },
                            onBack = { subPage = CloudSubPage.Home },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                secondaryContent = { target ->
                    CloudMusicDetailPage(
                        repository = neteaseRepository,
                        authStore = authStore,
                        active = active,
                        playbackBarOverlayHeight = playbackBarOverlayHeight,
                        target = target,
                        onBack = { selectedDetail = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )

            // 搜索覆盖层（zIndex 分层，仿 shell 的 SearchOverlay）
            if (searchVisible) {
                CloudMusicSearchPage(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    active = active,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    repository = neteaseRepository,
                    onCancel = {
                        searchVisible = false
                        searchQuery = ""
                    },
                    modifier = Modifier.fillMaxSize().zIndex(1f),
                )
            }
        }
    }
}
