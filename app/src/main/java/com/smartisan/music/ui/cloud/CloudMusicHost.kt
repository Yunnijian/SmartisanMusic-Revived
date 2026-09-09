package com.smartisan.music.ui.cloud

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicSearchBarHeight
import com.smartisan.music.ui.cloud.components.CloudMusicSectionTitle
import com.smartisan.music.ui.cloud.components.CloudSecondaryTextColor
import com.smartisan.music.ui.cloud.components.cloudMusicPressable
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import com.smartisan.music.ui.online.NeteaseWebLoginActivity

/**
 * 云音乐宿主页（垂直切片：登录 + 搜索 + 播放对接）。
 *
 * - 未登录：引导登录，点击拉起 [NeteaseWebLoginActivity]，登录态落在移植好的 [NeteaseAuthStore]；
 * - 已登录：提供搜索入口，进入 [CloudMusicSearchPage]；
 * - 首页推荐 / 我的 / 电台 / 歌单详情等旧版云音乐页面未随切片移植，保留结构便于后续扩展。
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
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }

    // 登录页返回后刷新登录态（点完成返回 RESULT_OK + cookie json，取消则保持原状）。
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

    // 页面重新激活时同步一次最新登录态（覆盖从设置等其它入口登录/退出的场景）。
    LaunchedEffect(active) {
        if (active) {
            authState = authStore.load()
        }
    }

    BackHandler(enabled = active && searchActive) {
        query = ""
        searchActive = false
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

        if (searchActive) {
            CloudMusicSearchPage(
                query = query,
                onQueryChange = { value -> query = value },
                repository = neteaseRepository,
                active = active,
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                onCancel = {
                    query = ""
                    searchActive = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            CloudMusicLoggedInHome(
                onSearchEntryClick = { searchActive = true },
                playbackBarOverlayHeight = playbackBarOverlayHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

/** 已登录宿主页：分区标题 + 搜索入口。后续可在此继续挂载推荐 / 歌单等内容区块。 */
@Composable
private fun CloudMusicLoggedInHome(
    onSearchEntryClick: () -> Unit,
    playbackBarOverlayHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = playbackBarOverlayHeight),
    ) {
        CloudMusicSectionTitle(
            title = stringResource(R.string.cloud_music_empty_title),
            modifier = Modifier.fillMaxWidth(),
        )
        CloudMusicSearchEntryRow(
            hint = stringResource(R.string.cloud_music_search_hint_netease),
            onClick = onSearchEntryClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 搜索入口行：复用原版搜索框背景，点击进入在线搜索页。 */
@Composable
private fun CloudMusicSearchEntryRow(
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(CloudMusicSearchBarHeight)
            .background(Color.White)
            .cloudMusicPressable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
            SmartisanDrawableBackground(
                drawableRes = R.drawable.search_field,
                modifier = Modifier.matchParentSize(),
            )
            Image(
                painter = painterResource(R.drawable.search_bar_left_icon),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 6.dp)
                    .width(24.dp)
                    .height(30.dp),
            )
            Text(
                text = hint,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = CloudSecondaryTextColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 36.dp, end = 12.dp),
            )
        }
    }
}
