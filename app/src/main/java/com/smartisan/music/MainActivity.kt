package com.smartisan.music

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.smartisan.music.data.settings.ThemeSettingsStore
import com.smartisan.music.listentogether.ListenTogetherInviteHost
import com.smartisan.music.listentogether.ListenTogetherInvitePath
import com.smartisan.music.listentogether.ListenTogetherShortHost
import com.smartisan.music.ui.artwork.AlbumArtworkBrowserHost
import com.smartisan.music.ui.components.audioPermission
import com.smartisan.music.ui.components.hasAudioPermission
import com.smartisan.music.ui.shell.MusicAppShell
import com.smartisan.music.ui.theme.MusicTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var playbackLaunchRequest by mutableIntStateOf(0)
    private var externalAudioLaunchRequestId by mutableIntStateOf(0)
    private var externalAudioLaunchRequest by mutableStateOf<ExternalAudioLaunchRequest?>(null)
    private var startupContentReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val themeSettingsStore = ThemeSettingsStore(this)
        AppCompatDelegate.setDefaultNightMode(themeSettingsStore.currentMode().appCompatNightMode)
        val splashScreen = installSplashScreen()
        // Android SplashScreen guidance checked 2026-08-25: the per-frame predicate only reads
        // memory state.
        splashScreen.setKeepOnScreenCondition { !startupContentReady }
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            delay(StartupSplashTimeoutMillis)
            startupContentReady = true
        }
        consumeLaunchIntent(intent)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            CompositionLocalProvider(
                LocalMusicAppContainer provides
                    (application as SmartisanMusicApplication).musicAppContainer,
            ) {
                MusicTheme {
                    RequestAudioPermissionOnLaunch()
                    AlbumArtworkBrowserHost {
                        MusicAppShell(
                            playbackLaunchRequest = playbackLaunchRequest,
                            externalAudioLaunchRequest = externalAudioLaunchRequest,
                            onExternalAudioLaunchConsumed = ::clearExternalAudioLaunchRequest,
                            onStartupReady = {
                                startupContentReady = true
                            },
                            onThemeModeChange = { mode ->
                                themeSettingsStore.setMode(mode)
                                AppCompatDelegate.setDefaultNightMode(mode.appCompatNightMode)
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        val launchIntent = intent ?: return
        if (isPlaybackLaunchIntent(launchIntent) && !isConsumedPlaybackLaunchIntent(launchIntent)) {
            playbackLaunchRequest += 1
            launchIntent.putExtra(ExtraOpenPlaybackConsumed, true)
            return
        }

        val listenTogetherUrl = launchIntent.listenTogetherInviteUrlOrNull()
        if (listenTogetherUrl != null && !isConsumedListenTogetherInvite(launchIntent)) {
            (application as SmartisanMusicApplication).musicAppContainer
                .listenTogetherStore.joinRoomFromUrl(listenTogetherUrl)
            launchIntent.putExtra(ExtraListenTogetherConsumed, true)
            return
        }

        val externalAudioMimeType = launchIntent.resolveType(contentResolver)
        if (
            isExternalAudioLaunchIntent(launchIntent, externalAudioMimeType) &&
                !isConsumedExternalAudioLaunchIntent(launchIntent)
        ) {
            externalAudioLaunchRequestId += 1
            externalAudioLaunchRequest =
                ExternalAudioLaunchRequest(
                    requestId = externalAudioLaunchRequestId,
                    uri = requireNotNull(launchIntent.data),
                    mimeType = externalAudioMimeType,
                    displayName = resolveExternalAudioDisplayName(launchIntent.data),
                )
            launchIntent.putExtra(ExtraExternalAudioConsumed, true)
        }
    }

    private fun Intent?.listenTogetherInviteUrlOrNull(): String? {
        if (this?.action != Intent.ACTION_VIEW) {
            return null
        }
        val uri = data ?: return null
        if (uri.scheme != "https") {
            return null
        }
        val host = uri.host ?: return null
        val isLongInvite =
            host.equals(ListenTogetherInviteHost, ignoreCase = true) &&
                uri.path?.startsWith(ListenTogetherInvitePath) == true
        val isShortInvite = host.equals(ListenTogetherShortHost, ignoreCase = true)
        return if (isLongInvite || isShortInvite) uri.toString() else null
    }

    private fun isConsumedListenTogetherInvite(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(ExtraListenTogetherConsumed, false) == true
    }

    private fun clearExternalAudioLaunchRequest(requestId: Int) {
        if (externalAudioLaunchRequest?.requestId == requestId) {
            externalAudioLaunchRequest = null
        }
    }

    private fun resolveExternalAudioDisplayName(uri: Uri?): String? {
        uri ?: return null
        if (uri.scheme == ContentScheme) {
            runCatching {
                contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )
            }
                .getOrNull()
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) {
                            return cursor.getString(column)?.takeIf(String::isNotBlank)
                        }
                    }
                }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
    }

    companion object {
        private const val ActionOpenPlayback = "com.smartisan.music.action.OPEN_PLAYBACK"
        private const val ExtraOpenPlayback = "com.smartisan.music.extra.OPEN_PLAYBACK"
        private const val ExtraOpenPlaybackConsumed =
            "com.smartisan.music.extra.OPEN_PLAYBACK_CONSUMED"
        private const val ExtraExternalAudioConsumed =
            "com.smartisan.music.extra.EXTERNAL_AUDIO_CONSUMED"
        private const val ExtraListenTogetherConsumed =
            "com.smartisan.music.extra.LISTEN_TOGETHER_CONSUMED"
        private const val ContentScheme = "content"
        private const val FileScheme = "file"
        private const val StartupSplashTimeoutMillis = 2_500L

        fun createOpenPlaybackIntent(context: Context): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ActionOpenPlayback
                putExtra(ExtraOpenPlayback, true)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        private fun isPlaybackLaunchIntent(intent: Intent?): Boolean {
            return intent?.action == ActionOpenPlayback ||
                intent?.getBooleanExtra(ExtraOpenPlayback, false) == true
        }

        private fun isConsumedPlaybackLaunchIntent(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(ExtraOpenPlaybackConsumed, false) == true
        }

        private fun isExternalAudioLaunchIntent(intent: Intent?, mimeType: String?): Boolean {
            val uri = intent?.data ?: return false
            val normalizedMimeType = mimeType?.lowercase() ?: return false
            return intent.action == Intent.ACTION_VIEW &&
                uri.scheme in setOf(ContentScheme, FileScheme) &&
                (normalizedMimeType.startsWith("audio/") ||
                    normalizedMimeType == "application/ogg" ||
                    normalizedMimeType == "application/x-ogg" ||
                    normalizedMimeType == "application/itunes")
        }

        private fun isConsumedExternalAudioLaunchIntent(intent: Intent?): Boolean {
            return intent?.getBooleanExtra(ExtraExternalAudioConsumed, false) == true
        }
    }
}

@Composable
private fun RequestAudioPermissionOnLaunch() {
    val context = LocalContext.current
    val permission = audioPermission()
    // 首装时两个系统权限弹窗同帧 launch 会互相顶掉，所以串行：音频结果落定后再申请通知。
    var audioPermissionSettled by rememberSaveable {
        mutableStateOf(hasAudioPermission(context))
    }
    // 结果回调不在这里同步权限状态：被拒绝后由资料库空状态按 checkSelfPermission 展示引导入口，
    // 用户从系统弹窗或系统详情页授权返回后，由 rememberAudioPermissionState 统一触发资料库刷新。
    // 授权与被拒都要放行通知权限请求，否则拒绝音频权限会连带通知权限永远不申请。
    val permissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {
            audioPermissionSettled = true
        }

    LaunchedEffect(permission) {
        if (hasAudioPermission(context)) {
            audioPermissionSettled = true
            return@LaunchedEffect
        }
        permissionLauncher.launch(permission)
    }

    // Android 13+ 媒体通知必须运行时申请；被拒不影响播放，仅失去通知栏/锁屏控制入口，结果无需同步。
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(audioPermissionSettled) {
        if (!audioPermissionSettled) {
            return@LaunchedEffect
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
