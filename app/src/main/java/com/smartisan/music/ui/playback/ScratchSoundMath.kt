package com.smartisan.music.ui.playback

import android.media.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal fun scratchPlaybackRatePermille(
    deltaAngleDegrees: Float,
    deltaTimeMs: Long,
): Int {
    val scaledRate = (
        ScratchPlaybackCycleMs * deltaAngleDegrees /
            (360f * deltaTimeMs.coerceIn(ScratchMinDeltaTimeMs, ScratchMaxDeltaTimeMs).toFloat())
    ) * 1_000f
    return scaledRate
        .roundToInt()
        .coerceIn(0, ScratchMaxPlaybackRatePermille)
}

internal fun amplifySample(sample: Int): Short {
    return (sample * ScratchOutputGain)
        .roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()
}

internal fun interpolateStereoSample(
    samples: ShortArray,
    sampleIndex: Double,
    channel: Int,
): Int {
    if (samples.isEmpty()) {
        return 0
    }
    val frameCount = samples.size / ScratchOutputChannels
    val lastFrameIndex = frameCount - 1
    val clampedIndex = sampleIndex.coerceIn(0.0, lastFrameIndex.toDouble())
    val leftFrameIndex = clampedIndex.toInt()
    val rightFrameIndex = (leftFrameIndex + 1).coerceAtMost(lastFrameIndex)
    val fraction = clampedIndex - leftFrameIndex
    val sampleChannel = channel.coerceIn(0, ScratchOutputChannels - 1)
    val left = samples[(leftFrameIndex * ScratchOutputChannels) + sampleChannel].toDouble()
    val right = samples[(rightFrameIndex * ScratchOutputChannels) + sampleChannel].toDouble()
    return (left + ((right - left) * fraction)).roundToInt()
}

internal data class ScratchBuffer(
    val sourceKey: String,
    val sampleRate: Int,
    val windowStartMs: Long,
    val stereoSamples: ShortArray,
) {
    val frameCount: Int
        get() = stereoSamples.size / ScratchOutputChannels

    val identity: ScratchBufferIdentity
        get() = ScratchBufferIdentity(
            sourceKey = sourceKey,
            windowStartMs = windowStartMs,
            sampleRate = sampleRate,
            frameCount = frameCount,
        )

    private val windowEndMs: Long
        get() = windowStartMs + ((frameCount * 1_000L) / sampleRate.coerceAtLeast(1))

    fun covers(
        sourceKey: String,
        positionMs: Long,
    ): Boolean {
        return this.sourceKey == sourceKey && positionMs in windowStartMs..windowEndMs
    }

    fun needsRefresh(positionMs: Long): Boolean {
        return (windowStartMs > 0L && positionMs - windowStartMs < ScratchDecodeRefreshDistanceMs) ||
            windowEndMs - positionMs < ScratchDecodeRefreshDistanceMs
    }

    fun containsSample(sampleIndex: Double): Boolean {
        return sampleIndex in 0.0..(frameCount - 1).toDouble()
    }

    fun containsSampleSpan(
        startSampleIndex: Double,
        frameStep: Double,
        outputFrames: Int,
    ): Boolean {
        return containsScratchSampleSpan(
            frameCount = frameCount,
            startSampleIndex = startSampleIndex,
            frameStep = frameStep,
            outputFrames = outputFrames,
        )
    }

    fun positionMsToSample(positionMs: Long): Double {
        if (stereoSamples.isEmpty()) {
            return 0.0
        }
        val localPositionMs = positionMs.coerceAtLeast(windowStartMs) - windowStartMs
        val samplePosition = localPositionMs * sampleRate.toDouble() / 1_000.0
        return samplePosition.coerceIn(0.0, (frameCount - 1).toDouble())
    }
}

internal data class ScratchBufferIdentity(
    val sourceKey: String,
    val windowStartMs: Long,
    val sampleRate: Int,
    val frameCount: Int,
)

internal fun shouldResetScratchCursor(
    appliedBufferIdentity: ScratchBufferIdentity?,
    currentBufferIdentity: ScratchBufferIdentity,
    appliedCursorResetGeneration: Long,
    currentCursorResetGeneration: Long,
): Boolean {
    return appliedBufferIdentity != currentBufferIdentity ||
        appliedCursorResetGeneration != currentCursorResetGeneration
}

internal fun containsScratchSampleSpan(
    frameCount: Int,
    startSampleIndex: Double,
    frameStep: Double,
    outputFrames: Int,
): Boolean {
    if (frameCount <= 0 || outputFrames <= 0) {
        return false
    }
    val lastSampleIndex = (frameCount - 1).toDouble()
    val endSampleIndex = startSampleIndex + (frameStep * (outputFrames - 1))
    return startSampleIndex in 0.0..lastSampleIndex &&
        endSampleIndex in 0.0..lastSampleIndex
}

internal fun pcmBytesPerSample(pcmEncoding: Int): Int {
    return when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT,
        -> 4
        else -> 2
    }
}

internal fun ByteBuffer.readPcmSampleAsShort(pcmEncoding: Int): Short {
    return when (pcmEncoding) {
        AudioFormat.ENCODING_PCM_8BIT -> {
            (((get().toInt() and 0xFF) - 128) shl 8).toShort()
        }
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            val signed24 = readSigned24BitSample()
            (signed24 shr 8).toShort()
        }
        AudioFormat.ENCODING_PCM_32BIT -> {
            (int shr 16).toShort()
        }
        else -> short
    }
}

private fun ByteBuffer.readSigned24BitSample(): Int {
    val first = get().toInt() and 0xFF
    val second = get().toInt() and 0xFF
    val third = get().toInt() and 0xFF
    val value = if (order() == ByteOrder.BIG_ENDIAN) {
        (first shl 16) or (second shl 8) or third
    } else {
        first or (second shl 8) or (third shl 16)
    }
    return if ((value and 0x80_0000) != 0) {
        value or -0x100_0000
    } else {
        value
    }
}
