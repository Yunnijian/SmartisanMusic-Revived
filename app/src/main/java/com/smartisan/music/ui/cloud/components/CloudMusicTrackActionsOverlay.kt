package com.smartisan.music.ui.cloud.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 歌曲「更多」菜单中的一个操作项。 */
internal data class CloudMusicTrackAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 歌曲操作底部弹层：底部滑入的白色圆角面板 + 半透明遮罩（点击遮罩关闭）。
 * 顶部展示歌曲名，下方为 [actions] 操作列表；破坏性操作使用强调色文字。
 */
@Composable
internal fun CloudMusicTrackActionsOverlay(
    visible: Boolean,
    trackTitle: String,
    actions: List<CloudMusicTrackAction>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 半透明遮罩，点击关闭。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.40f))
                    .cloudMusicPressable(onClick = onDismiss),
            )
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .background(CloudSurfaceColor)
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = trackTitle,
                        style = TextStyle(
                            fontSize = 13.sp,
                            color = CloudSecondaryTextColor,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    )
                    CloudMusicDivider()
                    actions.forEach { action ->
                        Text(
                            text = action.label,
                            style = TextStyle(
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (action.destructive) CloudAccentColor else CloudTrackTitleColor,
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .cloudMusicPressable(onClick = action.onClick)
                                .padding(vertical = 16.dp),
                        )
                        CloudMusicDivider()
                    }
                    // 底部留白，贴合系统手势条区域。
                    Box(modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        }
    }
}