package com.smartisan.music.ui.search

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.search.SearchHistoryStore
import com.smartisan.music.data.settings.ArtistSettings
import com.smartisan.music.playback.LocalPlaybackBrowser
import com.smartisan.music.playback.await
import com.smartisan.music.playback.replaceQueueAndPlay
import com.smartisan.music.ui.components.GlobalPlaybackBar
import com.smartisan.music.ui.components.SmartisanDrawableBackground
import com.smartisan.music.ui.components.collectSmartisanPressedAsState
import com.smartisan.music.ui.components.hasAudioPermission
import com.smartisan.music.ui.shell.titlebar.TitleBarShadow
import kotlinx.coroutines.launch

internal val SearchPageBackground: Color
    @Composable get() = colorResource(R.color.page_background)
internal val SearchFieldTextColor: Color
    @Composable get() = colorResource(R.color.text_primary)
internal val SearchSectionTitleColor: Color
    @Composable get() = colorResource(R.color.text_tertiary)
internal val SearchDividerColor: Color
    @Composable get() = colorResource(R.color.divider_soft)
internal val SearchSongTitleColor: Color
    @Composable get() = colorResource(R.color.text_primary)
internal val SearchSongPlayingColor: Color
    @Composable get() = colorResource(R.color.playing_red)
internal val SearchResultHighlightColor: Color
    @Composable get() = colorResource(R.color.highlight_red)
internal val SearchSubtitleColor: Color
    @Composable get() = colorResource(R.color.text_tertiary)
internal val SearchEmptyTextColor: Color
    @Composable get() = colorResource(R.color.text_placeholder)

internal val SearchFieldTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            color = SearchFieldTextColor,
        )
internal val SearchSectionTitleStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 15.sp,
            color = SearchSongTitleColor,
        )
internal val SearchPrimaryTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = SearchSongTitleColor,
        )
internal val SearchSecondaryTextStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 13.sp,
            color = SearchSubtitleColor,
        )

internal val SearchTopBarHeight = 50.dp
internal val SearchFieldHeight = 32.dp
internal val SearchTopBarItemSpacing = 12.dp
internal val SearchCancelButtonSize = 36.dp
internal val SearchCancelIconSize = 36.dp
internal val SearchClearButtonSize = 30.dp
internal val SearchClearIconSize = 30.dp
internal val SearchFieldInnerEdgePadding = 6.dp
internal val SearchLeftIconWidth = 24.dp
internal val SearchLeftIconHeight = 30.dp
internal val SearchTextStartPadding = 36.dp
internal val SearchHistoryTopPadding = 19.dp
internal val SearchSectionHorizontalPadding = 21.dp
internal val SearchHistoryRowSpacing = 10.dp
internal val SearchHistoryChipHeight = 30.dp
internal val SearchSectionHeaderHeight = 45.dp
internal val SearchSectionHeaderStartPadding = 11.dp
internal val SearchResultRowHeight = 60.dp
internal val SearchResultArtworkFrameWidth = 48.dp
internal val SearchResultArtworkSize = 38.dp
internal val SearchResultActionWidth = 34.dp
internal val SearchResultMoreIconSize = 30.dp
internal val SearchResultSourceIconSize = 14.dp
internal val SearchPlaybackBarReservedHeight = 67.dp
internal val SearchTopHorizontalPadding = 6.dp
internal val SearchNoResultTopPadding = 85.dp
internal val SearchNoResultArtworkSize = 140.dp
internal val SearchArtworkDecodeSize = Size(128, 128)

internal enum class SearchEntityAction {
    More,
    Source,
}

@Composable
fun GlobalSearchScreen(
    query: String,
    modifier: Modifier = Modifier,
    libraryRefreshVersion: Int = 0,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenPlayback: () -> Unit,
    onAlbumClick: (String, String) -> Unit,
    onArtistClick: (String, String) -> Unit,
    artistSettings: ArtistSettings = ArtistSettings(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playbackBrowser = LocalPlaybackBrowser.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val historyStore =
        remember(context.applicationContext) {
            SearchHistoryStore(context.applicationContext)
        }
    val exclusionsStore =
        remember(context.applicationContext) {
            LibraryExclusionsStore(context.applicationContext)
        }
    val history by historyStore.history.collectAsState(initial = emptyList())
    val libraryRevision by exclusionsStore.revision.collectAsState(initial = 0)
    val hasPermission = hasAudioPermission(context)
    var permissionVersion by remember { mutableIntStateOf(0) }
    var songs by remember(playbackBrowser) { mutableStateOf(emptyList<MediaItem>()) }
    var currentMediaId by
        remember(playbackBrowser) {
            mutableStateOf(playbackBrowser?.currentMediaItem?.mediaId)
        }
    val dismissSearch by
        rememberUpdatedState(
            newValue = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                onDismiss()
            }
        )
    val clearSearchInputFocus by
        rememberUpdatedState(
            newValue = {
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
            }
        )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionVersion += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(playbackBrowser) {
        val browser = playbackBrowser ?: return@DisposableEffect onDispose {}
        val listener =
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    currentMediaId = player.currentMediaItem?.mediaId
                }
            }
        browser.addListener(listener)
        currentMediaId = browser.currentMediaItem?.mediaId
        onDispose {
            browser.removeListener(listener)
        }
    }

    LaunchedEffect(
        playbackBrowser,
        permissionVersion,
        libraryRevision,
        libraryRefreshVersion,
        hasPermission,
    ) {
        val browser =
            playbackBrowser
                ?: run {
                    songs = emptyList()
                    return@LaunchedEffect
                }
        if (!hasPermission) {
            songs = emptyList()
            return@LaunchedEffect
        }
        val rootResult = browser.getLibraryRoot(null).await(context)
        val rootItem =
            rootResult.value
                ?: run {
                    songs = emptyList()
                    return@LaunchedEffect
                }
        val childrenResult =
            browser.getChildren(rootItem.mediaId, 0, Int.MAX_VALUE, null).await(context)
        songs = childrenResult.value?.toList().orEmpty()
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    val unknownAlbumTitle = stringResource(R.string.unknown_album)
    val unknownArtistTitle = stringResource(R.string.unknown_artist)
    val multipleArtistsTitle = stringResource(R.string.many_artist)
    val results =
        remember(
            query,
            songs,
            unknownAlbumTitle,
            unknownArtistTitle,
            multipleArtistsTitle,
            artistSettings,
        ) {
            buildSearchResults(
                query = query,
                songs = songs,
                unknownAlbumTitle = unknownAlbumTitle,
                unknownArtistTitle = unknownArtistTitle,
                multipleArtistsTitle = multipleArtistsTitle,
                artistSettings = artistSettings,
            )
        }
    val showPlaybackBar = currentMediaId != null

    BackHandler(onBack = dismissSearch)

    Box(modifier = modifier.fillMaxSize().background(SearchPageBackground).imePadding()) {
        SmartisanDrawableBackground(
            drawableRes = R.drawable.account_background,
            modifier = Modifier.matchParentSize(),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            SearchTopBar(
                query = query,
                focusRequester = focusRequester,
                onQueryChange = onQueryChange,
                onSearch = {
                    scope.launch {
                        historyStore.record(query)
                    }
                    keyboardController?.hide()
                    focusManager.clearFocus()
                },
                onDismiss = dismissSearch,
            )
            if (query.isBlank()) {
                SearchHistoryPage(
                    history = history,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onHistoryClick = { entry ->
                        onQueryChange(entry)
                        scope.launch {
                            historyStore.record(entry)
                        }
                    },
                    onClearHistory = {
                        scope.launch {
                            historyStore.clear()
                        }
                    },
                )
            } else {
                SearchResultsPage(
                    results = results,
                    currentMediaId = currentMediaId,
                    showPlaybackBar = showPlaybackBar,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSongClick = { item ->
                        val targetIndex =
                            results.songs.indexOfFirst { song -> song.mediaId == item.mediaId }
                        if (targetIndex >= 0) {
                            playbackBrowser.replaceQueueAndPlay(results.songs, targetIndex)
                            scope.launch {
                                historyStore.record(query)
                            }
                        }
                    },
                    onAlbumClick = { album ->
                        scope.launch {
                            historyStore.record(query)
                        }
                        clearSearchInputFocus()
                        onAlbumClick(album.id, album.title)
                    },
                    onArtistClick = { artist ->
                        scope.launch {
                            historyStore.record(query)
                        }
                        clearSearchInputFocus()
                        onArtistClick(artist.id, artist.name)
                    },
                )
            }
        }
        if (showPlaybackBar) {
            GlobalPlaybackBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onOpenPlayback = onOpenPlayback,
            )
        }
    }
}

@Composable
private fun SearchTopBar(
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val topInset =
        with(density) {
            WindowInsets.safeDrawing.getTop(this).toDp()
        }
    val shadowHeight = dimensionResource(R.dimen.title_bar_shadow_height)

    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(SearchTopBarHeight + topInset)
                .background(colorResource(R.color.title_bar_background))
                .zIndex(1f)
    ) {
        Row(
            modifier =
                Modifier.fillMaxSize()
                    .padding(top = topInset)
                    .padding(horizontal = SearchTopHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SearchTopBarItemSpacing),
        ) {
            SearchField(
                value = query,
                focusRequester = focusRequester,
                modifier = Modifier.weight(1f),
                onValueChange = onQueryChange,
                onSearch = onSearch,
            )
            SearchCancelButton(onDismiss = onDismiss)
        }
        TitleBarShadow(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .offset(y = shadowHeight)
                    .fillMaxWidth()
                    .height(shadowHeight)
        )
    }
}

@Composable
private fun SearchField(
    value: String,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = SearchFieldTextStyle,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSearch() }),
        modifier = modifier.height(SearchFieldHeight).focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            val clearInteractionSource = remember { MutableInteractionSource() }
            val clearPressed by clearInteractionSource.collectSmartisanPressedAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                SmartisanDrawableBackground(
                    drawableRes = R.drawable.search_field,
                    modifier = Modifier.matchParentSize(),
                )
                Image(
                    painter = painterResource(R.drawable.search_bar_left_icon),
                    contentDescription = null,
                    modifier =
                        Modifier.align(Alignment.CenterStart)
                            .padding(start = SearchFieldInnerEdgePadding)
                            .width(SearchLeftIconWidth)
                            .height(SearchLeftIconHeight),
                )
                Box(
                    modifier =
                        Modifier.matchParentSize()
                            .padding(
                                start = SearchTextStartPadding,
                                end = if (value.isNotEmpty()) SearchClearButtonSize else 12.dp,
                            ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Box(
                        modifier =
                            Modifier.size(SearchClearButtonSize)
                                .align(Alignment.CenterEnd)
                                .clickable(
                                    interactionSource = clearInteractionSource,
                                    indication = null,
                                    onClick = { onValueChange("") },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter =
                                painterResource(
                                    if (clearPressed) {
                                        R.drawable.text_clear_btn_pressed
                                    } else {
                                        R.drawable.text_clear_btn
                                    }
                                ),
                            contentDescription = stringResource(R.string.clear_search_text),
                            modifier = Modifier.size(SearchClearIconSize),
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun SearchCancelButton(onDismiss: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectSmartisanPressedAsState()

    Box(
        modifier =
            Modifier.size(SearchCancelButtonSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onDismiss,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter =
                painterResource(
                    if (pressed) {
                        R.drawable.standard_icon_cancel_pressed
                    } else {
                        R.drawable.standard_icon_cancel
                    }
                ),
            contentDescription = stringResource(R.string.cancel),
            modifier = Modifier.size(SearchCancelIconSize),
        )
    }
}
