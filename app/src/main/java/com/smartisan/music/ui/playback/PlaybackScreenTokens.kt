package com.smartisan.music.ui.playback

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartisan.music.R

internal val PlaybackPageBackground: Color
    @Composable get() = colorResource(R.color.page_background)
private val PlaybackTitleColor: Color
    @Composable get() = colorResource(R.color.playing_title_text)
private val PlaybackSubtitleColor: Color
    @Composable get() = colorResource(R.color.playing_subtitle_text)
private val PlaybackTimeColor: Color
    @Composable get() = colorResource(R.color.playing_time_text)

internal const val PlaybackDiscCycleDurationMs = 15_500f
internal const val ScratchCycleDurationMs = 1_800f
internal const val DiscRotationDegrees = 360f
internal const val ScratchHubDeadZoneRatio = 0.06f
internal const val PlaybackScratchMinMotionDegrees = 0.18f
internal const val PlaybackScratchMaxDeltaTimeMs = 72L
internal const val ScratchMaxAngleStepDegrees = 54f
internal const val ScratchVelocityMaxDegreesPerSecond = 1_000f
internal const val ScratchFlingMinVelocityDegreesPerSecond = 160f
internal const val ScratchFlingReleaseTimeoutMs = 120L
internal const val ScratchFlingVelocitySampleWindowMs = 120L
internal const val ScratchFlingMinVelocitySampleMs = 16L
internal const val ScratchFlingDurationMultiplier = 1.5f
internal const val ScratchFlingPlayingRewindDurationScale = 1.2f
internal const val ScratchFlingFrameDivisor = 1_700f
internal const val ScratchPixelFlingDivisor = 40f
internal const val ScratchWarmupRefreshMs = 3_000L
internal const val ScratchPlaybackVelocityDegreesPerSecond =
    DiscRotationDegrees * 1_000f / PlaybackDiscCycleDurationMs
internal const val CoverPreviewTimeoutMs = 260L
internal const val CoverPreviewSettleToleranceMs = 24L
internal const val NeedleSeekSettleHoldTimeoutMs = 900L
internal const val NeedleLiftHoldAfterReleaseMs = 250L
internal const val OriginalTurntableBaseWidthDp = 360f
internal const val OriginalLargeNeedleBreakpointScale = 411f / OriginalTurntableBaseWidthDp
internal const val OriginalNeedleTouchStartRatio = 0.8f
internal const val OriginalNeedleWidthBaseDp = 73.3f
internal const val OriginalNeedleHeightBaseDp = 310f
internal const val OriginalNeedleTopWidthBaseDp = 41f
internal const val OriginalNeedleTopMarginBaseDp = 25.5f
internal const val OriginalNeedleRightMarginDp = 2.5f
internal const val OriginalNeedleShadowRightMarginDp = 2f
internal const val OriginalNeedlePivotXDp = 48f
internal const val OriginalNeedlePivotYDp = 28f
internal const val OriginalNeedleHeightLargeDp = 354.6953f
internal const val OriginalNeedleTopMarginLargeDp = 29.199982f
internal const val OriginalNeedleRightMarginLargeDp = 2.7999878f
internal const val OriginalNeedleShadowRightMarginLargeDp = 2.2999878f
internal const val NeedleSeekOutsideActivationDistanceDp = 36f
internal const val NeedleSeekStartPositionGuardMs = 1_000L
internal const val NeedleRestRotationDegrees = 0f
internal const val NeedlePlaybackStartRotationDegrees = 12f
internal const val NeedlePlaybackSweepDegrees = 22.3f
internal const val NeedlePlaybackEndRotationDegrees =
    NeedlePlaybackStartRotationDegrees + NeedlePlaybackSweepDegrees
internal const val NeedleLiftScaleY = 0.98f
internal const val NeedleLiftShadowRotationOffsetDegrees = 4f
internal const val PlaybackAlbumArtDiameterRatio = 405f / 1080f
internal const val PlaybackTurntableAxisDiameterRatio = 62f / 1080f
internal const val PlaybackTurntableAxisSourceDiameterPx = 60
internal const val NeteaseDiscCycleDurationMs = 20_000f
// 可见黑胶直径相对转盘宽度，沿用真机校准后的 0.78。
internal const val NeteaseVinylVisibleDiameterRatio = 0.78f
// disc.png 的黑胶有效边界为 1167/1200 ≈ 0.9725，四周保留约 1.4% 透明边。
internal const val NeteaseVinylBitmapVisibleDiameterRatio = 0.9725f
// 官方播放页把 outline.png 与 disc.png 按同尺寸、同中心叠放，因此图像容器
// 相对转盘宽度要按位图透明边补偿，使可见黑胶维持 0.78。
internal const val NeteaseDiscDiameterRatio =
    NeteaseVinylVisibleDiameterRatio / NeteaseVinylBitmapVisibleDiameterRatio
// 封面径相对图像容器。官方 CenterImg 基准 400、封面 214/0.85，二者之比为 0.6294。
internal const val NeteaseCoverDiameterRatio = NeteaseDiscDiameterRatio * 0.6294f
// 碟心在舞台页面区内的纵向位置。页面区高度按 NeteaseTurntableHeightToWidthRatio 加高后，
// 该比例使碟心落在与原版相同的位置（0.6286 × 原页面区高 = 0.5277 × 新页面区高），
// 唱片大小与视觉位置不变。
internal const val NeteaseDiscCenterYRatio = 0.5277f
// 唱针几何按手机版实测：支点在碟心正上方、支点到唱头中心 0.5087 碟径
// （官方 SVG 内该距离为 146.6 单位）。支点偏移解算为 0.7346 参考直径时，
// 针尖落点半径恰好等于封面半径，即贴合「黑胶内侧与封面交界」。
// 碟径放大后唱针不跟随：针顶在 0.72 标定下已贴舞台上沿，再放大必越界；
// 故针的缩放与支点偏移都按此参考直径（手机版标定值）计算。
internal const val NeteaseNeedleReferenceDiameterRatio = 0.72f
internal const val NeteaseNeedlePivotOffsetToDiscRatio = 0.7346f
internal const val NeteaseNeedleScaleToDiscRatio = 0.5087f / 146.6f
internal const val NeteaseNeedleSvgViewBoxWidth = 114f
internal const val NeteaseNeedleSvgViewBoxHeight = 174f
internal const val NeteaseNeedlePivotSvgX = 20f
internal const val NeteaseNeedlePivotSvgY = 20f
// 唱头中心（SVG 坐标），用于校验播放态针尖落点。
internal const val NeteaseNeedleTipSvgX = 100f
internal const val NeteaseNeedleTipSvgY = 170f
// 唱头矩形旋转 40° 后的几何中心（SVG 坐标），距支点 146.6 单位。
internal const val NeteaseNeedleHeadCenterSvgX = 87.469f
internal const val NeteaseNeedleHeadCenterSvgY = 150.157f
internal const val NeteaseNeedlePlayingRotationDegrees = 0f
internal const val NeteaseNeedlePausedRotationDegrees = -35f
internal const val NeteaseNeedleDropDurationMs = 300

internal val PlaybackVisualStageTopPadding = 16.dp
// Music 8.1.0: audio_player.xml and the source xxhdpi playback assets.
internal val PlaybackSeekBarHeight = 41.dp
internal val PlaybackSeekBarHorizontalPadding = 51.299988.dp
internal val PlaybackSeekTrackDrawableHeight = (41f / 3f).dp
internal val PlaybackSeekThumbWidth = (67f / 3f).dp
internal val PlaybackSeekThumbHeight = 41.dp
internal val PlaybackSeekBarDividerHeight = 0.7.dp
internal val PlaybackVolumeBarHeight = 60.dp
internal val PlaybackVolumeHorizontalPadding = 26.5.dp
internal val PlaybackVolumeThumbOffset = 5.dp
internal val PlaybackActionButtonSize = 31.dp
internal val PlaybackMinimumTouchTargetSize = 48.dp
internal val PlaybackControlEntranceOffset = 186.dp
internal val PlaybackControlButtonsTopPadding = 6.dp
internal val PlaybackBottomControlsVolumeTopPadding = 18.dp
internal val PlaybackBottomControlsContentBottomPadding = 19.dp
internal val PlaybackBottomControlsBottomSpacing = 14.dp
internal val PlaybackBottomControlsMinimumBottomSpacing = 16.dp

internal const val PlaybackEntranceTotalDurationMillis = 980
internal const val PlaybackTurntableEntranceDurationMillis = 300
internal const val PlaybackControlEntranceDurationMillis = 240
internal const val PlaybackPlayButtonEntranceDelayMillis = 100
internal const val PlaybackSideButtonEntranceDelayMillis = 150
internal const val PlaybackVolumeEntranceDelayMillis = 155
internal const val PlaybackOuterButtonAlphaDelayMillis = 500
internal const val PlaybackOuterButtonAlphaDurationMillis = 480
internal const val PlaybackVisualPageEnterDurationMillis = 240
internal const val PlaybackVisualPageExitDurationMillis = 180
internal const val PlaybackVisualPageEnterDelayMillis = 40
internal const val PlaybackLyricsActionEnterDelayMillis = 70

internal val PlaybackControlEasing = Easing { fraction ->
    val inverse = 1f - fraction.coerceIn(0f, 1f)
    1f - inverse * inverse * inverse
}

internal fun playbackEntranceProgress(
    timeMillis: Float,
    delayMillis: Int,
    durationMillis: Int,
): Float {
    if (durationMillis <= 0) {
        return 1f
    }
    val linear = ((timeMillis - delayMillis.toFloat()) / durationMillis.toFloat()).coerceIn(0f, 1f)
    val inverse = 1f - linear
    return 1f - inverse * inverse * inverse
}

internal val PlaybackTitleStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = PlaybackTitleColor,
        )
internal val PlaybackArtistStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 11.sp,
            color = PlaybackSubtitleColor,
        )
internal val PlaybackTimeStyle: TextStyle
    @Composable
    get() =
        TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = PlaybackTimeColor,
        )
