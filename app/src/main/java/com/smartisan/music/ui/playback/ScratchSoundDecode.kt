package com.smartisan.music.ui.playback

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal data class ScratchDecodeRequest(
    val sourceUri: Uri,
    val sourceKey: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
) {
    val windowStartUs: Long
        get() = windowStartMs * 1_000L

    val windowEndUs: Long
        get() = windowEndMs * 1_000L
}

internal fun decodeScratchBuffer(
    context: Context,
    request: ScratchDecodeRequest,
): ScratchBuffer? {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    return try {
        extractor.setDataSource(context, request.sourceUri, null)
        val trackIndex = findAudioTrackIndex(extractor)
        if (trackIndex == -1) {
            null
        } else {
            extractor.selectTrack(trackIndex)
            extractor.seekTo(request.windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val sourceFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = sourceFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val decoder = MediaCodec.createDecoderByType(mimeType).apply {
                configure(sourceFormat, null, null, 0)
                start()
            }
            codec = decoder

            val initialSampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val initialCapacity = buildInitialSampleCapacity(request, initialSampleRate)
            val sampleBuilder = StereoSampleBuilder(initialCapacity)
            val bufferInfo = MediaCodec.BufferInfo()
            var outputSampleRate = initialSampleRate
            var outputChannelCount = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var firstOutputTimeUs: Long? = null
            var inputEnded = false
            var outputEnded = false

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(ScratchDecodeTimeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: break
                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0 || (sampleTimeUs >= 0L && sampleTimeUs > request.windowEndUs)) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                sampleTimeUs,
                                extractor.sampleFlags,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, ScratchDecodeTimeoutUs)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> {
                        if (outputIndex >= 0) {
                            if (bufferInfo.size > 0) {
                                if (firstOutputTimeUs == null) {
                                    firstOutputTimeUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                                }
                                val outputBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?.duplicate()
                                    ?.apply {
                                        position(bufferInfo.offset)
                                        limit(bufferInfo.offset + bufferInfo.size)
                                    }
                                if (outputBuffer != null) {
                                    sampleBuilder.append(
                                        outputBuffer,
                                        outputChannelCount,
                                        pcmEncoding,
                                    )
                                }
                            }
                            decoder.releaseOutputBuffer(outputIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputEnded = true
                            }
                        }
                    }
                }
            }

            val stereoSamples = sampleBuilder.build()
            if (stereoSamples.isEmpty()) {
                null
            } else {
                ScratchBuffer(
                    sourceKey = request.sourceKey,
                    sampleRate = outputSampleRate,
                    windowStartMs = (firstOutputTimeUs ?: request.windowStartUs) / 1_000L,
                    stereoSamples = stereoSamples,
                )
            }
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        extractor.release()
    }
}

private fun findAudioTrackIndex(extractor: MediaExtractor): Int {
    repeat(extractor.trackCount) { index ->
        val format = extractor.getTrackFormat(index)
        val mimeType = format.getString(MediaFormat.KEY_MIME) ?: return@repeat
        if (mimeType.startsWith("audio/")) {
            return index
        }
    }
    return -1
}

private fun buildInitialSampleCapacity(
    request: ScratchDecodeRequest,
    sampleRate: Int,
): Int {
    val durationMs = (request.windowEndMs - request.windowStartMs).coerceAtLeast(1L)
    val projectedSamples = (durationMs * sampleRate / 1_000L)
        .coerceAtLeast(sampleRate.toLong())
    return projectedSamples.toInt()
}

private class StereoSampleBuilder(initialCapacity: Int) {
    private var buffer = ShortArray(initialCapacity.coerceAtLeast(2_048) * ScratchOutputChannels)
    private var frameCount = 0

    fun append(
        outputBuffer: ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> appendFloat(outputBuffer, channelCount)
            else -> appendIntegerPcm(outputBuffer, channelCount, pcmEncoding)
        }
    }

    fun build(): ShortArray = buffer.copyOf(frameCount * ScratchOutputChannels)

    private fun appendIntegerPcm(
        outputBuffer: ByteBuffer,
        channelCount: Int,
        pcmEncoding: Int,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        val bytesPerSample = pcmBytesPerSample(pcmEncoding)
        val pcmBuffer = outputBuffer.order(ByteOrder.nativeOrder())
        val frameCount = pcmBuffer.remaining() / (channels * bytesPerSample)
        ensureCapacity(this.frameCount + frameCount)
        repeat(frameCount) {
            val left = pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            val right = if (channels > 1) {
                pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            } else {
                left
            }
            repeat((channels - 2).coerceAtLeast(0)) {
                pcmBuffer.readPcmSampleAsShort(pcmEncoding)
            }
            val outputIndex = this.frameCount * ScratchOutputChannels
            buffer[outputIndex] = left
            buffer[outputIndex + 1] = right
            this.frameCount += 1
        }
    }

    private fun appendFloat(
        outputBuffer: ByteBuffer,
        channelCount: Int,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        val floatBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frameCount = floatBuffer.remaining() / channels
        ensureCapacity(this.frameCount + frameCount)
        repeat(frameCount) {
            val left = floatBuffer.get()
            val right = if (channels > 1) {
                floatBuffer.get()
            } else {
                left
            }
            repeat((channels - 2).coerceAtLeast(0)) {
                floatBuffer.get()
            }
            val outputIndex = this.frameCount * ScratchOutputChannels
            buffer[outputIndex] = (left * Short.MAX_VALUE.toFloat())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            buffer[outputIndex + 1] = (right * Short.MAX_VALUE.toFloat())
                .roundToInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            this.frameCount += 1
        }
    }

    private fun ensureCapacity(requiredFrameCount: Int) {
        val requiredSize = requiredFrameCount * ScratchOutputChannels
        if (requiredSize <= buffer.size) {
            return
        }
        var newSize = buffer.size
        while (newSize < requiredSize) {
            newSize *= 2
        }
        buffer = buffer.copyOf(newSize)
    }
}

