package com.smartisan.music.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.playback.NowPlayingLyricsRepository
import com.smartisan.music.playback.PlaybackSleepTimer
import com.smartisan.music.playback.artworkRequestKey
import com.smartisan.music.playback.await
import com.smartisan.music.playback.extractEmbeddedLyrics
import com.smartisan.music.playback.invalidateLibrary
import com.smartisan.music.playback.removeMediaItemsByMediaIds
import com.smartisan.music.playback.setScratchSeekModeEnabled
import com.smartisan.music.ui.components.MediaStoreDeleteCoordinator
import com.smartisan.music.ui.components.loadEmbeddedArtwork
import com.smartisan.music.ui.components.peekArtworkThumbnail
import com.smartisan.music.ui.components.rememberMediaStoreDeleteCoordinator
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
internal fun PlaybackScreenEntranceEffect(entranceTimeMillis: Animatable<Float, AnimationVector1D>) {
    LaunchedEffect(Unit) {
        entranceTimeMillis.snapTo(0f)
        entranceTimeMillis.animateTo(
            targetValue = PlaybackEntranceTotalDurationMillis.toFloat(),
            animationSpec =
                tween(
                    durationMillis = PlaybackEntranceTotalDurationMillis,
                    easing = LinearEasing,
                ),
        )
    }
}

@Composable
internal fun rememberPlaybackDeleteCoordinator(
    context: Context,
    controller: MediaController?,
    scope: CoroutineScope,
    favoriteRepository: FavoriteSongsRepository,
    playlistRepository: PlaylistRepository,
    playbackStatsRepository: PlaybackStatsRepository,
    currentOnLibraryChanged: () -> Unit,
): MediaStoreDeleteCoordinator {
    return rememberMediaStoreDeleteCoordinator(
            onDeleted = { mediaIds ->
                controller.removeMediaItemsByMediaIds(mediaIds)
                scope.launch {
                    runCatching {
                        favoriteRepository.removeAll(mediaIds)
                    }
                    runCatching {
                        playlistRepository.removeMediaIdsFromAll(mediaIds)
                    }
                    runCatching {
                        playbackStatsRepository.deleteByIds(mediaIds)
                    }
                    runCatching {
                        controller?.invalidateLibrary()?.await(context)
                    }
                    currentOnLibraryChanged()
                }
                context.toast(R.string.playback_delete_success)
            },
            onNotDeleted = {
                context.toast(R.string.playback_delete_failed)
            },
        )
}

@Composable
internal fun PlaybackScreenSessionEffects(host: PlaybackScreenHost) {
    BackHandler {
        if (host.showSleepTimerDialog) {
            host.showSleepTimerDialog = false
        } else if (host.showMorePanel) {
            host.showMorePanel = false
        } else {
            host.onCollapse()
        }
    }

    DisposableEffect(host.controller) {
        val playbackController = host.controller ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    val nextState = playbackController.snapshot(volume = host.latestVolume.value)
                    host.state = nextState
                    host.livePositionMs = nextState.currentPositionMs
                }
            }
        playbackController.addListener(listener)
        onDispose {
            playbackController.removeListener(listener)
        }
    }

    DisposableEffect(host.lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START || event == Lifecycle.Event.ON_RESUME) {
                PlaybackSleepTimer.refresh()
            }
        }
        host.lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            host.lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(host.showSleepTimerDialog) {
        if (host.showSleepTimerDialog) {
            PlaybackSleepTimer.refresh()
        }
    }

    LaunchedEffect(host.sleepTimerState.isActive) {
        if (host.sleepTimerWasActive && !host.sleepTimerState.isActive) {
            host.showSleepTimerDialog = false
        }
        host.sleepTimerWasActive = host.sleepTimerState.isActive
    }
}

@Composable
internal fun PlaybackScreenPlaybackEffects(host: PlaybackScreenHost) {
    DisposableEffect(host.controller) {
        val playbackController = host.controller
        onDispose {
            playbackController?.setScratchSeekModeEnabled(false)
        }
    }

    DisposableEffect(host.scratchSoundController) {
        onDispose {
            host.scratchFlingJob?.cancel()
            host.scratchSoundController.release()
        }
    }

    DisposableEffect(host.popcornSoundController) {
        onDispose {
            host.popcornSoundController.release()
        }
    }

    val keepLyricsScreenOn = host.currentVisualPage == PlaybackVisualPage.Lyrics && host.keepLyricsScreenAwake
    DisposableEffect(host.context, keepLyricsScreenOn) {
        val window = host.context.findActivity()?.window
        if (keepLyricsScreenOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(host.controller, host.state.mediaItem?.mediaId) {
        val playbackController = host.controller ?: return@LaunchedEffect
        while (isActive) {
            host.livePositionMs = playbackController.currentPosition.coerceAtLeast(0L)
            delay(
                if (playbackController.isPlaying) {
                    PlaybackPositionPlayingRefreshMs
                } else {
                    PlaybackPositionIdleRefreshMs
                }
            )
        }
    }

    LaunchedEffect(host.context) {
        while (isActive) {
            delay(PlaybackVolumeRefreshMs)
            val nextVolume = host.context.musicStreamVolumeFraction()
            if (abs(nextVolume - host.latestVolume.value) >= PlaybackVolumeChangeEpsilon) {
                host.volume = nextVolume
                host.state = host.state.copy(volume = nextVolume)
            }
        }
    }

    LaunchedEffect(host.controller, host.playbackSettings.scratchEnabled, host.currentVisualPage) {
        if (!host.playbackSettings.scratchEnabled || host.currentVisualPage != PlaybackVisualPage.Cover) {
            host.resetCoverPageInteraction(resumePlayback = true)
        }
    }

    LaunchedEffect(
        host.playbackSettings.popcornSoundEnabled,
        host.state.isPlaying,
        host.coverPageState.dragMode,
    ) {
        if (
            !host.playbackSettings.popcornSoundEnabled ||
                !host.state.isPlaying ||
                host.coverPageState.dragMode != CoverDragMode.None
        ) {
            host.popcornSoundController.stop()
            return@LaunchedEffect
        }
        try {
            while (isActive) {
                host.popcornSoundController.playRandomPop()
                delay(Random.nextLong(from = 860L, until = 1_640L))
            }
        } finally {
            host.popcornSoundController.stop()
        }
    }
}

@Composable
internal fun PlaybackScreenDerivedValues(host: PlaybackScreenHost) {
    val mediaMetadata = host.state.mediaItem?.mediaMetadata
    val scratchSourceUri = host.state.mediaItem?.localConfiguration?.uri
    val title =
        mediaMetadata?.displayTitle?.toString()
            ?: mediaMetadata?.title?.toString()
            ?: stringResource(R.string.unknown_song_title)
    val artist =
        mediaMetadata?.subtitle?.toString()
            ?: mediaMetadata?.artist?.toString()
            ?: stringResource(R.string.unknown_artist)
    val durationMs = host.state.durationMs.takeIf { it > 0L } ?: mediaMetadata?.durationMs ?: 0L
    val currentMediaItem = host.state.mediaItem
    val currentMediaId = currentMediaItem?.mediaId
    val currentIsExternalAudio = currentMediaItem?.isExternalAudioLaunchItem() == true
    val favoriteEnabled =
        !currentIsExternalAudio && !currentMediaId.isNullOrBlank() && currentMediaId in host.favoriteIds
    val coverPreviewPositionMs = host.coverPageState.previewPositionMs
    val boundedLivePositionMs = host.livePositionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
    val displayPositionMs =
        if (host.currentVisualPage == PlaybackVisualPage.Cover) {
            coverPreviewPositionMs?.coerceIn(0L, durationMs.coerceAtLeast(0L))
                ?: boundedLivePositionMs
        } else {
            boundedLivePositionMs
        }
    val noLyricsLine = stringResource(R.string.playback_more_primary_line)
    val fallbackLyricsLines =
        remember(noLyricsLine) {
            listOf(noLyricsLine)
        }
    val controllerTracks = host.controller?.currentTracks
    val trackLyrics =
        remember(host.state.mediaItem?.mediaId, controllerTracks) {
            controllerTracks?.let(::extractEmbeddedLyrics)
        }
    val embeddedLyrics by
        produceState<EmbeddedLyrics?>(
            initialValue = trackLyrics ?: host.state.mediaItem?.let(NowPlayingLyricsRepository::peek),
            key1 = host.state.mediaItem?.mediaId,
            key2 = host.state.mediaItem?.localConfiguration?.uri,
            key3 = trackLyrics,
        ) {
            val mediaItem = host.state.mediaItem
            value = trackLyrics ?: mediaItem?.let(NowPlayingLyricsRepository::peek)
            if (trackLyrics == null && mediaItem != null) {
                value = NowPlayingLyricsRepository.load(host.context, mediaItem)
            }
        }
    val artworkRequestKey = host.state.mediaItem?.artworkRequestKey()
    val albumArtwork by
        produceState<ImageBitmap?>(
            initialValue = host.state.mediaItem?.let(::peekArtworkThumbnail),
            artworkRequestKey,
        ) {
            val mediaItem = host.state.mediaItem
            if (mediaItem == null) {
                value = null
                return@produceState
            }
            value = peekArtworkThumbnail(mediaItem) ?: value
            value = loadEmbeddedArtwork(host.context, mediaItem)
        }

    host.title = title
    host.artist = artist
    host.durationMs = durationMs
    host.currentMediaItem = currentMediaItem
    host.currentMediaId = currentMediaId
    host.currentIsExternalAudio = currentIsExternalAudio
    host.favoriteEnabled = favoriteEnabled
    host.coverPreviewPositionMs = coverPreviewPositionMs
    host.boundedLivePositionMs = boundedLivePositionMs
    host.displayPositionMs = displayPositionMs
    host.scratchSourceUri = scratchSourceUri
    host.fallbackLyricsLines = fallbackLyricsLines
    host.embeddedLyrics = embeddedLyrics
    host.albumArtwork = albumArtwork
}

@Composable
internal fun PlaybackScreenCoverEffects(host: PlaybackScreenHost) {
    // coverPageState 现在是跨重组的同一份对象，换歌时的复位必须显式做（原先靠 remember(mediaId) 换对象）。
    LaunchedEffect(host.currentMediaId) {
        host.resetCoverPageInteraction(resumePlayback = false)
    }

    LaunchedEffect(
        host.currentVisualPage,
        host.coverPageState.dragMode,
        host.coverPageState.previewPositionMs,
        host.boundedLivePositionMs,
    ) {
        if (host.currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val previewPosition = host.coverPageState.previewPositionMs ?: return@LaunchedEffect
        if (
            host.coverPageState.dragMode == CoverDragMode.None &&
                abs(host.boundedLivePositionMs - previewPosition) <= CoverPreviewSettleToleranceMs
        ) {
            host.coverPageState = host.coverPageState.copy(previewPositionMs = null)
        }
    }

    LaunchedEffect(
        host.currentVisualPage,
        host.coverPageState.dragMode,
        host.coverPageState.previewPositionMs,
    ) {
        if (host.currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val previewPosition = host.coverPageState.previewPositionMs ?: return@LaunchedEffect
        if (host.coverPageState.dragMode != CoverDragMode.None) {
            return@LaunchedEffect
        }
        delay(CoverPreviewTimeoutMs)
        if (
            host.coverPageState.dragMode == CoverDragMode.None &&
                host.coverPageState.previewPositionMs == previewPosition
        ) {
            host.coverPageState = host.coverPageState.copy(previewPositionMs = null)
        }
    }

    LaunchedEffect(
        host.currentVisualPage,
        host.coverPageState.dragMode,
        host.coverPageState.needleSettlingPositionMs,
        host.boundedLivePositionMs,
    ) {
        if (host.currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val settlingPosition = host.coverPageState.needleSettlingPositionMs ?: return@LaunchedEffect
        if (
            host.coverPageState.dragMode == CoverDragMode.None &&
                abs(host.boundedLivePositionMs - settlingPosition) <= CoverPreviewSettleToleranceMs
        ) {
            host.coverPageState =
                host.coverPageState.copy(
                    needlePreviewRotationDegrees = null,
                    needleSettlingPositionMs = null,
                )
        }
    }

    LaunchedEffect(
        host.currentVisualPage,
        host.coverPageState.dragMode,
        host.coverPageState.needleSettlingPositionMs,
    ) {
        if (host.currentVisualPage != PlaybackVisualPage.Cover) {
            return@LaunchedEffect
        }
        val settlingPosition = host.coverPageState.needleSettlingPositionMs ?: return@LaunchedEffect
        if (host.coverPageState.dragMode != CoverDragMode.None) {
            return@LaunchedEffect
        }
        delay(NeedleSeekSettleHoldTimeoutMs)
        if (
            host.coverPageState.dragMode == CoverDragMode.None &&
                host.coverPageState.needleSettlingPositionMs == settlingPosition
        ) {
            host.coverPageState =
                host.coverPageState.copy(
                    needlePreviewRotationDegrees = null,
                    needleSettlingPositionMs = null,
                )
        }
    }

    val latestScratchWarmupPositionMs by rememberUpdatedState(host.boundedLivePositionMs)
    LaunchedEffect(host.scratchSourceUri, host.playbackSettings.scratchEnabled) {
        host.scratchFlingJob?.cancel()
        host.scratchFlingJob = null
        host.scratchSoundController.stop()
        if (host.scratchSourceUri == null || !host.playbackSettings.scratchEnabled) {
            host.scratchSoundController.prepareSource(null, 0L)
            return@LaunchedEffect
        }
        while (isActive) {
            host.scratchSoundController.prepareSource(
                sourceUri = host.scratchSourceUri,
                positionMs = latestScratchWarmupPositionMs,
            )
            delay(ScratchWarmupRefreshMs)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private const val PlaybackPositionPlayingRefreshMs = 250L
private const val PlaybackPositionIdleRefreshMs = 500L
private const val PlaybackVolumeRefreshMs = 200L
private const val PlaybackVolumeChangeEpsilon = 0.001f
