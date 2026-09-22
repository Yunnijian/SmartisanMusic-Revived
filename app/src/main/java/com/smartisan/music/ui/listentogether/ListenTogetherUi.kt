package com.smartisan.music.ui.listentogether

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.playback.LocalPlaybackController

/**
 * 无 UI 的一起听会话接线：把 [LocalPlaybackController] 挂到应用级 Store 上，
 * 使轮询能读取/控制播放器；Store 里的错误信息以 Toast 提示。
 *
 * 邀请确认弹层也挂在这里：[ListenTogetherInviteConfirmOverlay] 是全 App 唯一的入房确认入口。
 * 挂在整个 App 壳层，保证用户离开播放页后同步不中断。
 */
@Composable
internal fun ListenTogetherSessionWiring() {
    val container = LocalMusicAppContainer.current
    val store = container.listenTogetherStore
    val controller = LocalPlaybackController.current
    val state by store.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(controller, store) {
        controller?.let(store::attach)
        onDispose { store.detach() }
    }

    // 壳层生命周期就是前后台边界：退到后台且没在播放时停轮询，回前台恢复。
    DisposableEffect(lifecycleOwner, store) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> store.setAppForeground(true)
                    Lifecycle.Event.ON_STOP -> store.setAppForeground(false)
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 事件只在转换时派发，壳层重建时按当前状态对齐一次，别让闸门停在旧值上。
        store.setAppForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    ListenTogetherInviteConfirmOverlay()
}
