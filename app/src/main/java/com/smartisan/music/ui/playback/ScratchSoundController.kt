package com.smartisan.music.ui.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

internal const val ScratchPlaybackCycleMs = 1_800f
internal const val ScratchMinMotionDegrees = 0.05f
internal const val ScratchDecodeTimeoutUs = 10_000L
internal const val ScratchDecodeWindowBeforeMs = 12_000L
internal const val ScratchDecodeWindowAfterMs = 18_000L
internal const val ScratchDecodeRefreshDistanceMs = 4_000L
internal const val ScratchMinDeltaTimeMs = 8L
internal const val ScratchMaxDeltaTimeMs = 72L
internal const val ScratchOutputFrames = 384
internal const val ScratchOutputChannels = 2
internal const val ScratchOutputGain = 1.08f
internal const val ScratchMaxPlaybackRatePermille = 6_000

internal class ScratchSoundController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val playbackLock = ReentrantLock()
    private val playbackWakeCondition = playbackLock.newCondition()
    private val playbackThread = Thread(::playbackLoop, "ScratchAudioTrack").apply {
        isDaemon = true
        start()
    }

    @Volatile
    private var released = false

    @Volatile
    private var sourceGeneration = 0

    @Volatile
    private var currentSourceKey: String? = null

    @Volatile
    private var currentSourceUri: Uri? = null

    @Volatile
    private var loadingRequest: ScratchDecodeRequest? = null

    @Volatile
    private var pendingDecodePositionMs: Long? = null

    @Volatile
    private var scratchBuffer: ScratchBuffer? = null

    @Volatile
    private var scratchActive = false

    @Volatile
    private var scratchPositionMs = 0L

    @Volatile
    private var requestedDirection = 0

    @Volatile
    private var requestedPlaybackRatePermille = 0f

    @Volatile
    private var cursorResetGeneration = 0L

    @Volatile
    private var lastMotionRealtimeMs = 0L

    private var audioTrack: AudioTrack? = null
    private var audioTrackSampleRate = 0
    private var playbackCursor = 0.0
    private var appliedCursorResetGeneration = -1L
    private var appliedBufferIdentity: ScratchBufferIdentity? = null
    private val stereoWriteBuffer = ShortArray(ScratchOutputFrames * ScratchOutputChannels)

    fun prepareSource(
        sourceUri: Uri?,
        positionMs: Long,
    ) {
        val sourceKey = sourceUri?.toString()
        if (sourceKey.isNullOrEmpty()) {
            sourceGeneration += 1
            currentSourceKey = null
            currentSourceUri = null
            loadingRequest = null
            pendingDecodePositionMs = null
            scratchBuffer = null
            wakePlaybackThread()
            return
        }
        currentSourceUri = sourceUri
        if (currentSourceKey != sourceKey) {
            currentSourceKey = sourceKey
            scratchBuffer = null
            loadingRequest = null
            pendingDecodePositionMs = null
        }
        requestBufferForPosition(sourceUri, sourceKey, positionMs)
    }

    private fun requestBufferForPosition(
        sourceUri: Uri,
        sourceKey: String,
        positionMs: Long,
    ) {
        if (released) {
            return
        }
        val anchorPositionMs = positionMs.coerceAtLeast(0L)
        scratchBuffer?.takeIf { buffer ->
            buffer.covers(sourceKey, anchorPositionMs) &&
                !buffer.needsRefresh(anchorPositionMs)
        }?.let {
            return
        }
        loadingRequest?.takeIf { request ->
            request.sourceKey == sourceKey
        }?.let {
            pendingDecodePositionMs = anchorPositionMs
            return
        }
        startDecodeRequest(sourceUri, sourceKey, anchorPositionMs)
    }

    private fun startDecodeRequest(
        sourceUri: Uri,
        sourceKey: String,
        anchorPositionMs: Long,
    ) {
        val request = ScratchDecodeRequest(
            sourceUri = sourceUri,
            sourceKey = sourceKey,
            windowStartMs = (anchorPositionMs - ScratchDecodeWindowBeforeMs).coerceAtLeast(0L),
            windowEndMs = anchorPositionMs + ScratchDecodeWindowAfterMs,
        )
        val generation = sourceGeneration + 1
        sourceGeneration = generation
        loadingRequest = request
        decodeExecutor.execute {
            val decodedBuffer = decodeScratchBuffer(appContext, request)
            if (released || generation != sourceGeneration) {
                return@execute
            }
            if (decodedBuffer != null) {
                scratchBuffer = decodedBuffer
            }
            loadingRequest = null
            val pendingPositionMs = pendingDecodePositionMs
            pendingDecodePositionMs = null
            if (
                pendingPositionMs != null &&
                currentSourceKey == sourceKey &&
                currentSourceUri == sourceUri &&
                scratchBuffer?.takeIf { buffer ->
                    buffer.covers(sourceKey, pendingPositionMs) &&
                        !buffer.needsRefresh(pendingPositionMs)
                } == null
            ) {
                startDecodeRequest(sourceUri, sourceKey, pendingPositionMs.coerceAtLeast(0L))
            }
            wakePlaybackThread()
        }
    }

    fun onScratchStart(
        sourceUri: Uri?,
        positionMs: Long,
    ) {
        prepareSource(sourceUri, positionMs)
        scratchPositionMs = positionMs
        scratchActive = true
        requestedDirection = 1
        requestedPlaybackRatePermille = 0f
        lastMotionRealtimeMs = 0L
        cursorResetGeneration += 1
        wakePlaybackThread()
    }

    fun onScratchMotion(
        positionMs: Long,
        deltaAngleDegrees: Float,
    ) {
        val magnitude = abs(deltaAngleDegrees)
        if (released) {
            return
        }
        scratchPositionMs = positionMs
        if (magnitude < ScratchMinMotionDegrees) {
            requestedDirection = 0
            requestedPlaybackRatePermille = 0f
            cursorResetGeneration += 1
            wakePlaybackThread()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val deltaTimeMs = when {
            lastMotionRealtimeMs == 0L -> 16L
            else -> (now - lastMotionRealtimeMs).coerceIn(
                ScratchMinDeltaTimeMs,
                ScratchMaxDeltaTimeMs,
            )
        }
        lastMotionRealtimeMs = now

        val direction = if (deltaAngleDegrees >= 0f) 1 else -1
        requestedPlaybackRatePermille = scratchPlaybackRatePermille(magnitude, deltaTimeMs).toFloat()
        requestedDirection = direction
        currentSourceUri?.let { sourceUri ->
            currentSourceKey?.let { sourceKey ->
                requestBufferForPosition(sourceUri, sourceKey, positionMs)
            }
        }
        scratchActive = true
        wakePlaybackThread()
    }

    fun stop() {
        scratchActive = false
        requestedDirection = 0
        requestedPlaybackRatePermille = 0f
        lastMotionRealtimeMs = 0L
        wakePlaybackThread()
    }

    fun release() {
        stop()
        released = true
        sourceGeneration += 1
        decodeExecutor.shutdownNow()
        wakePlaybackThread()
        playbackThread.join(500)
    }

    private fun wakePlaybackThread() {
        playbackLock.withLock {
            playbackWakeCondition.signalAll()
        }
    }

    private fun playbackLoop() {
        try {
            while (!released) {
                val buffer = scratchBuffer
                val sourceKey = currentSourceKey
                val shouldPlay = scratchActive &&
                    buffer != null &&
                    sourceKey != null &&
                    buffer.covers(sourceKey, scratchPositionMs) &&
                    !released

                if (!shouldPlay || buffer.stereoSamples.isEmpty()) {
                    pauseAndFlushTrack()
                    playbackLock.withLock {
                        if (!released) {
                            playbackWakeCondition.await(24L, TimeUnit.MILLISECONDS)
                        }
                    }
                    continue
                }

                val localCursorResetGeneration = cursorResetGeneration
                val bufferIdentity = buffer.identity
                if (shouldResetScratchCursor(
                        appliedBufferIdentity = appliedBufferIdentity,
                        currentBufferIdentity = bufferIdentity,
                        appliedCursorResetGeneration = appliedCursorResetGeneration,
                        currentCursorResetGeneration = localCursorResetGeneration,
                    ) ||
                    !buffer.containsSample(playbackCursor)
                ) {
                    playbackCursor = buffer.positionMsToSample(scratchPositionMs)
                    appliedBufferIdentity = bufferIdentity
                    appliedCursorResetGeneration = localCursorResetGeneration
                }

                val frameStep = (requestedPlaybackRatePermille / 1_000f) *
                    requestedDirection.toDouble()
                if (!buffer.containsSampleSpan(playbackCursor, frameStep, ScratchOutputFrames)) {
                    playbackCursor = buffer.positionMsToSample(scratchPositionMs)
                }
                if (!buffer.containsSampleSpan(playbackCursor, frameStep, ScratchOutputFrames)) {
                    currentSourceUri?.let { sourceUri ->
                        requestBufferForPosition(sourceUri, sourceKey, scratchPositionMs)
                    }
                    pauseAndFlushTrack()
                    playbackLock.withLock {
                        if (!released) {
                            playbackWakeCondition.await(24L, TimeUnit.MILLISECONDS)
                        }
                    }
                    continue
                }

                val track = ensureAudioTrack(buffer.sampleRate)
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                fillStereoBuffer(buffer, frameStep)
                track.write(
                    stereoWriteBuffer,
                    0,
                    stereoWriteBuffer.size,
                    AudioTrack.WRITE_BLOCKING,
                )
            }
        } finally {
            releaseAudioTrack()
        }
    }

    private fun fillStereoBuffer(
        buffer: ScratchBuffer,
        frameStep: Double,
    ) {
        val lastFrameIndex = buffer.frameCount - 1
        if (lastFrameIndex < 0) {
            stereoWriteBuffer.fill(0)
            return
        }
        if (frameStep == 0.0) {
            stereoWriteBuffer.fill(0)
            return
        }
        var outputIndex = 0
        repeat(ScratchOutputFrames) {
            val left = amplifySample(
                interpolateStereoSample(buffer.stereoSamples, playbackCursor, channel = 0),
            )
            val right = amplifySample(
                interpolateStereoSample(buffer.stereoSamples, playbackCursor, channel = 1),
            )
            stereoWriteBuffer[outputIndex++] = left
            stereoWriteBuffer[outputIndex++] = right
            playbackCursor += frameStep
        }
    }

    private fun ensureAudioTrack(sampleRate: Int): AudioTrack {
        audioTrack?.takeIf { audioTrackSampleRate == sampleRate }?.let { return it }
        releaseAudioTrack()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val safeBufferSize = maxOf(
            minBufferSize.coerceAtLeast(0),
            stereoWriteBuffer.size * Short.SIZE_BYTES * 4,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(safeBufferSize)
            .build()
            .also {
                audioTrack = it
                audioTrackSampleRate = sampleRate
            }
    }

    private fun pauseAndFlushTrack() {
        val track = audioTrack ?: return
        runCatching {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.release()
        audioTrack = null
        audioTrackSampleRate = 0
    }
}
