package com.smartisan.music.ui.playback

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.smartisan.music.data.settings.TurntableStyle
import kotlin.math.roundToLong

/**
 * 唱机样式（原版 / 网易云）的几何与动画常数集。Original 分支全部委托给
 * PlaybackScreenTokens / PlaybackTurntableMath 的既有常数与函数，保证原版零回归；
 * Netease 分支取自官方 mac 客户端明文 CSS/SVG 的取证值。
 */
internal data class TurntableStyleSpec(
    val style: TurntableStyle,
    val discCycleDurationMs: Float,
    val needleDragMinRotationDegrees: Float,
    val needleDragMaxRotationDegrees: Float,
    val needleProgressStartRotationDegrees: Float,
    val needleProgressSweepDegrees: Float,
    val needlePlayingRotationDegrees: Float?,
    val needleParkedRotationDegrees: Float,
    val needleAnimationDurationMs: Int,
    val needleAnimationEasing: Easing,
)

internal val OriginalTurntableStyleSpec = TurntableStyleSpec(
    style = TurntableStyle.Original,
    discCycleDurationMs = PlaybackDiscCycleDurationMs,
    needleDragMinRotationDegrees = NeedleRestRotationDegrees,
    needleDragMaxRotationDegrees = NeedlePlaybackEndRotationDegrees,
    needleProgressStartRotationDegrees = NeedlePlaybackStartRotationDegrees,
    needleProgressSweepDegrees = NeedlePlaybackSweepDegrees,
    needlePlayingRotationDegrees = null,
    needleParkedRotationDegrees = NeedleRestRotationDegrees,
    needleAnimationDurationMs = 220,
    needleAnimationEasing = FastOutSlowInEasing,
)

/** 官方 CSS `transition: transform 0.3s ease-out` 对应的贝塞尔。 */
internal val NeteaseNeedleDropEasing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

internal val NeteaseTurntableStyleSpec = TurntableStyleSpec(
    style = TurntableStyle.Netease,
    discCycleDurationMs = NeteaseDiscCycleDurationMs,
    needleDragMinRotationDegrees = NeteaseNeedlePausedRotationDegrees,
    needleDragMaxRotationDegrees = NeteaseNeedlePlayingRotationDegrees,
    needleProgressStartRotationDegrees = NeteaseNeedlePausedRotationDegrees,
    needleProgressSweepDegrees =
        NeteaseNeedlePlayingRotationDegrees - NeteaseNeedlePausedRotationDegrees,
    needlePlayingRotationDegrees = NeteaseNeedlePlayingRotationDegrees,
    needleParkedRotationDegrees = NeteaseNeedlePausedRotationDegrees,
    needleAnimationDurationMs = NeteaseNeedleDropDurationMs,
    needleAnimationEasing = NeteaseNeedleDropEasing,
)

internal fun turntableStyleSpec(style: TurntableStyle): TurntableStyleSpec =
    when (style) {
        TurntableStyle.Original -> OriginalTurntableStyleSpec
        TurntableStyle.Netease -> NeteaseTurntableStyleSpec
    }

/**
 * 唱针目标角。Original 的针按播放进度扫动、暂停也不抬起。
 *
 * 网易云：播放中针角与进度绝对对应（0% 落在最低点、100% 抬到最高点），指针随进度扫动；
 * 暂停则抬到最高点表示「离碟」，与播放态形成可见变化。拖动起步角由进度反推（见
 * [needleRotationForProgress]），所以抬起姿态不会把进度读错。
 */
internal fun TurntableStyleSpec.needleTargetRotation(
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    needleParkedOutside: Boolean,
    progress: Float,
): Float =
    when (style) {
        TurntableStyle.Original ->
            if (hasMediaItem && !(needleParkedOutside && !isPlaying)) {
                NeedlePlaybackStartRotationDegrees + (progress * NeedlePlaybackSweepDegrees)
            } else {
                NeedleRestRotationDegrees
            }
        TurntableStyle.Netease ->
            if (hasMediaItem && isPlaying) {
                needleDragMinRotationDegrees * progress
            } else {
                needleParkedRotationDegrees
            }
    }

/** 网易云：由进度反推针角（0% → 最低点 0°，100% → 最高点 -35°）。 */
internal fun TurntableStyleSpec.needleRotationForProgress(
    positionMs: Long,
    durationMs: Long,
): Float {
    if (durationMs <= 0L) {
        return needleDragMaxRotationDegrees
    }
    val fraction = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    return needleDragMinRotationDegrees * fraction
}

/**
 * 拖针时的进度换算。
 *
 * 两种样式的针角度都携带进度，用绝对映射即可。网易云的 0° 是进度 0%、-35° 是进度 100%，
 * 于是「向上拖 = 角度变小 = 快进」「拖到顶 = 满进度、拖到底 = 归零」自然成立。
 */
internal fun TurntableStyleSpec.needleDragPosition(
    rotationDegrees: Float,
    durationMs: Long,
): Long? =
    when (style) {
        TurntableStyle.Original -> needleSeekPositionFromRotation(rotationDegrees, durationMs)
        TurntableStyle.Netease -> {
            if (durationMs <= 0L || needleDragMinRotationDegrees == 0f) {
                null
            } else {
                val fraction = (rotationDegrees / needleDragMinRotationDegrees).coerceIn(0f, 1f)
                (durationMs.toFloat() * fraction).roundToLong().coerceIn(0L, durationMs)
            }
        }
    }

internal fun TurntableStyleSpec.needleRotationFromPoint(
    point: Offset,
    containerSize: IntSize,
    densityPxPerDp: Float,
    turntableScale: Float,
): Float {
    if (style == TurntableStyle.Original) {
        return needleSeekRotationFromPoint(point, containerSize, densityPxPerDp, turntableScale)
    }
    if (containerSize.width <= 0 || containerSize.height <= 0 || densityPxPerDp <= 0f) {
        return needleDragMinRotationDegrees
    }
    val neutralGeometry = needleGeometry(containerSize, densityPxPerDp, turntableScale)
    val neutralAngle =
        angleDegrees(
            point =
                Offset(
                    x = neutralGeometry.left + (neutralGeometry.width / 2f),
                    y =
                        neutralGeometry.top +
                            (neutralGeometry.height * OriginalNeedleTouchStartRatio),
                ),
            center = neutralGeometry.pivot,
        )
    val pointAngle = angleDegrees(point, neutralGeometry.pivot)
    return normalizeAngleDelta(pointAngle - neutralAngle)
        .coerceIn(needleDragMinRotationDegrees, needleDragMaxRotationDegrees)
}

internal fun TurntableStyleSpec.needleGeometry(
    containerSize: IntSize,
    densityPxPerDp: Float,
    turntableScale: Float,
): PlaybackNeedleGeometry =
    when (style) {
        TurntableStyle.Original ->
            playbackNeedleGeometry(
                containerSize = containerSize,
                densityPxPerDp = densityPxPerDp,
                turntableScale = turntableScale,
                rotationDegrees = 0f,
            )
        TurntableStyle.Netease -> neteaseNeedleGeometry(containerSize)
    }

internal fun TurntableStyleSpec.isWithinNeedleSeekRegion(
    point: Offset,
    containerSize: IntSize,
    densityPxPerDp: Float,
    turntableScale: Float,
    rotationDegrees: Float,
): Boolean {
    if (containerSize.width <= 0 || containerSize.height <= 0 || densityPxPerDp <= 0f) {
        return false
    }
    val geometry = needleGeometry(containerSize, densityPxPerDp, turntableScale)
    val localPoint = needleLocalPoint(point, geometry, rotationDegrees)
    return localPoint.x >= 0f &&
        localPoint.x <= geometry.width &&
        localPoint.y >= geometry.height * OriginalNeedleTouchStartRatio &&
        localPoint.y <= geometry.height
}

/** 网易云唱针几何：支点在碟心正上方，长度按手机版实测标定（与绘制同源）。 */
internal fun neteaseNeedleGeometry(containerSize: IntSize): PlaybackNeedleGeometry {
    // 针不随碟径放大（0.72 标定下针顶已贴舞台上沿），缩放与支点偏移都按参考直径。
    val needleDiameter = containerSize.width * NeteaseNeedleReferenceDiameterRatio
    val svgScale = NeteaseNeedleScaleToDiscRatio * needleDiameter
    val pivot = Offset(
        x = containerSize.width / 2f,
        y = (containerSize.height * NeteaseDiscCenterYRatio) -
            (NeteaseNeedlePivotOffsetToDiscRatio * needleDiameter),
    )
    val pivotLocal = Offset(
        x = NeteaseNeedlePivotSvgX * svgScale,
        y = NeteaseNeedlePivotSvgY * svgScale,
    )
    return PlaybackNeedleGeometry(
        left = pivot.x - pivotLocal.x,
        top = pivot.y - pivotLocal.y,
        width = NeteaseNeedleSvgViewBoxWidth * svgScale,
        height = NeteaseNeedleSvgViewBoxHeight * svgScale,
        pivotLocal = pivotLocal,
        pivot = pivot,
    )
}
