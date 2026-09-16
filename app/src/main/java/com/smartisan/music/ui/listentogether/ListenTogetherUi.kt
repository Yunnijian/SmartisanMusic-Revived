package com.smartisan.music.ui.listentogether

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.playback.LocalPlaybackController

/**
 * 无 UI 的一起听会话接线：把 [LocalPlaybackController] 挂到应用级 Store 上，
 * 使轮询能读取/控制播放器；Store 里的错误信息以 Toast 提示。
 *
 * 挂在整个 App 壳层，保证用户离开播放页后同步不中断。
 */
@Composable
internal fun ListenTogetherSessionWiring() {
    val container = LocalMusicAppContainer.current
    val store = container.listenTogetherStore
    val controller = LocalPlaybackController.current
    val state by store.state.collectAsState()
    val context = LocalContext.current

    DisposableEffect(controller, store) {
        controller?.let(store::attach)
        onDispose { store.detach() }
    }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
