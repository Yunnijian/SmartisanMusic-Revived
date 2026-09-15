package com.smartisan.music.ui.playback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.smartisan.music.R
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.isExternalAudioLaunchItem
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.NowPlayingLyricsRepository
import com.smartisan.music.playback.PlaybackSleepTimer
import com.smartisan.music.playback.PlaybackSleepTimerState
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PlaybackCoverPageState(
    val dragMode: CoverDragMode = CoverDragMode.None,
    val previewPositionMs: Long? = null,
    val resumePlaybackAfterDrag: Boolean = false,
    val needlePreviewRotationDegrees: Float? = null,
    val needleSettlingPositionMs: Long? = null,
    val needleParkedOutside: Boolean = false,
)

/**
 * 播放页状态宿主：承载可变状态（由调用方以 MutableState 注入，保证与重组前同一份状态对象）、
 * 搓碟/拖针的交互函数，以及供布局读取的展示派生值。
 */
internal class PlaybackScreenHost(
    val context: Context,
    val controller: MediaController?,
    val scope: CoroutineScope,
    val onCollapse: () -> Unit,
    val onScratchEnabledChange: (Boolean) -> Unit,
    val onRequestAddToPlaylist: (List<MediaItem>) -> Unit,
    val onRequestAddToQueue: (List<MediaItem>) -> Unit,
    val onFavoriteToggle: ((MediaItem) -> Unit)?,
    val favoriteRepository: FavoriteSongsRepository,
    val playlistRepository: PlaylistRepository,
    val playbackStatsRepository: PlaybackStatsRepository,
    val scratchSoundController: ScratchSoundController,
    val popcornSoundController: VinylPopcornSoundController,
    val entranceTimeMillis: Animatable<Float, AnimationVector1D>,
    val deleteCoordinator: MediaStoreDeleteCoordinator,
    private val favoriteIdsState: State<Set<String>>,
    private val sleepTimerStateState: State<PlaybackSleepTimerState>,
    val playbackSettings: PlaybackSettings,
    val lifecycleOwner: LifecycleOwner,
    val latestVolume: State<Float>,
    val currentOnLibraryChanged: () -> Unit,
    volumeState: MutableFloatState,
    snapshotState: MutableState<PlaybackScreenState>,
    livePositionMsState: MutableLongState,
    showMorePanelState: MutableState<Boolean>,
    showSleepTimerDialogState: MutableState<Boolean>,
    currentVisualPageState: MutableState<PlaybackVisualPage>,
    keepLyricsScreenAwakeState: MutableState<Boolean>,
    sleepTimerWasActiveState: MutableState<Boolean>,
    coverPageStateState: MutableState<PlaybackCoverPageState>,
    scratchFlingJobState: MutableState<Job?>,
    discManualRotationOffsetState: MutableFloatState,
) {
    val favoriteIds: Set<String>
        get() = favoriteIdsState.value
    val sleepTimerState: PlaybackSleepTimerState
        get() = sleepTimerStateState.value

    var volume by volumeState
    var state by snapshotState
    var livePositionMs by livePositionMsState
    var showMorePanel by showMorePanelState
    var showSleepTimerDialog by showSleepTimerDialogState
    var currentVisualPage by currentVisualPageState
    var keepLyricsScreenAwake by keepLyricsScreenAwakeState
    var sleepTimerWasActive by sleepTimerWasActiveState
    var coverPageState by coverPageStateState
    var scratchFlingJob by scratchFlingJobState
    var discManualRotationOffsetDegrees by discManualRotationOffsetState

    var title: String by mutableStateOf("")
    var artist: String by mutableStateOf("")
    var durationMs: Long by mutableStateOf(0L)
    var currentMediaItem: MediaItem? by mutableStateOf(null)
    var currentMediaId: String? by mutableStateOf(null)
    var currentIsExternalAudio: Boolean by mutableStateOf(false)
    var favoriteEnabled: Boolean by mutableStateOf(false)
    var coverPreviewPositionMs: Long? by mutableStateOf(null)
    var boundedLivePositionMs: Long by mutableStateOf(0L)
    var displayPositionMs: Long by mutableStateOf(0L)
    var scratchSourceUri: Uri? by mutableStateOf(null)
    var fallbackLyricsLines: List<String> by mutableStateOf(emptyList())
    var embeddedLyrics: EmbeddedLyrics? by mutableStateOf(null)
    var albumArtwork: ImageBitmap? by mutableStateOf(null)

    fun resetCoverPageInteraction(resumePlayback: Boolean) {
        scratchFlingJob?.cancel()
        scratchFlingJob = null
        val shouldResumePlayback = resumePlayback && coverPageState.resumePlaybackAfterDrag
        coverPageState = PlaybackCoverPageState()
        if (shouldResumePlayback) {
            controller?.play()
        }
        controller?.setScratchSeekModeEnabled(false)
        scratchSoundController.stop()
    }

    fun finishDiscScratch(
        positionMs: Long,
        resumePlaybackAfterDrag: Boolean,
    ) {
        coverPageState =
            coverPageState.copy(
                dragMode = CoverDragMode.None,
                previewPositionMs = positionMs,
                resumePlaybackAfterDrag = false,
                needlePreviewRotationDegrees = null,
                needleSettlingPositionMs = null,
                needleParkedOutside = false,
            )
        controller?.seekTo(positionMs)
        if (resumePlaybackAfterDrag) {
            controller?.play()
        }
        controller?.setScratchSeekModeEnabled(false)
        scratchSoundController.stop()
    }

    fun launchDiscScratchFling(
        startPositionMs: Long,
        initialVelocityDegreesPerSecond: Float,
        resumePlaybackAfterDrag: Boolean,
        durationMs: Long,
    ) {
        scratchFlingJob?.cancel()
        scratchFlingJob = null
        val clampedVelocity =
            initialVelocityDegreesPerSecond.coerceIn(
                -ScratchVelocityMaxDegreesPerSecond,
                ScratchVelocityMaxDegreesPerSecond,
            )
        if (abs(clampedVelocity) < ScratchFlingMinVelocityDegreesPerSecond || durationMs <= 0L) {
            finishDiscScratch(startPositionMs, resumePlaybackAfterDrag)
            return
        }

        val flingDurationMs =
            scratchFlingDurationMs(
                velocityDegreesPerSecond = clampedVelocity,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
            )
        val velocityKeyframes =
            scratchFlingVelocityKeyframes(
                velocityDegreesPerSecond = clampedVelocity,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
            )
        coverPageState =
            coverPageState.copy(
                dragMode = CoverDragMode.DiscScratch,
                previewPositionMs = startPositionMs,
                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                needlePreviewRotationDegrees = null,
                needleSettlingPositionMs = null,
                needleParkedOutside = false,
            )
        scratchFlingJob = scope.launch {
            var positionMs = startPositionMs.coerceIn(0L, durationMs)
            var previousFrameNanos = Long.MIN_VALUE
            var previousVelocity = velocityKeyframes.first()
            var elapsedMs = 0f
            while (isActive && elapsedMs < flingDurationMs) {
                val frameNanos = withFrameNanos { it }
                if (previousFrameNanos == Long.MIN_VALUE) {
                    previousFrameNanos = frameNanos
                    continue
                }
                val frameDeltaMs =
                    ((frameNanos - previousFrameNanos) / 1_000_000f).coerceIn(
                        1f,
                        PlaybackScratchMaxDeltaTimeMs.toFloat(),
                    )
                previousFrameNanos = frameNanos
                elapsedMs = (elapsedMs + frameDeltaMs).coerceAtMost(flingDurationMs.toFloat())

                val currentVelocity =
                    scratchFlingVelocityAt(
                        keyframes = velocityKeyframes,
                        elapsedMs = elapsedMs,
                        durationMs = flingDurationMs,
                    )
                val deltaAngle =
                    ((previousVelocity + currentVelocity) * frameDeltaMs) / ScratchFlingFrameDivisor
                previousVelocity = currentVelocity

                if (abs(deltaAngle) >= PlaybackScratchMinMotionDegrees) {
                    discManualRotationOffsetDegrees += deltaAngle
                    val targetPosition =
                        scratchPositionAfterAngle(
                            positionMs = positionMs,
                            deltaAngleDegrees = deltaAngle,
                            durationMs = durationMs,
                        )
                    scratchSoundController.onScratchMotion(targetPosition, deltaAngle)
                    if (targetPosition != positionMs) {
                        positionMs = targetPosition
                        coverPageState = coverPageState.copy(previewPositionMs = positionMs)
                    }
                }
            }
            finishDiscScratch(positionMs, resumePlaybackAfterDrag)
            scratchFlingJob = null
        }
    }

    fun setVisualPage(targetPage: PlaybackVisualPage) {
        if (currentVisualPage == targetPage) {
            return
        }
        currentVisualPage = targetPage
        if (targetPage != PlaybackVisualPage.Cover) {
            resetCoverPageInteraction(resumePlayback = true)
        }
    }

    fun toggleVisualPage() {
        setVisualPage(
            if (currentVisualPage == PlaybackVisualPage.Cover) {
                PlaybackVisualPage.Lyrics
            } else {
                PlaybackVisualPage.Cover
            }
        )
    }
}

@Composable
internal fun rememberPlaybackScreenHost(
    playbackSettings: PlaybackSettings,
    onScratchEnabledChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    onRequestAddToPlaylist: (List<MediaItem>) -> Unit,
    onRequestAddToQueue: (List<MediaItem>) -> Unit,
    onLibraryChanged: () -> Unit,
    onFavoriteToggle: ((MediaItem) -> Unit)?,
): PlaybackScreenHost {
    val controller = LocalPlaybackController.current
    val context = LocalContext.current
    val favoriteRepository =
        remember(context.applicationContext) {
            FavoriteSongsRepository.getInstance(context.applicationContext)
        }
    val playlistRepository =
        remember(context.applicationContext) {
            PlaylistRepository.getInstance(context.applicationContext)
        }
    val playbackStatsRepository =
        remember(context.applicationContext) {
            PlaybackStatsRepository.getInstance(context.applicationContext)
        }
    val entranceTimeMillis = remember { Animatable(0f) }
    val favoriteIdsState =
        favoriteRepository.observeFavoriteIds().collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLibraryChanged by rememberUpdatedState(onLibraryChanged)
    val scratchSoundController =
        remember(context) {
            ScratchSoundController(context)
        }
    val popcornSoundController =
        remember(context) {
            VinylPopcornSoundController(context)
        }
    val volumeState =
        remember(context) {
            mutableFloatStateOf(context.musicStreamVolumeFraction())
        }
    val snapshotState =
        remember(controller) {
            mutableStateOf(controller.snapshot(volume = volumeState.value))
        }
    val latestVolumeState = rememberUpdatedState(volumeState.value)
    val livePositionMsState =
        remember(controller) {
            mutableLongStateOf(snapshotState.value.currentPositionMs)
        }
    val showMorePanelState = rememberSaveable { mutableStateOf(false) }
    val showSleepTimerDialogState = rememberSaveable { mutableStateOf(false) }
    val currentVisualPageState = rememberSaveable { mutableStateOf(PlaybackVisualPage.Cover) }
    val keepLyricsScreenAwakeState = rememberSaveable { mutableStateOf(false) }
    val sleepTimerWasActiveState = remember { mutableStateOf(false) }
    val coverPageStateState =
        remember(snapshotState.value.mediaItem?.mediaId) {
            mutableStateOf(PlaybackCoverPageState())
        }
    val scratchFlingJobState = remember { mutableStateOf<Job?>(null) }
    val discManualRotationOffsetState = remember { mutableFloatStateOf(0f) }
    val sleepTimerStateState = PlaybackSleepTimer.state.collectAsStateWithLifecycle()

    PlaybackScreenEntranceEffect(entranceTimeMillis)

    val deleteCoordinator =
        rememberPlaybackDeleteCoordinator(
            context = context,
            controller = controller,
            scope = scope,
            favoriteRepository = favoriteRepository,
            playlistRepository = playlistRepository,
            playbackStatsRepository = playbackStatsRepository,
            currentOnLibraryChanged = currentOnLibraryChanged,
        )

    val host =
        PlaybackScreenHost(
            context = context,
            controller = controller,
            scope = scope,
            onCollapse = onCollapse,
            onScratchEnabledChange = onScratchEnabledChange,
            onRequestAddToPlaylist = onRequestAddToPlaylist,
            onRequestAddToQueue = onRequestAddToQueue,
            onFavoriteToggle = onFavoriteToggle,
            favoriteRepository = favoriteRepository,
            playlistRepository = playlistRepository,
            playbackStatsRepository = playbackStatsRepository,
            scratchSoundController = scratchSoundController,
            popcornSoundController = popcornSoundController,
            entranceTimeMillis = entranceTimeMillis,
            deleteCoordinator = deleteCoordinator,
            favoriteIdsState = favoriteIdsState,
            sleepTimerStateState = sleepTimerStateState,
            playbackSettings = playbackSettings,
            lifecycleOwner = lifecycleOwner,
            latestVolume = latestVolumeState,
            currentOnLibraryChanged = currentOnLibraryChanged,
            volumeState = volumeState,
            snapshotState = snapshotState,
            livePositionMsState = livePositionMsState,
            showMorePanelState = showMorePanelState,
            showSleepTimerDialogState = showSleepTimerDialogState,
            currentVisualPageState = currentVisualPageState,
            keepLyricsScreenAwakeState = keepLyricsScreenAwakeState,
            sleepTimerWasActiveState = sleepTimerWasActiveState,
            coverPageStateState = coverPageStateState,
            scratchFlingJobState = scratchFlingJobState,
            discManualRotationOffsetState = discManualRotationOffsetState,
        )

    PlaybackScreenSessionEffects(host)
    PlaybackScreenPlaybackEffects(host)
    PlaybackScreenDerivedValues(host)
    PlaybackScreenCoverEffects(host)
    return host
}

@Composable
private fun PlaybackScreenEntranceEffect(entranceTimeMillis: Animatable<Float, AnimationVector1D>) {
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
private fun rememberPlaybackDeleteCoordinator(
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
private fun PlaybackScreenSessionEffects(host: PlaybackScreenHost) {
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
private fun PlaybackScreenPlaybackEffects(host: PlaybackScreenHost) {
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
private fun PlaybackScreenDerivedValues(host: PlaybackScreenHost) {
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
private fun PlaybackScreenCoverEffects(host: PlaybackScreenHost) {
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
