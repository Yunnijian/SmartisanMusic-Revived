package com.smartisan.music.ui.playback

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.smartisan.music.data.favorite.FavoriteSongsRepository
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.playlist.PlaylistRepository
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.playback.LocalPlaybackController
import com.smartisan.music.playback.PlaybackSleepTimer
import com.smartisan.music.playback.PlaybackSleepTimerState
import com.smartisan.music.playback.setScratchSeekModeEnabled
import com.smartisan.music.ui.components.MediaStoreDeleteCoordinator
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
    val onDismissQueue: () -> Unit,
    private val queueVisibleState: State<Boolean>,
    volumeState: MutableFloatState,
    snapshotState: MutableState<PlaybackScreenState>,
    livePositionMsState: MutableLongState,
    showMorePanelState: MutableState<Boolean>,
    showSleepTimerDialogState: MutableState<Boolean>,
    showShareOptionsPanelState: MutableState<Boolean>,
    currentVisualPageState: MutableState<PlaybackVisualPage>,
    keepLyricsScreenAwakeState: MutableState<Boolean>,
    sleepTimerWasActiveState: MutableState<Boolean>,
    coverPageStateState: MutableState<PlaybackCoverPageState>,
    scratchFlingJobState: MutableState<Job?>,
    discManualRotationOffsetState: MutableFloatState,
    titleState: MutableState<String>,
    artistState: MutableState<String>,
    durationMsState: MutableLongState,
    currentMediaItemState: MutableState<MediaItem?>,
    currentMediaIdState: MutableState<String?>,
    currentIsExternalAudioState: MutableState<Boolean>,
    favoriteEnabledState: MutableState<Boolean>,
    coverPreviewPositionMsState: MutableState<Long?>,
    boundedLivePositionMsState: MutableLongState,
    displayPositionMsState: MutableLongState,
    scratchSourceUriState: MutableState<Uri?>,
    fallbackLyricsLinesState: MutableState<List<String>>,
    embeddedLyricsState: MutableState<EmbeddedLyrics?>,
    albumArtworkState: MutableState<ImageBitmap?>,
) {
    val favoriteIds: Set<String>
        get() = favoriteIdsState.value
    val sleepTimerState: PlaybackSleepTimerState
        get() = sleepTimerStateState.value

    /** 播放队列整页是否展开：返回仲裁要读当前值，所以按 State 保存而不是快照值。 */
    val queueVisible: Boolean
        get() = queueVisibleState.value

    var volume by volumeState
    var state by snapshotState
    var livePositionMs by livePositionMsState
    var showMorePanel by showMorePanelState
    var showSleepTimerDialog by showSleepTimerDialogState
    var showShareOptionsPanel by showShareOptionsPanelState
    var currentVisualPage by currentVisualPageState
    var keepLyricsScreenAwake by keepLyricsScreenAwakeState
    var sleepTimerWasActive by sleepTimerWasActiveState
    var coverPageState by coverPageStateState
    var scratchFlingJob by scratchFlingJobState
    var discManualRotationOffsetDegrees by discManualRotationOffsetState

    var title: String by titleState
    var artist: String by artistState
    var durationMs: Long by durationMsState
    var currentMediaItem: MediaItem? by currentMediaItemState
    var currentMediaId: String? by currentMediaIdState
    var currentIsExternalAudio: Boolean by currentIsExternalAudioState
    var favoriteEnabled: Boolean by favoriteEnabledState
    var coverPreviewPositionMs: Long? by coverPreviewPositionMsState
    var boundedLivePositionMs: Long by boundedLivePositionMsState
    var displayPositionMs: Long by displayPositionMsState
    var scratchSourceUri: Uri? by scratchSourceUriState
    var fallbackLyricsLines: List<String> by fallbackLyricsLinesState
    var embeddedLyrics: EmbeddedLyrics? by embeddedLyricsState
    var albumArtwork: ImageBitmap? by albumArtworkState

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
    queueVisible: Boolean,
    onDismissQueue: () -> Unit,
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
    // 队列可见性与关闭动作都由 PlaybackPage 持有，这里按 State 保存最新值供返回仲裁读取。
    val queueVisibleState = rememberUpdatedState(queueVisible)
    val currentOnDismissQueue by rememberUpdatedState(onDismissQueue)
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
    val showShareOptionsPanelState = rememberSaveable { mutableStateOf(false) }
    val currentVisualPageState = rememberSaveable { mutableStateOf(PlaybackVisualPage.Cover) }
    val keepLyricsScreenAwakeState = rememberSaveable { mutableStateOf(false) }
    val sleepTimerWasActiveState = remember { mutableStateOf(false) }
    val coverPageStateState =
        remember {
            // 键里不能放 mediaId：换歌会换一份状态对象，而手势层（pointerInput 的长生命周期闭包）
            // 仍持有旧对象，于是搓碟/拖针写进上一首的状态、进度条读不到 —— 表现为「换首之后进度条不跟手」。
            // 换歌的重置改由 PlaybackScreenCoverEffects 的 effect 显式做。
            mutableStateOf(PlaybackCoverPageState())
        }
    val scratchFlingJobState = remember { mutableStateOf<Job?>(null) }
    val discManualRotationOffsetState = remember { mutableFloatStateOf(0f) }
    val titleState = remember { mutableStateOf("") }
    val artistState = remember { mutableStateOf("") }
    val durationMsState = remember { mutableLongStateOf(0L) }
    val currentMediaItemState = remember { mutableStateOf<MediaItem?>(null) }
    val currentMediaIdState = remember { mutableStateOf<String?>(null) }
    val currentIsExternalAudioState = remember { mutableStateOf(false) }
    val favoriteEnabledState = remember { mutableStateOf(false) }
    val coverPreviewPositionMsState = remember { mutableStateOf<Long?>(null) }
    val boundedLivePositionMsState = remember { mutableLongStateOf(0L) }
    val displayPositionMsState = remember { mutableLongStateOf(0L) }
    val scratchSourceUriState = remember { mutableStateOf<Uri?>(null) }
    val fallbackLyricsLinesState = remember { mutableStateOf<List<String>>(emptyList()) }
    val embeddedLyricsState = remember { mutableStateOf<EmbeddedLyrics?>(null) }
    val albumArtworkState = remember { mutableStateOf<ImageBitmap?>(null) }
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
            onDismissQueue = { currentOnDismissQueue() },
            queueVisibleState = queueVisibleState,
            volumeState = volumeState,
            snapshotState = snapshotState,
            livePositionMsState = livePositionMsState,
            showMorePanelState = showMorePanelState,
            showSleepTimerDialogState = showSleepTimerDialogState,
            showShareOptionsPanelState = showShareOptionsPanelState,
            currentVisualPageState = currentVisualPageState,
            keepLyricsScreenAwakeState = keepLyricsScreenAwakeState,
            sleepTimerWasActiveState = sleepTimerWasActiveState,
            coverPageStateState = coverPageStateState,
            scratchFlingJobState = scratchFlingJobState,
            discManualRotationOffsetState = discManualRotationOffsetState,
            titleState = titleState,
            artistState = artistState,
            durationMsState = durationMsState,
            currentMediaItemState = currentMediaItemState,
            currentMediaIdState = currentMediaIdState,
            currentIsExternalAudioState = currentIsExternalAudioState,
            favoriteEnabledState = favoriteEnabledState,
            coverPreviewPositionMsState = coverPreviewPositionMsState,
            boundedLivePositionMsState = boundedLivePositionMsState,
            displayPositionMsState = displayPositionMsState,
            scratchSourceUriState = scratchSourceUriState,
            fallbackLyricsLinesState = fallbackLyricsLinesState,
            embeddedLyricsState = embeddedLyricsState,
            albumArtworkState = albumArtworkState,
        )

    PlaybackScreenSessionEffects(host)
    PlaybackScreenPlaybackEffects(host)
    PlaybackScreenDerivedValues(host)
    PlaybackScreenCoverEffects(host)
    return host
}
