package com.smartisan.music.ui.settings

import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.settings.*
import com.smartisan.music.launcher.AppIconManager
import com.smartisan.music.ui.online.NeteaseLoginActivity
import com.smartisan.music.ui.shell.PageStackTransition
import kotlinx.coroutines.launch

@Composable
internal fun SettingsPage(
    active: Boolean,
    secondaryPage: SettingsSecondaryPage?,
    onSecondaryPageChange: (SettingsSecondaryPage?) -> Unit,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    onClose: () -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAudioFxEnabledChange: (Boolean) -> Unit,
    onAudioFxPresetChange: (AudioFxPreset) -> Unit,
    onAudioFxCustomGainDbPointsChange: (List<Float>) -> Unit,
    onArtistSeparatorsChange: (Set<String>) -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appIconManager = remember(context) { AppIconManager(context) }
    var appIcon by remember(appIconManager) { mutableStateOf(appIconManager.currentIcon()) }
    var editingArtistSeparators by remember { mutableStateOf(false) }
    var artistSeparatorsInitialValues by remember { mutableStateOf(emptySet<String>()) }
    val latestOnArtistSeparatorsChange by rememberUpdatedState(onArtistSeparatorsChange)

    val appContext = context.applicationContext
    val container = LocalMusicAppContainer.current
    val authStore = container.neteaseAuthStore
    var neteaseSignedIn by remember { mutableStateOf(authStore.load().isLoggedIn) }
    var logoutConfirmationVisible by remember { mutableStateOf(false) }
    val onlineSettingsStore = container.onlineMusicSettingsStore
    val onlineSettings by onlineSettingsStore.settings.collectAsStateWithLifecycle(
        initialValue = OnlineMusicSettings(),
    )
    val onlineSettingsScope = rememberCoroutineScope()

    // 登录也可能发生在云音乐 tab 内，进入设置页时按存储重读一次，避免行值停留在旧登录态。
    LaunchedEffect(active) {
        if (active) {
            neteaseSignedIn = authStore.load().isLoggedIn
        }
    }

    val neteaseLoginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookieJson = result.data
                ?.getStringExtra(NeteaseLoginActivity.ExtraCookieJson)
                .orEmpty()
            if (cookieJson.isNotBlank() && authStore.saveCookieJson(cookieJson)) {
                Toast.makeText(context, R.string.netease_login_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.netease_login_cookie_missing, Toast.LENGTH_SHORT)
                    .show()
            }
        }
        neteaseSignedIn = authStore.load().isLoggedIn
    }

    BackHandler(enabled = active && secondaryPage != null) {
        onSecondaryPageChange(null)
    }
    BackHandler(enabled = active && secondaryPage == null) {
        onClose()
    }

    PageStackTransition(
        secondaryKey = secondaryPage,
        modifier = modifier.fillMaxSize().background(colorResource(R.color.page_background)),
        label = "settings page stack",
        primaryContent = {
            SettingsRootPage(
                active = active,
                playbackSettings = playbackSettings,
                artistSettings = artistSettings,
                navigationSettings = navigationSettings,
                themeMode = themeMode,
                appIcon = appIcon,
                neteaseSignedIn = neteaseSignedIn,
                playbackQuality = onlineSettings.neteasePlaybackQuality,
                onClose = onClose,
                onScratchEnabledChange = onScratchEnabledChange,
                onHidePlayerAxisEnabledChange = onHidePlayerAxisEnabledChange,
                onPopcornSoundEnabledChange = onPopcornSoundEnabledChange,
                onAccountClick = {
                    if (neteaseSignedIn) {
                        logoutConfirmationVisible = true
                    } else {
                        neteaseLoginLauncher.launch(NeteaseLoginActivity.createIntent(context))
                    }
                },
                onPlaybackQualityClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.OnlineQuality)
                },
                onAudioFxClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.AudioFx)
                },
                onArtistSeparatorsClick = {
                    artistSeparatorsInitialValues = artistSettings.separators
                    editingArtistSeparators = true
                },
                onNavigationClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.Navigation)
                },
                onAppIconClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.AppIcon)
                },
                onThemeClick = {
                    onSecondaryPageChange(SettingsSecondaryPage.Theme)
                },
                modifier = Modifier.fillMaxSize(),
            )
        },
        secondaryContent = { page ->
            when (page) {
                SettingsSecondaryPage.OnlineQuality ->
                    OnlineQualitySettingsPage(
                        active = active,
                        quality = onlineSettings.neteasePlaybackQuality,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onQualityChange = { selected ->
                            onlineSettingsScope.launch {
                                onlineSettingsStore.setNeteasePlaybackQuality(selected)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.AudioFx ->
                    AudioFxSettingsPage(
                        active = active,
                        playbackSettings = playbackSettings,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onAudioFxEnabledChange = onAudioFxEnabledChange,
                        onAudioFxPresetChange = onAudioFxPresetChange,
                        onAudioFxCustomGainDbPointsChange = onAudioFxCustomGainDbPointsChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Navigation ->
                    NavigationSettingsPage(
                        active = active,
                        navigationSettings = navigationSettings,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onTabPinnedChange = onTabPinnedChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.Theme ->
                    ThemeSettingsPage(
                        active = active,
                        themeMode = themeMode,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onThemeModeChange = onThemeModeChange,
                        modifier = Modifier.fillMaxSize(),
                    )
                SettingsSecondaryPage.AppIcon ->
                    AppIconSettingsPage(
                        active = active,
                        selectedIcon = appIcon,
                        onClose = {
                            onSecondaryPageChange(null)
                        },
                        onIconSelected = { selectedIcon ->
                            appIconManager
                                .setIcon(selectedIcon)
                                .onSuccess { appliedIcon ->
                                    appIcon = appliedIcon
                                }
                                .onFailure { error ->
                                    Log.e(
                                        "AppIconSettings",
                                        "Failed to change launcher icon",
                                        error,
                                    )
                                    Toast.makeText(
                                            context,
                                            R.string.app_icon_change_failed,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        },
    )

    if (editingArtistSeparators) {
        ArtistSeparatorsDialog(
            initialSeparators = artistSeparatorsInitialValues,
            onDismiss = { editingArtistSeparators = false },
            onConfirm = { separators ->
                editingArtistSeparators = false
                latestOnArtistSeparatorsChange(separators)
            },
        )
    }

    if (logoutConfirmationVisible) {
        NeteaseLogoutConfirmation(
            onDismiss = { logoutConfirmationVisible = false },
            onConfirm = {
                authStore.clear()
                // 登出必须一并抹掉 WebView 落盘的明文 cookie（含 MUSIC_U），避免第三方读到。
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                neteaseSignedIn = false
                logoutConfirmationVisible = false
                Toast.makeText(context, R.string.netease_logout_success, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

internal enum class SettingsSecondaryPage {
    OnlineQuality,
    AudioFx,
    Navigation,
    Theme,
    AppIcon,
}
