package com.smartisan.music.ui.shell.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player

/**
 * 底部播放条的状态宿主。
 *
 * 原先这些状态与播放器监听散在 `MusicAppShellContent` 的身体里；搬过来后保持既有语义：
 * - [snapshot]/[contentSnapshot] 仍然是 `remember(controller)`，切换 controller 时重建；
 * - 内容快照仍然只在有新内容时才覆盖，播放清空后保留最后一帧直到退场动画结束；
 * - [composed] 仍然是「组合过就留着」，退场动画收尾才撤回，底栏与内容区尺寸据此微调 6dp；
 * - 封面位图的 `produceState` 仍留在主壳，避免让位图跨组合边界回传。
 *
 * 返回的是按状态 remember 住的稳定包装对象，读取都发生在各自的组合域内，不引入第二套状态源。
 */
internal class PlaybackBarHost internal constructor(
    private val snapshotState: MutableState<PlaybackBarSnapshot>,
    private val contentSnapshotState: MutableState<PlaybackBarSnapshot>,
    private val composedState: MutableState<Boolean>,
) {
    /** 播放器实时快照。 */
    var snapshot: PlaybackBarSnapshot by snapshotState

    /** 播放条绘制用的快照，清空播放后仍保留最后一次有内容的值。 */
    var contentSnapshot: PlaybackBarSnapshot by contentSnapshotState

    /** 播放条是否已经组合过。 */
    var composed: Boolean by composedState

    /** 当前是否要求显示播放条。 */
    val requestedVisible: Boolean
        get() = snapshotState.value.mediaItem != null

    /** 退场动画结束：只有确实不需要可见时才撤回组合。 */
    fun onHidden() {
        if (!requestedVisible) {
            composedState.value = false
        }
    }
}

@Composable
internal fun rememberPlaybackBarHost(controller: Player?): PlaybackBarHost {
    val snapshotState =
        remember(controller) {
            mutableStateOf(controller.playbackBarSnapshot())
        }
    val contentSnapshotState =
        remember(controller) {
            mutableStateOf(snapshotState.value)
        }
    val composedState = remember { mutableStateOf(false) }
    DisposableEffect(controller) {
        if (controller == null) {
            snapshotState.value = PlaybackBarSnapshot()
            return@DisposableEffect onDispose {}
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val nextSnapshot = player.playbackBarSnapshot()
                    snapshotState.value = nextSnapshot
                    if (nextSnapshot.mediaItem != null) {
                        contentSnapshotState.value = nextSnapshot
                    }
                }
            }
        controller.addListener(listener)
        val initialSnapshot = controller.playbackBarSnapshot()
        snapshotState.value = initialSnapshot
        if (initialSnapshot.mediaItem != null) {
            contentSnapshotState.value = initialSnapshot
        }
        onDispose {
            controller.removeListener(listener)
        }
    }
    val requestedVisible = snapshotState.value.mediaItem != null
    LaunchedEffect(requestedVisible) {
        if (requestedVisible) {
            composedState.value = true
        }
    }
    return remember(snapshotState, contentSnapshotState, composedState) {
        PlaybackBarHost(snapshotState, contentSnapshotState, composedState)
    }
}
