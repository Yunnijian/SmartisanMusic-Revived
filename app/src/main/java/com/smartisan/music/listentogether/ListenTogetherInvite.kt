package com.smartisan.music.listentogether

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「一起听」邀请链接：官方格式为
 * `https://st.music.163.com/listen-together/share/?songId=…&roomId=…&inviterId=…`，
 * 官方 App 分享出去的是其短链服务生成的 `https://163cn.tv/xxxx`，后者 302 跳到上面的长链。
 *
 * 解析与构造用纯字符串/`java.net` 实现，避免依赖 `android.net.Uri`，便于 JVM 单测。
 */

internal const val ListenTogetherInviteHost = "st.music.163.com"
internal const val ListenTogetherInvitePath = "/listen-together/share"
internal const val ListenTogetherShortHost = "163cn.tv"

internal data class ListenTogetherInvite(
    val roomId: String,
    val inviterId: String,
)

internal fun buildListenTogetherInviteUrl(
    roomId: String,
    inviterId: String,
    songId: String? = null,
): String {
    val sb = StringBuilder("https://")
        .append(ListenTogetherInviteHost)
        .append(ListenTogetherInvitePath)
        .append("/?roomId=")
        .append(encodeParam(roomId))
        .append("&inviterId=")
        .append(encodeParam(inviterId))
    if (songId != null) {
        sb.append("&songId=").append(encodeParam(songId))
    }
    return sb.toString()
}

internal fun parseListenTogetherInviteParams(url: String): ListenTogetherInvite? {
    val question = url.indexOf('?')
    if (question < 0) {
        return null
    }
    val params = url.substring(question + 1)
        .split('&')
        .mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) {
                return@mapNotNull null
            }
            part.substring(0, eq) to decodeParam(part.substring(eq + 1))
        }
        .toMap()
    val roomId = params["roomId"]?.takeIf(String::isNotBlank) ?: return null
    val inviterId = params["inviterId"]?.takeIf(String::isNotBlank) ?: return null
    return ListenTogetherInvite(roomId = roomId, inviterId = inviterId)
}

/** 短链（163cn.tv）是否仍需解析出真实参数。 */
internal fun isListenTogetherShortLink(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
    return host.equals(ListenTogetherShortHost, ignoreCase = true)
}

/** 跟随 163cn.tv 短链的 302，取最终长链；非短链直接原样返回。 */
internal suspend fun resolveListenTogetherInviteUrl(url: String): String {
    if (!isListenTogetherShortLink(url)) {
        return url
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            var current = url
            repeat(3) {
                val connection = URL(current).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.requestMethod = "GET"
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?.takeIf(String::isNotBlank)
                    if (location != null) {
                        current = location
                        return@repeat
                    }
                }
            }
            current
        }.getOrDefault(url)
    }
}

private fun encodeParam(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun decodeParam(value: String): String = URLDecoder.decode(value, "UTF-8")
