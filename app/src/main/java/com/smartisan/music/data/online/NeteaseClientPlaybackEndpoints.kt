package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.settings.NeteaseAudioQuality
import com.smartisan.music.data.settings.fallbackCandidates
import kotlinx.coroutines.withContext

/** 播放地址解析域端点：试听地址获取与歌词拉取。 */

internal suspend fun NeteaseCloudMusicClient.getPlaybackUrl(
    trackId: String,
    originalDurationMs: Long = 0L,
    requestedQuality: NeteaseAudioQuality? = null,
): OnlinePlaybackUrl? {
    return getPlaybackUrlResult(
        trackId = trackId,
        originalDurationMs = originalDurationMs,
        requestedQuality = requestedQuality,
    ).playbackUrl
}

internal suspend fun NeteaseCloudMusicClient.getPlaybackUrlResult(
    trackId: String,
    originalDurationMs: Long = 0L,
    requestedQuality: NeteaseAudioQuality? = null,
): NeteasePlaybackParseResult = withContext(AppDispatchers.IO) {
    val id = trackId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Unavailable)
    val idsJson = "[$id]"
    var restrictedPlaybackReturned = false
    var previewPlaybackReturned = false
    val targetQuality = requestedQuality ?: playbackQualityProvider()
    for (quality in targetQuality.fallbackCandidates()) {
        val eapiResult = requestPlaybackUrlWithSessionRetry(originalDurationMs) {
            callEApi(
                path = "/song/enhance/player/url/v1",
                params = mapOf(
                    "ids" to idsJson,
                    "level" to quality.level,
                    "encodeType" to quality.encodeType,
                ),
            )
        }
        when (eapiResult.status) {
            NeteasePlaybackParseStatus.Success -> return@withContext eapiResult
            NeteasePlaybackParseStatus.Preview -> {
                restrictedPlaybackReturned = true
                previewPlaybackReturned = true
            }
            NeteasePlaybackParseStatus.RequiresLogin -> restrictedPlaybackReturned = true
            NeteasePlaybackParseStatus.Unavailable -> Unit
        }
    }
    if (!restrictedPlaybackReturned && !hasLogin()) {
        resolveOuterPlaybackUrl(id)?.let { playbackUrl ->
            return@withContext NeteasePlaybackParseResult(
                status = NeteasePlaybackParseStatus.Success,
                playbackUrl = playbackUrl,
            )
        }
    }
    NeteasePlaybackParseResult(
        status = when {
            previewPlaybackReturned -> NeteasePlaybackParseStatus.Preview
            restrictedPlaybackReturned -> NeteasePlaybackParseStatus.RequiresLogin
            else -> NeteasePlaybackParseStatus.Unavailable
        },
    )
}

internal suspend fun NeteaseCloudMusicClient.getLyrics(trackId: String): OnlineLyrics = withContext(AppDispatchers.IO) {
    val id = trackId.trim().takeIf(String::isNotEmpty) ?: return@withContext OnlineLyrics(null, null)
    // 使用 eapi /song/lyric/v1（与官方 PC 客户端一致），比旧版明文 /api/song/lyric 更稳定，
    // 对版权/会员歌词返回更完整。参数对齐 NeriPlayer：lv=原词, tv=翻译, yv=逐字歌词, ytv=逐字翻译。
    val params = mapOf(
        "id" to id,
        "cp" to "false",
        "lv" to "0",
        "tv" to "1",
        "rv" to "0",
        "yv" to "1",
        "ytv" to "1",
        "yrv" to "0",
    )
    val response = requestLyricsWithSessionRetry { callEApi("/song/lyric/v1", params) }
    parseLyricsResponse(response)
}
