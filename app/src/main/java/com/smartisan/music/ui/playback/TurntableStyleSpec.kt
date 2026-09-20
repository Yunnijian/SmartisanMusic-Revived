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
 * 播放/暂停的唱针目标角。Original 的针始终按播放进度定位，暂停也不抬起；
 * 网易云只有真正在播放时才落针，其余情况（暂停、无媒体、拖到碟外）一律抬起。
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
                NeteaseNeedlePlayingRotationDegrees
            } else {
                needleParkedRotationDegrees
            }
    }

internal fun TurntableStyleSpec.needlePositionFromRotation(
    rotationDegrees: Float,
    durationMs: Long,
): Long? =
    when (style) {
        TurntableStyle.Original -> needleSeekPositionFromRotation(rotationDegrees, durationMs)
        TurntableStyle.Netease -> {
            if (durationMs <= 0L || rotationDegrees <= needleProgressStartRotationDegrees) {
                null
            } else {
                val fraction =
                    ((rotationDegrees - needleProgressStartRotationDegrees) /
                        needleProgressSweepDegrees).coerceIn(0f, 1f)
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
    val discDiameter = containerSize.width * NeteaseDiscDiameterRatio
    val svgScale = NeteaseNeedleScaleToDiscRatio * discDiameter
    val pivot = Offset(
        x = containerSize.width / 2f,
        y = (containerSize.height * NeteaseDiscCenterYRatio) -
            (NeteaseNeedlePivotOffsetToDiscRatio * discDiameter),
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
