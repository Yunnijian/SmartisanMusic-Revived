package com.smartisan.music.ui.playback

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.smartisan.music.R
import com.smartisan.music.data.online.OnlinePlaybackBitrateExtraKey
import com.smartisan.music.data.online.OnlinePlaybackLevelExtraKey
import com.smartisan.music.data.online.onlineIdentityOrNull
import com.smartisan.music.platform.media.audioMediaItemUri
import com.smartisan.music.playback.LocalAudioLibrary
import java.util.Locale
import kotlin.math.roundToInt

internal fun Player?.snapshot(volume: Float = 1f): PlaybackScreenState {
    val player = this ?: return PlaybackScreenState()
    return PlaybackScreenState(
        mediaItem = player.currentMediaItem,
        isPlaying = player.isPlaying,
        playWhenReady = player.playWhenReady,
        isBuffering = player.playbackState == Player.STATE_BUFFERING,
        repeatMode = player.repeatMode,
        shuffleEnabled = player.shuffleModeEnabled,
        currentPositionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = player.duration.takeIf { it > 0L } ?: 0L,
        volume = volume,
    )
}

internal fun MediaItem.resolveDeleteTarget(): PlaybackDeleteTargetResult {
    val targetMediaId = mediaId.trim()
    if (targetMediaId.isEmpty()) {
        return PlaybackDeleteTargetResult.Unavailable
    }
    val audioQualityBadge =
        mediaMetadata.extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey)
    if (audioQualityBadge == LocalAudioLibrary.AudioQualityBadgeCue) {
        return PlaybackDeleteTargetResult.CueFile
    }
    val deleteUri =
        localConfiguration?.uri?.takeIf(Uri::isMediaStoreUri)
            ?: targetMediaId.toLongOrNull()?.let { id ->
                audioMediaItemUri(id)
            }
            ?: return PlaybackDeleteTargetResult.Unavailable
    return PlaybackDeleteTargetResult.Available(
        PlaybackDeleteTarget(
            mediaId = targetMediaId,
            uri = deleteUri,
        )
    )
}

internal fun MediaItem.canShareAudio(): Boolean {
    return localConfiguration?.uri?.scheme == ContentResolver.SCHEME_CONTENT
}

/** 播放页展示的音质档位文案（中文名）；未知 level 返回 null。 */
private fun playbackLevelLabel(level: String): Int? =
    when (level) {
        "standard" -> R.string.online_music_quality_standard
        "higher" -> R.string.online_music_quality_higher
        "exhigh" -> R.string.online_music_quality_exhigh
        "lossless" -> R.string.online_music_quality_lossless
        "hires" -> R.string.online_music_quality_hires
        "jyeffect" -> R.string.online_music_quality_hd_surround
        "sky" -> R.string.online_music_quality_surround
        "jymaster" -> R.string.online_music_quality_master
        "dolby" -> R.string.online_music_quality_dolby
        "vivid" -> R.string.online_music_quality_vivid
        else -> null
    }

/**
 * 播放页顶部的音质描述，形如「超清母带 · 5244kbps」。
 *
 * 在线条目取服务端回显的**实际**档位与码率（请求高档位而权益或资源不足时服务端会静默降级，
 * 回显值才是真实结果）；本地条目没有档位概念，按格式给出。都拿不到时返回 null。
 *
 * 非 Composable：标题栏状态由 Player 监听器在非组合环境重建，这里用 [Context] 取文案。
 */
internal fun MediaItem.resolvePlaybackQualityLabel(context: Context): String? {
    val extras = mediaMetadata.extras
    val level = extras?.getString(OnlinePlaybackLevelExtraKey)?.takeIf(String::isNotBlank)
    val bitrate = extras?.getLong(OnlinePlaybackBitrateExtraKey)?.takeIf { it > 0L }
    val levelLabel = level?.let(::playbackLevelLabel)?.let(context::getString)
    if (levelLabel != null) {
        return listOfNotNull(levelLabel, bitrate?.formatBitrateKbps()).joinToString(" · ")
    }
    // 本地条目：按文件格式给出（复用歌曲行的音质角标判据），无码率信息。
    val formatLabel = when (
        extras?.getString(LocalAudioLibrary.AudioQualityBadgeExtraKey)
    ) {
        "flac" -> "FLAC"
        "ape" -> "APE"
        "wav" -> "WAV"
        "aiff" -> "AIFF"
        "alac" -> "ALAC"
        else -> null
    }
    return formatLabel?.let { label ->
        listOfNotNull(label, bitrate?.formatBitrateKbps()).joinToString(" · ")
    }
}

private fun Long.formatBitrateKbps(): String {
    return "${this / 1000L}kbps"
}

/** 分享纯文本（邀请链接、歌曲链接等），失败返回 false。 */
internal fun Context.tryShareText(text: String): Boolean {
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    return runCatching {
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
    }
        .isSuccess
}

/** 分享在线歌曲链接（https://music.163.com/song?id=…）；非在线条目返回 false。 */
internal fun Context.tryShareOnlineSong(mediaItem: MediaItem): Boolean {
    val identity = mediaItem.onlineIdentityOrNull() ?: return false
    val link = "https://music.163.com/song?id=${identity.trackId}"
    val title = mediaItem.mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
    return tryShareText(if (title != null) "$title\n$link" else link)
}

internal fun Context.tryShareAudio(mediaItem: MediaItem): Boolean {
    val shareUri =
        mediaItem.localConfiguration?.uri?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
            ?: return false
    val title =
        mediaItem.mediaMetadata.title?.toString()?.takeIf(String::isNotBlank)
            ?: getString(R.string.unknown_song_title)
    val mimeType =
        sequenceOf(
                mediaItem.localConfiguration?.mimeType,
                runCatching { contentResolver.getType(shareUri) }.getOrNull(),
            )
            .mapNotNull { candidate ->
                candidate?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isShareableAudioMimeType)
            }
            .firstOrNull() ?: DefaultAudioShareMimeType
    val sendIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            clipData = ClipData.newRawUri(title, shareUri)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    val chooserIntent =
        Intent.createChooser(sendIntent, getString(R.string.share)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching {
        startActivity(chooserIntent)
    }
        .isSuccess
}

private fun Uri.isMediaStoreUri(): Boolean {
    return scheme == ContentResolver.SCHEME_CONTENT && authority == MediaStore.AUTHORITY
}

private fun String.isShareableAudioMimeType(): Boolean {
    return startsWith("audio/") || this in ShareableApplicationAudioMimeTypes
}

internal fun nextPlaybackRepeatMode(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }
}

@DrawableRes
internal fun playbackRepeatButtonRes(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> R.drawable.btn_playing_repeat_on
        Player.REPEAT_MODE_ALL -> R.drawable.btn_playing_cycle_on
        else -> R.drawable.btn_playing_cycle_off
    }
}

internal fun repeatContentDescriptionRes(repeatMode: Int): Int {
    return when (repeatMode) {
        Player.REPEAT_MODE_ONE -> R.string.repeat_single
        Player.REPEAT_MODE_ALL -> R.string.repeat_all
        else -> R.string.repeat_none
    }
}

internal fun repeatToastRes(repeatMode: Int): Int = repeatContentDescriptionRes(repeatMode)

internal fun shuffleToastRes(shuffleEnabled: Boolean): Int {
    return if (shuffleEnabled) {
        R.string.shuffle_on
    } else {
        R.string.shuffle_off
    }
}

internal fun Context.musicStreamVolumeFraction(): Float {
    val audioManager = getSystemService(AudioManager::class.java) ?: return 1f
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(0)
    return (currentVolume.toFloat() / maxVolume.toFloat()).coerceIn(0f, 1f)
}

internal fun Context.setMusicStreamVolumeFraction(value: Float) {
    val audioManager = getSystemService(AudioManager::class.java) ?: return
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val targetVolume =
        (value.coerceIn(0f, 1f) * maxVolume.toFloat()).roundToInt().coerceIn(0, maxVolume)
    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
}

internal fun Context.toast(stringRes: Int) {
    Toast.makeText(this, getString(stringRes), Toast.LENGTH_SHORT).show()
}

internal fun formatPlaybackTime(positionMs: Long): String {
    val totalSeconds = (positionMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    val secondsText = if (seconds < 10L) "0$seconds" else seconds.toString()
    return if (hours > 0L) {
        val minutesText = if (minutes < 10L) "0$minutes" else minutes.toString()
        "$hours:$minutesText:$secondsText"
    } else {
        "${totalSeconds / 60L}:$secondsText"
    }
}

internal fun fractionFromPosition(positionX: Float, trackWidthPx: Int): Float {
    if (trackWidthPx <= 0) return 0f
    return (positionX / trackWidthPx.toFloat()).coerceIn(0f, 1f)
}

private const val DefaultAudioShareMimeType = "audio/*"

private val ShareableApplicationAudioMimeTypes =
    setOf(
        "application/ogg",
        "application/x-ogg",
        "application/itunes",
    )
