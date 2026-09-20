package com.smartisan.music.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartisan.music.R
import com.smartisan.music.data.settings.*
import com.smartisan.music.launcher.AppIcon
import com.smartisan.music.ui.components.*
import com.smartisan.music.ui.navigation.*

@Composable
internal fun SettingsRootPage(
    active: Boolean,
    playbackSettings: PlaybackSettings,
    artistSettings: ArtistSettings,
    navigationSettings: NavigationSettings,
    themeMode: ThemeMode,
    turntableStyle: TurntableStyle,
    appIcon: AppIcon,
    neteaseSignedIn: Boolean,
    playbackQuality: NeteaseAudioQuality,
    onClose: () -> Unit,
    onScratchEnabledChange: (Boolean) -> Unit,
    onHidePlayerAxisEnabledChange: (Boolean) -> Unit,
    onPopcornSoundEnabledChange: (Boolean) -> Unit,
    onAccountClick: () -> Unit,
    onPlaybackQualityClick: () -> Unit,
    onAudioFxClick: () -> Unit,
    onArtistSeparatorsClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onAppIconClick: () -> Unit,
    onTurntableStyleClick: () -> Unit,
    onThemeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(active, stringResource(R.string.setting), onClose, modifier, complete = true) {
        SettingsSection(R.string.settings_section_online_music) {
            SettingsValueRow(
                R.string.cloud_music_account_netease,
                stringResource(
                    if (neteaseSignedIn) R.string.netease_logged_in
                    else R.string.cloud_music_account_not_logged_in
                ),
                true,
                RowShape.Top,
                onAccountClick,
            )
            SettingsValueRow(
                R.string.online_music_play_quality,
                stringResource(playbackQuality.labelRes()),
                true,
                RowShape.Bottom,
                onPlaybackQualityClick,
            )
        }
        SettingsSection(R.string.settings_section_playback) {
            SettingsValueRow(
                R.string.audio_fx,
                if (playbackSettings.audioFxEnabled)
                    stringResource(playbackSettings.audioFxPreset.labelRes())
                else stringResource(R.string.audio_fx_off),
                true,
                RowShape.Top,
                onAudioFxClick,
            )
            SettingsSwitchRow(
                R.string.djing,
                playbackSettings.scratchEnabled,
                RowShape.Middle,
                onScratchEnabledChange,
            )
            SettingsSwitchRow(
                R.string.player_axis_enabled,
                playbackSettings.hidePlayerAxisEnabled,
                RowShape.Middle,
                onHidePlayerAxisEnabledChange,
                enabled = turntableStyle == TurntableStyle.Original,
                lockedSummary =
                    if (turntableStyle == TurntableStyle.Netease) {
                        stringResource(R.string.turntable_style_axis_netease_summary)
                    } else {
                        null
                    },
            )
            SettingsSwitchRow(
                R.string.popcorn_sound,
                playbackSettings.popcornSoundEnabled,
                RowShape.Bottom,
                onPopcornSoundEnabledChange,
            )
        }
        SettingsSection(R.string.settings_section_library) {
            SettingsValueRow(
                R.string.artist_separators,
                artistSettings.separators.sorted().joinToString(" ").ifEmpty {
                    stringResource(R.string.not_set)
                },
                false,
                RowShape.Single,
                onArtistSeparatorsClick,
            )
        }
        SettingsSection(R.string.settings_section_navigation) {
            val pinned =
                pluralStringResource(
                    R.plurals.bottom_tab_pinned_count,
                    navigationSettings.layout.bottomCount,
                    navigationSettings.layout.bottomCount,
                )
            val more =
                pluralStringResource(
                    R.plurals.bottom_tab_more_count,
                    navigationSettings.layout.overflowDestinations.size,
                    navigationSettings.layout.overflowDestinations.size,
                )
            SettingsValueRow(
                R.string.bottom_tab_visibility,
                stringResource(R.string.bottom_tab_layout_summary, pinned, more),
                true,
                RowShape.Single,
                onNavigationClick,
            )
        }
        SettingsSection(R.string.settings_section_appearance) {
            SettingsValueRow(
                R.string.theme_settings,
                stringResource(themeMode.labelRes),
                true,
                RowShape.Top,
                onThemeClick,
            )
            SettingsValueRow(
                R.string.turntable_style,
                stringResource(turntableStyle.labelRes),
                true,
                RowShape.Middle,
                onTurntableStyleClick,
            )
            SettingsValueRow(
                R.string.app_icon,
                stringResource(appIcon.labelRes()),
                true,
                RowShape.Bottom,
                onAppIconClick,
            )
        }
        SettingsGap()
    }
}

@Composable
internal fun NavigationSettingsPage(
    active: Boolean,
    navigationSettings: NavigationSettings,
    onClose: () -> Unit,
    onTabPinnedChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(active, stringResource(R.string.bottom_tab_visibility), onClose, modifier) {
        SettingsSection(R.string.bottom_tab_visibility) {
            MusicDestination.movableEntries.forEachIndexed { index, destination ->
                val layout = navigationSettings.layout
                val pinned = layout.isPinned(destination)
                val enabled =
                    if (pinned) layout.bottomCount > MinBottomDestinationCount
                    else layout.bottomCount < MaxBottomDestinationCount
                SettingsSwitchRow(
                    destination.labelRes,
                    pinned,
                    if (index == 0) RowShape.Top else RowShape.Middle,
                    { onTabPinnedChange(destination.route, it) },
                    enabled = enabled,
                )
            }
            SettingsSwitchRow(
                MusicDestination.More.labelRes,
                true,
                RowShape.Bottom,
                {},
                enabled = false,
                lockedSummary = stringResource(R.string.bottom_tab_more_locked),
            )
        }
        SettingsGap()
    }
}

@Composable
internal fun SettingsScaffold(
    active: Boolean,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    complete: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Column(modifier.fillMaxSize().background(colorResource(R.color.page_background))) {
        if (active) {
            val close =
                SmartisanTitleBarAction(
                    if (complete) R.drawable.standard_icon_complete_selector
                    else R.drawable.standard_icon_back_selector,
                    stringResource(if (complete) R.string.done else R.string.back),
                    onClose,
                )
            SmartisanTitleBar(
                title,
                navigationIcon = if (complete) null else close,
                action = if (complete) close else null,
            )
            Column(
                Modifier.fillMaxWidth()
                    .weight(1f)
                    .smartisanPainterBackground(
                        rememberSmartisanDrawablePainter(R.drawable.account_background)
                    )
                    .verticalScroll(scroll)
                    .padding(horizontal = dimensionResource(R.dimen.list_item_left_right_margin)),
                content = content,
            )
        }
    }
}

@Composable
internal fun SettingsGap() {
    Spacer(Modifier.height(dimensionResource(R.dimen.list_item_vertical_gap)))
}

@Composable
internal fun SettingsSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    SettingsGap()
    BasicText(
        stringResource(title),
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 7.dp),
        style = settingsTextStyle(summary = true).copy(fontWeight = FontWeight.Bold),
    )
    Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
private fun SettingsValueRow(
    title: Int,
    value: String,
    arrow: Boolean,
    shape: RowShape,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectSmartisanPressedAsState()
    val focused by source.collectIsFocusedAsState()
    SettingsRow(
        title,
        null,
        shape,
        true,
        source,
        Modifier.clickable(source, null, onClick = smartisanClick(onClick)),
    ) {
        BasicText(
            value,
            Modifier.padding(end = if (arrow) 2.dp else 10.dp),
            style =
                settingsTextStyle(summary = true)
                    .copy(
                        color =
                            smartisanStateColor(
                                R.color.blue_btn_text_color_selector,
                                pressed = pressed,
                                focused = focused,
                            )
                    ),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (arrow)
            Image(
                rememberSmartisanDrawablePainter(
                    R.drawable.selector_list_content_item_arrow,
                    pressed = pressed,
                    focused = focused,
                ),
                null,
                contentScale = ContentScale.Inside,
            )
    }
}

@Composable
internal fun SettingsSwitchRow(
    title: Int,
    checked: Boolean,
    shape: RowShape,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    lockedSummary: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val switchState = rememberSmartisanSwitchState(checked, enabled, onChange)
    SettingsRow(
        title,
        null,
        shape,
        enabled || lockedSummary != null,
        source,
        Modifier.toggleable(
            checked,
            source,
            null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = { switchState.toggle() },
        ),
        titleAlpha = if (enabled || lockedSummary != null) 1f else 0.55f,
    ) {
        if (lockedSummary != null)
            BasicText(
                lockedSummary,
                Modifier.padding(end = 2.dp),
                style = settingsTextStyle(summary = true),
                maxLines = 1,
            )
        SmartisanSwitch(checked, onChange, Modifier.clearAndSetSemantics {}, enabled, switchState)
    }
}

@Composable
internal fun SettingsRow(
    title: Int,
    summary: String?,
    shape: RowShape,
    enabled: Boolean,
    source: MutableInteractionSource,
    modifier: Modifier,
    titleAlpha: Float = if (enabled) 1f else 0.62f,
    titleAccessoryGap: Dp? = null,
    accessory: @Composable RowScope.() -> Unit,
) {
    val pressed by source.collectSmartisanPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val localeDirection = LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth()
                .height(dimensionResource(R.dimen.list_item_min_height))
                .smartisanShadowBackground(
                    shape.background,
                    shape.shadow,
                    enabled,
                    pressed,
                    focused = focused,
                )
                .then(modifier)
                .padding(
                    start = dimensionResource(R.dimen.settings_row_content_margin_start),
                    end = dimensionResource(R.dimen.settings_row_accessory_margin_end),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f)
                    .padding(
                        end =
                            titleAccessoryGap
                                ?: dimensionResource(R.dimen.settings_row_title_accessory_gap)
                    )
                    .alpha(titleAlpha)
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides localeDirection) {
                    BasicText(
                        stringResource(title),
                        Modifier.fillMaxWidth(),
                        style =
                            settingsTextStyle()
                                .copy(
                                    color =
                                        smartisanStateColor(
                                            R.color.setting_item_text_colorlist,
                                            enabled,
                                            pressed,
                                            focused = focused,
                                        )
                                ),
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (summary != null)
                        BasicText(
                            summary,
                            Modifier.fillMaxWidth(),
                            style =
                                settingsTextStyle(summary = true)
                                    .copy(
                                        color =
                                            smartisanStateColor(
                                                R.color.setting_item_summary_text_colorlist,
                                                enabled,
                                                pressed,
                                                focused = focused,
                                            )
                                    ),
                            maxLines = 1,
                            softWrap = false,
                        )
                }
            }
            accessory()
        }
    }
}

@Composable
internal fun settingsTextStyle(summary: Boolean = false) =
    TextStyle(
        fontSize =
            smartisanTextSize(
                if (summary) R.dimen.settings_item_tips_text_size else R.dimen.primary_text_size
            ),
        color =
            colorResource(
                if (summary) R.color.setting_item_summary_text_color
                else R.color.setting_item_text_color
            ),
        platformStyle = PlatformTextStyle(includeFontPadding = true),
    )

internal enum class RowShape(val background: Int, val shadow: Int) {
    Single(R.drawable.group_list_item_bg_single, R.drawable.list_content_item_single_shadow),
    Top(R.drawable.group_list_item_bg_top, R.drawable.list_content_item_top_shadow),
    Middle(R.drawable.group_list_item_bg_mid, R.drawable.list_content_item_middle_shadow),
    Bottom(R.drawable.group_list_item_bg_bottom, R.drawable.list_content_item_bottom_shadow);

    companion object {
        fun at(index: Int, count: Int) =
            when {
                count == 1 -> Single
                index == 0 -> Top
                index == count - 1 -> Bottom
                else -> Middle
            }
    }
}
