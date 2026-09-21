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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.smartisan.music.data.settings.TurntableStyle
import com.smartisan.music.ui.components.rememberSmartisanDrawablePainter
import androidx.core.graphics.PathParser
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

@Composable
internal fun NeteaseTurntableDisc(
    albumArtwork: ImageBitmap?,
    running: Boolean,
    rotationCycleDurationMs: Float,
    manualRotationOffsetDegrees: Float,
    turntableWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val discRotation =
        rememberSmoothDiscRotation(
            running = running,
            cycleDurationMs = rotationCycleDurationMs,
            manualRotationOffsetDegrees = manualRotationOffsetDegrees,
        )
    val discDiameter = turntableWidth * NeteaseDiscDiameterRatio
    val coverDiameter = turntableWidth * NeteaseCoverDiameterRatio

    BoxWithConstraints(modifier = modifier) {
        val discCenterY = maxHeight * NeteaseDiscCenterYRatio
        // 官方播放页用同尺寸、同中心的两张 1200×1200 位图叠出唱片：
        // outline.png 是底层浅色圆盘，disc.png 是中心镂空的黑胶环。
        val discModifier =
            Modifier.align(Alignment.TopCenter)
                .offset(y = discCenterY - discDiameter / 2)
                .size(discDiameter)
        Image(
            painter = painterResource(R.drawable.netease_vinyl_outline),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = discModifier,
        )
        Image(
            painter = painterResource(R.drawable.netease_vinyl_disc),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = discModifier,
        )
        val coverModifier =
            Modifier.align(Alignment.TopCenter)
                .offset(y = discCenterY - coverDiameter / 2)
                .size(coverDiameter)
        if (albumArtwork != null) {
            Image(
                bitmap = albumArtwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    coverModifier.graphicsLayer {
                        rotationZ = discRotation.value
                    }.clip(CircleShape),
            )
        } else {
            Canvas(modifier = coverModifier.clip(CircleShape)) {
                drawRect(NeteaseCoverPlaceholderColor)
            }
        }
    }
}

private val NeteaseCoverPlaceholderColor = Color(0xFF2A2A2C)

// 官方唱针内联 SVG（viewBox 0 0 114 174）的 path 数据，逐字取自
// subApp.chunk.4b8efd5.js 的 handle_svg__ 段；填充/描边参数亦对照源码。
// arc 旗标补显式空格：Android PathParser 不接受 "011.508" 这类紧凑写法。
private val NeteaseNeedleArmPath =
    PathParser.createPathFromPathData(
        "M78.665 142.637s-20.72-18.081-35.721-34.604C28.646 85.53 17 16.559 17 16.559",
    ).asComposePath()
private val NeteaseNeedleArmShadowPath =
    PathParser.createPathFromPathData(
        "M71.316 136.199 a1.068 1.068 0 0 1 1.508 -.085 l8.425 7.44 " +
            "a1.07 1.07 0 0 1 -1.424 1.593 l-8.425 -7.44 a1.068 1.068 0 0 1 -.084 -1.508 z",
    ).asComposePath()
private val NeteaseNeedleHeadShadePath =
    PathParser.createPathFromPathData("M93.144 138.942h19.941v27.776H93.144z").asComposePath()
private val NeteaseNeedleTipPath =
    PathParser.createPathFromPathData(
        "M92.14 160.046 l6.838 -8.15 a3.193 3.193 0 0 1 4.496 -.394 l8.149 6.839 " +
            "a3.19 3.19 0 0 1 .394 4.496 l-6.838 8.15 a3.192 3.192 0 0 1 -4.497 .393 " +
            "l-8.15 -6.838 a3.191 3.191 0 0 1 -.393 -4.496 z",
    ).asComposePath()
private val NeteaseNeedleTipTexturePath =
    PathParser.createPathFromPathData(
        "M95.081 161.111 a.713 .713 0 0 1 1.003 -.088 l8.395 6.817 " +
            "a.711 .711 0 1 1 -.915 1.091 l-8.396 -6.816 a.713 .713 0 0 1 -.087 -1.004 z " +
            "m4.984 -6.055 a.713 .713 0 0 1 1.003 -.086 l8.397 6.815 " +
            "a.713 .713 0 0 1 -.916 1.091 l-8.396 -6.816 a.714 .714 0 0 1 -.088 -1.004 z",
    ).asComposePath()
private val NeteaseNeedleHeadRect =
    RoundRect(
        left = 79.949f,
        top = 136.405f,
        right = 79.949f + 29.2f,
        bottom = 136.405f + 11.395f,
        cornerRadius = CornerRadius(2.849f, 2.849f),
    )
private val NeteaseNeedleHeadPivot = Offset(79.949f, 136.405f)
private val NeteaseNeedleHeadShadePivot = Offset(93.144f, 138.942f)

@Composable
internal fun NeteaseNeedle(
    needleRotation: State<Float>,
    modifier: Modifier = Modifier,
) {
    // 浅色背景（page_background 纯白）下，纯白唱针会与背景融为一体。处理方式和
    // 原版 PNG 唱针一致：主体保持浅色，外围叠加一圈深灰描边，让整支唱针从背景
    // 中浮现出清晰轮廓；黑胶上仍是白主体，视觉不受影响。深色模式维持纯白。
    val isDark = isSystemInDarkTheme()
    Canvas(modifier) {
        // 唱针几何与碟片同基准：支点在碟心正上方，长度按手机版实测标定；
        // 针不随碟径放大（0.72 标定下针顶已贴舞台上沿），按参考直径缩放。
        val needleDiameter = size.width * NeteaseNeedleReferenceDiameterRatio
        val svgScale = NeteaseNeedleScaleToDiscRatio * needleDiameter
        val pivot =
            Offset(
                x = size.width / 2f,
                y = (size.height * NeteaseDiscCenterYRatio) -
                    (NeteaseNeedlePivotOffsetToDiscRatio * needleDiameter),
            )
        val renderLeft = pivot.x - (NeteaseNeedlePivotSvgX * svgScale)
        val renderTop = pivot.y - (NeteaseNeedlePivotSvgY * svgScale)
        rotate(degrees = needleRotation.value, pivot = pivot) {
            withTransform({
                translate(renderLeft, renderTop)
                scale(svgScale, svgScale, pivot = Offset.Zero)
            }) {
                drawNeteaseNeedleSvg(isDark = isDark)
            }
        }
    }
}

/** 浅色模式下唱针外围描边色：取自原版 play_stylus_lp_original 的轮廓中位色。 */
private const val NeteaseNeedleLightOutlineColor = 0xFF66666A

private fun DrawScope.drawNeteaseNeedleSvg(isDark: Boolean) {
    // 浅色模式下先描深灰边、再叠白主体；深色模式跳过描边保持纯白。
    val outlineColor = if (isDark) null else Color(NeteaseNeedleLightOutlineColor)
    val outlineInset = 1.2f
    val pivotBase = Offset(17f, 16.558f)
    drawCircle(
        color = Color.Black.copy(alpha = 0.1f),
        radius = 15.454f,
        center = pivotBase,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.05f),
        radius = 15.454f,
        center = pivotBase,
        style = Stroke(width = 0.552f),
    )
    outlineColor?.let {
        drawPath(NeteaseNeedleArmPath, it, style = Stroke(width = 6.623f + outlineInset))
    }
    drawPath(NeteaseNeedleArmPath, Color.White, style = Stroke(width = 6.623f))
    drawPath(NeteaseNeedleArmShadowPath, Color.Black.copy(alpha = 0.7f))
    val headPath = Path().apply { addRoundRect(NeteaseNeedleHeadRect) }
    outlineColor?.let {
        withTransform({ rotate(40f, NeteaseNeedleHeadPivot) }) {
            drawPath(headPath, it, style = Stroke(width = outlineInset))
        }
    }
    withTransform({ rotate(40f, NeteaseNeedleHeadPivot) }) {
        drawPath(headPath, Color.White)
    }
    withTransform({ rotate(40f, NeteaseNeedleHeadPivot) }) {
        clipPath(headPath) {
            withTransform({ rotate(40f, NeteaseNeedleHeadShadePivot) }) {
                drawPath(
                    NeteaseNeedleHeadShadePath,
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.001f),
                        1f to Color.Black.copy(alpha = 0.1f),
                        startX = 93.144f,
                        endX = 113.085f,
                    ),
                )
                drawPath(
                    NeteaseNeedleHeadShadePath,
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.001f),
                        1f to Color.Black.copy(alpha = 0.1f),
                        startX = 103.115f,
                        endX = 113.085f,
                    ),
                )
            }
        }
    }
    outlineColor?.let {
        drawPath(NeteaseNeedleTipPath, it, style = Stroke(width = outlineInset))
    }
    drawPath(NeteaseNeedleTipPath, Color.White)
    drawPath(NeteaseNeedleTipTexturePath, Color.Black.copy(alpha = 0.09f))
    val capCenter = Offset(16.999f, 16.559f)
    drawCircle(
        color = Color.Black.copy(alpha = 0.18f),
        radius = 8.831f + 0.552f,
        center = capCenter,
    )
    outlineColor?.let {
        drawCircle(color = it, radius = 8.831f + 0.552f, center = capCenter)
    }
    drawCircle(color = Color.White, radius = 8.831f, center = capCenter)
    drawCircle(
        brush =
            Brush.verticalGradient(
                colorStops =
                    arrayOf(
                        0f to Color(0xFFECE7E7),
                        0.471f to Color(0xFFD9D9D9),
                        1f to Color(0xFFF0EBEB),
                    ),
                startY = capCenter.y - 3.312f,
                endY = capCenter.y + 3.312f,
            ),
        radius = 3.312f,
        center = capCenter,
    )
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
    turntableStyleSpec: TurntableStyleSpec,
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
            turntableStyleSpec = turntableStyleSpec,
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
    // 手势协程只在 key 变化时重建，这些量必须按 State 下传、在事件里读 .value，
    // 否则会被 lambda 捕获成首次组合时的旧值（拖针会按旧进度跳角）。
    val latestDiscSize = rememberUpdatedState(discSize)
    val latestVisualPageToggle by rememberUpdatedState(onVisualPageToggle)
    val scratchAvailable by rememberUpdatedState(scratchEnabled && durationMs > 0L)
    val needleSeekAvailable by
        rememberUpdatedState(scratchEnabled && hasMediaItem && durationMs > 0L)
    val latestPositionMs = rememberUpdatedState(currentPositionMs)
    val latestDurationMs = rememberUpdatedState(durationMs)
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
            turntableStyleSpec.discCycleDurationMs
        }

    Box(modifier = modifier) {
        when (turntableStyleSpec.style) {
            TurntableStyle.Original ->
                PlaybackTurntableDisc(
                    albumArtwork = albumArtwork,
                    running = discRunning,
                    rotationCycleDurationMs = discRotationCycleDurationMs,
                    manualRotationOffsetDegrees = discManualRotationOffsetDegrees,
                    hidePlayerAxisEnabled = hidePlayerAxisEnabled,
                    turntableWidth = turntableWidth,
                    modifier = Modifier.matchParentSize(),
                )
            TurntableStyle.Netease ->
                NeteaseTurntableDisc(
                    albumArtwork = albumArtwork,
                    running = discRunning,
                    rotationCycleDurationMs = discRotationCycleDurationMs,
                    manualRotationOffsetDegrees = discManualRotationOffsetDegrees,
                    turntableWidth = turntableWidth,
                    modifier = Modifier.matchParentSize(),
                )
        }
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
                        spec = turntableStyleSpec,
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
        when (turntableStyleSpec.style) {
            TurntableStyle.Original ->
                OriginalNeedleStack(
                    needleRotation = needleRotation,
                    needleLiftFraction = needleLiftFraction,
                    scale = scale,
                    modifier = Modifier.matchParentSize().zIndex(2f),
                )
            TurntableStyle.Netease ->
                NeteaseNeedle(
                    needleRotation = needleRotation,
                    modifier = Modifier.matchParentSize().zIndex(2f),
                )
        }
    }
}
