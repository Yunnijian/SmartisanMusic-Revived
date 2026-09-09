package com.smartisan.music.ui.cloud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlineMusicProviderRepository
import com.smartisan.music.data.online.OnlineTrack
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.data.online.toMediaItem
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.cloud.components.CloudMusicBlankState
import com.smartisan.music.ui.cloud.components.CloudMusicDelayedLoadingState
import com.smartisan.music.ui.cloud.components.CloudMusicDivider
import com.smartisan.music.ui.cloud.components.CloudMusicSearchBarHeight
import com.smartisan.music.ui.cloud.components.CloudMusicTrackRow
import com.smartisan.music.ui.cloud.components.CloudAccentColor
import com.smartisan.music.ui.cloud.components.CloudSearchDebounceMs
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** 云音乐搜索结果状态机：输入为空时回到 Idle，网络失败可重试。 */
internal sealed interface CloudSearchResultsState {
    object Idle : CloudSearchResultsState
    object Loading : CloudSearchResultsState
    data class Empty(val query: String) : CloudSearchResultsState
    data class Error(val query: String) : CloudSearchResultsState
    data class Success(val tracks: List<OnlineTrack>) : CloudSearchResultsState
}

/**
 * 云音乐搜索页：搜索框 + 歌曲结果列表（封面 / 歌名 / 艺人 - 专辑 / 时长）。
 * 点击歌曲时把整个结果列表作为在线队列交给播放控制器（占位 URI 由服务端解析）。
 * query 由宿主持有（受控），与作者新版框架的 SearchOverlay 受控模式一致。
 */
@Composable
internal fun CloudMusicSearchPage(
    query: String,
    onQueryChange: (String) -> Unit,
    repository: OnlineMusicProviderRepository,
    active: Boolean,
    playbackBarOverlayHeight: Dp,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playbackBrowser = LocalPlaybackBrowser.current
    var state by remember { mutableStateOf<CloudSearchResultsState>(CloudSearchResultsState.Idle) }
    // 失败重试时递增，让 LaunchedEffect 以相同 query 重新发起搜索。
    var searchRevision by remember { mutableStateOf(0) }

    // 输入防抖后调用网易云搜索；query 变化即取消上一次未完成的请求。
    LaunchedEffect(query, searchRevision) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            state = CloudSearchResultsState.Idle
            return@LaunchedEffect
        }
        state = CloudSearchResultsState.Loading
        delay(CloudSearchDebounceMs)
        val result = runSuspendCatching {
            repository.search(normalizedQuery)
        }
        state = result.fold(
            onSuccess = { tracks ->
                if (tracks.isEmpty()) {
                    CloudSearchResultsState.Empty(normalizedQuery)
                } else {
                    CloudSearchResultsState.Success(tracks)
                }
            },
            onFailure = {
                CloudSearchResultsState.Error(normalizedQuery)
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        CloudMusicSearchField(
            query = query,
            hint = stringResource(R.string.cloud_music_search_hint_netease),
            active = active,
            onQueryChange = onQueryChange,
            onCancel = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val currentState = state) {
                CloudSearchResultsState.Idle -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_empty_title),
                    subtitle = stringResource(R.string.cloud_music_empty_subtitle),
                    modifier = Modifier.fillMaxSize(),
                )
                CloudSearchResultsState.Loading -> CloudMusicDelayedLoadingState(
                    title = stringResource(R.string.cloud_music_loading),
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSearchResultsState.Empty -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_no_result),
                    subtitle = currentState.query,
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSearchResultsState.Error -> CloudMusicBlankState(
                    title = stringResource(R.string.cloud_music_error),
                    subtitle = currentState.query,
                    actionText = stringResource(R.string.cloud_music_retry),
                    onActionClick = { searchRevision += 1 },
                    modifier = Modifier.fillMaxSize(),
                )
                is CloudSearchResultsState.Success -> CloudMusicSearchResultList(
                    tracks = currentState.tracks,
                    playbackBarOverlayHeight = playbackBarOverlayHeight,
                    onTrackClick = { items, index ->
                        playbackBrowser.replaceQueueAndPlay(
                            mediaItems = items,
                            startIndex = index,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun CloudMusicSearchResultList(
    tracks: List<OnlineTrack>,
    playbackBarOverlayHeight: Dp,
    onTrackClick: (List<androidx.media3.common.MediaItem>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // toMediaItem 携带在线身份 extras；withOnlinePlaybackPlaceholderUri 补上
    // smartisan-online://netease/{trackId} 占位 URI，播放服务端负责解析真实地址。
    val playableItems = remember(tracks) {
        tracks.map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
    }
    LazyColumn(
        modifier = modifier.background(Color.White),
        contentPadding = PaddingValues(bottom = playbackBarOverlayHeight + 10.dp),
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.mediaId },
        ) { index, track ->
            Column {
                CloudMusicTrackRow(
                    track = track,
                    onClick = { onTrackClick(playableItems, index) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CloudMusicDivider()
            }
        }
    }
}

/** 云音乐搜索输入框：沿用原版 search_field 背景 + 左侧放大镜 + 清空按钮 + 取消。 */
@Composable
internal fun CloudMusicSearchField(
    query: String,
    hint: String,
    active: Boolean,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            keyboardController?.show()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    Row(
        modifier = modifier
            .height(CloudMusicSearchBarHeight)
            .background(Color.White)
            .padding(horizontal = 15.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 15.sp,
                color = CloudSearchFieldTextColor,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
                val clearInteractionSource = remember { MutableInteractionSource() }
                val clearPressed by clearInteractionSource.collectIsPressedAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    SmartisanDrawableBackground(
                        drawableRes = R.drawable.search_field,
                        modifier = Modifier.matchParentSize(),
                    )
                    Image(
                        painter = painterResource(R.drawable.search_bar_left_icon),
                        contentDescription = null,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(start = 6.dp)
                            .width(24.dp)
                            .height(30.dp),
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .padding(
                                start = 36.dp,
                                end = if (query.isNotEmpty()) 30.dp else 12.dp,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = hint,
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    color = CloudSearchHintColor,
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                    if (query.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .align(Alignment.CenterEnd)
                                .clickable(
                                    interactionSource = clearInteractionSource,
                                    indication = null,
                                    onClick = { onQueryChange("") },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = painterResource(
                                    if (clearPressed) {
                                        R.drawable.text_clear_btn_pressed
                                    } else {
                                        R.drawable.text_clear_btn
                                    },
                                ),
                                contentDescription = stringResource(R.string.clear_search_text),
                                modifier = Modifier.size(30.dp),
                            )
                        }
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.cloud_music_search_cancel),
            style = TextStyle(
                fontSize = 14.sp,
                color = CloudAccentColor,
            ),
            maxLines = 1,
            modifier = Modifier
                .padding(start = 12.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onCancel,
                ),
        )
    }
}

private val CloudSearchFieldTextColor = Color(0xCC000000)
private val CloudSearchHintColor = Color(0x66000000)

/** 捕获非取消异常，避免网络错误直接把协程作用域打断。 */
private suspend inline fun <T> runSuspendCatching(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
