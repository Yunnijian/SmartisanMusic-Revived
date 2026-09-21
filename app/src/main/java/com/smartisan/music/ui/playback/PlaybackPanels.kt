package com.smartisan.music.ui.playback

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.settings.TurntableStyle
import com.smartisan.music.playback.EmbeddedLyrics
import com.smartisan.music.playback.EmbeddedLyricsLine
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

// Reverse resources:
// - lrc_layout.xml: full-height ListView with 80dp vertical fading edges
// - lrc_item_layout.xml: text_size_lryric=15sp and lineSpacingExtra=6dp
// - values-xxhdpi-v4/dimens.xml: lrc_horizontal_padding=53.599976dp
private val PlaybackLyricsPrimaryStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colorResource(R.color.panel_action_text),
            textAlign = TextAlign.Center,
        )
private val PlaybackLyricsSecondaryStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            color = colorResource(R.color.panel_item_text),
            textAlign = TextAlign.Center,
        )
private val PlaybackLyricsHorizontalPadding = 53.6.dp
private val PlaybackLyricsLineSpacing = 4.dp
private val PlaybackLyricsRowHeight = 24.dp
// 歌词可视区上下边界的羽化高度，对应原版 lrc_layout.xml 的 80dp fading edge。
private val PlaybackLyricsFadeHeight = 80.dp
private val PlaybackLyricsParagraphGap = 16.dp
private const val PlaybackLyricsSmoothScrollMaxLineJump = 4
private const val PlaybackLyricsManualScrollResumeDelayMillis = 2_800L
private const val PlaybackLyricsManualScrollMarkIntervalMillis = 220L

@Composable
internal fun PlaybackBottomControls(
    width: Dp,
    bottomInset: Dp,
    state: PlaybackScreenState,
    entranceTimeMillis: () -> Float,
    onRepeatClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onVolumeChange: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val bottomSpacing = playbackBottomControlsBottomSpacing(bottomInset)
    val controlEntranceOffsetPx =
        with(density) {
            PlaybackControlEntranceOffset.roundToPx().toFloat()
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier =
                Modifier.width(width).padding(bottom = PlaybackBottomControlsContentBottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlaybackControlButtons(
                isPlaying = state.isPlaybackActive,
                repeatMode = state.repeatMode,
                shuffleEnabled = state.shuffleEnabled,
                controlWidth = width,
                entranceTimeMillis = entranceTimeMillis,
                onRepeatClick = onRepeatClick,
                onPreviousClick = onPreviousClick,
                onPlayPauseClick = onPlayPauseClick,
                onNextClick = onNextClick,
                onShuffleClick = onShuffleClick,
            )
            PlaybackVolumeBar(
                modifier =
                    Modifier.padding(top = PlaybackBottomControlsVolumeTopPadding).graphicsLayer {
                        val volumeEntranceProgress =
                            playbackEntranceProgress(
                                timeMillis = entranceTimeMillis(),
                                delayMillis = PlaybackVolumeEntranceDelayMillis,
                                durationMillis = PlaybackControlEntranceDurationMillis,
                            )
                        translationY = (1f - volumeEntranceProgress) * controlEntranceOffsetPx
                    },
                width = width,
                value = state.volume.coerceIn(0f, 1f),
                onValueChange = onVolumeChange,
            )
        }
        Spacer(modifier = Modifier.height(bottomSpacing))
    }
}

private fun playbackBottomControlsBottomSpacing(bottomInset: Dp): Dp {
    return (PlaybackBottomControlsBottomSpacing + bottomInset).coerceAtLeast(
        PlaybackBottomControlsMinimumBottomSpacing
    )
}

@Composable
internal fun PlaybackLyricsOverlay(
    mediaId: String?,
    lyrics: EmbeddedLyrics?,
    fallbackLines: List<String>,
    currentPositionMs: Long,
    turntableStyleSpec: TurntableStyleSpec,
    modifier: Modifier = Modifier,
) {
    val lyricsTimingKey = if (lyrics?.isTimeSynced == true) currentPositionMs else Long.MIN_VALUE
    val renderModel =
        remember(lyrics, fallbackLines, lyricsTimingKey) {
            buildPlaybackLyricsRenderModel(
                lyrics = lyrics,
                fallbackLines = fallbackLines,
                currentPositionMs = currentPositionMs,
            )
        }

    key(mediaId, lyrics, fallbackLines) {
        val listState = rememberLazyListState()
        val autoFollowState = remember { PlaybackLyricsAutoFollowState() }
        val manualScrollConnection =
            remember(renderModel.mode, autoFollowState) {
                object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        if (
                            renderModel.mode == PlaybackLyricsMode.Timed &&
                                source == NestedScrollSource.UserInput &&
                                available.y != 0f
                        ) {
                            autoFollowState.suspendForManualScroll()
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (renderModel.mode == PlaybackLyricsMode.Timed && available.y != 0f) {
                            autoFollowState.suspendForManualScroll()
                        }
                        return Velocity.Zero
                    }
                }
            }

        BoxWithConstraints(modifier = modifier) {
            // 页面区在 Netease 样式下加高以容纳唱片与光晕，但歌词的居中位置不应随之
            // 改变：按原版页面区高度换算中心比例，保持与原版一致的绝对位置。
            val lyricsCenterRatio =
                if (turntableStyleSpec.style == TurntableStyle.Netease) {
                    (PlaybackTurntableHeightToWidthRatio / NeteaseTurntableHeightToWidthRatio) / 2f
                } else {
                    0.5f
                }
            val centerPadding =
                remember(maxHeight, lyricsCenterRatio, renderModel) {
                    val focusRowHeight =
                        renderModel.lines.getOrNull(renderModel.focusIndex)?.rowHeight()
                            ?: PlaybackLyricsRowHeight
                    ((maxHeight * lyricsCenterRatio) - (focusRowHeight / 2f)).coerceAtLeast(0.dp)
                }
            val visualCenterIndex by
                remember(listState, renderModel) {
                    derivedStateOf {
                        if (
                            renderModel.mode != PlaybackLyricsMode.Static &&
                                !autoFollowState.suspended
                        ) {
                            renderModel.alphaAnchorIndex
                        } else {
                            listState.centeredVisibleItemIndex(renderModel.alphaAnchorIndex)
                        }
                    }
                }

            LaunchedEffect(
                autoFollowState.suspended,
                autoFollowState.manualScrollGeneration,
                renderModel.mode,
            ) {
                if (autoFollowState.suspended && renderModel.mode == PlaybackLyricsMode.Timed) {
                    snapshotFlow { listState.isScrollInProgress }
                        .filter { scrolling -> !scrolling }
                        .first()
                    delay(PlaybackLyricsManualScrollResumeDelayMillis)
                    autoFollowState.resume()
                }
            }

            LaunchedEffect(
                renderModel.focusIndex,
                renderModel.mode,
                autoFollowState.suspended,
            ) {
                when (renderModel.mode) {
                    PlaybackLyricsMode.Timed -> {
                        if (autoFollowState.suspended) return@LaunchedEffect
                        if (autoFollowState.shouldAnimateTo(renderModel.focusIndex)) {
                            listState.animateScrollToItem(index = renderModel.focusIndex)
                        } else {
                            listState.scrollToItem(index = renderModel.focusIndex)
                        }
                    }
                    PlaybackLyricsMode.Fallback -> {
                        autoFollowState.resetFocusTracking()
                        listState.scrollToItem(index = renderModel.focusIndex)
                    }
                    PlaybackLyricsMode.Static -> {
                        autoFollowState.resetFocusTracking()
                    }
                }
            }

            // 歌词遮罩圆盘须与当前样式的唱片对齐：Netease 唱片更小更靠下，若沿用
            // 原版「舞台大小」的圆形遮罩，切换动画时遮罩盘底部弧线会横穿唱片底部，
            // 形成一条一闪而过的线。故 Netease 下按碟径/碟心重定位遮罩圆。
            val lyricsMaskModifier =
                if (turntableStyleSpec.style == TurntableStyle.Netease) {
                    // 用可见黑胶直径，而非带透明边的图像容器，避免遮罩弧线落在碟缘之外。
                    val discDiameter = maxWidth * NeteaseVinylVisibleDiameterRatio
                    Modifier
                        .align(Alignment.TopCenter)
                        .size(discDiameter)
                        .offset(y = (maxHeight * NeteaseDiscCenterYRatio) - (discDiameter / 2))
                } else {
                    Modifier.matchParentSize()
                }
            Box(modifier = lyricsMaskModifier.clip(CircleShape)) {
                Image(
                    painter = painterResource(R.drawable.mask_playing_lyric),
                    contentDescription = stringResource(R.string.lyrics),
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                )
            }
            LazyColumn(
                modifier =
                    Modifier.fillMaxSize()
                        // 歌词在可视区上下边界羽化淡出，而不是被硬裁成一条直线。
                        // DstIn 需要独立图层合成，否则渐变会作用到列表之外的内容。
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val fadeFraction =
                                (PlaybackLyricsFadeHeight.toPx() / size.height)
                                    .coerceIn(0f, 0.5f)
                            drawRect(
                                brush =
                                    Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        fadeFraction to Color.Black,
                                        (1f - fadeFraction) to Color.Black,
                                        1f to Color.Transparent,
                                    ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .nestedScroll(manualScrollConnection)
                        .padding(horizontal = PlaybackLyricsHorizontalPadding),
                state = listState,
                userScrollEnabled = renderModel.mode != PlaybackLyricsMode.Fallback,
                contentPadding = PaddingValues(vertical = centerPadding),
                verticalArrangement = Arrangement.spacedBy(PlaybackLyricsLineSpacing),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(
                    items = renderModel.lines,
                    key = { index, line -> "${renderModel.mode}-$index-${line.text}" },
                ) { index, line ->
                    val highlighted = renderModel.highlightedIndex == index
                    val style =
                        if (highlighted) {
                            PlaybackLyricsPrimaryStyle
                        } else {
                            PlaybackLyricsSecondaryStyle
                        }
                    Box(
                        modifier = Modifier.fillMaxWidth().height(line.rowHeight()),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (line.text.isNotBlank()) {
                            PlaybackLyricsLineText(
                                line = line,
                                style =
                                    style.copy(
                                        color =
                                            style.color.copy(
                                                alpha =
                                                    alphaForDistance(abs(index - visualCenterIndex))
                                            )
                                    ),
                                highlighted = highlighted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackLyricsLineText(
    line: PlaybackLyricsLineRenderModel,
    style: TextStyle,
    highlighted: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val text =
            if (highlighted && line.tokens.isNotEmpty()) {
                line.toAnnotatedString(
                    activeColor = PlaybackLyricsPrimaryStyle.color.copy(alpha = style.color.alpha),
                    pendingColor =
                        PlaybackLyricsSecondaryStyle.color.copy(alpha = style.color.alpha),
                )
            } else {
                AnnotatedString(line.text)
            }
        Text(
            text = text,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun PlaybackLyricsLineRenderModel.rowHeight(): Dp {
    return when {
        text.isBlank() -> PlaybackLyricsParagraphGap
        else -> PlaybackLyricsRowHeight
    }
}

private fun PlaybackLyricsLineRenderModel.toAnnotatedString(
    activeColor: Color,
    pendingColor: Color,
): AnnotatedString = buildAnnotatedString {
    tokens.forEach { token ->
        withStyle(SpanStyle(color = if (token.active) activeColor else pendingColor)) {
            append(token.text)
        }
    }
}

internal enum class PlaybackLyricsMode {
    Timed,
    Static,
    Fallback,
}

internal data class PlaybackLyricsRenderModel(
    val mode: PlaybackLyricsMode,
    val lines: List<PlaybackLyricsLineRenderModel>,
    val focusIndex: Int,
    val alphaAnchorIndex: Int,
    val highlightedIndex: Int?,
)

internal data class PlaybackLyricsLineRenderModel(
    val text: String,
    val tokens: List<PlaybackLyricsTokenRenderModel> = emptyList(),
    val activeTokenIndex: Int? = null,
)

internal data class PlaybackLyricsTokenRenderModel(
    val text: String,
    val active: Boolean,
)

private class PlaybackLyricsAutoFollowState {
    var suspended by mutableStateOf(false)
        private set

    var manualScrollGeneration by mutableIntStateOf(0)
        private set

    private var lastManualScrollMarkMillis = 0L
    private var lastFocusIndex: Int? = null

    fun suspendForManualScroll() {
        val now = SystemClock.uptimeMillis()
        if (now - lastManualScrollMarkMillis >= PlaybackLyricsManualScrollMarkIntervalMillis) {
            manualScrollGeneration += 1
            lastManualScrollMarkMillis = now
        }
        suspended = true
    }

    fun resume() {
        suspended = false
    }

    fun resetFocusTracking() {
        lastFocusIndex = null
    }

    fun shouldAnimateTo(focusIndex: Int): Boolean {
        val previousIndex = lastFocusIndex
        lastFocusIndex = focusIndex
        return previousIndex != null &&
            abs(focusIndex - previousIndex) <= PlaybackLyricsSmoothScrollMaxLineJump
    }
}

internal fun buildPlaybackLyricsRenderModel(
    lyrics: EmbeddedLyrics?,
    fallbackLines: List<String>,
    currentPositionMs: Long,
): PlaybackLyricsRenderModel {
    if (lyrics == null || lyrics.lines.isEmpty()) {
        return buildFallbackPlaybackLyricsRenderModel(fallbackLines)
    }

    return if (lyrics.isTimeSynced) {
        buildTimedPlaybackLyricsRenderModel(lyrics, currentPositionMs)
    } else {
        buildStaticPlaybackLyricsRenderModel(lyrics)
    }
}

private fun buildFallbackPlaybackLyricsRenderModel(
    fallbackLines: List<String>
): PlaybackLyricsRenderModel {
    val focusIndex = fallbackLines.lastIndex.coerceAtLeast(0).coerceAtMost(2)
    return PlaybackLyricsRenderModel(
        mode = PlaybackLyricsMode.Fallback,
        lines = fallbackLines.map { line -> PlaybackLyricsLineRenderModel(text = line) },
        focusIndex = focusIndex,
        alphaAnchorIndex = focusIndex,
        highlightedIndex = focusIndex,
    )
}

private fun buildTimedPlaybackLyricsRenderModel(
    lyrics: EmbeddedLyrics,
    currentPositionMs: Long,
): PlaybackLyricsRenderModel {
    val activeIndex =
        lyrics.lines.indexOfLast { (it.timestampMs ?: Long.MAX_VALUE) <= currentPositionMs }
    val focusIndex = activeIndex.takeIf { it >= 0 } ?: 0
    return PlaybackLyricsRenderModel(
        mode = PlaybackLyricsMode.Timed,
        lines =
            lyrics.lines.mapIndexed { index, line ->
                line.toRenderModel(
                    currentPositionMs = currentPositionMs,
                    includeTokenProgress = index == activeIndex,
                )
            },
        focusIndex = focusIndex,
        alphaAnchorIndex = focusIndex,
        highlightedIndex = activeIndex.takeIf { it >= 0 },
    )
}

private fun buildStaticPlaybackLyricsRenderModel(
    lyrics: EmbeddedLyrics
): PlaybackLyricsRenderModel =
    PlaybackLyricsRenderModel(
        mode = PlaybackLyricsMode.Static,
        lines =
            lyrics.lines.map { line ->
                line.toRenderModel(currentPositionMs = 0L, includeTokenProgress = false)
            },
        focusIndex = 0,
        alphaAnchorIndex = 0,
        highlightedIndex = null,
    )

private fun EmbeddedLyricsLine.toRenderModel(
    currentPositionMs: Long,
    includeTokenProgress: Boolean,
): PlaybackLyricsLineRenderModel {
    val activeTokenIndex =
        if (includeTokenProgress && tokens.isNotEmpty()) {
            tokens
                .indexOfLast { token -> token.timestampMs <= currentPositionMs }
                .takeIf { index -> index >= 0 }
        } else {
            null
        }
    return PlaybackLyricsLineRenderModel(
        text = text,
        tokens =
            tokens.mapIndexed { index, token ->
                PlaybackLyricsTokenRenderModel(
                    text = token.text,
                    active = activeTokenIndex != null && index <= activeTokenIndex,
                )
            },
        activeTokenIndex = activeTokenIndex,
    )
}

private fun alphaForDistance(distance: Int): Float =
    when (distance) {
        0 -> 1f
        1 -> 0.84f
        2 -> 0.68f
        3 -> 0.52f
        4 -> 0.36f
        else -> 0.2f
    }

private fun LazyListState.centeredVisibleItemIndex(fallbackIndex: Int): Int {
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    if (visibleItems.isEmpty()) {
        return fallbackIndex
    }
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    return visibleItems
        .minByOrNull { item ->
            abs((item.offset + (item.size / 2)) - viewportCenter)
        }
        ?.index ?: fallbackIndex
}
