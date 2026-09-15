package com.smartisan.music.playback

import android.os.SystemClock
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlaybackStartFadePlayer(
    private val playbackPlayer: Player,
    private val fadeController: PlaybackStartFadeController,
) : ForwardingPlayer(playbackPlayer) {

    override fun play() {
        fadeController.protectResumeIfNeeded(playbackPlayer)
        super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) {
            fadeController.protectResumeIfNeeded(playbackPlayer)
        }
        super.setPlayWhenReady(playWhenReady)
    }
}

internal class PlaybackStartFadeController(
    private val scope: CoroutineScope,
) {
    private var fadeJob: Job? = null
    private var fadeArmTimeoutJob: Job? = null
    private var fadeArmed = false
    private var lastPausedAtMs: Long = 0L
    private var lastPausedMediaId: String? = null
    private var hasPlayed = false

    fun onPlayWhenReadyChanged(playbackPlayer: Player, playWhenReady: Boolean) {
        if (playWhenReady) {
            protectResumeIfNeeded(playbackPlayer)
            return
        }
        cancel(playbackPlayer, resetVolumeToFull = true)
        val currentMediaItem = playbackPlayer.currentMediaItem
        if (hasPlayed && currentMediaItem != null) {
            lastPausedAtMs = SystemClock.elapsedRealtime()
            lastPausedMediaId = currentMediaItem.mediaId
        }
    }

    fun onIsPlayingChanged(playbackPlayer: Player, isPlaying: Boolean) {
        if (isPlaying) {
            hasPlayed = true
            startFadeIfArmed(playbackPlayer)
        }
    }

    fun protectResumeIfNeeded(playbackPlayer: Player) {
        if (fadeJob != null || fadeArmed || !shouldProtectResume(playbackPlayer)) {
            return
        }
        lastPausedAtMs = 0L
        lastPausedMediaId = null
        armFade(playbackPlayer, startImmediatelyIfPlaying = true)
    }

    fun protectNextPlayback(playbackPlayer: Player) {
        lastPausedAtMs = 0L
        lastPausedMediaId = null
        hasPlayed = false
        armFade(playbackPlayer, startImmediatelyIfPlaying = false)
    }

    fun release(playbackPlayer: Player?) {
        cancel(playbackPlayer, resetVolumeToFull = true)
    }

    private fun shouldProtectResume(playbackPlayer: Player): Boolean {
        val currentMediaItem = playbackPlayer.currentMediaItem ?: return false
        if (lastPausedAtMs <= 0L) {
            return false
        }
        val pausedMediaId = lastPausedMediaId
        if (!pausedMediaId.isNullOrBlank() && currentMediaItem.mediaId != pausedMediaId) {
            return false
        }
        return SystemClock.elapsedRealtime() - lastPausedAtMs >= ResumeFadeThresholdMs
    }

    private fun armFade(
        playbackPlayer: Player,
        startImmediatelyIfPlaying: Boolean,
    ) {
        cancel(playbackPlayer = null, resetVolumeToFull = false)
        fadeArmed = true
        playbackPlayer.volume = 0f
        fadeArmTimeoutJob = scope.launch {
            delay(StartFadeArmTimeoutMs)
            if (fadeArmed && fadeJob == null) {
                playbackPlayer.volume = 1f
                fadeArmed = false
                fadeArmTimeoutJob = null
            }
        }
        if (startImmediatelyIfPlaying && playbackPlayer.isPlaying) {
            startFadeIfArmed(playbackPlayer)
        }
    }

    private fun startFadeIfArmed(playbackPlayer: Player) {
        if (!fadeArmed || fadeJob != null) {
            return
        }
        fadeArmTimeoutJob?.cancel()
        fadeArmTimeoutJob = null
        val steps = (ResumeFadeDurationMs / ResumeFadeStepIntervalMs)
            .toInt()
            .coerceIn(ResumeFadeMinSteps, ResumeFadeMaxSteps)
        val stepDelay = (ResumeFadeDurationMs / steps).coerceAtLeast(1L)
        fadeJob = scope.launch {
            repeat(steps) { step ->
                delay(stepDelay)
                playbackPlayer.volume = ((step + 1).toFloat() / steps).coerceAtMost(1f)
            }
            playbackPlayer.volume = 1f
            fadeJob = null
            fadeArmed = false
            lastPausedMediaId = null
        }
    }

    private fun cancel(
        playbackPlayer: Player?,
        resetVolumeToFull: Boolean,
    ) {
        val shouldRestoreVolume = resetVolumeToFull &&
            (fadeArmed || fadeJob?.isActive == true || fadeArmTimeoutJob?.isActive == true)
        fadeJob?.cancel()
        fadeJob = null
        fadeArmTimeoutJob?.cancel()
        fadeArmTimeoutJob = null
        fadeArmed = false
        if (shouldRestoreVolume) {
            playbackPlayer?.volume = 1f
        }
    }

    private companion object {
        private const val ResumeFadeThresholdMs = 800L
        private const val ResumeFadeDurationMs = 120L
        private const val ResumeFadeStepIntervalMs = 40L
        private const val StartFadeArmTimeoutMs = 5_000L
        private const val ResumeFadeMinSteps = 4
        private const val ResumeFadeMaxSteps = 30
    }
}
