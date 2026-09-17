package com.smartisan.music.data.online

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.smartisan.music.data.settings.NeteaseVipLevel
import com.smartisan.music.playback.LocalAudioLibrary
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale

private const val CompletePlaylistTrackRequestLimit = 100_000
private const val MinSongDurationForPreviewDetectionMs = 60_000L
private const val MaxKnownPreviewDurationMs = 45_000L
private const val MaxPreviewDurationRatio = 0.5
private const val NeteaseApiSuccessCode = 200
private const val NeteaseApiLoginRequiredCode = 301

/** 网易云风控拦截的业务码（HTTP 仍为 200）。 */
private val NeteaseApiRiskControlCodes = setOf(460)

/** 业务码失败分类，供上层区分「请先登录」/「风控拦截」/通用失败。 */
internal enum class NeteaseApiFailureReason {
    RequiresLogin,
    RiskControl,
    Unknown,
}

internal class NeteaseApiException(
    val code: Int,
    val reason: NeteaseApiFailureReason,
) : IOException("NetEase API failed: code=$code ($reason)")

/**
 * 解析并校验响应体的业务码。
 *
 * 网易云的登录态失效与风控都是 HTTP 200 + JSON `code`（如 301 / 460），只校验 HTTP 状态会把
 * 这类响应当成空结果，上层只能显示空白列表。缺省 `code` 时按成功处理：部分老接口不返回该字段。
 */
internal fun parseNeteaseApiResponse(response: String): JSONObject {
    return requireNeteaseApiSuccess(JSONObject(response))
}

internal fun requireNeteaseApiSuccess(root: JSONObject): JSONObject {
    val code = root.optInt("code", NeteaseApiSuccessCode)
    if (code != NeteaseApiSuccessCode) {
        throw NeteaseApiException(code, neteaseApiFailureReason(code))
    }
    return root
}

private fun neteaseApiFailureReason(code: Int): NeteaseApiFailureReason {
    return when (code) {
        NeteaseApiLoginRequiredCode -> NeteaseApiFailureReason.RequiresLogin
        in NeteaseApiRiskControlCodes -> NeteaseApiFailureReason.RiskControl
        else -> NeteaseApiFailureReason.Unknown
    }
}

internal fun parseNeteaseAccountProfileResponse(response: String): NeteaseAccountProfile? {
    val root = requireNeteaseApiSuccess(JSONObject(response))
    val profile = root.optJSONObject("profile") ?: return null
    return parseNeteaseAccountProfileJson(profile.toString())
}

internal fun parseNeteaseUserPlaylistsResponse(response: String): List<NeteasePlaylistSummary> {
    val root = requireNeteaseApiSuccess(JSONObject(response))
    return root.optJSONArray("playlist")
        ?.toJsonObjects()
        ?.mapNotNull(::parseNeteasePlaylistSummary)
        .orEmpty()
}

internal fun parseNeteaseAccountAlbumsResponse(response: String): List<OnlineAlbum> {
    val root = requireNeteaseApiSuccess(JSONObject(response))
    val albums = root.optJSONArray("playlist")
        ?: root.optJSONObject("data")
            ?.optJSONObject("mainCollectInfo")
            ?.optJSONObject("mineAllTabDto")
            ?.optJSONArray("dataList")
        ?: root.optJSONObject("data")?.optJSONArray("dataList")
        ?: root.optJSONArray("data")
        ?: return emptyList()
    return albums
        .toJsonObjects()
        .mapNotNull(::parseNeteaseAccountAlbumItem)
}

internal fun parseNeteaseAccountRadiosResponse(response: String): List<OnlineRadio> {
    val root = requireNeteaseApiSuccess(JSONObject(response))
    val radios = root.optJSONArray("djRadios")
        ?: root.optJSONObject("data")?.optJSONArray("djRadios")
        ?: root.optJSONObject("data")?.optJSONArray("radios")
        ?: root.optJSONArray("radios")
        ?: return emptyList()
    return radios
        .toJsonObjects()
        .mapNotNull(::parseNeteaseRadio)
}

internal fun parseNeteasePlaylistDetailResponse(response: String): NeteasePlaylistDetail {
    val root = requireNeteaseApiSuccess(JSONObject(response))
    val playlist = root.optJSONObject("playlist")
        ?: error("NetEase playlist detail response missing playlist")
    return NeteasePlaylistDetail(
        tracks = playlist.optJSONArray("tracks")
            ?.toJsonObjects()
            ?.mapNotNull(::parseNeteaseSong)
            .orEmpty(),
        trackIds = playlist.optPlaylistTrackIds(),
        trackCount = playlist.optInt("trackCount", 0).coerceAtLeast(0),
    )
}

internal fun OnlineTrack.toMediaItem(
    playbackUrl: String? = null,
    mimeType: String? = null,
    lyrics: OnlineLyrics? = null,
    playbackLevel: String? = null,
    playbackBr: Long? = null,
): MediaItem {
    val extras = Bundle().apply {
        putBoolean(OnlineTrackExtraKey, true)
        putString(OnlineProviderExtraKey, source)
        putString(OnlineSourceExtraKey, source)
        putString(OnlineTrackIdExtraKey, trackId)
        putString(LocalAudioLibrary.StableKeyExtraKey, mediaId)
        putString(LocalAudioLibrary.MediaIdExtraKey, mediaId)
        lyrics?.lyric?.takeIf(String::isNotBlank)?.let { lyric ->
            putString(OnlineLyricsExtraKey, lyric)
        }
        lyrics?.translatedLyric?.takeIf(String::isNotBlank)?.let { translatedLyric ->
            putString(OnlineTranslatedLyricsExtraKey, translatedLyric)
        }
        lyrics?.wordLyric?.takeIf(String::isNotBlank)?.let { wordLyric ->
            putString(OnlineWordLyricsExtraKey, wordLyric)
        }
        lyrics?.translatedWordLyric?.takeIf(String::isNotBlank)?.let { translatedWordLyric ->
            putString(OnlineTranslatedWordLyricsExtraKey, translatedWordLyric)
        }
        if (!playbackUrl.isNullOrBlank()) {
            putLong(OnlinePlaybackResolvedAtExtraKey, System.currentTimeMillis())
        }
        playbackLevel?.takeIf(String::isNotBlank)?.let { level ->
            putString(OnlinePlaybackLevelExtraKey, level)
        }
        playbackBr?.takeIf { bitrate -> bitrate > 0L }?.let { bitrate ->
            putLong(OnlinePlaybackBitrateExtraKey, bitrate)
        }
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setDisplayTitle(title)
        .setArtist(artist.takeIf(String::isNotBlank))
        .setSubtitle(artist.takeIf(String::isNotBlank))
        .setAlbumTitle(album?.takeIf(String::isNotBlank))
        .setDurationMs(durationMs)
        .setArtworkUri(artworkUrl?.normalizedArtworkUrl()?.let(Uri::parse))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(extras)
        .build()
    val cacheKey = OnlineTrackIdentity(source = source, trackId = trackId)
        .toOnlinePlaybackCacheKey()
    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(metadata)
        .apply {
            playbackUrl?.takeIf(String::isNotBlank)?.let { url ->
                setUri(Uri.parse(url))
                setMimeType(mimeType ?: "audio/mpeg")
                setCustomCacheKey(cacheKey)
            }
        }
        .build()
}

internal fun MediaItem.isOnlineMediaItem(): Boolean {
    return mediaMetadata.extras?.getBoolean(OnlineTrackExtraKey, false) == true ||
        mediaId.startsWith(OnlineMediaIdPrefix)
}

internal fun MediaItem.shouldRefreshOnlinePlaybackUrl(nowMs: Long = System.currentTimeMillis()): Boolean {
    val resolvedAtMs = mediaMetadata.extras
        ?.getLong(OnlinePlaybackResolvedAtExtraKey, 0L)
        ?: 0L
    return shouldRefreshOnlinePlaybackUrlState(
        isOnline = isOnlineMediaItem(),
        hasPlaybackUrl = localConfiguration?.uri != null,
        resolvedAtMs = resolvedAtMs,
        nowMs = nowMs,
    )
}

internal fun shouldRefreshOnlinePlaybackUrlState(
    isOnline: Boolean,
    hasPlaybackUrl: Boolean,
    resolvedAtMs: Long,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (!isOnline) {
        return false
    }
    if (!hasPlaybackUrl) {
        return true
    }
    return resolvedAtMs <= 0L || nowMs - resolvedAtMs > OnlinePlaybackUrlMaxAgeMs
}

internal fun MediaItem.onlineIdentityOrNull(): OnlineTrackIdentity? {
    val extras = mediaMetadata.extras
    val source = extras
        ?.getString(OnlineSourceExtraKey)
        ?.takeIf(String::isNotBlank)
    val trackId = extras
        ?.getString(OnlineTrackIdExtraKey)
        ?.takeIf(String::isNotBlank)
    if (source != null && trackId != null) {
        return OnlineTrackIdentity(source = source, trackId = trackId)
    }
    return mediaId.onlineTrackIdentityOrNull()
}

internal fun String.onlineTrackIdentityOrNull(): OnlineTrackIdentity? {
    if (!startsWith(OnlineMediaIdPrefix)) {
        return null
    }
    val parts = removePrefix(OnlineMediaIdPrefix).split(':', limit = 2)
    val source = parts.getOrNull(0)?.takeIf(String::isNotBlank) ?: return null
    val trackId = parts.getOrNull(1)?.takeIf(String::isNotBlank) ?: return null
    return OnlineTrackIdentity(source = source, trackId = trackId)
}

internal fun OnlineTrackIdentity.toOnlinePlaybackCacheKey(): String {
    return "$OnlineMediaIdPrefix$source:$trackId"
}

internal fun OnlineTrackIdentity.toOnlinePlaybackPlaceholderMediaItem(): MediaItem {
    return OnlineTrack(
        source = source,
        trackId = trackId,
        title = trackId,
        artist = "",
        album = null,
        durationMs = 0L,
        artworkUrl = null,
    )
        .toMediaItem()
        .withOnlinePlaybackPlaceholderUri()
}

internal fun buildOnlineMediaId(source: String, trackId: String): String {
    return "$OnlineMediaIdPrefix$source:$trackId"
}

internal fun MediaItem.toOnlineTrackFallback(identity: OnlineTrackIdentity): OnlineTrack? {
    val title = mediaMetadata.title?.toString()
        ?: mediaMetadata.displayTitle?.toString()
        ?: return null
    return OnlineTrack(
        source = identity.source,
        trackId = identity.trackId,
        title = title,
        artist = mediaMetadata.artist?.toString().orEmpty(),
        album = mediaMetadata.albumTitle?.toString(),
        durationMs = mediaMetadata.durationMs ?: 0L,
        artworkUrl = mediaMetadata.artworkUri?.toString(),
    )
}

private fun JSONObject.optArtworkUrl(): String? {
    return optNonBlankString("picUrl")
        ?: optNonBlankString("blurPicUrl")
        ?: optNonBlankString("img1v1Url")
}

internal fun JSONObject.optArtistArtworkUrl(): String? {
    return optNonBlankString("img1v1Url")
        ?: optNonBlankString("picUrl")
        ?: optNonBlankString("blurPicUrl")
}

internal fun parseNeteaseSong(song: JSONObject): OnlineTrack? {
    val id = song.optLong("id", 0L).takeIf { it > 0L }?.toString() ?: return null
    val album = song.optJSONObject("album") ?: song.optJSONObject("al")
    val artists = song.optJSONArray("artists") ?: song.optJSONArray("ar")
    val title = song.optNonBlankString("name") ?: return null
    val artist = artists
        ?.toJsonObjects()
        ?.mapNotNull { artist -> artist.optNonBlankString("name") }
        ?.takeIf(List<String>::isNotEmpty)
        ?.joinToString("/")
        ?: ""
    val duration = when {
        song.has("duration") -> song.optLong("duration", 0L)
        song.has("dt") -> song.optLong("dt", 0L)
        else -> 0L
    }
    return OnlineTrack(
        source = NeteaseSourceId,
        trackId = id,
        title = title,
        artist = artist,
        album = album?.optNonBlankString("name"),
        durationMs = duration.coerceAtLeast(0L),
        artworkUrl = album?.optArtworkUrl(),
    )
}

/** 会员权益包的有效期字段；无该包或已过期返回 null。 */
private fun JSONObject.vipPackageExpireTime(name: String): Long? {
    return optJSONObject(name)?.optLongOrNull("expireTime")?.takeIf { it > 0L }
}

/**
 * 解析 vip/info 响应，得出账号音质权益档次。
 *
 * 判定顺序与官方一致：`redplus` 有效即黑胶 SVIP（官方 isPlusVip），
 * 否则 `musicPackage` / `associator` 有效为黑胶 VIP；都没有则免费。
 * 用本地时间与 expireTime 比较，不依赖响应的 now 字段。
 */
internal fun parseNeteaseVipLevelResponse(
    response: String,
    nowMs: Long = System.currentTimeMillis(),
): NeteaseVipLevel? {
    val root = runCatching { JSONObject(response) }.getOrNull() ?: return null
    if (root.optInt("code", -1) != 200) {
        return null
    }
    val data = root.optJSONObject("data") ?: return null
    fun isActive(name: String): Boolean {
        val expireTime = data.vipPackageExpireTime(name) ?: return false
        return expireTime > nowMs
    }
    return when {
        isActive("redplus") -> NeteaseVipLevel.Svip
        isActive("musicPackage") || isActive("associator") -> NeteaseVipLevel.Vip
        else -> NeteaseVipLevel.Free
    }
}

internal fun parseNeteaseAccountProfileJson(profileJson: String): NeteaseAccountProfile? {
    val profile = JSONObject(profileJson)
    val userId = profile.optLong("userId", 0L)
    val nickname = profile.optNonBlankString("nickname")
    if (userId <= 0L || nickname == null) {
        return null
    }
    return NeteaseAccountProfile(
        userId = userId,
        nickname = nickname,
        avatarUrl = profile.optNonBlankString("avatarUrl"),
    )
}

private fun parseNeteasePlaylistSummary(playlist: JSONObject): NeteasePlaylistSummary? {
    val playlistId = playlist.optLong("id", 0L)
        .takeIf { id -> id > 0L }
        ?.toString()
        ?: return null
    val name = playlist.optNonBlankString("name") ?: return null
    return NeteasePlaylistSummary(
        playlistId = playlistId,
        name = name,
        trackCount = playlist.optInt("trackCount", 0).coerceAtLeast(0),
        specialType = playlist.optInt("specialType", 0),
        creatorUserId = playlist.optJSONObject("creator")
            ?.optLongOrNull("userId")
            ?.takeIf { userId -> userId > 0L },
        subscribed = playlist.optBoolean("subscribed", false),
    )
}

private fun parseNeteaseAccountAlbumItem(item: JSONObject): OnlineAlbum? {
    val dataInfo = item.optJSONObject("dataInfo")
    val album = dataInfo?.optJSONObject("data")
        ?: item.optJSONObject("album")
        ?: item
    val parsed = parseNeteaseAlbum(album) ?: return null
    val coverUrl = dataInfo?.optNonBlankString("picUrl")
        ?: parsed.artworkUrl
    return parsed.copy(
        artworkUrl = coverUrl?.normalizedPlayableUrl(),
    )
}

internal fun parseNeteaseAlbum(album: JSONObject): OnlineAlbum? {
    val id = album.optLong("id", 0L)
        .takeIf { albumId -> albumId > 0L }
        ?.toString()
        ?: album.optNonBlankString("idStr")
        ?: return null
    val title = album.optNonBlankString("name") ?: return null
    val artist = album.optJSONObject("artist")?.optNonBlankString("name")
        ?: album.optJSONArray("artists")
            ?.toJsonObjects()
            ?.mapNotNull { artist -> artist.optNonBlankString("name") }
            ?.takeIf(List<String>::isNotEmpty)
            ?.joinToString("/")
    return OnlineAlbum(
        provider = OnlineMusicProvider.Netease,
        albumId = id,
        title = title,
        artist = artist,
        artworkUrl = album.optArtworkUrl(),
        trackCount = album.optInt("size", 0).coerceAtLeast(0),
        publishTimeMs = album.optLong("publishTime", 0L).coerceAtLeast(0L),
    )
}

internal fun parseNeteaseRadio(radio: JSONObject): OnlineRadio? {
    val id = radio.optLong("id", 0L)
        .takeIf { radioId -> radioId > 0L }
        ?.toString()
        ?: return null
    val title = radio.optNonBlankString("name") ?: return null
    val dj = radio.optJSONObject("dj")
    val category = radio.optNonBlankString("category")
    val creator = dj?.optNonBlankString("nickname")
    return OnlineRadio(
        provider = OnlineMusicProvider.Netease,
        radioId = id,
        title = title,
        subtitle = radio.optNonBlankString("rcmdtext")
            ?: radio.optNonBlankString("copywriter")
            ?: creator
            ?: category,
        category = category,
        creator = creator,
        artworkUrl = radio.optNonBlankString("picUrl"),
        programCount = radio.optInt("programCount", 0).coerceAtLeast(0),
        playCount = radio.optDouble("playCount", 0.0).toLong().coerceAtLeast(0L),
    )
}

internal fun NeteasePlaylistSummary.trackFetchLimit(maxLimit: Int = Int.MAX_VALUE): Int {
    return playlistTrackFetchLimit(trackCount = trackCount, maxLimit = maxLimit)
}

internal fun OnlineAccountPlaylist.trackFetchLimit(): Int {
    return playlistTrackFetchLimit(trackCount = trackCount)
}

internal fun OnlinePlaylist.trackFetchLimit(): Int {
    return playlistTrackFetchLimit(trackCount = trackCount)
}

private fun playlistTrackFetchLimit(trackCount: Int, maxLimit: Int = Int.MAX_VALUE): Int {
    val normalizedMaxLimit = maxLimit.coerceAtLeast(1)
    val requestedLimit = if (trackCount > 0) {
        trackCount
    } else {
        CompletePlaylistTrackRequestLimit
    }
    return requestedLimit.coerceAtMost(normalizedMaxLimit).coerceAtLeast(1)
}

private fun JSONObject.optPlaylistTrackIds(): List<String> {
    return optJSONArray("trackIds")
        ?.toJsonObjects()
        ?.mapNotNull { track ->
            track.optLongOrNull("id")
                ?.takeIf { id -> id > 0L }
                ?.toString()
        }
        .orEmpty()
}

internal fun JSONObject.optNonBlankString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name)
        .takeIf(String::isNotBlank)
        ?.takeUnless { it.equals("null", ignoreCase = true) }
}

private fun JSONObject.hasNonNullValue(name: String): Boolean {
    return has(name) && !isNull(name)
}

private fun JSONObject.optIntOrNull(name: String): Int? {
    val value = opt(name)
    return when {
        value == null || value == JSONObject.NULL -> null
        value is Number -> value.toInt()
        value is String -> value.toIntOrNull()
        else -> null
    }
}

internal fun JSONObject.optLongOrNull(name: String): Long? {
    val value = opt(name)
    return when {
        value == null || value == JSONObject.NULL -> null
        value is Number -> value.toLong()
        value is String -> value.toLongOrNull()
        else -> null
    }
}

private fun JSONObject.optPlaybackDataObject(): JSONObject? {
    val data = opt("data")
    return when {
        data is JSONObject -> data
        data is JSONArray -> data.toJsonObjects().firstOrNull()
        else -> null
    }
}

internal fun parseNeteasePlaybackUrlResponse(
    response: String,
    originalDurationMs: Long,
): NeteasePlaybackParseResult {
    val root = JSONObject(response)
    if (root.optInt("code", -1) == 301) {
        return NeteasePlaybackParseResult(NeteasePlaybackParseStatus.RequiresLogin)
    }
    val item = root.optPlaybackDataObject()
        ?: return NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Unavailable)
    if (item.hasNonNullValue("freeTrialInfo")) {
        return NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Preview)
    }
    val returnedDurationMs = item.optLongOrNull("time")
        ?: item.optLongOrNull("duration")
    if (isNeteasePreviewDuration(returnedDurationMs, originalDurationMs)) {
        return NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Preview)
    }
    val dataCode = item.optInt("code", -1)
    val cannotListenReason = item.optJSONObject("freeTrialPrivilege")
        ?.optIntOrNull("cannotListenReason")
    val streamUrl = item.optNonBlankString("url")
        ?: return if (dataCode == 404 || cannotListenReason == 1 || item.optInt("fee", 0) > 0) {
            NeteasePlaybackParseResult(NeteasePlaybackParseStatus.RequiresLogin)
        } else {
            NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Unavailable)
        }
    val playableUrl = streamUrl.normalizedPlayableUrl()
        ?: return NeteasePlaybackParseResult(NeteasePlaybackParseStatus.Unavailable)
    return NeteasePlaybackParseResult(
        status = NeteasePlaybackParseStatus.Success,
        playbackUrl =
            OnlinePlaybackUrl(
                url = playableUrl,
                mimeType = item.optNonBlankString("type")?.toAudioMimeType(),
                // 服务端回显的实际档位/码率：请求与权益不匹配时会静默降级，只有这里能反映真实结果。
                level = item.optNonBlankString("level"),
                br = item.optLongOrNull("br")?.takeIf { bitrate -> bitrate > 0L },
            ),
    )
}

internal fun parseNeteaseAccountActionResponse(response: String): NeteaseAccountActionResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    return NeteaseAccountActionResult(
        status = status,
        code = code.takeIf { value -> value >= 0 },
    )
}

internal fun parseNeteaseLikedTrackIdsResponse(response: String): NeteaseLikedTrackIdsResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    if (status != NeteaseAccountActionStatus.Success) {
        return NeteaseLikedTrackIdsResult(
            status = status,
            code = code.takeIf { value -> value >= 0 },
        )
    }
    val idsArray = root.optJSONArray("ids")
        ?: root.optJSONObject("data")?.optJSONArray("ids")
        ?: root.optJSONArray("data")
    return NeteaseLikedTrackIdsResult(
        status = NeteaseAccountActionStatus.Success,
        trackIds = idsArray?.toPositiveIdStrings().orEmpty(),
        code = code,
    )
}

internal fun resolveNeteaseLikedTrackIds(
    directResult: NeteaseLikedTrackIdsResult,
    playlistTrackIds: Set<String>?,
): Set<String>? {
    if (directResult.status == NeteaseAccountActionStatus.Success && directResult.trackIds.isNotEmpty()) {
        return directResult.trackIds
    }
    return when {
        playlistTrackIds != null -> playlistTrackIds
        directResult.status == NeteaseAccountActionStatus.Success -> directResult.trackIds
        else -> null
    }
}

internal fun normalizeNeteasePlaylistTrackIds(trackIds: List<String>): List<String> {
    return trackIds
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()
}

internal fun buildNeteasePlaylistTrackIdsJson(trackIds: List<String>): String {
    return buildNeteaseNumericIdsJson(normalizeNeteasePlaylistTrackIds(trackIds))
}

internal fun buildNeteasePlaylistIdsJson(playlistIds: List<String>): String {
    return buildNeteaseNumericIdsJson(normalizeNeteasePlaylistTrackIds(playlistIds))
}

private fun buildNeteaseNumericIdsJson(ids: List<String>): String {
    return JSONArray().also { array ->
        ids.forEach { id ->
            array.put(id.toLongOrNull() ?: id)
        }
    }.toString()
}

internal fun parseNeteaseDailyRecommendedTracksResponse(response: String): NeteaseDailyRecommendedTracksResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    if (status != NeteaseAccountActionStatus.Success) {
        return NeteaseDailyRecommendedTracksResult(
            status = status,
            code = code.takeIf { value -> value >= 0 },
        )
    }
    val data = root.optJSONObject("data")
    val songs = data?.optJSONArray("dailySongs")
        ?: data?.optJSONArray("recommend")
        ?: root.optJSONArray("recommend")
        ?: root.optJSONArray("dailySongs")
    return NeteaseDailyRecommendedTracksResult(
        status = NeteaseAccountActionStatus.Success,
        tracks = songs
            ?.toJsonObjects()
            ?.mapNotNull(::parseNeteaseSong)
            .orEmpty(),
        code = code,
    )
}

internal fun parseNeteaseDailyStyleHomeResponse(response: String): NeteaseDailyStyleHomeResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    if (status != NeteaseAccountActionStatus.Success) {
        return NeteaseDailyStyleHomeResult(status = status, code = code.takeIf { it >= 0 })
    }
    val data = root.optJSONObject("data")
    val songs = data?.optJSONArray("dailySongs")
        ?: data?.optJSONArray("recommend")
        ?: root.optJSONArray("recommend")
        ?: root.optJSONArray("dailySongs")
    val tracks = songs
        ?.toJsonObjects()
        ?.mapNotNull(::parseNeteaseSong)
        .orEmpty()
    // 当前风格由 tags 回显：{"categoryId":1000,"categoryName":"曲风","tagVOList":[{...,"tagId":10005,"tagName":"嘻哈/说唱"}]}
    val tagsObject = data?.optJSONObject("tags") ?: root.optJSONObject("tags")
    val selection = tagsObject
        ?.optJSONArray("tagVOList")
        ?.toJsonObjects()
        ?.firstOrNull()
        ?.let { tag ->
            val tagName = tag.optNonBlankString("tagName") ?: return@let null
            NeteaseDailyStyleSelection(
                categoryId = tag.optInt("categoryId", 0),
                categoryName = tagsObject.optNonBlankString("categoryName"),
                tagId = tag.optInt("tagId", 0),
                tagName = tagName,
            )
        }
    return NeteaseDailyStyleHomeResult(
        status = NeteaseAccountActionStatus.Success,
        home = NeteaseDailyStyleHome(tracks = tracks, selection = selection),
        code = code,
    )
}

internal fun parseNeteaseDailyStylesResponse(response: String): NeteaseDailyStylesResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    if (status != NeteaseAccountActionStatus.Success) {
        return NeteaseDailyStylesResult(status = status, code = code.takeIf { it >= 0 })
    }
    val categories = root.optJSONObject("data")
        ?.optJSONArray("categorys")
        ?.toJsonObjects()
        ?.mapNotNull { category ->
            val categoryId = category.optInt("categoryId", 0)
            val name = category.optNonBlankString("categoryName") ?: return@mapNotNull null
            val tags = category.optJSONArray("tagVOList")
                ?.toJsonObjects()
                ?.mapNotNull { tag ->
                    val tagId = tag.optInt("tagId", 0)
                    val tagName = tag.optNonBlankString("tagName") ?: return@mapNotNull null
                    if (tagId <= 0) null else NeteaseDailyStyleTag(categoryId, tagId, tagName)
                }
                .orEmpty()
            if (tags.isEmpty()) null else NeteaseDailyStyleCategory(categoryId, name, tags)
        }
        .orEmpty()
    return NeteaseDailyStylesResult(
        status = NeteaseAccountActionStatus.Success,
        categories = categories,
        code = code,
    )
}

internal fun parseNeteasePlaylistCreateResponse(response: String): OnlineAccountPlaylistCreateResult {
    val root = JSONObject(response)
    val code = root.optInt("code", -1)
    val status = when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
    if (status != NeteaseAccountActionStatus.Success) {
        return OnlineAccountPlaylistCreateResult(
            status = status,
            code = code.takeIf { value -> value >= 0 },
        )
    }
    val playlist = root.optJSONObject("playlist")
        ?.let(::parseNeteasePlaylistSummary)
        ?.let { summary ->
            OnlineAccountPlaylist(
                provider = OnlineMusicProvider.Netease,
                playlistId = summary.playlistId,
                title = summary.name,
                trackCount = summary.trackCount,
                isLikedSongs = summary.isLikedSongs,
                isEditable = true,
            )
        }
    return OnlineAccountPlaylistCreateResult(
        status = if (playlist == null) NeteaseAccountActionStatus.Failed else NeteaseAccountActionStatus.Success,
        playlist = playlist,
        code = code,
    )
}

internal fun isNeteasePreviewDuration(
    returnedDurationMs: Long?,
    originalDurationMs: Long,
): Boolean {
    val returnedDurationMs = returnedDurationMs ?: return false
    if (originalDurationMs < MinSongDurationForPreviewDetectionMs || returnedDurationMs <= 0L) {
        return false
    }
    val durationRatio = returnedDurationMs.toDouble() / originalDurationMs.toDouble()
    return returnedDurationMs <= MaxKnownPreviewDurationMs &&
        durationRatio <= MaxPreviewDurationRatio
}

internal fun buildNeteaseEffectiveCookies(
    persistedCookies: Map<String, String>,
    sessionCookies: Map<String, String>,
): Map<String, String> {
    val cookies = linkedMapOf<String, String>()
    persistedCookies.addSanitizedCookiesTo(cookies)
    val hasPersistedLogin = !cookies[NeteaseLoginCookieName].isNullOrBlank()
    sessionCookies.addSanitizedCookiesTo(cookies) { key ->
        key != NeteaseLoginCookieName || hasPersistedLogin
    }
    return cookies
}

private inline fun Map<String, String>.addSanitizedCookiesTo(
    target: MutableMap<String, String>,
    keyFilter: (String) -> Boolean = { true },
) {
    forEach { (key, value) ->
        val safeKey = key.trim()
        val safeValue = value.trim()
        if (safeKey.isNotEmpty() && safeValue.isNotEmpty() && keyFilter(safeKey)) {
            target[safeKey] = safeValue
        }
    }
}

internal fun JSONArray.toJsonObjects(): List<JSONObject> {
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let(::add)
        }
    }
}

internal fun JSONArray.toStrings(): List<String> {
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun JSONArray.toPositiveIdStrings(): Set<String> {
    return buildSet {
        for (index in 0 until length()) {
            optLong(index, 0L)
                .takeIf { id -> id > 0L }
                ?.toString()
                ?.let(::add)
        }
    }
}

internal fun String.urlEncoded(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

/**
 * 归一化播放/封面地址：只放行 http(s)，并把 http 大小写不敏感地升级为 https。
 *
 * 该值会直接交给 `DefaultDataSource`，因此非 http(s) 的 scheme（`file://`、`content://` 等）
 * 一律返回 null，避免响应被篡改后把本地路径喂给数据源解析。
 */
internal fun String.normalizedPlayableUrl(): String? {
    val schemeEndIndex = indexOf(':')
    if (schemeEndIndex <= 0) {
        return null
    }
    return when (substring(0, schemeEndIndex).lowercase(Locale.ROOT)) {
        "http" -> "https${substring(schemeEndIndex)}"
        "https" -> this
        else -> null
    }
}

private fun String.normalizedArtworkUrl(): String? {
    val httpsUrl = normalizedPlayableUrl() ?: return null
    if (httpsUrl.contains("?param=")) {
        return httpsUrl
    }
    val separator = if (httpsUrl.contains('?')) "&" else "?"
    return "${httpsUrl}${separator}param=512y512"
}

private fun String.toAudioMimeType(): String {
    return when (lowercase(Locale.ROOT)) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        else -> "audio/mpeg"
    }
}
