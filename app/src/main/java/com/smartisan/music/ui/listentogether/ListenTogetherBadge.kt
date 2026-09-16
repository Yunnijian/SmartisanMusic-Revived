package com.smartisan.music.ui.listentogether

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.listentogether.ListenTogetherConnectionState
import com.smartisan.music.ui.cloud.components.CloudMusicCoverImage

/**
 * 播放页碟片下方的「一起听」状态：双头像 + 耳机装饰弧 + 累计时长。
 *
 * 这块要落进舞台底部那条 52（基准 360dp）高的空白带，所以尺寸按「头像行 36dp +
 * 12sp 文案」收紧；耳机画布比头像行高，靠绘制溢出向上越到碟片边缘空白处，
 * 不参与测量，因此不会把整块顶到下方播放控件。
 */
@Composable
internal fun ListenTogetherBadge(
    selfAvatarUrl: String?,
    otherAvatarUrl: String?,
    totalSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val oneMinute = stringResource(R.string.listen_together_listened_one_minute)
    val minutes = stringResource(R.string.listen_together_listened_minutes)
    val hoursMinutes = stringResource(R.string.listen_together_listened_hours_minutes)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeadphoneDecoration(mirrored = false)
            CloudMusicCoverImage(
                imageUrl = selfAvatarUrl,
                modifier = Modifier.size(AvatarSize).clip(CircleShape),
            )
            Spacer(modifier = Modifier.width(AvatarGap))
            CloudMusicCoverImage(
                imageUrl = otherAvatarUrl,
                modifier = Modifier.size(AvatarSize).clip(CircleShape),
            )
            HeadphoneDecoration(mirrored = true)
        }
        Text(
            text = formatTogetherDuration(totalSeconds, oneMinute, minutes, hoursMinutes),
            color = colorResource(R.color.title_color),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        )
    }
}

/**
 * 播放页舞台内的接线：自己头像只在会话开始后取一次（资料在房间存续期间不变），
 * 可见性由「已连上且房间里确实有对方」驱动，整块 300ms 淡入淡出。
 */
@Composable
internal fun ListenTogetherStatusOverlay(modifier: Modifier = Modifier) {
    val container = LocalMusicAppContainer.current
    val store = container.listenTogetherStore
    val state by store.state.collectAsState()
    var selfAvatarUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(store) {
        selfAvatarUrl = container.onlineRepositoryRouter.currentUserProfile()?.avatarUrl
    }

    val visible =
        state.connectionState == ListenTogetherConnectionState.Connected && state.otherMember != null
    val alpha by
        animateFloatAsState(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(BadgeFadeDurationMs, easing = LinearOutSlowInEasing),
            label = "ListenTogetherBadgeAlpha",
        )
    // 淡出跑完再摘掉节点，否则退出房间时整块会瞬间消失。
    if (!visible && alpha == 0f) {
        return
    }
    ListenTogetherBadge(
        selfAvatarUrl = selfAvatarUrl,
        otherAvatarUrl = state.otherMember?.avatarUrl,
        totalSeconds = (state.accumulatedSeconds + state.thisRoomSeconds).coerceAtLeast(60L),
        modifier = modifier.alpha(alpha),
    )
}

/**
 * 耳机装饰：官方素材是一条自顶部偏左起、先向左下弯再向右下扫的 S 形细弧，下端渐隐。
 * 这里按官方图片逐点提取的几何用矢量重绘（不使用位图素材），右侧水平镜像。
 */
@Composable
private fun HeadphoneDecoration(mirrored: Boolean) {
    // 页面底色日夜两套（日间白、夜间深），耳机不能写死白色，否则日间整条弧看不见。
    val earphoneColor = colorResource(R.color.title_color)
    Box(
        modifier = Modifier.size(HeadphoneSlotWidth, AvatarSize),
        contentAlignment = Alignment.Center,
    ) {
        // requiredSize：弧线比头像行高，靠绘制越界向上落到碟片边缘，不参与行高测量。
        Canvas(modifier = Modifier.requiredSize(HeadphoneSlotWidth, HeadphoneArcHeight)) {
            val uniformScale = size.height / HeadphoneArcDesignHeight
            val drawnWidth = HeadphoneArcDesignWidth * uniformScale
            val originX = (size.width - drawnWidth) / 2f - HeadphoneArcDesignMinX * uniformScale
            fun point(designX: Float, designY: Float): Offset {
                val mappedX = originX + designX * uniformScale
                return Offset(
                    x = if (mirrored) size.width - mappedX else mappedX,
                    y = (designY - HeadphoneArcDesignMinY) * uniformScale,
                )
            }

            val start = point(15.5f, 10f)
            val arc =
                Path().apply {
                    moveTo(start.x, start.y)
                    cubicThrough(point(12.5f, 14f), point(9.5f, 18f), point(9.5f, 22f))
                    cubicThrough(point(9.5f, 26f), point(9.8f, 30f), point(10.5f, 34f))
                    cubicThrough(point(11.2f, 38f), point(12.0f, 42f), point(13.5f, 46f))
                    cubicThrough(point(15.0f, 50f), point(17.0f, 54f), point(19.5f, 58f))
                    cubicThrough(point(22.0f, 62f), point(25.0f, 68f), point(28.0f, 76f))
                }
            drawPath(
                path = arc,
                brush =
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                earphoneColor.copy(alpha = HeadphoneTopAlpha),
                                earphoneColor.copy(alpha = HeadphoneBottomAlpha),
                            ),
                        startY = 0f,
                        endY = size.height,
                    ),
                style =
                    Stroke(
                        width = HeadphoneStrokeDesignWidth * uniformScale,
                        cap = StrokeCap.Round,
                    ),
            )
        }
    }
}

/**
 * 累计时长文案。入参是三个已本地化的模板（`%1$d` 占位），
 * 纯函数不读 Context，便于 JVM 单测覆盖边界。
 */
internal fun formatTogetherDuration(
    seconds: Long,
    oneMinute: String,
    minutes: String,
    hoursMinutes: String,
): String {
    val totalSeconds = seconds.coerceAtLeast(0L)
    if (totalSeconds < 60L) {
        return oneMinute
    }
    if (totalSeconds < 3_600L) {
        return minutes.format(totalSeconds / 60L)
    }
    return hoursMinutes.format(totalSeconds / 3_600L, (totalSeconds % 3_600L) / 60L)
}

private val AvatarSize = 36.dp
private val AvatarGap = 12.dp
private val HeadphoneSlotWidth = 18.dp
private val HeadphoneArcHeight = 40.dp

/** 官方素材画布 51×121，弧线本体占 x 9.5..28、y 10..76。 */
private const val HeadphoneArcDesignWidth = 18.5f
private const val HeadphoneArcDesignHeight = 66f
private const val HeadphoneArcDesignMinX = 9.5f
private const val HeadphoneArcDesignMinY = 10f
private const val HeadphoneStrokeDesignWidth = 1.6f

private const val BadgeFadeDurationMs = 300
private const val HeadphoneTopAlpha = 0.9f
private const val HeadphoneBottomAlpha = 0.15f

private fun Path.cubicThrough(first: Offset, second: Offset, end: Offset) {
    cubicTo(first.x, first.y, second.x, second.y, end.x, end.y)
}
