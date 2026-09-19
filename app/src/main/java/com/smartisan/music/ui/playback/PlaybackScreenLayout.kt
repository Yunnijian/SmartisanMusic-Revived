package com.smartisan.music.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.smartisan.music.LocalMusicAppContainer
import com.smartisan.music.R
import com.smartisan.music.data.online.NeteaseSourceId
import com.smartisan.music.data.online.onlineIdentityOrNull
import com.smartisan.music.data.settings.PlaybackSettings
import com.smartisan.music.playback.cancelSleepTimer
import com.smartisan.music.playback.setScratchSeekModeEnabled
import com.smartisan.music.playback.startSleepTimer
import com.smartisan.music.ui.components.MediaStoreDeleteItem
import com.smartisan.music.ui.components.SmartisanTouchShield
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 播放页布局：顶部栏、时间轴、视觉舞台、底部控制与更多操作覆盖层。 */
@Composable
internal fun PlaybackScreenLayout(
    host: PlaybackScreenHost,
    playbackSettings: PlaybackSettings,
    showTopBar: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().background(PlaybackPageBackground)) {
        val density = LocalDensity.current
        val screenHeightPx =
            with(density) {
                maxHeight.roundToPx().toFloat()
            }
        val topInset =
            with(density) {
                WindowInsets.safeDrawing.getTop(this).toDp()
            }
        val bottomInset =
            with(density) {
                WindowInsets.safeDrawing.getBottom(this).toDp()
            }
        var turntableWidth by
            remember(maxWidth, maxHeight) {
                mutableStateOf<Dp?>(null)
            }
        val bottomControlsMinimumWidth = PlaybackBottomControlsMinimumWidth.coerceAtMost(maxWidth)
        val bottomControlsWidth =
            turntableWidth?.coerceIn(bottomControlsMinimumWidth, maxWidth) ?: maxWidth

        SmartisanTouchShield()
        Column(modifier = Modifier.fillMaxSize()) {
            PlaybackScreenHeaderSection(
                host = host,
                showTopBar = showTopBar,
                topInset = topInset,
            )
            PlaybackScreenStageSection(
                host = host,
                playbackSettings = playbackSettings,
                screenHeightPx = screenHeightPx,
                onTurntableWidthChanged = { resolvedWidth -> turntableWidth = resolvedWidth },
            )
            PlaybackScreenBottomControlsSection(
                host = host,
                width = bottomControlsWidth,
                bottomInset = bottomInset,
            )
        }

        PlaybackScreenOverlaysSection(
            host = host,
            playbackSettings = playbackSettings,
            bottomInsetPx = (bottomInset.value * density.density).roundToInt(),
        )
    }
}

@Composable
private fun PlaybackScreenOverlaysSection(
    host: PlaybackScreenHost,
    playbackSettings: PlaybackSettings,
    bottomInsetPx: Int,
) {
        val listenTogetherStore = LocalMusicAppContainer.current.listenTogetherStore
        PlaybackMoreActionOverlays(
            showMorePanel = host.showMorePanel,
            favoriteEnabled = host.favoriteEnabled,
            currentVisualPage = host.currentVisualPage,
            scratchEnabled = playbackSettings.scratchEnabled,
            sleepTimerActive = host.sleepTimerState.isActive,
            addToPlaylistEnabled = !host.currentIsExternalAudio,
            shareEnabled = host.currentMediaItem != null,
            showShareOptionsPanel = host.showShareOptionsPanel,
            listenTogetherEnabled =
                host.currentMediaItem?.onlineIdentityOrNull()?.source == NeteaseSourceId,
            showSleepTimerDialog = host.showSleepTimerDialog,
            sleepTimerState = host.sleepTimerState,
            bottomInsetPx = bottomInsetPx,
            onAddToPlaylistClick = {
                host.state.mediaItem?.let { host.onRequestAddToPlaylist(listOf(it)) }
                host.showMorePanel = false
            },
            onAddToQueueClick = {
                host.state.mediaItem?.let { host.onRequestAddToQueue(listOf(it)) }
                host.showMorePanel = false
            },
            onFavoriteToggle = {
                val currentItem = host.state.mediaItem
                val mediaId = host.currentMediaId
                if (currentItem != null && !mediaId.isNullOrBlank() && !host.currentIsExternalAudio) {
                    if (host.onFavoriteToggle != null) {
                        host.onFavoriteToggle(currentItem)
                    } else {
                        host.scope.launch {
                            host.favoriteRepository.toggle(mediaId)
                        }
                    }
                }
                host.showMorePanel = false
            },
            onShareClick = {
                host.showMorePanel = false
                host.showShareOptionsPanel = true
            },
            onShareSongClick = {
                host.showShareOptionsPanel = false
                val mediaItem = host.state.mediaItem
                if (mediaItem != null) {
                    val shared =
                        if (mediaItem.canShareAudio()) {
                            host.context.tryShareAudio(mediaItem)
                        } else {
                            host.context.tryShareOnlineSong(mediaItem)
                        }
                    if (!shared) {
                        host.context.toast(R.string.can_not_share_song)
                    }
                }
            },
            onListenTogetherClick = {
                host.showShareOptionsPanel = false
                host.scope.launch {
                    val inviteUrl = listenTogetherStore.createRoomInviteUrl()
                    if (inviteUrl != null) {
                        host.context.tryShareText(inviteUrl)
                    }
                }
            },
            onSleepTimerClick = {
                host.showMorePanel = false
                host.showSleepTimerDialog = true
            },
            onLyricsToggle = {
                host.toggleVisualPage()
                host.showMorePanel = false
            },
            onScratchToggle = {
                host.onScratchEnabledChange(!playbackSettings.scratchEnabled)
                host.showMorePanel = false
            },
            onDeleteClick = {
                when (
                    val result =
                        host.state.mediaItem?.resolveDeleteTarget()
                            ?: PlaybackDeleteTargetResult.Unavailable
                ) {
                    is PlaybackDeleteTargetResult.Available -> {
                        host.deleteCoordinator.delete(
                            listOf(
                                MediaStoreDeleteItem(
                                    mediaId = result.target.mediaId,
                                    uri = result.target.uri,
                                )
                            )
                        )
                    }
                    PlaybackDeleteTargetResult.CueFile -> {
                        host.context.toast(R.string.can_not_delete_cue_file)
                    }
                    PlaybackDeleteTargetResult.Unavailable -> {
                        host.context.toast(R.string.can_not_delete_song)
                    }
                }
                host.showMorePanel = false
            },
            onDismissMorePanel = {
                host.showMorePanel = false
            },
            onDismissShareOptions = {
                host.showShareOptionsPanel = false
            },
            onSleepTimerDismiss = {
                host.showSleepTimerDialog = false
            },
            onSleepTimerDurationSelected = { selectedDurationMs ->
                host.showSleepTimerDialog = false
                if (selectedDurationMs > 0L) {
                    host.controller?.startSleepTimer(selectedDurationMs)
                } else {
                    host.controller?.cancelSleepTimer()
                    if (host.sleepTimerState.isActive) {
                        host.context.toast(R.string.sleep_timer_stopped)
                    }
                }
            },
        )
}

@Composable
private fun PlaybackScreenHeaderSection(
    host: PlaybackScreenHost,
    showTopBar: Boolean,
    topInset: Dp,
) {
            if (showTopBar) {
                PlaybackTopBar(
                    title = host.title,
                    artist = host.artist,
                    topInset = topInset,
                    onCollapse = host.onCollapse,
                )
            }
            PlaybackTimeSeekBar(
                durationMs = host.durationMs,
                currentPositionMs = host.displayPositionMs,
                thumbRes = R.drawable.playing_control_time,
                modifier = Modifier.fillMaxWidth(),
                onSeek = { positionMs ->
                    host.controller?.seekTo(positionMs)
                },
            )
}

@Composable
private fun PlaybackScreenBottomControlsSection(
    host: PlaybackScreenHost,
    width: Dp,
    bottomInset: Dp,
) {
            PlaybackBottomControls(
                width = width,
                bottomInset = bottomInset,
                state = host.state.copy(volume = host.volume),
                // 与 PlaybackVisualStage 的入场位移一致：进度只在绘制阶段读，
                // 组合期读动画值会让入场期间整个播放页每帧重组。
                entranceTimeMillis = { host.entranceTimeMillis.value },
                onRepeatClick = {
                    val nextRepeatMode = nextPlaybackRepeatMode(host.state.repeatMode)
                    host.controller?.repeatMode = nextRepeatMode
                    host.state = host.state.copy(repeatMode = nextRepeatMode)
                    host.context.toast(repeatToastRes(nextRepeatMode))
                },
                onPreviousClick = {
                    host.controller?.seekToPrevious()
                },
                onPlayPauseClick = {
                    if (host.state.isPlaybackActive) {
                        host.controller?.pause()
                    } else {
                        host.controller?.play()
                    }
                },
                onNextClick = {
                    host.controller?.seekToNext()
                },
                onShuffleClick = {
                    val shuffleEnabled = !host.state.shuffleEnabled
                    host.controller?.shuffleModeEnabled = shuffleEnabled
                    host.state = host.state.copy(shuffleEnabled = shuffleEnabled)
                    host.context.toast(shuffleToastRes(shuffleEnabled))
                },
                onVolumeChange = { targetVolume ->
                    host.context.setMusicStreamVolumeFraction(targetVolume)
                    val actualVolume = host.context.musicStreamVolumeFraction()
                    host.volume = actualVolume
                    host.state = host.state.copy(volume = actualVolume)
                },
            )
}

@Composable
private fun ColumnScope.PlaybackScreenStageSection(
    host: PlaybackScreenHost,
    playbackSettings: PlaybackSettings,
    screenHeightPx: Float,
    onTurntableWidthChanged: (Dp) -> Unit,
) {
    val interactions = remember(host) { PlaybackStageInteractions(host) }
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .weight(1f)
                        .padding(top = PlaybackVisualStageTopPadding)
                        // 入场进度只在绘制阶段读：组合期读动画值会让入场动画每帧重组整个播放页。
                        .graphicsLayer {
                            val entranceProgress =
                                playbackEntranceProgress(
                                    timeMillis = host.entranceTimeMillis.value,
                                    delayMillis = 0,
                                    durationMillis = PlaybackTurntableEntranceDurationMillis,
                                )
                            translationY = (1f - entranceProgress) * screenHeightPx
                        },
                contentAlignment = Alignment.TopCenter,
            ) {
                PlaybackVisualStage(
                    modifier = Modifier.fillMaxSize(),
                    currentVisualPage = host.currentVisualPage,
                    coverPositionMs = host.displayPositionMs,
                    lyricsPositionMs = host.boundedLivePositionMs,
                    durationMs = host.durationMs,
                    scratchEnabled = playbackSettings.scratchEnabled,
                    hidePlayerAxisEnabled = playbackSettings.hidePlayerAxisEnabled,
                    albumArtwork = host.albumArtwork,
                    keepLyricsScreenAwake = host.keepLyricsScreenAwake,
                    embeddedLyrics = host.embeddedLyrics,
                    fallbackLyricsLines = host.fallbackLyricsLines,
                    hasMediaItem = host.state.mediaItem != null,
                    isPlaying = host.state.isPlaybackActive,
                    coverDragMode = host.coverPageState.dragMode,
                    previewPositionMs = host.coverPageState.previewPositionMs,
                    needlePreviewRotationDegrees = host.coverPageState.needlePreviewRotationDegrees,
                    needleParkedOutside = host.coverPageState.needleParkedOutside,
                    discManualRotationOffsetDegrees = host.discManualRotationOffsetDegrees,
                    mediaId = host.state.mediaItem?.mediaId,
                    onVisualPageToggle = host::toggleVisualPage,
                    onTurntableWidthChanged = onTurntableWidthChanged,
                    onMoreClick = interactions.onMoreClick,
                    onKeepLyricsScreenAwakeToggle = interactions.onKeepLyricsScreenAwakeToggle,
                    onDiscScratchStart = interactions.onDiscScratchStart,
                    onDiscScratchMotion = interactions.onDiscScratchMotion,
                    onDiscScratchPositionChange = interactions.onDiscScratchPositionChange,
                    onDiscScratchEnd = interactions.onDiscScratchEnd,
                    onDiscScratchCancel = interactions.onDiscScratchCancel,
                    onNeedleSeekStart = interactions.onNeedleSeekStart,
                    onNeedleSeekPositionChange = interactions.onNeedleSeekPositionChange,
                    onNeedleSeekEnd = interactions.onNeedleSeekEnd,
                    onNeedleSeekCancel = interactions.onNeedleSeekCancel,
                )
            }
}

/**
 * 视觉舞台的手势回调：原内联在 PlaybackVisualStage 调用处的 lambda 逐字搬入，
 * 使舞台 composable 保持精简；状态读写仍走宿主。
 */
internal class PlaybackStageInteractions(
    private val host: PlaybackScreenHost,
) {
    val onMoreClick: () -> Unit = {
                        host.showMorePanel = true
                    }

    val onKeepLyricsScreenAwakeToggle: () -> Unit = {
                        val enabled = !host.keepLyricsScreenAwake
                        host.keepLyricsScreenAwake = enabled
                        host.context.toast(
                            if (enabled) {
                                R.string.screen_light_on
                            } else {
                                R.string.screen_light_off
                            }
                        )
                    }

    val onDiscScratchStart: () -> Unit = {
                        host.scratchFlingJob?.cancel()
                        host.scratchFlingJob = null
                        val resumePlaybackAfterDrag =
                            host.state.isPlaybackActive || host.coverPageState.resumePlaybackAfterDrag
                        host.coverPageState =
                            host.coverPageState.copy(
                                dragMode = CoverDragMode.DiscScratch,
                                previewPositionMs = host.boundedLivePositionMs,
                                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                                needlePreviewRotationDegrees = null,
                                needleSettlingPositionMs = null,
                                needleParkedOutside = false,
                            )
                        if (resumePlaybackAfterDrag) {
                            host.controller?.pause()
                        }
                        host.controller?.setScratchSeekModeEnabled(true)
                        host.scratchSoundController.onScratchStart(
                            sourceUri = host.scratchSourceUri,
                            positionMs = host.boundedLivePositionMs,
                        )
                    }

    val onDiscScratchMotion: (Long, Float) -> Unit = { positionMs, deltaAngle ->
                        host.discManualRotationOffsetDegrees += deltaAngle
                        host.scratchSoundController.onScratchMotion(positionMs, deltaAngle)
                    }

    val onDiscScratchPositionChange: (Long, Float) -> Unit = { positionMs, _ ->
                        host.coverPageState = host.coverPageState.copy(previewPositionMs = positionMs)
                    }

    val onDiscScratchEnd: (Long, Float) -> Unit = { positionMs, flingVelocityDegreesPerSecond ->
                        val resumePlaybackAfterDrag = host.coverPageState.resumePlaybackAfterDrag
                        host.launchDiscScratchFling(
                            startPositionMs = positionMs,
                            initialVelocityDegreesPerSecond = flingVelocityDegreesPerSecond,
                            resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                            durationMs = host.durationMs,
                        )
                    }

    val onDiscScratchCancel: () -> Unit = {
                        host.resetCoverPageInteraction(resumePlayback = true)
                    }

    val onNeedleSeekStart: (Float, Long?) -> Unit = { rotationDegrees, positionMs ->
                        PlaybackHaptics.vibrateEffect(host.context)
                        val resumePlaybackAfterDrag = host.state.isPlaybackActive
                        host.coverPageState =
                            host.coverPageState.copy(
                                dragMode = CoverDragMode.NeedleSeek,
                                previewPositionMs = positionMs ?: 0L,
                                resumePlaybackAfterDrag = resumePlaybackAfterDrag,
                                needlePreviewRotationDegrees = rotationDegrees,
                                needleSettlingPositionMs = null,
                                needleParkedOutside = false,
                            )
                        if (resumePlaybackAfterDrag) {
                            host.controller?.pause()
                            host.state =
                                host.state.copy(
                                    isPlaying = false,
                                    playWhenReady = false,
                                    isBuffering = false,
                                )
                        }
                        host.controller?.setScratchSeekModeEnabled(true)
                    }

    val onNeedleSeekPositionChange: (Float, Long?) -> Unit = { rotationDegrees, positionMs ->
                        host.coverPageState =
                            host.coverPageState.copy(
                                previewPositionMs = positionMs ?: 0L,
                                needlePreviewRotationDegrees = rotationDegrees,
                                needleSettlingPositionMs = null,
                            )
                    }

    val onNeedleSeekEnd: (Float, Long?) -> Unit = { rotationDegrees, positionMs ->
                        PlaybackHaptics.vibrateEffect(host.context)
                        val resumePlaybackAfterDrag = host.coverPageState.resumePlaybackAfterDrag
                        if (positionMs == null) {
                            host.coverPageState =
                                host.coverPageState.copy(
                                    dragMode = CoverDragMode.None,
                                    previewPositionMs = 0L,
                                    resumePlaybackAfterDrag = false,
                                    needlePreviewRotationDegrees = null,
                                    needleSettlingPositionMs = null,
                                    needleParkedOutside = true,
                                )
                            host.controller?.seekTo(0L)
                            host.controller?.pause()
                            host.livePositionMs = 0L
                            host.state =
                                host.state.copy(
                                    isPlaying = false,
                                    playWhenReady = false,
                                    isBuffering = false,
                                    currentPositionMs = 0L,
                                )
                        } else {
                            host.coverPageState =
                                host.coverPageState.copy(
                                    dragMode = CoverDragMode.None,
                                    previewPositionMs = positionMs,
                                    resumePlaybackAfterDrag = false,
                                    needlePreviewRotationDegrees = rotationDegrees,
                                    needleSettlingPositionMs = positionMs,
                                    needleParkedOutside = false,
                                )
                            host.controller?.seekTo(positionMs)
                            host.livePositionMs = positionMs
                            host.state =
                                host.state.copy(
                                    isPlaying = resumePlaybackAfterDrag,
                                    playWhenReady = resumePlaybackAfterDrag,
                                    isBuffering = false,
                                    currentPositionMs = positionMs,
                                )
                            if (resumePlaybackAfterDrag) {
                                host.controller?.play()
                            }
                        }
                        host.controller?.setScratchSeekModeEnabled(false)
                        host.scratchSoundController.stop()
                    }

    val onNeedleSeekCancel: () -> Unit = {
                        PlaybackHaptics.vibrateEffect(host.context)
                        host.resetCoverPageInteraction(resumePlayback = true)
                    }
}
