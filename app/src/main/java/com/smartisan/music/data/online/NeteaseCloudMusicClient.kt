package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.settings.NeteaseAudioQuality
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

internal const val ArtistAlbumPageSize = 50
private const val PlaylistSongDetailBatchSize = 300
private const val PlaylistSongDetailParallelism = 4
internal const val PlaylistDetailSubscriberCount = 8
private const val HttpTimeoutMs = 15_000
private const val UserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/**
 * NetEase 云音乐裸 HTTP 客户端：会话 Cookie 管理、请求签名发送与登录态重试。
 * 各业务端点按域拆在同包扩展函数文件里（browse/account/playback），
 * 响应 JSON→DTO 解析拆在 [NeteaseClientParsing] 顶层函数里。
 */
internal class NeteaseCloudMusicClient(
    private val cookieProvider: () -> Map<String, String> = { emptyMap() },
    internal val playbackQualityProvider: suspend () -> NeteaseAudioQuality = { NeteaseAudioQuality.ExHigh },
) {
    private val sessionCookieLock = Any()
    private val sessionCookies = linkedMapOf<String, String>()

    suspend fun getSongs(trackIds: List<String>): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val ids = trackIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (ids.isEmpty()) {
            return@withContext emptyList()
        }
        val response = parseNeteaseApiResponse(requestSongDetails(ids))
        response.optJSONArray("songs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseSong)
            .orEmpty()
    }

    internal suspend fun fetchSongDetails(trackIds: List<String>): List<OnlineTrack> = coroutineScope {
        val ids = trackIds
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (ids.isEmpty()) {
            return@coroutineScope emptyList()
        }
        val results = mutableListOf<Pair<Int, List<OnlineTrack>>>()
        ids.chunked(PlaylistSongDetailBatchSize)
            .mapIndexed { index, chunk -> index to chunk }
            .chunked(PlaylistSongDetailParallelism)
            .forEach { window ->
                results += window
                    .map { (index, chunk) ->
                        async(AppDispatchers.IO) {
                            index to getSongs(chunk)
                        }
                    }
                    .awaitAll()
            }
        results
            .sortedBy { (index, _) -> index }
            .flatMap { (_, tracks) -> tracks }
    }

    internal fun requestLyricsWithSessionRetry(request: () -> String): String {
        var response = runSuspendCatching { request() }.getOrNull() ?: ""
        // 已登录且接口返回 code=301（登录态/csrf 过期）时，预热会话后重试一次。
        // 注意网易云返回的是 HTTP 200 + JSON code=301，不会抛异常，必须解析响应体判断。
        if (hasLogin() && responseJsonRequiresLogin(response)) {
            ensureWeapiSession()
            response = runSuspendCatching { request() }.getOrNull() ?: ""
        }
        return response
    }

    internal fun resolveOuterPlaybackUrl(trackId: String): OnlinePlaybackUrl? {
        val url = "https://music.163.com/song/media/outer/url?id=${trackId.urlEncoded()}.mp3"
        val connection = openConnection(url, followRedirects = false).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=0-0")
        }
        return connection.useResponse {
            val code = responseCode
            if (code !in 300..399) {
                return@useResponse null
            }
            val location = getHeaderField("Location")?.takeIf(String::isNotBlank)
                ?: return@useResponse null
            val playbackUrl = location.normalizedPlayableUrl()
                ?: return@useResponse null
            OnlinePlaybackUrl(
                url = playbackUrl,
                mimeType = "audio/mpeg",
            )
        }
    }

    internal fun readText(url: String): String {
        val connection = openConnection(url, followRedirects = true)
        return connection.useResponse {
            val code = responseCode
            if (code !in 200..299) {
                throw IOException("NetEase request failed: HTTP $code")
            }
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun ensureWeapiSession() {
        runSuspendCatching {
            readText("https://music.163.com/")
        }
    }

    internal fun callEApi(
        path: String,
        params: Map<String, String>,
        host: String = "interface.music.163.com",
        anonymous: Boolean = false,
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val eapiPath = "/eapi$normalizedPath"
        val apiPath = "/api$normalizedPath"
        val url = "https://$host$eapiPath"
        val encryptedParams = NeteaseCrypto.encryptEApiParams(apiPath, params.toJsonObjectString())
        val body = "params=${encryptedParams.urlEncoded()}"
        val connection = openConnection(url, followRedirects = true, anonymous = anonymous).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return connection.useResponse {
            outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = responseCode
            if (code !in 200..299) {
                throw IOException("NetEase EAPI request failed: HTTP $code")
            }
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    internal fun callWeApi(
        path: String,
        params: Map<String, String>,
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val csrf = effectiveCookies()["__csrf"].orEmpty()
        val url = "https://music.163.com/weapi$normalizedPath?csrf_token=${csrf.urlEncoded()}"
        val encryptedParams = NeteaseCrypto.encryptWeApiParams(params.toJsonObjectString())
        val body = encryptedParams.entries.joinToString("&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }
        val connection = openConnection(url, followRedirects = true).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return connection.useResponse {
            outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            val code = responseCode
            if (code !in 200..299) {
                throw IOException("NetEase WEAPI request failed: HTTP $code")
            }
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun requestSongDetails(ids: List<String>): String {
        require(ids.isNotEmpty()) { "ids must not be empty" }
        val detailParam = ids.joinToString(
            separator = ",",
            prefix = "[",
            postfix = "]",
        ) { id -> """{"id":$id}""" }
        return callWeApi(
            path = "/v3/song/detail",
            params = mapOf(
                "c" to detailParam,
                "ids" to ids.joinToString(prefix = "[", postfix = "]"),
            ),
        )
    }

    /**
     * [anonymous] 为真时**不发已持久化的账号 Cookie**（MUSIC_U 等），但仍带上本次会话新产生的
     * Cookie（如取 unikey 时服务端下发的 NMTID）。
     *
     * 登录类接口必须这样：带上已登录的 MUSIC_U 会被服务端按「已登录」拒绝
     * （扫码轮询返回 `code=400 "device has login success"`）；而完全不发 Cookie 又会让
     * 取 key 与轮询分属两个会话，服务端无法关联同一扫码流程，容易被判环境异常。
     */
    private fun openConnection(
        url: String,
        followRedirects: Boolean,
        anonymous: Boolean = false,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HttpTimeoutMs
            readTimeout = HttpTimeoutMs
            instanceFollowRedirects = followRedirects
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            setRequestProperty("Referer", "https://music.163.com/")
            setRequestProperty("User-Agent", UserAgent)
            val cookieHeader = if (anonymous) buildSessionOnlyCookieHeader() else buildCookieHeader()
            cookieHeader.takeIf(String::isNotBlank)?.let { header ->
                setRequestProperty("Cookie", header)
            }
        }
    }

    /** 只含本次会话 Cookie 的请求头：登录流程用，避开已持久化账号态。 */
    private fun buildSessionOnlyCookieHeader(): String {
        val cookies = linkedMapOf<String, String>()
        synchronized(sessionCookieLock) {
            sessionCookies.forEach { (key, value) -> cookies[key] = value }
        }
        cookies.putIfAbsent("os", "pc")
        cookies.putIfAbsent("appver", "8.10.35")
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    internal fun requestPlaybackUrlWithSessionRetry(
        originalDurationMs: Long,
        request: () -> String,
    ): NeteasePlaybackParseResult {
        var result = tryParsePlaybackUrlResponse(originalDurationMs, request)
        if (
            hasLogin() &&
            (
                result.status == NeteasePlaybackParseStatus.RequiresLogin ||
                    result.status == NeteasePlaybackParseStatus.Preview
                )
        ) {
            ensureWeapiSession()
            result = tryParsePlaybackUrlResponse(originalDurationMs, request)
        }
        return result
    }

    private fun tryParsePlaybackUrlResponse(
        originalDurationMs: Long,
        request: () -> String,
    ): NeteasePlaybackParseResult {
        return runSuspendCatching {
            parseNeteasePlaybackUrlResponse(
                response = request(),
                originalDurationMs = originalDurationMs,
            )
        }.getOrDefault(NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Unavailable))
    }

    internal fun requestAccountActionWithSessionRetry(
        request: () -> String,
    ): NeteaseAccountActionResult {
        var result = tryParseAccountActionResponse(request)
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = tryParseAccountActionResponse(request)
        }
        return result
    }

    private fun tryParseAccountActionResponse(
        request: () -> String,
    ): NeteaseAccountActionResult {
        return runSuspendCatching {
            parseNeteaseAccountActionResponse(request())
        }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
    }

    internal fun requestLikedTrackIdsWithSessionRetry(
        request: () -> String,
    ): NeteaseLikedTrackIdsResult {
        var result = tryParseLikedTrackIdsResponse(request)
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = tryParseLikedTrackIdsResponse(request)
        }
        return result
    }

    private fun tryParseLikedTrackIdsResponse(
        request: () -> String,
    ): NeteaseLikedTrackIdsResult {
        return runSuspendCatching {
            parseNeteaseLikedTrackIdsResponse(request())
        }.getOrDefault(NeteaseLikedTrackIdsResult(NeteaseAccountActionStatus.Failed))
    }

    internal fun requestDailyRecommendedTracksWithSessionRetry(
        request: () -> String,
    ): NeteaseDailyRecommendedTracksResult {
        var result = tryParseDailyRecommendedTracksResponse(request)
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = tryParseDailyRecommendedTracksResponse(request)
        }
        return result
    }

    internal fun requestDailyStyleSongsWithSessionRetry(
        request: () -> String,
    ): NeteaseDailyStyleHomeResult {
        var result = runSuspendCatching {
            parseNeteaseDailyStyleHomeResponse(request())
        }.getOrDefault(NeteaseDailyStyleHomeResult(NeteaseAccountActionStatus.Failed))
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = runSuspendCatching {
                parseNeteaseDailyStyleHomeResponse(request())
            }.getOrDefault(NeteaseDailyStyleHomeResult(NeteaseAccountActionStatus.Failed))
        }
        return result
    }

    private fun tryParseDailyRecommendedTracksResponse(
        request: () -> String,
    ): NeteaseDailyRecommendedTracksResult {
        return runSuspendCatching {
            parseNeteaseDailyRecommendedTracksResponse(request())
        }.getOrDefault(NeteaseDailyRecommendedTracksResult(NeteaseAccountActionStatus.Failed))
    }

    internal fun requestPlaylistCreateWithSessionRetry(
        request: () -> String,
    ): OnlineAccountPlaylistCreateResult {
        var result = tryParsePlaylistCreateResponse(request)
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = tryParsePlaylistCreateResponse(request)
        }
        return result
    }

    private fun tryParsePlaylistCreateResponse(
        request: () -> String,
    ): OnlineAccountPlaylistCreateResult {
        return runSuspendCatching {
            parseNeteasePlaylistCreateResponse(request())
        }.getOrDefault(OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed))
    }

    /**
     * 通用的登录态读接口 session retry：首次请求若返回 code=301（需重新登录态/csrf）且已登录，
     * 则 ensureWeapiSession 预热后重试一次。适用于 getCurrentUserProfile/getUserPlaylists/
     * getUserAlbums/getUserRadios 等没有专用 Result 类型的读接口。
     */
    internal fun requestWithLoginRetry(request: () -> String): String {
        var response = runSuspendCatching { request() }.getOrNull() ?: ""
        if (hasLogin() && responseJsonRequiresLogin(response)) {
            ensureWeapiSession()
            response = runSuspendCatching { request() }.getOrNull() ?: ""
        }
        return response
    }

    private fun responseJsonRequiresLogin(response: String): Boolean {
        if (response.isBlank()) {
            return false
        }
        val code = runCatching { JSONObject(response).optInt("code", 0) }.getOrDefault(0)
        return code == 301
    }

    internal fun hasLogin(): Boolean {
        return !effectiveCookies()[NeteaseLoginCookieName].isNullOrBlank()
    }

    /**
     * 本次会话收到的响应 Cookie 快照。
     *
     * 登录类端点（手机号 / 扫码）成功后由 Set-Cookie 下发 MUSIC_U，
     * 调用方取这份快照写进 [NeteaseAuthStore] 才完成登录持久化。
     * 不走 [effectiveCookies]：那里对 session 的 MUSIC_U 有「已持久化才生效」的过滤，
     * 首次登录时会把刚拿到的凭据挡掉。
     */
    internal fun sessionCookieSnapshot(): Map<String, String> {
        return synchronized(sessionCookieLock) { sessionCookies.toMap() }
    }

    /**
     * 清空本次会话累积的响应 Cookie。登录流程开始时调用：
     * 否则换号登录时旧账号的会话 Cookie 会混进新手快照被一起落盘。
     */
    internal fun clearSessionCookies() {
        synchronized(sessionCookieLock) { sessionCookies.clear() }
    }

    private fun effectiveCookies(): Map<String, String> {
        val currentSessionCookies = synchronized(sessionCookieLock) {
            sessionCookies.toMap()
        }
        return buildNeteaseEffectiveCookies(
            persistedCookies = cookieProvider(),
            sessionCookies = currentSessionCookies,
        )
    }

    private fun buildCookieHeader(): String {
        val cookies = linkedMapOf<String, String>()
        effectiveCookies().forEach { (key, value) ->
            cookies[key] = value
        }
        cookies.putIfAbsent("os", "pc")
        cookies.putIfAbsent("appver", "8.10.35")
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private inline fun <T> HttpURLConnection.useResponse(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            storeResponseCookies()
            disconnect()
        }
    }

    private fun HttpURLConnection.storeResponseCookies() {
        val setCookieHeaders = headerFields
            ?.filterKeys { key -> key.equals("Set-Cookie", ignoreCase = true) }
            ?.values
            ?.flatten()
            .orEmpty()
        if (setCookieHeaders.isEmpty()) {
            return
        }
        synchronized(sessionCookieLock) {
            setCookieHeaders
                .mapNotNull(::parseSetCookieHeader)
                .forEach { (key, value) -> sessionCookies[key] = value }
        }
    }
}

private fun Map<String, String>.toJsonObjectString(): String {
    return JSONObject().also { root ->
        forEach { (key, value) -> root.put(key, value) }
    }.toString()
}

private fun parseSetCookieHeader(header: String): Pair<String, String>? {
    val firstPart = header.substringBefore(';').trim()
    if ('=' !in firstPart) {
        return null
    }
    val key = firstPart.substringBefore('=').trim()
    val value = firstPart.substringAfter('=').trim()
    if (key.isBlank() || value.isBlank() || value.any(Char::isISOControl)) {
        return null
    }
    return key to value
}
