package com.smartisan.music.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.await
import com.smartisan.music.playback.invalidateLibrary

fun hasAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED
}

fun audioPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

/** 资料库空状态需要的权限信息：当前是否已授权 + 跳转系统详情页的入口。 */
internal class AudioPermissionState internal constructor(
    val granted: Boolean,
    val openPermissionSettings: () -> Unit,
)

/**
 * 权限感知的本地音乐状态，直接读 checkSelfPermission，不引入任何跨层状态源。
 *
 * - 每次 ON_RESUME 重新判定授权（与 GlobalSearchScreen 的 permissionVersion 同一套先例），
 *   因此系统弹窗授权、系统详情页授权两条返回路径都能被看到；
 * - 由「未授权」翻成「已授权」时，走既有的 INVALIDATE_LIBRARY 自定义 session command
 *   让 MediaLibrarySession notifyChildrenChanged，主壳的 rememberLibraryMediaState 随之重读资料库。
 *   项目里 MediaStore 变化没有被 ContentObserver 被动监听，必须显式触发这一次。
 *
 * 各页面各持一份该状态，同时挂载时最坏会各发一次刷新命令；服务端在单线程 executor 上串行处理，
 * 因此只是多扫一次资料库，不会互相打架。
 */
@Composable
internal fun rememberAudioPermissionState(): AudioPermissionState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val browser = LocalPlaybackBrowser.current
    var granted by remember { mutableStateOf(hasAudioPermission(context)) }
    // 启动时就有权限的情况交给主壳正常加载，只有「先被拒绝后被放行」才补一次刷新。
    var needsLibraryRefresh by remember { mutableStateOf(!granted) }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    granted = hasAudioPermission(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val settingsLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            granted = hasAudioPermission(context)
        }

    LaunchedEffect(granted, browser, needsLibraryRefresh) {
        if (!granted || !needsLibraryRefresh) {
            return@LaunchedEffect
        }
        val playbackBrowser = browser ?: return@LaunchedEffect
        // 只在命令确实送达后清零，否则保持待刷新，下次 ON_RESUME 还会重试。
        runCatching { playbackBrowser.invalidateLibrary()?.await(context) }.onSuccess {
            needsLibraryRefresh = false
        }
    }

    return AudioPermissionState(
        granted = granted,
        openPermissionSettings = {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            )
        },
    )
}
