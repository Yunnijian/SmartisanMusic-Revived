package com.smartisan.music.ui.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.smartisan.music.R
import com.smartisan.music.data.settings.TurntableStyle
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.ui.listentogether.ListenTogetherStatusOverlay

@Composable
internal fun PlaybackVisualStage(
    currentVisualPage: PlaybackVisualPage,
    turntableStyleSpec: TurntableStyleSpec,
    coverPositionMs: Long,
    lyricsPositionMs: Long,
    durationMs: Long,
    scratchEnabled: Boolean,
    hidePlayerAxisEnabled: Boolean,
    albumArtwork: ImageBitmap?,
    keepLyricsScreenAwake: Boolean,
    embeddedLyrics: EmbeddedLyrics?,
    fallbackLyricsLines: List<String>,
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    coverDragMode: CoverDragMode,
    previewPositionMs: Long?,
    needlePreviewRotationDegrees: Float?,
    needleParkedOutside: Boolean,
    discManualRotationOffsetDegrees: Float,
    mediaId: String?,
    onMoreClick: () -> Unit,
    onVisualPageToggle: () -> Unit,
    onKeepLyricsScreenAwakeToggle: () -> Unit,
    onDiscScratchStart: () -> Unit,
    onDiscScratchMotion: (Long, Float) -> Unit,
    onDiscScratchPositionChange: (Long, Float) -> Unit,
    onDiscScratchEnd: (Long, Float) -> Unit,
    onDiscScratchCancel: () -> Unit,
    onNeedleSeekStart: (Float, Long?) -> Unit,
    onNeedleSeekPositionChange: (Float, Long?) -> Unit,
    onNeedleSeekEnd: (Float, Long?) -> Unit,
    onNeedleSeekCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onTurntableWidthChanged: (Dp) -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val turntableWidth = playbackVisualStageWidth(maxWidth, maxHeight)
        if (turntableWidth <= 0.dp) {
            return@BoxWithConstraints
        }
        LaunchedEffect(turntableWidth) {
            onTurntableWidthChanged(turntableWidth)
        }
        val scale = turntableWidth.value / OriginalTurntableBaseWidthDp
        // Netease 唱片更大更靠下，页面区（AnimatedContent 的边界）必须相应加高，
        // 否则碟底超出边界会被裁成一条水平直线（切页时尤其明显）。
        val turntableHeightRatio =
            if (turntableStyleSpec.style == TurntableStyle.Netease) {
                NeteaseTurntableHeightToWidthRatio
            } else {
                PlaybackTurntableHeightToWidthRatio
            }
        val turntableHeight = turntableWidth * turntableHeightRatio
        val moreButtonMargin = 12.dp * scale
        val moreButtonTopMargin = 38.dp * scale
        val actionButtonSize = PlaybackActionButtonSize * scale
        val isLyricsPage = currentVisualPage == PlaybackVisualPage.Lyrics
        // 封面页切走/切回时的进场与出场；与封面同一转场参数，徽标才能跟碟片同步。
        val coverEnterSpec =
            tween<Float>(
                durationMillis = PlaybackVisualPageEnterDurationMillis,
                delayMillis = PlaybackVisualPageEnterDelayMillis,
                easing = PlaybackControlEasing,
            )
        val coverExitSpec =
            tween<Float>(
                durationMillis = PlaybackVisualPageExitDurationMillis,
                easing = PlaybackControlEasing,
            )

        Box(
            modifier =
                Modifier.width(turntableWidth)
                    .height(turntableWidth * PlaybackVisualStageHeightToWidthRatio),
        ) {
            PlaybackStageTopActions(
                isLyricsPage = isLyricsPage,
                moreButtonMargin = moreButtonMargin,
                moreButtonTopMargin = moreButtonTopMargin,
                actionButtonSize = actionButtonSize,
                keepLyricsScreenAwake = keepLyricsScreenAwake,
                onMoreClick = onMoreClick,
                onKeepLyricsScreenAwakeToggle = onKeepLyricsScreenAwakeToggle,
            )
            // 徽标属于封面页：切到歌词页要随碟片一起淡出并略微放大，切回来再复原。
            // 抬 zIndex：耳机弧线上端会压在碟片下缘，必须画在转盘之后。
            AnimatedVisibility(
                visible = !isLyricsPage,
                enter = fadeIn(coverEnterSpec) + scaleIn(initialScale = 1.015f, animationSpec = coverEnterSpec),
                exit = fadeOut(coverExitSpec) + scaleOut(targetScale = 1.015f, animationSpec = coverExitSpec),
                modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
            ) {
                ListenTogetherStatusOverlay()
            }
            Box(
                modifier =
                    Modifier.align(Alignment.TopCenter)
                        .width(turntableWidth)
                        .height(turntableHeight),
                contentAlignment = Alignment.Center,
            ) {
                PlaybackStagePages(
                    currentVisualPage = currentVisualPage,
                    turntableStyleSpec = turntableStyleSpec,
                    turntableWidth = turntableWidth,
                    scale = scale,
                    coverPositionMs = coverPositionMs,
                    durationMs = durationMs,
                    scratchEnabled = scratchEnabled,
                    hidePlayerAxisEnabled = hidePlayerAxisEnabled,
                    albumArtwork = albumArtwork,
                    hasMediaItem = hasMediaItem,
                    isPlaying = isPlaying,
                    coverDragMode = coverDragMode,
                    previewPositionMs = previewPositionMs,
                    needlePreviewRotationDegrees = needlePreviewRotationDegrees,
                    needleParkedOutside = needleParkedOutside,
                    discManualRotationOffsetDegrees = discManualRotationOffsetDegrees,
                    mediaId = mediaId,
                    embeddedLyrics = embeddedLyrics,
                    fallbackLyricsLines = fallbackLyricsLines,
                    lyricsPositionMs = lyricsPositionMs,
                    onVisualPageToggle = onVisualPageToggle,
                    onDiscScratchStart = onDiscScratchStart,
                    onDiscScratchMotion = onDiscScratchMotion,
                    onDiscScratchPositionChange = onDiscScratchPositionChange,
                    onDiscScratchEnd = onDiscScratchEnd,
                    onDiscScratchCancel = onDiscScratchCancel,
                    onNeedleSeekStart = onNeedleSeekStart,
                    onNeedleSeekPositionChange = onNeedleSeekPositionChange,
                    onNeedleSeekEnd = onNeedleSeekEnd,
                    onNeedleSeekCancel = onNeedleSeekCancel,
                )
            }
        }
    }
}

private fun playbackVisualStageWidth(maxWidth: Dp, maxHeight: Dp): Dp {
    val heightBoundWidth = maxHeight.value / PlaybackVisualStageHeightToWidthRatio
    val width =
        minOf(maxWidth.value, heightBoundWidth).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    return width.dp
}

internal const val PlaybackTurntableHeightToWidthRatio = 356.5938f / OriginalTurntableBaseWidthDp
/**
 * Netease 页面区高度比。原版页面区高 0.9905 转盘宽，而 Netease 唱片底边在
 * 0.6286×0.9905 + 0.78/2 ≈ 1.0126 转盘宽、外圈光晕底边更到约 1.0673 转盘宽处，
 * 都超出原页面区，被 AnimatedContent 裁成一条水平直线（切页时一闪而过）。
 * 转场时页面内容还叠加 1.015 倍缩放（底边外扩 0.008）与全高 1/36 的竖直位移
 * （约 0.033），最坏情况 1.0673×1.015 + 0.033 ≈ 1.117，与 1.12 贴边仍有残余裁切，
 * 故留足余量取 1.18。
 */
internal const val NeteaseTurntableHeightToWidthRatio = 1.18f
private const val PlaybackVisualStageHeightToWidthRatio =
    (356.5938f + 52f) / OriginalTurntableBaseWidthDp

/** 视觉舞台顶部的「更多」与常亮开关按钮；内容随是否歌词页显隐，位置按转盘缩放联动。 */
@Composable
private fun BoxScope.PlaybackStageTopActions(
    isLyricsPage: Boolean,
    moreButtonMargin: Dp,
    moreButtonTopMargin: Dp,
    actionButtonSize: Dp,
    keepLyricsScreenAwake: Boolean,
    onMoreClick: () -> Unit,
    onKeepLyricsScreenAwakeToggle: () -> Unit,
) {
    PressedDrawableButton(
        normalRes = R.drawable.more_btn,
        pressedRes = R.drawable.more_btn_down,
        contentDescription = stringResource(R.string.player_more_actions),
        modifier =
            Modifier.align(Alignment.TopStart)
                .zIndex(2f)
                .padding(start = moreButtonMargin, top = moreButtonTopMargin)
                .size(actionButtonSize),
        onClick = onMoreClick,
    )
    AnimatedVisibility(
        visible = isLyricsPage,
        enter =
            fadeIn(
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageEnterDurationMillis,
                        delayMillis = PlaybackLyricsActionEnterDelayMillis,
                        easing = PlaybackControlEasing,
                    )
            ) +
                scaleIn(
                    initialScale = 0.92f,
                    animationSpec =
                        tween(
                            durationMillis = PlaybackVisualPageEnterDurationMillis,
                            delayMillis = PlaybackLyricsActionEnterDelayMillis,
                            easing = PlaybackControlEasing,
                        ),
                ),
        exit =
            fadeOut(
                animationSpec =
                    tween(
                        durationMillis = PlaybackVisualPageExitDurationMillis,
                        easing = PlaybackControlEasing,
                    )
            ) +
                scaleOut(
                    targetScale = 0.92f,
                    animationSpec =
                        tween(
                            durationMillis = PlaybackVisualPageExitDurationMillis,
                            easing = PlaybackControlEasing,
                        ),
                ),
        modifier =
            Modifier.align(Alignment.TopEnd)
                .zIndex(2f)
                .padding(end = moreButtonMargin, top = moreButtonTopMargin),
    ) {
        val screenSwitchNormalRes =
            if (keepLyricsScreenAwake) {
                R.drawable.sun_btn_on
            } else {
                R.drawable.sun_btn_off
            }
        val screenSwitchPressedRes =
            if (keepLyricsScreenAwake) {
                R.drawable.sun_btn_on_down
            } else {
                R.drawable.sun_btn_off_down
            }
        PressedDrawableButton(
            normalRes = screenSwitchNormalRes,
            pressedRes = screenSwitchPressedRes,
            contentDescription = stringResource(R.string.always_on),
            modifier = Modifier.size(actionButtonSize),
            onClick = onKeepLyricsScreenAwakeToggle,
        )
    }
}

/** 封面页与歌词页的切换容器；两页各自消费同一批播放状态与手势回调。 */
@Composable
private fun BoxScope.PlaybackStagePages(
    currentVisualPage: PlaybackVisualPage,
    turntableStyleSpec: TurntableStyleSpec,
    turntableWidth: Dp,
    scale: Float,
    coverPositionMs: Long,
    durationMs: Long,
    scratchEnabled: Boolean,
    hidePlayerAxisEnabled: Boolean,
    albumArtwork: ImageBitmap?,
    hasMediaItem: Boolean,
    isPlaying: Boolean,
    coverDragMode: CoverDragMode,
    previewPositionMs: Long?,
    needlePreviewRotationDegrees: Float?,
    needleParkedOutside: Boolean,
    discManualRotationOffsetDegrees: Float,
    mediaId: String?,
    embeddedLyrics: EmbeddedLyrics?,
    fallbackLyricsLines: List<String>,
    lyricsPositionMs: Long,
    onVisualPageToggle: () -> Unit,
    onDiscScratchStart: () -> Unit,
    onDiscScratchMotion: (Long, Float) -> Unit,
    onDiscScratchPositionChange: (Long, Float) -> Unit,
    onDiscScratchEnd: (Long, Float) -> Unit,
    onDiscScratchCancel: () -> Unit,
    onNeedleSeekStart: (Float, Long?) -> Unit,
    onNeedleSeekPositionChange: (Float, Long?) -> Unit,
    onNeedleSeekEnd: (Float, Long?) -> Unit,
    onNeedleSeekCancel: () -> Unit,
) {
    AnimatedContent(
        targetState = currentVisualPage,
        transitionSpec = {
            playbackVisualPageTransform(
                enteringLyrics = targetState == PlaybackVisualPage.Lyrics
            )
        },
        label = "playbackVisualPage",
        modifier = Modifier.matchParentSize(),
    ) { targetPage ->
        when (targetPage) {
            PlaybackVisualPage.Cover ->
                PlaybackCoverPage(
                    turntableWidth = turntableWidth,
                    scale = scale,
                    turntableStyleSpec = turntableStyleSpec,
                    currentPositionMs = coverPositionMs,
                    durationMs = durationMs,
                    scratchEnabled = scratchEnabled,
                    hidePlayerAxisEnabled = hidePlayerAxisEnabled,
                    albumArtwork = albumArtwork,
                    hasMediaItem = hasMediaItem,
                    isPlaying = isPlaying,
                    coverDragMode = coverDragMode,
                    previewPositionMs = previewPositionMs,
                    needlePreviewRotationDegrees = needlePreviewRotationDegrees,
                    needleParkedOutside = needleParkedOutside,
                    discManualRotationOffsetDegrees = discManualRotationOffsetDegrees,
                    mediaId = mediaId,
                    onVisualPageToggle = onVisualPageToggle,
                    onDiscScratchStart = onDiscScratchStart,
                    onDiscScratchMotion = onDiscScratchMotion,
                    onDiscScratchPositionChange = onDiscScratchPositionChange,
                    onDiscScratchEnd = onDiscScratchEnd,
                    onDiscScratchCancel = onDiscScratchCancel,
                    onNeedleSeekStart = onNeedleSeekStart,
                    onNeedleSeekPositionChange = onNeedleSeekPositionChange,
                    onNeedleSeekEnd = onNeedleSeekEnd,
                    onNeedleSeekCancel = onNeedleSeekCancel,
                    modifier = Modifier.matchParentSize(),
                )

            PlaybackVisualPage.Lyrics ->
                PlaybackLyricsPage(
                    mediaId = mediaId,
                    lyrics = embeddedLyrics,
                    fallbackLyricsLines = fallbackLyricsLines,
                    currentPositionMs = lyricsPositionMs,
                    turntableStyleSpec = turntableStyleSpec,
                    onVisualPageToggle = onVisualPageToggle,
                    modifier = Modifier.matchParentSize(),
                )
        }
    }
}

@Composable
private fun PlaybackLyricsPage(
    mediaId: String?,
    lyrics: EmbeddedLyrics?,
    fallbackLyricsLines: List<String>,
    currentPositionMs: Long,
    turntableStyleSpec: TurntableStyleSpec,
    onVisualPageToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestVisualPageToggle by rememberUpdatedState(onVisualPageToggle)
    PlaybackLyricsOverlay(
        mediaId = mediaId,
        lyrics = lyrics,
        fallbackLines = fallbackLyricsLines,
        currentPositionMs = currentPositionMs,
        turntableStyleSpec = turntableStyleSpec,
        modifier =
            modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        latestVisualPageToggle()
                    }
                )
            },
    )
}
