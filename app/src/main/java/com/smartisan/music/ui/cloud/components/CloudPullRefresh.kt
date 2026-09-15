package com.smartisan.music.ui.cloud.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 下拉揭示阶段的帧上限：1-45 帧对应 0→阈值 的下拉距离（邮件 pull_refresh_anim 序列）。 */
private const val MailPullThresholdFrame = 45

/** 序列总帧数：46-85 是刷新结束收起时补转的半圈。 */
private const val MailPullFrameTotal = 85

/** 邮件的帧映射除数（px）：frame 值 = pullPx / 5.3，阈值处恰好第 45 帧。 */
private const val MailPullFrameDivisor = 5.3f

/** 过阈值后图标额外旋转：(frame 值 - 45) * 5 度。 */
private const val MailPullOverRotationPerFrame = 5f

/** 刷新中图标自转一圈的时长（邮件 refresh_anim：700ms 线性无限循环）。 */
private const val MailRefreshSpinMillis = 700

/** 下拉阻尼。 */
private const val MailPullResistance = 0.6f

/** 下拉距离上限（头高的倍数）：到顶后不再位移，只继续积累过阈值旋转，杜绝无限下拉。 */
private const val MailPullOverPullFactor = 1.5f

private enum class CloudPullPhase { Idle, Dragging, Refreshing, Finishing }

/**
 * 云音乐下拉刷新：视觉与状态机移植自锤子邮件 7.1.0 的 ConversationListView +
 * RefreshHeaderView（反编译取证），帧序列用邮件的 pull_refresh_anim_01..85。
 *
 * 行为对齐点：
 * - 下拉距离线性映射到第 1-45 帧，图标随下拉从顶边逐行揭示；
 * - 拉过阈值（第 45 帧）后帧定格、图标按超出量旋转；
 * - 松开未过阈值：回弹收回；过阈值：进入刷新，第 45 帧 + 700ms 线性自转；
 * - 刷新结束：45→85 帧补转半圈（500ms）后整体收起（300ms），文案淡出；
 * - 三态文案：下拉即可刷新 / 松开即可刷新 / 正在刷新...
 *
 * 交互不变量：
 * - 头部参与布局（非覆盖层）：揭示高度即整页下移量，内容不被遮挡；
 * - 下拉距离封顶（头高 1.5 倍），揭示高度封顶头高，不存在无限下拉；
 * - 刷新中上滑即收回头部并取消本次刷新，手势交还列表正常滚动；
 * - 调用方的 onRefresh 需先作废仓库缓存再 reload（见 CloudMusicDataStore.refresh*），
 *   否则 TTL 缓存会让刷新空转、内容不变。
 *
 * [refreshing] 由调用方在数据重载期间置真；[canChildScrollUp] 判断列表是否已到顶。
 */
@Composable
internal fun CloudPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    canChildScrollUp: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val headerHeightPx = with(density) { 80.dp.toPx() }

    var phase by remember { mutableStateOf(CloudPullPhase.Idle) }
    var pull by remember { mutableFloatStateOf(0f) }
    // 布局揭示高度 = min(pull, 头高)：头部在布局流里，整页随它下移；刷新中钉住、上滑收回。
    var reveal by remember { mutableFloatStateOf(0f) }
    // 收起补转期间的帧号；0 表示不在补转阶段。
    var spinOutFrame by remember { mutableIntStateOf(0) }

    // 帧资源 id 一次性解析：frameRes 在下拉/补转期间每帧都会被调用，
    // 每次 getIdentifier + String.format 是纯开销。
    val frames =
        remember(context) {
            IntArray(MailPullFrameTotal) { index ->
                context.resources.getIdentifier(
                    String.format("mail_pull_refresh_%02d", index + 1),
                    "drawable",
                    context.packageName,
                )
            }
        }

    fun frameRes(index: Int): Int {
        return frames[index.coerceIn(1, MailPullFrameTotal) - 1]
    }

    fun handleRelease() {
        if (phase != CloudPullPhase.Dragging) return
        if (pull >= headerHeightPx) {
            phase = CloudPullPhase.Refreshing
            reveal = headerHeightPx
            onRefresh()
        } else {
            phase = CloudPullPhase.Finishing
            scope.launch {
                Animatable(pull).animateTo(0f, tween(200, easing = FastOutSlowInEasing)) {
                    pull = value
                    reveal = min(value, headerHeightPx)
                }
                phase = CloudPullPhase.Idle
            }
        }
    }

    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.Drag) return Offset.Zero
                if (phase == CloudPullPhase.Finishing) return Offset.Zero
                if (available.y < 0f) {
                    if (phase == CloudPullPhase.Refreshing) {
                        // 刷新中上滑：收回头部；收完即取消本次刷新，剩余手势交还列表。
                        val consume = max(-reveal, available.y)
                        reveal += consume
                        if (reveal <= 0.5f) {
                            reveal = 0f
                            pull = 0f
                            phase = CloudPullPhase.Idle
                        }
                        return Offset(0f, consume)
                    }
                    // 上拖：先把已拉出的头部收回去，再交还给列表。
                    if (pull > 0f && phase == CloudPullPhase.Dragging) {
                        val consume = max(-pull, available.y)
                        pull += consume
                        reveal = min(pull, headerHeightPx)
                        if (pull <= 0.5f) {
                            pull = 0f
                            reveal = 0f
                            phase = CloudPullPhase.Idle
                        }
                        return Offset(0f, consume)
                    }
                    return Offset.Zero
                }
                if (phase == CloudPullPhase.Refreshing) {
                    // 刷新中头部钉住，吃掉下拖避免列表跟着跳。
                    return Offset(0f, available.y)
                }
                if (canChildScrollUp()) return Offset.Zero
                val consume = (available.y * MailPullResistance)
                    .coerceAtMost(headerHeightPx * MailPullOverPullFactor - pull)
                    .coerceAtLeast(0f)
                pull += consume
                reveal = min(pull, headerHeightPx)
                phase = CloudPullPhase.Dragging
                return Offset(0f, consume)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (phase != CloudPullPhase.Dragging) return Velocity.Zero
                handleRelease()
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                handleRelease()
                return available
            }
        }
    }

    // 数据重载结束 → 补转半圈再收起。
    LaunchedEffect(refreshing) {
        if (refreshing || phase != CloudPullPhase.Refreshing) return@LaunchedEffect
        phase = CloudPullPhase.Finishing
        Animatable(MailPullThresholdFrame.toFloat()).animateTo(
            targetValue = MailPullFrameTotal.toFloat(),
            animationSpec = tween(500, easing = FastOutSlowInEasing),
        ) {
            spinOutFrame = value.roundToInt()
        }
        Animatable(pull).animateTo(0f, tween(300, easing = FastOutSlowInEasing)) {
            pull = value
            reveal = min(value, headerHeightPx)
        }
        spinOutFrame = 0
        phase = CloudPullPhase.Idle
    }

    val spinTransition = rememberInfiniteTransition(label = "cloudPullSpin")
    val spinAngle by spinTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(MailRefreshSpinMillis, easing = LinearEasing)),
        label = "cloudPullSpinAngle",
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (phase == CloudPullPhase.Idle) 0f else 1f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "cloudPullTextAlpha",
    )

    val overPullFrames = max(0f, pull / MailPullFrameDivisor - MailPullThresholdFrame)
    val pullFrame = (pull / headerHeightPx * MailPullThresholdFrame)
        .roundToInt()
        .coerceIn(1, MailPullThresholdFrame)
    val shownFrame = when {
        spinOutFrame > 0 -> spinOutFrame
        phase == CloudPullPhase.Refreshing -> MailPullThresholdFrame
        else -> pullFrame
    }
    val rotation = when {
        phase == CloudPullPhase.Refreshing -> spinAngle
        overPullFrames > 0f -> overPullFrames * MailPullOverRotationPerFrame
        else -> 0f
    }
    val label = when {
        refreshing -> stringResource(R.string.cloud_refreshing)
        pull >= headerHeightPx -> stringResource(R.string.cloud_release_to_refresh)
        else -> stringResource(R.string.cloud_pull_to_refresh)
    }
    val revealDp = with(density) { reveal.toDp() }

    Column(modifier = modifier.nestedScroll(connection)) {
        // 头部在布局流里：揭示高度就是整页下移量，下拉不会遮住内容。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(revealDp)
                .clipToBounds()
                .background(CloudPageBackgroundColor),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .height(80.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(frameRes(shownFrame)),
                    contentDescription = null,
                    modifier = Modifier
                        .height(80.dp)
                        .graphicsLayer { rotationZ = rotation },
                )
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 15.sp,
                        color = CloudSecondaryTextColor,
                    ),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .graphicsLayer { alpha = textAlpha },
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
