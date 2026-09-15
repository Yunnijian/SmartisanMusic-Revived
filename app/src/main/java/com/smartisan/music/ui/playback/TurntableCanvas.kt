package com.smartisan.music.ui.playback

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import kotlin.math.roundToInt

/**
 * 转盘封面与唱针的 Canvas 绘制：唱片盘面、封面圆角裁切、轴向叠加层与原始唱针叠放。
 *
 * 从 [PlaybackTurntableStage] 搬出的绘制层：转盘/唱针 Canvas、封面页组装与页面过渡；
 * 手势与命中判定见 [TurntableNeedleGestures]。
 */

@Composable
internal fun PlaybackTurntableDisc(
    albumArtwork: ImageBitmap?,
    running: Boolean,
    rotationCycleDurationMs: Float,
    manualRotationOffsetDegrees: Float,
    hidePlayerAxisEnabled: Boolean,
    turntableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val discRotation =
        rememberSmoothDiscRotation(
            running = running,
            cycleDurationMs = rotationCycleDurationMs,
            manualRotationOffsetDegrees = manualRotationOffsetDegrees,
        )

    Box(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.playing_lp),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
        )
        Image(
            painter = painterResource(R.drawable.playing_cover_lp),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.matchParentSize().graphicsLayer {
                    rotationZ = discRotation.value
                },
        )
        if (albumArtwork != null) {
            PlaybackTurntableAlbumArt(
                artwork = albumArtwork,
                turntableWidth = turntableWidth,
                discRotation = discRotation,
                modifier = Modifier.align(Alignment.Center).zIndex(1f),
            )
            if (!hidePlayerAxisEnabled) {
                PlaybackTurntableAxisOverlay(
                    turntableWidth = turntableWidth,
                    modifier = Modifier.align(Alignment.Center).zIndex(1.1f),
                )
            }
        }
    }
}

@Composable
private fun PlaybackTurntableAlbumArt(
    artwork: ImageBitmap,
    turntableWidth: Dp,
    discRotation: State<Float>,
    modifier: Modifier = Modifier,
) {
    Image(
        bitmap = artwork,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .size(turntableWidth * PlaybackAlbumArtDiameterRatio)
                .graphicsLayer {
                    rotationZ = discRotation.value
                }
                .clip(CircleShape),
    )
}

@Composable
private fun PlaybackTurntableAxisOverlay(
    turntableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val axisBitmap = ImageBitmap.imageResource(id = R.drawable.playing_lp)
    val srcLeft = (axisBitmap.width - PlaybackTurntableAxisSourceDiameterPx) / 2
    val srcTop = (axisBitmap.height - PlaybackTurntableAxisSourceDiameterPx) / 2

    Canvas(
        modifier =
            modifier.size(turntableWidth * PlaybackTurntableAxisDiameterRatio).clip(CircleShape)
    ) {
        drawImage(
            image = axisBitmap,
            srcOffset = IntOffset(srcLeft, srcTop),
            srcSize =
                IntSize(
                    PlaybackTurntableAxisSourceDiameterPx,
                    PlaybackTurntableAxisSourceDiameterPx,
                ),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}

private data class OriginalNeedleLayoutPx(
    val needleWidthPx: Int,
    val needleHeightPx: Int,
    val needleTopWidthPx: Int,
    val needleTopMarginPx: Int,
    val needleRightMarginPx: Int,
    val needleShadowRightMarginPx: Int,
    val needlePivotXPx: Float,
    val needlePivotYPx: Float,
)

@Composable
internal fun OriginalNeedleStack(
    needleRotation: State<Float>,
    needleLiftFraction: State<Float>,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val layout =
        remember(scale, density) {
            val metrics = originalNeedleMetrics(scale)
            OriginalNeedleLayoutPx(
                needleWidthPx = with(density) { metrics.widthDp.dp.roundToPx() },
                needleHeightPx = with(density) { metrics.heightDp.dp.roundToPx() },
                needleTopWidthPx = with(density) { metrics.topWidthDp.dp.roundToPx() },
                needleTopMarginPx = with(density) { metrics.topMarginDp.dp.roundToPx() },
                needleRightMarginPx = with(density) { metrics.rightMarginDp.dp.roundToPx() },
                needleShadowRightMarginPx =
                    with(density) {
                        metrics.shadowRightMarginDp.dp.roundToPx()
                    },
                needlePivotXPx = with(density) { metrics.pivotXDp.dp.toPx() },
                needlePivotYPx = with(density) { metrics.pivotYDp.dp.toPx() },
            )
        }
    val base = rememberSmartisanDrawablePainter(R.drawable.playing_stylus_lp_bg_original)
    val shadow = rememberSmartisanDrawablePainter(R.drawable.needle_shadow2)
    val needle = rememberSmartisanDrawablePainter(R.drawable.playing_stylus_lp_original)
    val top = rememberSmartisanDrawablePainter(R.drawable.playing_stylus_lp_top_original)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier) {
        val rotationDegrees = needleRotation.value
        val liftFraction = needleLiftFraction.value
        val needleSize = Size(layout.needleWidthPx.toFloat(), layout.needleHeightPx.toFloat())
        val pivot = Offset(layout.needlePivotXPx, layout.needlePivotYPx)
        val liftedScaleY = 1f - ((1f - NeedleLiftScaleY) * liftFraction)
        fun left(width: Int, margin: Int): Float =
            if (rtl) margin.toFloat() else size.width - width - margin
        fun androidx.compose.ui.graphics.drawscope.DrawScope.layer(
            painter: Painter,
            width: Int,
            endMargin: Int,
            rotation: Float = 0f,
            scaleY: Float = 1f,
            fit: Int = 0,
        ) {
            translate(left(width, endMargin), layout.needleTopMarginPx.toFloat()) {
                rotate(rotation, pivot) {
                    scale(1f, scaleY, pivot) {
                        val target = Size(width.toFloat(), needleSize.height)
                        val intrinsic = painter.intrinsicSize
                        if (fit == 0) {
                            with(painter) { draw(target) }
                        } else {
                            val fitScale =
                                minOf(
                                    target.width / intrinsic.width,
                                    target.height / intrinsic.height,
                                )
                            val fitted =
                                Size(intrinsic.width * fitScale, intrinsic.height * fitScale)
                            // ImageView FIT_END/FIT_START align both axes, independent of layout
                            // direction.
                            val dx = if (fit > 0) target.width - fitted.width else 0f
                            val dy = if (fit > 0) target.height - fitted.height else 0f
                            translate(dx, dy) { with(painter) { draw(fitted) } }
                        }
                    }
                }
            }
        }
        layer(base, layout.needleWidthPx, layout.needleRightMarginPx)
        layer(
            shadow,
            layout.needleWidthPx,
            layout.needleShadowRightMarginPx,
            rotationDegrees - NeedleLiftShadowRotationOffsetDegrees * liftFraction,
            liftedScaleY,
            fit = 1,
        )
        layer(
            needle,
            layout.needleWidthPx,
            layout.needleRightMarginPx,
            rotationDegrees,
            liftedScaleY,
        )
        layer(top, layout.needleTopWidthPx, layout.needleRightMarginPx, fit = -1)
    }
}

internal fun playbackVisualPageTransform(enteringLyrics: Boolean): ContentTransform {
    val enterOffsetDirection = if (enteringLyrics) 1 else -1
    val exitOffsetDirection = if (enteringLyrics) -1 else 1
    val enterScale = if (enteringLyrics) 0.985f else 1.015f
    val exitScale = if (enteringLyrics) 1.015f else 0.985f
    val enter =
        fadeIn(
            animationSpec =
                tween(
                    durationMillis = PlaybackVisualPageEnterDurationMillis,
                    delayMillis = PlaybackVisualPageEnterDelayMillis,
                    easing = PlaybackControlEasing,
                )
        ) +
            scaleIn(
                initialScale = enterScale,
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageEnterDurationMillis,
                        delayMillis = PlaybackVisualPageEnterDelayMillis,
                        easing = PlaybackControlEasing,
                    ),
            ) +
            slideInVertically(
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageEnterDurationMillis,
                        delayMillis = PlaybackVisualPageEnterDelayMillis,
                        easing = PlaybackControlEasing,
                    )
            ) { fullHeight ->
                enterOffsetDirection * (fullHeight / 36)
            }
    val exit =
        fadeOut(
            animationSpec =
                tween(
                    durationMillis = PlaybackVisualPageExitDurationMillis,
                    easing = PlaybackControlEasing,
                )
        ) +
            scaleOut(
                targetScale = exitScale,
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageExitDurationMillis,
                        easing = PlaybackControlEasing,
                    ),
            ) +
            slideOutVertically(
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageExitDurationMillis,
                        easing = PlaybackControlEasing,
                    )
            ) { fullHeight ->
                exitOffsetDirection * (fullHeight / 42)
            }

    return enter togetherWith exit
}
@Composable
internal fun PlaybackCoverPage(
    turntableWidth: Dp,
    scale: Float,
    currentPositionMs: Long,
    durationMs: Long,
    scratchEnabled: Boolean,
    hidePlayerAxisEnabled: Boolean,
    albumArtwork: ImageBitmap?,
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    coverDragMode: CoverDragMode,
    previewPositionMs: Long?,
    needlePreviewRotationDegrees: Float?,
    needleParkedOutside: Boolean,
    discManualRotationOffsetDegrees: Float,
    mediaId: String?,
    onVisualPageToggle: () -> Unit,
    onDiscScratchStart: () -> Unit,
    onDiscScratchMotion: (Long, Float) -> Unit,
    onDiscScratchPositionChange: (Long, Float) -> Unit,
    onDiscScratchEnd: (Long, Float) -> Unit,
    onDiscScratchCancel: () -> Unit,
    onNeedleSeekStart: (Float, Long?) -> Unit,
    onNeedleSeekPositionChange: (Float, Long?) -> Unit,
    onNeedleSeekEnd: (Float, Long?) -> Unit,
    onNeedleSeekCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val needleGestureState =
        rememberNeedleGestureState(
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            hasMediaItem = hasMediaItem,
            isPlaying = isPlaying,
            coverDragMode = coverDragMode,
            needlePreviewRotationDegrees = needlePreviewRotationDegrees,
            needleParkedOutside = needleParkedOutside,
            mediaId = mediaId,
        )
    val needleAnimatable = needleGestureState.animatable
    val needleRotation = needleGestureState.rotation
    val needleLiftFraction = needleGestureState.liftFraction
    val density = LocalDensity.current
    val densityPxPerDp = density.density
    var discSize by remember { mutableStateOf(IntSize.Zero) }
    val latestDiscSize by rememberUpdatedState(discSize)
    val latestVisualPageToggle by rememberUpdatedState(onVisualPageToggle)
    val scratchAvailable by rememberUpdatedState(scratchEnabled && durationMs > 0L)
    val needleSeekAvailable by
        rememberUpdatedState(scratchEnabled && hasMediaItem && durationMs > 0L)
    val latestPositionMs by rememberUpdatedState(currentPositionMs)
    val latestDurationMs by rememberUpdatedState(durationMs)
    val latestDiscScratchStart by rememberUpdatedState(onDiscScratchStart)
    val latestDiscScratchMotion by rememberUpdatedState(onDiscScratchMotion)
    val latestDiscScratchPositionChange by rememberUpdatedState(onDiscScratchPositionChange)
    val latestDiscScratchEnd by rememberUpdatedState(onDiscScratchEnd)
    val latestDiscScratchCancel by rememberUpdatedState(onDiscScratchCancel)
    val latestNeedleSeekStart by rememberUpdatedState(onNeedleSeekStart)
    val latestNeedleSeekPositionChange by rememberUpdatedState(onNeedleSeekPositionChange)
    val latestNeedleSeekEnd by rememberUpdatedState(onNeedleSeekEnd)
    val latestNeedleSeekCancel by rememberUpdatedState(onNeedleSeekCancel)
    val discRunning = isPlaying && coverDragMode == CoverDragMode.None && previewPositionMs == null
    val discRotationCycleDurationMs =
        if (coverDragMode == CoverDragMode.DiscScratch) {
            ScratchCycleDurationMs
        } else {
            PlaybackDiscCycleDurationMs
        }

    Box(modifier = modifier) {
        PlaybackTurntableDisc(
            albumArtwork = albumArtwork,
            running = discRunning,
            rotationCycleDurationMs = discRotationCycleDurationMs,
            manualRotationOffsetDegrees = discManualRotationOffsetDegrees,
            hidePlayerAxisEnabled = hidePlayerAxisEnabled,
            turntableWidth = turntableWidth,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier =
                Modifier.matchParentSize()
                    .onSizeChanged { discSize = it }
                    .zIndex(3f)
                    .playbackCoverPointerInput(
                        scratchAvailable = scratchAvailable,
                        needleSeekAvailable = needleSeekAvailable,
                        densityPxPerDp = densityPxPerDp,
                        scale = scale,
                        needleAnimatable = needleAnimatable,
                        latestDiscSize = latestDiscSize,
                        latestPositionMs = latestPositionMs,
                        latestDurationMs = latestDurationMs,
                        latestVisualPageToggle = latestVisualPageToggle,
                        latestDiscScratchStart = latestDiscScratchStart,
                        latestDiscScratchMotion = latestDiscScratchMotion,
                        latestDiscScratchPositionChange = latestDiscScratchPositionChange,
                        latestDiscScratchEnd = latestDiscScratchEnd,
                        latestDiscScratchCancel = latestDiscScratchCancel,
                        latestNeedleSeekStart = latestNeedleSeekStart,
                        latestNeedleSeekPositionChange = latestNeedleSeekPositionChange,
                        latestNeedleSeekEnd = latestNeedleSeekEnd,
                        latestNeedleSeekCancel = latestNeedleSeekCancel,
                    )
        )
        OriginalNeedleStack(
            needleRotation = needleRotation,
            needleLiftFraction = needleLiftFraction,
            scale = scale,
            modifier = Modifier.matchParentSize().zIndex(2f),
        )
    }
}
