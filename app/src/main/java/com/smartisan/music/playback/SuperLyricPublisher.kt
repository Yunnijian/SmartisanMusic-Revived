@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import com.smartisan.music.data.online.onlineIdentityOrNull

/** 位置粒度：时间轴在这段跨度内不变化则视为同一帧（约 4 帧/秒）。
 *  逐字进度因此也会消隐，但桌面歌词通常不依赖逐字逐帧更新。 */
internal const val SuperLyricPublishGranularityMs = 250L

/**
 * SuperLyricApi 发布器：把当前播放的实时歌词（当前行、逐字、翻译、歌曲信息）推给系统歌词服务，
 * 供 SuperLyric 等 Xposed 模块渲染桌面/悬浮歌词。
 *
 * 只在线程安全地做「映射 + 去重 + 发送」，状态读取与触发由播放服务驱动。
 * 无 SuperLyric 系统服务时所有调用静默失败，不影响播放。
 */
internal class SuperLyricPublisher {

    /** 上次实际发送的状态指纹，避免重复行/重复状态反复广播。 */
    private var lastFingerprint: String = ""

    /** 注册为发布者；服务不存在（未装 Xposed 模块）时静默跳过。 */
    fun tryRegister() {
        runCatching {
            if (SuperLyricHelper.isAvailable()) {
                SuperLyricHelper.registerPublisher()
                SuperLyricHelper.setSystemPlayStateListenerEnabled(false)
            }
        }
    }

    fun tryUnregister() {
        runCatching {
            if (SuperLyricHelper.isPublisherRegistered()) {
                SuperLyricHelper.unregisterPublisher()
            }
        }
    }

    /**
     * 发送一帧歌词状态。
     *
     * @param mediaItem 当前媒体项；null 表示无可播放内容，广播停止。
     * @param lyrics 当前歌曲的歌词（可为 null，此时仍广播歌曲信息供桌面显示）。
     * @param positionMs 当前播放位置（毫秒）。
     * @param isPlaying 是否正在播放；false 时广播停止事件。
     */
    fun publish(
        mediaItem: MediaItem?,
        lyrics: EmbeddedLyrics?,
        positionMs: Long,
        isPlaying: Boolean,
    ) {
        if (mediaItem == null || !isPlaying) {
            sendStopSafe(mediaItem)
            return
        }
        val metadata = mediaItem.mediaMetadata
        val title = (metadata.title ?: metadata.displayTitle)?.toString() ?: ""
        val artist = metadata.artist?.toString().orEmpty()
        val album = metadata.albumTitle?.toString().orEmpty()

        val currentLine = currentTimedLine(lyrics, positionMs)
        val fingerprint =
            buildString {
                append(mediaItem.mediaId)
                append('|')
                append(positionMs / SuperLyricPublishGranularityMs)
                append('|')
                append(isPlaying)
            }
        if (fingerprint == lastFingerprint) {
            return
        }
        lastFingerprint = fingerprint

        val data = SuperLyricData()
            .setTitle(title)
            .setArtist(artist)
            .setAlbum(album)
            .setExtra(filledExtra(mediaItem))
        if (currentLine != null) {
            data.setLyric(currentLine.line)
            currentLine.translation?.let { data.setTranslation(it) }
        }
        runCatching { SuperLyricHelper.sendLyric(data) }
    }

    /** 播放停止 / 切到无媒体项时的广播。 */
    private fun sendStopSafe(mediaItem: MediaItem?) {
        val key = mediaItem?.mediaId ?: "stopped"
        if (key == lastFingerprint) {
            return
        }
        lastFingerprint = key
        runCatching { SuperLyricHelper.sendStop(SuperLyricData()) }
    }

    /** 定位“当前歌词行”，并映射为 SuperLyricApi 的行结构（含逐字与翻译）。 */
    private fun currentTimedLine(
        lyrics: EmbeddedLyrics?,
        positionMs: Long,
    ): MappedLyricLine? {
        val lines = lyrics?.lines ?: return null
        val activeIndex = lines.indexOfLast { (it.timestampMs ?: Long.MAX_VALUE) <= positionMs }
        if (activeIndex < 0) {
            return null
        }
        val line = lines[activeIndex]
        val startMs = line.timestampMs ?: positionMs
        val endMs = lines.getOrNull(activeIndex + 1)?.timestampMs ?: startMs
        val words =
            line.tokens
                .filter { token -> token.text.isNotBlank() }
                .map { token ->
                    SuperLyricWord(
                        token.text,
                        token.timestampMs,
                        token.endTimestampMs ?: token.timestampMs,
                    )
                }
                .toTypedArray()
                .takeIf { it.isNotEmpty() }
        val mapped =
            SuperLyricLine(
                line.text,
                words,
                startMs,
                endMs,
            )
        val translation = line.translation?.takeIf(String::isNotBlank)?.let { text ->
            SuperLyricLine(text, startMs, endMs)
        }
        return MappedLyricLine(line = mapped, translation = translation)
    }

    /** extra 携带在线条目身份，供接收端按 trackId 关联封面等；本地条目留空。 */
    private fun filledExtra(mediaItem: MediaItem): Bundle {
        val identity = mediaItem.onlineIdentityOrNull() ?: return Bundle.EMPTY
        return Bundle().apply {
            putString(ExtraKeyOnlineSource, identity.source)
            putString(ExtraKeyOnlineTrackId, identity.trackId)
        }
    }

    private data class MappedLyricLine(
        val line: SuperLyricLine,
        val translation: SuperLyricLine?,
    )

    private companion object {
        internal const val ExtraKeyOnlineSource = "smartisan.online.source"
        internal const val ExtraKeyOnlineTrackId = "smartisan.online.trackId"
    }
}