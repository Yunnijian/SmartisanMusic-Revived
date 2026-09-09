package com.smartisan.music.ui.cloud.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 云音乐统一按压动效：按压时整体缩放到 [pressedScale]。
 * 与 ripple 不同，这里使用 indication = null 保持锤子风格的干净触感。
 */
fun Modifier.cloudMusicPressable(
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed && enabled) pressedScale else 1f
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            indication = null,
            interactionSource = interactionSource,
            onClick = onClick,
        )
}
