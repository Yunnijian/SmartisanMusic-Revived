package com.smartisan.music.ui.playback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import com.smartisan.music.data.settings.TurntableStyle
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay

/**
 * 唱针/搓碟手势的 pointer-input：命中判定、角度增量换算、松手甩动与搓碟采样。
 *
 * 从 [PlaybackTurntableStage] 的封面页搬出的手势层；几何换算仍在 [PlaybackTurntableMath]。
 */

internal fun Modifier.playbackCoverPointerInput(
    scratchAvailable: Boolean,
    needleSeekAvailable: Boolean,
    densityPxPerDp: Float,
    scale: Float,
    spec: TurntableStyleSpec,
    needleAnimatable: Animatable<Float, AnimationVector1D>,
    latestDiscSize: State<IntSize>,
    latestPositionMs: State<Long>,
    latestDurationMs: State<Long>,
    latestVisualPageToggle: () -> Unit,
    latestDiscScratchStart: () -> Unit,
    latestDiscScratchMotion: (Long, Float) -> Unit,
    latestDiscScratchPositionChange: (Long, Float) -> Unit,
    latestDiscScratchEnd: (Long, Float) -> Unit,
    latestDiscScratchCancel: () -> Unit,
    latestNeedleSeekStart: (Float, Long?) -> Unit,
    latestNeedleSeekPositionChange: (Float, Long?) -> Unit,
    latestNeedleSeekEnd: (Float, Long?) -> Unit,
    latestNeedleSeekCancel: () -> Unit,
): Modifier =
    pointerInput(scratchAvailable, needleSeekAvailable, densityPxPerDp, scale, spec.style) {
            val tapTouchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val size = latestDiscSize.value
                val center = discCenter(size)
                val radius = discRadius(size)
                val withinNeedleSeekRegion =
                    needleSeekAvailable &&
                        spec.isWithinNeedleSeekRegion(
                            point = down.position,
                            containerSize = size,
                            densityPxPerDp = densityPxPerDp,
                            turntableScale = scale,
                            rotationDegrees = needleAnimatable.value,
                        )
                val withinScratchRegion =
                    scratchAvailable &&
                        isWithinScratchRegion(down.position, center, radius)
                val dragMode =
                    when {
                        withinNeedleSeekRegion -> CoverDragMode.NeedleSeek
                        withinScratchRegion -> CoverDragMode.DiscScratch
                        else -> CoverDragMode.None
                    }

                when (dragMode) {
                    CoverDragMode.None ->
                        handleCoverTapGesture(
                            down = down,
                            center = center,
                            radius = radius,
                            tapTouchSlop = tapTouchSlop,
                            latestVisualPageToggle = latestVisualPageToggle,
                        )

                    CoverDragMode.DiscScratch ->
                        handleDiscScratchGesture(
                            down = down,
                            center = center,
                            radius = radius,
                            tapTouchSlop = tapTouchSlop,
                            latestPositionMs = latestPositionMs.value,
                            latestDurationMs = latestDurationMs.value,
                            latestDiscScratchStart = latestDiscScratchStart,
                            latestDiscScratchMotion = latestDiscScratchMotion,
                            latestDiscScratchPositionChange = latestDiscScratchPositionChange,
                            latestDiscScratchEnd = latestDiscScratchEnd,
                            latestDiscScratchCancel = latestDiscScratchCancel,
                            latestVisualPageToggle = latestVisualPageToggle,
                        )

                    CoverDragMode.NeedleSeek ->
                        handleNeedleSeekGesture(
                            down = down,
                            size = size,
                            densityPxPerDp = densityPxPerDp,
                            scale = scale,
                            spec = spec,
                            tapTouchSlop = tapTouchSlop,
                            needleAnimatable = needleAnimatable,
                            latestPositionMs = latestPositionMs.value,
                            latestDurationMs = latestDurationMs.value,
                            latestNeedleSeekStart = latestNeedleSeekStart,
                            latestNeedleSeekPositionChange = latestNeedleSeekPositionChange,
                            latestNeedleSeekEnd = latestNeedleSeekEnd,
                            latestNeedleSeekCancel = latestNeedleSeekCancel,
                        )
                }
            }
    }

internal data class ScratchMotionSample(
    val position: Offset,
    val uptimeMs: Long,
)

internal fun scratchReleaseVelocityDegreesPerSecond(
    angularVelocityDegreesPerSecond: Float,
    pixelFlingVelocityDegreesPerSecond: Float,
    releaseDelayMs: Long,
    directionHint: Int,
): Float {
    val recentAngularVelocity =
        if (releaseDelayMs <= ScratchFlingReleaseTimeoutMs) {
            angularVelocityDegreesPerSecond
        } else {
            0f
        }
    val direction =
        when {
            recentAngularVelocity < 0f -> -1
            recentAngularVelocity > 0f -> 1
            directionHint < 0 -> -1
            directionHint > 0 -> 1
            else -> 0
        }
    if (direction == 0) {
        return 0f
    }
    val blendedVelocity =
        (abs(recentAngularVelocity) + pixelFlingVelocityDegreesPerSecond.coerceAtLeast(0f)) / 2f
    if (blendedVelocity < ScratchFlingMinVelocityDegreesPerSecond) {
        return 0f
    }
    val signedVelocity = blendedVelocity * direction
    return signedVelocity.coerceIn(
        -ScratchVelocityMaxDegreesPerSecond,
        ScratchVelocityMaxDegreesPerSecond,
    )
}

internal fun recordScratchMotionSample(
    samples: ArrayDeque<ScratchMotionSample>,
    position: Offset,
    uptimeMs: Long,
) {
    samples.addLast(ScratchMotionSample(position, uptimeMs))
    while (
        samples.size > 2 && uptimeMs - samples.first().uptimeMs > ScratchFlingVelocitySampleWindowMs
    ) {
        samples.removeFirst()
    }
}

internal fun scratchPixelFlingVelocityDegreesPerSecond(
    samples: ArrayDeque<ScratchMotionSample>,
    releasePosition: Offset,
    releaseUptimeMs: Long,
): Float {
    val sample =
        samples.firstOrNull {
            val ageMs = releaseUptimeMs - it.uptimeMs
            ageMs in ScratchFlingMinVelocitySampleMs..ScratchFlingVelocitySampleWindowMs
        }
            ?: samples.lastOrNull {
                releaseUptimeMs - it.uptimeMs >= ScratchFlingMinVelocitySampleMs
            }
            ?: return 0f

    val deltaTimeMs = (releaseUptimeMs - sample.uptimeMs).coerceAtLeast(1L).toFloat()
    val velocityX = (releasePosition.x - sample.position.x) * 1_000f / deltaTimeMs
    val velocityY = (releasePosition.y - sample.position.y) * 1_000f / deltaTimeMs
    return ((abs(velocityX) + abs(velocityY)) / ScratchPixelFlingDivisor).coerceIn(
        0f,
        ScratchVelocityMaxDegreesPerSecond,
    )
}

/** 唱针旋转的手势态：目标角度补间、拖拽抬针，以及只在绘制期读取的旋转值。 */
internal class NeedleGestureState(
    val animatable: Animatable<Float, AnimationVector1D>,
    val rotation: State<Float>,
    val liftFraction: State<Float>,
)

@Composable
internal fun rememberNeedleGestureState(
    turntableStyleSpec: TurntableStyleSpec,
    currentPositionMs: Long,
    durationMs: Long,
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    coverDragMode: CoverDragMode,
    needlePreviewRotationDegrees: Float?,
    needleParkedOutside: Boolean,
    mediaId: String?,
): NeedleGestureState {
        val progress =
            durationMs
                .takeIf { it > 0L }
                ?.let { currentPositionMs.toFloat() / it.toFloat() }
                ?.coerceIn(0f, 1f) ?: 0f
        val targetNeedleRotation =
            needlePreviewRotationDegrees
                ?: turntableStyleSpec.needleTargetRotation(
                    hasMediaItem = hasMediaItem,
                    isPlaying = isPlaying,
                    needleParkedOutside = needleParkedOutside,
                    progress = progress,
                )

        val needleAnimatable = remember { Animatable(targetNeedleRotation) }
        val needleSeekDragging = coverDragMode == CoverDragMode.NeedleSeek
        var needleLiftHeldAfterSeek by remember { mutableStateOf(false) }

        LaunchedEffect(needleSeekDragging, needleParkedOutside) {
            if (needleSeekDragging) {
                needleLiftHeldAfterSeek = true
                return@LaunchedEffect
            }
            if (needleLiftHeldAfterSeek && !needleParkedOutside) {
                delay(NeedleLiftHoldAfterReleaseMs)
            }
            needleLiftHeldAfterSeek = false
        }

        val needleLiftFraction =
            animateFloatAsState(
                targetValue = if (needleSeekDragging || needleLiftHeldAfterSeek) 1f else 0f,
                animationSpec = tween(if (needleSeekDragging || needleLiftHeldAfterSeek) 125 else 250),
                label = "needleLiftFraction",
            )

        // 播放位置约每 250ms 推进一次，与 tween(220) 同量级：位置派生目标不进 key（否则每次位置更新
        // 都会重建协程并取消在途补间），只按切歌/拖拽态建一次协程，目标变化交给 snapshotFlow 追踪。
        val latestTargetNeedleRotation by rememberUpdatedState(targetNeedleRotation)
        val needleTweenSpec =
            if (turntableStyleSpec.style == TurntableStyle.Original) {
                tween<Float>(220)
            } else {
                tween(
                    durationMillis = turntableStyleSpec.needleAnimationDurationMs,
                    easing = turntableStyleSpec.needleAnimationEasing,
                )
            }
        LaunchedEffect(mediaId, needleSeekDragging) {
            snapshotFlow { latestTargetNeedleRotation }.collect { target ->
                if (needleSeekDragging) {
                    needleAnimatable.snapTo(target)
                } else {
                    needleAnimatable.animateTo(target, animationSpec = needleTweenSpec)
                }
            }
        }

        // 组合期不读动画值：以 State 形式下传到绘制阶段（Canvas / graphicsLayer）再读 .value，
        // 否则补间的每一帧都会重组整棵封面页。
        val needleRotation = remember { derivedStateOf { needleAnimatable.value } }
    return NeedleGestureState(
        animatable = needleAnimatable,
        rotation = needleRotation,
        liftFraction = needleLiftFraction,
    )
}

private suspend fun AwaitPointerEventScope.handleCoverTapGesture(
    down: PointerInputChange,
    center: Offset,
    radius: Float,
    tapTouchSlop: Float,
    latestVisualPageToggle: () -> Unit,
) {
    val initialPosition = down.position
    var finalPosition = initialPosition
    var maxMoveDistance = 0f
    val pointerId = down.id
    while (true) {
        val event = awaitPointerEvent()
        val change =
            event.changes.firstOrNull { it.id == pointerId }
                ?: break
        finalPosition = change.position
        val moveDistance =
            distanceBetween(initialPosition, finalPosition)
        if (moveDistance > maxMoveDistance) {
            maxMoveDistance = moveDistance
        }
        if (!change.pressed) {
            if (
                maxMoveDistance <= tapTouchSlop &&
                    isDiscTapWithinSlop(
                        initialPosition = initialPosition,
                        finalPosition = finalPosition,
                        maxMoveDistance = maxMoveDistance,
                        center = center,
                        radius = radius,
                        tapTouchSlop = tapTouchSlop,
                    )
            ) {
                latestVisualPageToggle()
            }
            break
        }
    }
}

private suspend fun AwaitPointerEventScope.handleDiscScratchGesture(
    down: PointerInputChange,
    center: Offset,
    radius: Float,
    tapTouchSlop: Float,
    latestPositionMs: Long,
    latestDurationMs: Long,
    latestDiscScratchStart: () -> Unit,
    latestDiscScratchMotion: (Long, Float) -> Unit,
    latestDiscScratchPositionChange: (Long, Float) -> Unit,
    latestDiscScratchEnd: (Long, Float) -> Unit,
    latestDiscScratchCancel: () -> Unit,
    latestVisualPageToggle: () -> Unit,
) {
    val initialPosition = down.position
    var finalPosition = initialPosition
    var maxMoveDistance = 0f
    var scratchPositionMs = 0L
    var lastAngleDegrees = angleDegrees(initialPosition, center)
    var lastMotionUptimeMs = down.uptimeMillis
    var lastAngularVelocityDegreesPerSecond = 0f
    var lastScratchDirection = 0
    val scratchMotionSamples = ArrayDeque<ScratchMotionSample>()
    val pointerId = down.id
    var scratchStarted = false
    var cancelled = true
    while (true) {
        val event = awaitPointerEvent()
        val change =
            event.changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            break
        }
        finalPosition = change.position
        maxMoveDistance =
            max(
                maxMoveDistance,
                distanceBetween(initialPosition, finalPosition),
            )
        if (!change.pressed) {
            if (scratchStarted) {
                latestDiscScratchEnd(
                    scratchPositionMs,
                    scratchReleaseVelocityDegreesPerSecond(
                        angularVelocityDegreesPerSecond =
                            lastAngularVelocityDegreesPerSecond,
                        pixelFlingVelocityDegreesPerSecond =
                            scratchPixelFlingVelocityDegreesPerSecond(
                                samples = scratchMotionSamples,
                                releasePosition = change.position,
                                releaseUptimeMs =
                                    change.uptimeMillis,
                            ),
                        releaseDelayMs =
                            change.uptimeMillis -
                                lastMotionUptimeMs,
                        directionHint = lastScratchDirection,
                    ),
                )
                change.consume()
            } else if (
                isDiscTapWithinSlop(
                    initialPosition = initialPosition,
                    finalPosition = finalPosition,
                    maxMoveDistance = maxMoveDistance,
                    center = center,
                    radius = radius,
                    tapTouchSlop = tapTouchSlop,
                )
            ) {
                latestVisualPageToggle()
            }
            cancelled = false
            break
        }
        if (!scratchStarted) {
            if (maxMoveDistance <= tapTouchSlop) {
                continue
            }
            scratchStarted = true
            scratchPositionMs =
                scratchStartPosition(
                    positionMs = latestPositionMs,
                    durationMs = latestDurationMs,
                )
            lastAngleDegrees = angleDegrees(change.position, center)
            lastMotionUptimeMs = change.uptimeMillis
            lastAngularVelocityDegreesPerSecond = 0f
            lastScratchDirection = 0
            scratchMotionSamples.clear()
            recordScratchMotionSample(
                samples = scratchMotionSamples,
                position = change.position,
                uptimeMs = change.uptimeMillis,
            )
            latestDiscScratchStart()
            latestDiscScratchPositionChange(scratchPositionMs, 0f)
            change.consume()
            continue
        }

        val currentAngle = angleDegrees(change.position, center)
        val deltaAngle =
            normalizeAngleDelta(currentAngle - lastAngleDegrees)
                .coerceIn(
                    -ScratchMaxAngleStepDegrees,
                    ScratchMaxAngleStepDegrees,
                )
        lastAngleDegrees = currentAngle
        val deltaTimeMs =
            (change.uptimeMillis - lastMotionUptimeMs)
                .coerceAtLeast(1L)
        lastAngularVelocityDegreesPerSecond =
            (deltaAngle * 1_000f / deltaTimeMs.toFloat()).coerceIn(
                -ScratchVelocityMaxDegreesPerSecond,
                ScratchVelocityMaxDegreesPerSecond,
            )
        if (deltaAngle != 0f) {
            lastScratchDirection = if (deltaAngle < 0f) -1 else 1
        }
        lastMotionUptimeMs = change.uptimeMillis
        recordScratchMotionSample(
            samples = scratchMotionSamples,
            position = change.position,
            uptimeMs = change.uptimeMillis,
        )

        val targetPosition =
            scratchPositionAfterAngle(
                positionMs = scratchPositionMs,
                deltaAngleDegrees = deltaAngle,
                durationMs = latestDurationMs,
            )
        latestDiscScratchMotion(targetPosition, deltaAngle)
        if (targetPosition != scratchPositionMs) {
            scratchPositionMs = targetPosition
            latestDiscScratchPositionChange(
                targetPosition,
                deltaAngle,
            )
        }
        change.consume()
    }
    if (cancelled && scratchStarted) {
        latestDiscScratchCancel()
    }
}

private suspend fun AwaitPointerEventScope.handleNeedleSeekGesture(
    down: PointerInputChange,
    size: IntSize,
    densityPxPerDp: Float,
    scale: Float,
    spec: TurntableStyleSpec,
    tapTouchSlop: Float,
    needleAnimatable: Animatable<Float, AnimationVector1D>,
    latestPositionMs: Long,
    latestDurationMs: Long,
    latestNeedleSeekStart: (Float, Long?) -> Unit,
    latestNeedleSeekPositionChange: (Float, Long?) -> Unit,
    latestNeedleSeekEnd: (Float, Long?) -> Unit,
    latestNeedleSeekCancel: () -> Unit,
) {
    val initialPosition = down.position
    var maxMoveDistance = 0f
    // 暂停时指针是抬起姿态、不携带进度，起步角必须由当前进度反推；
    // 否则抬起角（= 最高点）会被读成满进度，一按下进度就跳。
    var needleRotationDegrees =
        if (spec.style == TurntableStyle.Netease) {
            spec.needleRotationForProgress(latestPositionMs, latestDurationMs)
        } else {
            needleAnimatable.value.coerceIn(
                spec.needleDragMinRotationDegrees,
                spec.needleDragMaxRotationDegrees,
            )
        }
    var needlePositionMs =
        spec.needleDragPosition(
            rotationDegrees = needleRotationDegrees,
            durationMs = latestDurationMs,
        )
    var needleSeekHadPlayablePosition = needlePositionMs != null
    var needlePivot =
        spec.needleGeometry(
                containerSize = size,
                densityPxPerDp = densityPxPerDp,
                turntableScale = scale,
            )
            .pivot
    var lastNeedleAngleDegrees =
        angleDegrees(initialPosition, needlePivot)
    val pointerId = down.id
    var needleSeekStarted = false
    var cancelled = true
    val outsideActivationDistancePx =
        NeedleSeekOutsideActivationDistanceDp * densityPxPerDp
    while (true) {
        val event = awaitPointerEvent()
        val change =
            event.changes.firstOrNull { it.id == pointerId }
        if (change == null) {
            break
        }
        maxMoveDistance =
            max(
                maxMoveDistance,
                distanceBetween(initialPosition, change.position),
            )
        if (!change.pressed) {
            if (needleSeekStarted) {
                if (
                    needleSeekHadPlayablePosition ||
                        needlePositionMs != null
                ) {
                    latestNeedleSeekEnd(
                        needleRotationDegrees,
                        needlePositionMs,
                    )
                } else {
                    latestNeedleSeekCancel()
                }
                change.consume()
            }
            cancelled = false
            break
        }
        if (!needleSeekStarted) {
            if (maxMoveDistance <= tapTouchSlop) {
                continue
            }
            val candidate =
                resolveNeedleSeekStartCandidate(
                    currentRotationDegrees = needleRotationDegrees,
                    currentPositionMs = needlePositionMs,
                    lastNeedleAngleDegrees = lastNeedleAngleDegrees,
                    pointerPosition = change.position,
                    needlePivot = needlePivot,
                    maxMoveDistance = maxMoveDistance,
                    outsideActivationDistancePx = outsideActivationDistancePx,
                    containerSize = size,
                    densityPxPerDp = densityPxPerDp,
                    turntableScale = scale,
                    spec = spec,
                    durationMs = latestDurationMs,
                ) ?: continue
            needleSeekStarted = true
            needlePivot = candidate.pivot
            needleRotationDegrees = candidate.rotationDegrees
            needlePositionMs = candidate.positionMs
            if (needlePositionMs != null) {
                needleSeekHadPlayablePosition = true
            }
            lastNeedleAngleDegrees = candidate.angleDegrees
            latestNeedleSeekStart(
                needleRotationDegrees,
                needlePositionMs,
            )
            change.consume()
            continue
        }

        val currentNeedleAngleDegrees =
            angleDegrees(change.position, needlePivot)
        val deltaNeedleAngle =
            normalizeAngleDelta(
                    currentNeedleAngleDegrees -
                        lastNeedleAngleDegrees
                )
                .coerceIn(
                    -ScratchMaxAngleStepDegrees,
                    ScratchMaxAngleStepDegrees,
                )
        lastNeedleAngleDegrees = currentNeedleAngleDegrees
        needleRotationDegrees =
            (needleRotationDegrees + deltaNeedleAngle).coerceIn(
                spec.needleDragMinRotationDegrees,
                spec.needleDragMaxRotationDegrees,
            )
        needlePositionMs =
            spec.needleDragPosition(
                rotationDegrees = needleRotationDegrees,
                durationMs = latestDurationMs,
            )
        if (needlePositionMs != null) {
            needleSeekHadPlayablePosition = true
        }
        latestNeedleSeekPositionChange(
            needleRotationDegrees,
            needlePositionMs,
        )
        change.consume()
    }
    if (cancelled && needleSeekStarted) {
        latestNeedleSeekCancel()
    }
}

/** 唱针拖拽起始判定：候选角度/进度与命中条件都由 [shouldStartNeedleSeekDrag] 决定，未达阈值返回 null。 */
private class NeedleSeekStartCandidate(
    val rotationDegrees: Float,
    val positionMs: Long?,
    val pivot: Offset,
    val angleDegrees: Float,
)

private fun resolveNeedleSeekStartCandidate(
    currentRotationDegrees: Float,
    currentPositionMs: Long?,
    lastNeedleAngleDegrees: Float,
    pointerPosition: Offset,
    needlePivot: Offset,
    maxMoveDistance: Float,
    outsideActivationDistancePx: Float,
    containerSize: IntSize,
    densityPxPerDp: Float,
    turntableScale: Float,
    spec: TurntableStyleSpec,
    durationMs: Long,
): NeedleSeekStartCandidate? {
    val candidateNeedleAngleDegrees = angleDegrees(pointerPosition, needlePivot)
    val candidateDeltaNeedleAngle =
        normalizeAngleDelta(candidateNeedleAngleDegrees - lastNeedleAngleDegrees)
            .coerceIn(
                -ScratchMaxAngleStepDegrees,
                ScratchMaxAngleStepDegrees,
            )
    val candidateNeedleRotationDegrees =
        (currentRotationDegrees + candidateDeltaNeedleAngle)
            .coerceIn(
                spec.needleDragMinRotationDegrees,
                spec.needleDragMaxRotationDegrees,
            )
    val candidateNeedlePositionMs =
        spec.needleDragPosition(
            rotationDegrees = candidateNeedleRotationDegrees,
            durationMs = durationMs,
        )
    if (
        !shouldStartNeedleSeekDrag(
            initialPositionMs = currentPositionMs,
            candidatePositionMs = candidateNeedlePositionMs,
            maxMoveDistance = maxMoveDistance,
            outsideActivationDistancePx = outsideActivationDistancePx,
        )
    ) {
        return null
    }
    val nextPivot =
        spec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = densityPxPerDp,
                turntableScale = turntableScale,
            )
            .pivot
    return NeedleSeekStartCandidate(
        rotationDegrees = candidateNeedleRotationDegrees,
        positionMs = candidateNeedlePositionMs,
        pivot = nextPivot,
        angleDegrees = angleDegrees(pointerPosition, nextPivot),
    )
}
