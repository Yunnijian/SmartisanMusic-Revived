package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.settings.NeteaseAudioQuality
import com.smartisan.music.data.settings.fallbackCandidates
import kotlinx.coroutines.CancellationException
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

private const val ArtistAlbumPageSize = 50
private const val PlaylistSongDetailBatchSize = 300
private const val PlaylistSongDetailParallelism = 4
private const val PlaylistDetailSubscriberCount = 8
private const val HttpTimeoutMs = 15_000
private const val UserAgent =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

internal class NeteaseCloudMusicClient(
    private val cookieProvider: () -> Map<String, String> = { emptyMap() },
    private val playbackQualityProvider: suspend () -> NeteaseAudioQuality = { NeteaseAudioQuality.ExHigh },
) {
    private val sessionCookieLock = Any()
    private val sessionCookies = linkedMapOf<String, String>()

    suspend fun searchSongs(query: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, SearchLimit)
        val response = JSONObject(
            callWeApi(
                path = "/cloudsearch/get/web",
                params = mapOf(
                    "s" to query,
                    "type" to "1",
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "total" to "true",
                ),
            ),
        )
        val songs = response.optJSONObject("result")
            ?.optJSONArray("songs")
            ?: return@withContext emptyList()
        val baseTracks = songs.toJsonObjects()
            .mapNotNull(::parseSong)
        // 详情补全失败不应连累搜索结果：失败时沿用基础信息。
        // 注意不能用 runCatching——它会吞掉 CancellationException 导致协程取消信号丢失。
        val detailsById = try {
            getSongs(baseTracks.map(OnlineTrack::trackId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }.associateBy(OnlineTrack::trackId)
        baseTracks.map { track -> detailsById[track.trackId] ?: track }
    }

    suspend fun searchArtists(query: String, limit: Int): List<OnlineArtist> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, ArtistSearchLimit)
        val response = JSONObject(
            callWeApi(
                path = "/cloudsearch/get/web",
                params = mapOf(
                    "s" to query,
                    "type" to "100",
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "total" to "true",
                ),
            ),
        )
        response.optJSONObject("result")
            ?.optJSONArray("artists")
            ?.toJsonObjects()
            ?.mapNotNull(::parseArtist)
            .orEmpty()
    }

    suspend fun searchAlbums(query: String, limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, AlbumSearchLimit)
        val response = JSONObject(
            callWeApi(
                path = "/cloudsearch/get/web",
                params = mapOf(
                    "s" to query,
                    "type" to "10",
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "total" to "true",
                ),
            ),
        )
        response.optJSONObject("result")
            ?.optJSONArray("albums")
            ?.toJsonObjects()
            ?.mapNotNull(::parseAlbum)
            .orEmpty()
    }

    suspend fun searchPlaylists(query: String, limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, PlaylistSearchLimit)
        val response = JSONObject(
            callWeApi(
                path = "/cloudsearch/get/web",
                params = mapOf(
                    "s" to query,
                    "type" to "1000",
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "total" to "true",
                ),
            ),
        )
        response.optJSONObject("result")
            ?.optJSONArray("playlists")
            ?.toJsonObjects()
            ?.mapNotNull { playlist ->
                parsePlaylist(
                    playlist = playlist,
                    kind = OnlinePlaylistKind.Featured,
                )
            }
            .orEmpty()
    }

    suspend fun getHotSearchKeywords(limit: Int): List<OnlineSearchHotKeyword> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, HotSearchLimit)
        val response = JSONObject(callWeApi("/hotsearchlist/get", emptyMap()))
        response.optJSONArray("data")
            ?.toJsonObjects()
            ?.mapNotNull(::parseHotSearchKeyword)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getTopArtists(limit: Int): List<OnlineArtist> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedArtistLimit)
        val url = "https://music.163.com/api/artist/top?limit=$safeLimit&offset=0"
        val response = JSONObject(readText(url))
        response.optJSONArray("artists")
            ?.toJsonObjects()
            ?.mapNotNull(::parseArtist)
            .orEmpty()
    }

    suspend fun getBanners(limit: Int): List<OnlineBanner> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedBannerLimit)
        val url = "https://music.163.com/api/v2/banner/get?clientType=pc"
        val response = JSONObject(readText(url))
        response.optJSONArray("banners")
            ?.toJsonObjects()
            ?.mapIndexedNotNull { index, banner -> parseBanner(banner, index) }
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getPersonalizedPlaylists(limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedPlaylistLimit)
        val response = JSONObject(
            callWeApi(
                path = "/personalized/playlist",
                params = mapOf(
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "total" to "true",
                    "n" to "1000",
                ),
            ),
        )
        response.optJSONArray("result")
            ?.toJsonObjects()
            ?.mapNotNull { playlist ->
                parsePlaylist(
                    playlist = playlist,
                    kind = OnlinePlaylistKind.Featured,
                )
            }
            .orEmpty()
    }

    suspend fun getToplists(limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedChartLimit)
        val url = "https://music.163.com/api/toplist/detail"
        val response = JSONObject(readText(url))
        response.optJSONArray("list")
            ?.toJsonObjects()
            ?.mapNotNull { playlist ->
                parsePlaylist(
                    playlist = playlist,
                    kind = OnlinePlaylistKind.Chart,
                )
            }
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getNewAlbums(limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedAlbumLimit)
        val url = "https://music.163.com/api/album/new?area=ALL&limit=$safeLimit&offset=0"
        val response = JSONObject(readText(url))
        response.optJSONArray("albums")
            ?.toJsonObjects()
            ?.mapNotNull(::parseAlbum)
            .orEmpty()
    }

    suspend fun getArtistTopSongs(artistId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, ArtistTopTracksLimit)
        val url = "https://music.163.com/api/artist/${id.urlEncoded()}"
        val response = JSONObject(readText(url))
        response.optJSONArray("hotSongs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseSong)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getArtistAlbums(artistId: String, expectedCount: Int?): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
        val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val albums = mutableListOf<OnlineAlbum>()
        val seenAlbumIds = linkedSetOf<String>()
        var expectedTotalCount = expectedCount
        var offset = 0
        var more = true
        while (more) {
            val url = "https://music.163.com/api/artist/albums/${id.urlEncoded()}" +
                "?limit=$ArtistAlbumPageSize&offset=$offset"
            val response = JSONObject(readText(url))
            val rawAlbums = response.optJSONArray("hotAlbums") ?: break
            val rawCount = rawAlbums.length()
            if (rawCount == 0) {
                break
            }
            var addedCount = 0
            rawAlbums
                .toJsonObjects()
                .mapNotNull(::parseAlbum)
                .forEach { album ->
                    if (seenAlbumIds.add(album.albumId)) {
                        albums += album
                        addedCount += 1
                    }
                }
            if (addedCount == 0) {
                break
            }
            offset += rawCount
            val reportedAlbumCount = response.optJSONObject("artist")
                ?.optInt("albumSize", 0)
                ?.coerceAtLeast(0)
                ?: 0
            if (expectedTotalCount == null && reportedAlbumCount > 0) {
                expectedTotalCount = reportedAlbumCount
            }
            val serverHasMore = response.optBoolean("more", false)
            val countSuggestsMore = expectedTotalCount?.let { totalCount ->
                albums.size < totalCount && rawCount >= ArtistAlbumPageSize
            } ?: (rawCount >= ArtistAlbumPageSize)
            more = serverHasMore || countSuggestsMore
        }
        albums
    }

    suspend fun getArtistIntroduction(artistId: String): List<OnlineArtistIntroduction> = withContext(AppDispatchers.IO) {
        val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val url = "https://music.163.com/api/artist/introduction?id=${id.urlEncoded()}"
        val response = JSONObject(readText(url))
        buildList {
            response.optNonBlankString("briefDesc")?.let { briefDesc ->
                add(
                    OnlineArtistIntroduction(
                        title = "简介",
                        text = briefDesc,
                    ),
                )
            }
            response.optJSONArray("introduction")
                ?.toJsonObjects()
                ?.mapNotNull(::parseArtistIntroduction)
                ?.let(::addAll)
        }
    }

    suspend fun getRecommendedRadioPrograms(limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedRadioTrackLimit)
        val response = runSuspendCatching {
            JSONObject(readText("https://music.163.com/api/program/recommend/v1?limit=$safeLimit&offset=0"))
        }.getOrNull()
        val recommendedPrograms = response
            ?.optJSONArray("programs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseProgramSong)
            .orEmpty()
        if (recommendedPrograms.isNotEmpty()) {
            return@withContext recommendedPrograms.take(safeLimit)
        }
        val fallbackResponse = JSONObject(readText("https://music.163.com/api/personalized/djprogram"))
        fallbackResponse.optJSONArray("result")
            ?.toJsonObjects()
            ?.mapNotNull { item -> item.optJSONObject("program") }
            ?.mapNotNull(::parseProgramSong)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getRecommendedRadios(limit: Int): List<OnlineRadio> = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceIn(1, FeaturedRadioLimit)
        val response = JSONObject(readText("https://music.163.com/api/djradio/recommend/v1"))
        response.optJSONArray("djRadios")
            ?.toJsonObjects()
            ?.mapNotNull(::parseRadio)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getRadioPrograms(radioId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val id = radioId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, RadioTracksLimit)
        val url = "https://music.163.com/api/dj/program/byradio" +
            "?radioId=${id.urlEncoded()}&limit=$safeLimit&offset=0&asc=false"
        val response = JSONObject(readText(url))
        val code = response.optInt("code", 200)
        if (code != 200) {
            throw IOException("NetEase radio programs unavailable: code $code")
        }
        response.optJSONArray("programs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseProgramSong)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getAlbumSongs(albumId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val id = albumId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val safeLimit = limit.coerceIn(1, AlbumTracksLimit)
        val response = JSONObject(
            callWeApi(
                path = "/v1/album/${id.urlEncoded()}",
                params = mapOf(
                    "n" to safeLimit.toString(),
                    "s" to "8",
                ),
            ),
        )
        response.optJSONArray("songs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseSong)
            ?.take(safeLimit)
            .orEmpty()
    }

    suspend fun getPlaylistSongs(playlistId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
        val id = playlistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        val url = "https://music.163.com/api/v6/playlist/detail" +
            "?id=${id.urlEncoded()}&n=$safeLimit&s=$PlaylistDetailSubscriberCount"
        val detail = parseNeteasePlaylistDetailResponse(readText(url))
        val trackIds = detail.trackIds.take(safeLimit)
        if (trackIds.isEmpty()) {
            if (detail.trackCount == 0) {
                return@withContext emptyList()
            }
            error("NetEase playlist detail response missing trackIds")
        }
        val embeddedTracksById = detail.tracks.associateBy(OnlineTrack::trackId)
        val missingTracks = fetchSongDetails(
            trackIds = trackIds.filterNot(embeddedTracksById::containsKey),
        )
        val missingTracksById = missingTracks.associateBy(OnlineTrack::trackId)
        trackIds.mapNotNull { trackId ->
            embeddedTracksById[trackId] ?: missingTracksById[trackId]
        }
    }

    suspend fun getPlaylistTrackIds(playlistId: String, limit: Int): List<String> = withContext(AppDispatchers.IO) {
        val id = playlistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
        val safeLimit = limit.coerceAtLeast(1)
        val url = "https://music.163.com/api/v6/playlist/detail" +
            "?id=${id.urlEncoded()}&n=$safeLimit&s=$PlaylistDetailSubscriberCount"
        parseNeteasePlaylistDetailResponse(readText(url)).trackIds.take(safeLimit)
    }

    suspend fun getUserPlaylists(userId: Long, limit: Int): List<NeteasePlaylistSummary> = withContext(AppDispatchers.IO) {
        if (userId <= 0L) {
            return@withContext emptyList()
        }
        val safeLimit = limit.coerceIn(1, AccountPlaylistLimit)
        val response = requestWithLoginRetry {
            callWeApi(
                path = "/user/playlist",
                params = mapOf(
                    "uid" to userId.toString(),
                    "limit" to safeLimit.toString(),
                    "offset" to "0",
                    "includeVideo" to "true",
                ),
            )
        }
        parseNeteaseUserPlaylistsResponse(response)
    }

    suspend fun getUserAlbums(userId: Long, limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
        if (userId <= 0L) {
            return@withContext emptyList()
        }
        val safeLimit = limit.coerceIn(1, AccountAlbumLimit)
        val response = requestWithLoginRetry {
            callEApi(
                path = "/mine/rn/resource/list",
                params = mapOf(
                    "userId" to userId.toString(),
                    "offset" to "0",
                    "limit" to safeLimit.toString(),
                    "pageType" to "3",
                    "needRcmd" to "0",
                    "isVistor" to "false",
                    "includeStarPodcast" to "true",
                ),
                host = "interface3.music.163.com",
            )
        }
        parseNeteaseAccountAlbumsResponse(response).take(safeLimit)
    }

    suspend fun getUserRadios(userId: Long, limit: Int): List<OnlineRadio> = withContext(AppDispatchers.IO) {
        if (userId <= 0L) {
            return@withContext emptyList()
        }
        val safeLimit = limit.coerceIn(1, AccountRadioLimit)
        val response = requestWithLoginRetry {
            callWeApi(
                path = "/user/djradio/get/subed",
                params = mapOf(
                    "uid" to userId.toString(),
                    "offset" to "0",
                    "limit" to safeLimit.toString(),
                ),
            )
        }
        parseNeteaseAccountRadiosResponse(response).take(safeLimit)
    }

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
        val response = JSONObject(requestSongDetails(ids))
        response.optJSONArray("songs")
            ?.toJsonObjects()
            ?.mapNotNull(::parseSong)
            .orEmpty()
    }

    private suspend fun fetchSongDetails(trackIds: List<String>): List<OnlineTrack> = coroutineScope {
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

    suspend fun getPlaybackUrl(
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

    suspend fun getPlaybackUrlResult(
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

    suspend fun getLyrics(trackId: String): OnlineLyrics = withContext(AppDispatchers.IO) {
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

    private fun requestLyricsWithSessionRetry(request: () -> String): String {
        var response = runSuspendCatching { request() }.getOrNull() ?: ""
        // 已登录且接口返回 code=301（登录态/csrf 过期）时，预热会话后重试一次。
        // 注意网易云返回的是 HTTP 200 + JSON code=301，不会抛异常，必须解析响应体判断。
        if (hasLogin() && responseJsonRequiresLogin(response)) {
            ensureWeapiSession()
            response = runSuspendCatching { request() }.getOrNull() ?: ""
        }
        return response
    }

    private fun parseLyricsResponse(response: String): OnlineLyrics {
        if (response.isBlank()) {
            return OnlineLyrics(null, null)
        }
        val json = runCatching { JSONObject(response) }.getOrNull()
            ?: return OnlineLyrics(null, null)
        // code=301 表示需要登录态，此时歌词字段为空。
        val code = json.optInt("code", 0)
        if (code == 301) {
            return OnlineLyrics(null, null)
        }
        return OnlineLyrics(
            lyric = json.optJSONObject("lrc")?.optNonBlankString("lyric"),
            translatedLyric = json.optJSONObject("tlyric")?.optNonBlankString("lyric")
                ?: json.optJSONObject("ytlrc")?.optNonBlankString("lyric"),
            wordLyric = json.optJSONObject("yrc")?.optNonBlankString("lyric"),
            translatedWordLyric = json.optJSONObject("ytlrc")?.optNonBlankString("lyric"),
        )
    }

    suspend fun getCurrentUserProfile(): NeteaseAccountProfile? = withContext(AppDispatchers.IO) {
        val response = requestWithLoginRetry {
            callWeApi("/w/nuser/account/get", emptyMap())
        }
        parseNeteaseAccountProfileResponse(response)
    }

    suspend fun setSongLiked(trackId: String, liked: Boolean): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
        val id = trackId.trim().takeIf(String::isNotEmpty)
            ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        requestAccountActionWithSessionRetry {
            callWeApi(
                path = "/song/like",
                params = mapOf(
                    "trackId" to id,
                    "like" to liked.toString(),
                ),
            )
        }
    }

    suspend fun getUserLikedTrackIds(userId: Long): NeteaseLikedTrackIdsResult = withContext(AppDispatchers.IO) {
        if (userId <= 0L) {
            return@withContext NeteaseLikedTrackIdsResult(NeteaseAccountActionStatus.Failed)
        }
        requestLikedTrackIdsWithSessionRetry {
            callWeApi(
                path = "/song/like/get",
                params = mapOf("uid" to userId.toString()),
            )
        }
    }

    suspend fun getDailyRecommendedSongs(limit: Int): NeteaseDailyRecommendedTracksResult = withContext(AppDispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        requestDailyRecommendedTracksWithSessionRetry {
            callWeApi(
                path = "/v3/discovery/recommend/songs",
                params = mapOf(
                    "total" to "true",
                    "limit" to safeLimit.toString(),
                ),
            )
        }.let { result ->
            if (result.status == NeteaseAccountActionStatus.Success) {
                result.copy(tracks = result.tracks.take(safeLimit))
            } else {
                result
            }
        }
    }

    suspend fun manipulatePlaylistTracks(
        playlistId: String,
        trackIds: List<String>,
        operation: NeteasePlaylistTrackOperation,
    ): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
        val id = playlistId.trim().takeIf(String::isNotEmpty)
            ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        val ids = normalizeNeteasePlaylistTrackIds(trackIds)
        if (ids.isEmpty()) {
            return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        requestAccountActionWithSessionRetry {
            callWeApi(
                path = "/playlist/manipulate/tracks",
                params = mapOf(
                    "op" to operation.apiValue,
                    "pid" to id,
                    "trackIds" to buildNeteasePlaylistTrackIdsJson(ids),
                    "imme" to "true",
                ),
            )
        }
    }

    suspend fun createPlaylist(name: String): OnlineAccountPlaylistCreateResult = withContext(AppDispatchers.IO) {
        val normalizedName = name.trim().takeIf(String::isNotEmpty)
            ?: return@withContext OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed)
        requestPlaylistCreateWithSessionRetry {
            callWeApi(
                path = "/playlist/create",
                params = mapOf(
                    "name" to normalizedName,
                    "privacy" to "0",
                    "type" to "NORMAL",
                ),
            )
        }
    }

    suspend fun deletePlaylist(playlistId: String): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
        val id = playlistId.trim().takeIf(String::isNotEmpty)
            ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        requestAccountActionWithSessionRetry {
            callWeApi(
                path = "/playlist/remove",
                params = mapOf("ids" to buildNeteasePlaylistIdsJson(listOf(id))),
            )
        }
    }

    private fun resolveOuterPlaybackUrl(trackId: String): OnlinePlaybackUrl? {
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
            OnlinePlaybackUrl(
                url = location.normalizedPlayableUrl(),
                mimeType = "audio/mpeg",
            )
        }
    }

    private fun readText(url: String): String {
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

    private fun callEApi(
        path: String,
        params: Map<String, String>,
        host: String = "interface.music.163.com",
    ): String {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val eapiPath = "/eapi$normalizedPath"
        val apiPath = "/api$normalizedPath"
        val url = "https://$host$eapiPath"
        val encryptedParams = NeteaseCrypto.encryptEApiParams(apiPath, params.toJsonObjectString())
        val body = "params=${encryptedParams.urlEncoded()}"
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
                throw IOException("NetEase EAPI request failed: HTTP $code")
            }
            inputStream.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
        }
    }

    private fun callWeApi(
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

    private fun openConnection(
        url: String,
        followRedirects: Boolean,
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = HttpTimeoutMs
            readTimeout = HttpTimeoutMs
            instanceFollowRedirects = followRedirects
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag())
            setRequestProperty("Referer", "https://music.163.com/")
            setRequestProperty("User-Agent", UserAgent)
            buildCookieHeader().takeIf(String::isNotBlank)?.let { cookieHeader ->
                setRequestProperty("Cookie", cookieHeader)
            }
        }
    }

    private fun requestPlaybackUrlWithSessionRetry(
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

    private fun requestAccountActionWithSessionRetry(
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

    private fun requestLikedTrackIdsWithSessionRetry(
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

    private fun requestDailyRecommendedTracksWithSessionRetry(
        request: () -> String,
    ): NeteaseDailyRecommendedTracksResult {
        var result = tryParseDailyRecommendedTracksResponse(request)
        if (hasLogin() && result.status == NeteaseAccountActionStatus.RequiresLogin) {
            ensureWeapiSession()
            result = tryParseDailyRecommendedTracksResponse(request)
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

    private fun requestPlaylistCreateWithSessionRetry(
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
    private fun requestWithLoginRetry(request: () -> String): String {
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

    private fun hasLogin(): Boolean {
        return !effectiveCookies()[NeteaseLoginCookieName].isNullOrBlank()
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

    private fun parseSong(song: JSONObject): OnlineTrack? = parseNeteaseSong(song)

    private fun parseProgramSong(program: JSONObject): OnlineTrack? {
        val song = program.optJSONObject("mainSong") ?: return null
        val parsedSong = parseSong(song) ?: return null
        val radio = program.optJSONObject("radio")
        val dj = program.optJSONObject("dj")
        val artist = parsedSong.artist.takeIf(String::isNotBlank)
            ?: radio?.optNonBlankString("name")
            ?: dj?.optNonBlankString("nickname")
            ?: ""
        val artworkUrl = parsedSong.artworkUrl
            ?: program.optNonBlankString("coverUrl")
            ?: program.optNonBlankString("picUrl")
            ?: radio?.optNonBlankString("picUrl")
        return parsedSong.copy(
            artist = artist,
            artworkUrl = artworkUrl,
        )
    }

    private fun parseHotSearchKeyword(item: JSONObject): OnlineSearchHotKeyword? {
        val keyword = item.optNonBlankString("searchWord")
            ?: item.optNonBlankString("first")
            ?: return null
        return OnlineSearchHotKeyword(
            keyword = keyword,
            subtitle = item.optNonBlankString("content")
                ?: item.optNonBlankString("second"),
            score = item.optLong("score", 0L).coerceAtLeast(0L),
        )
    }

    private fun parseArtist(artist: JSONObject): OnlineArtist? {
        val id = artist.optLong("id", 0L)
            .takeIf { artistId -> artistId > 0L }
            ?.toString()
            ?: return null
        val name = artist.optNonBlankString("name") ?: return null
        val aliases = artist.optJSONArray("alias")
            ?.toStrings()
            ?.filter(String::isNotBlank)
            .orEmpty()
        val subtitle = when {
            aliases.isNotEmpty() -> aliases.joinToString("/")
            artist.optInt("musicSize", 0) > 0 -> null
            else -> null
        }
        return OnlineArtist(
            provider = OnlineMusicProvider.Netease,
            artistId = id,
            name = name,
            subtitle = subtitle,
            artworkUrl = artist.optArtistArtworkUrl(),
            trackCount = artist.optInt("musicSize", 0).coerceAtLeast(0),
            albumCount = artist.optInt("albumSize", 0).coerceAtLeast(0),
        )
    }

    private fun parseRadio(radio: JSONObject): OnlineRadio? {
        return parseNeteaseRadio(radio)
    }

    private fun parseBanner(banner: JSONObject, index: Int): OnlineBanner? {
        val title = banner.optNonBlankString("typeTitle")
            ?: banner.optJSONObject("song")?.optNonBlankString("name")
            ?: return null
        val targetType = banner.optInt("targetType", 0)
        val targetId = banner.optLong("targetId", 0L)
            .takeIf { id -> id > 0L }
            ?.toString()
        val targetUrl = banner.optNonBlankString("url").orEmpty()
        val targetTrackId = banner.optJSONObject("song")
            ?.optLong("id", 0L)
            ?.takeIf { songId -> songId > 0L }
            ?.toString()
            ?: targetId.takeIf { targetType == 1 || targetUrl.startsWith("orpheus://song/") }
        val targetAlbumId = targetId.takeIf {
            targetType == 10 || targetUrl.startsWith("orpheus://album/")
        }
        val targetPlaylistId = targetId.takeIf {
            targetType == 1000 || targetUrl.startsWith("orpheus://playlist/")
        }
        return OnlineBanner(
            provider = OnlineMusicProvider.Netease,
            bannerId = banner.optNonBlankString("bannerId")
                ?: targetTrackId
                ?: targetAlbumId
                ?: targetPlaylistId
                ?: "netease-banner-$index",
            title = title,
            subtitle = banner.optJSONObject("song")?.optNonBlankString("name"),
            imageUrl = banner.optNonBlankString("imageUrl")
                ?: banner.optNonBlankString("bigImageUrl")
                ?: banner.optNonBlankString("pic")
                ?: banner.optNonBlankString("picUrl"),
            targetTrackId = targetTrackId,
            targetAlbumId = targetAlbumId,
            targetPlaylistId = targetPlaylistId,
        )
    }

    private fun parsePlaylist(
        playlist: JSONObject,
        kind: OnlinePlaylistKind,
    ): OnlinePlaylist? {
        val id = playlist.optLong("id", 0L)
            .takeIf { playlistId -> playlistId > 0L }
            ?.toString()
            ?: return null
        val title = playlist.optNonBlankString("name") ?: return null
        val topTracks = playlist.optJSONArray("tracks")
            ?.toJsonObjects()
            ?.mapNotNull { track ->
                val name = track.optNonBlankString("first") ?: return@mapNotNull null
                val artist = track.optNonBlankString("second")
                if (artist.isNullOrBlank()) {
                    name
                } else {
                    "$name - $artist"
                }
            }
            ?.take(3)
            ?.joinToString(" / ")
        return OnlinePlaylist(
            provider = OnlineMusicProvider.Netease,
            playlistId = id,
            title = title,
            subtitle = playlist.optNonBlankString("copywriter")
                ?: playlist.optNonBlankString("updateFrequency")
                ?: topTracks
                ?: playlist.optNonBlankString("description"),
            artworkUrl = playlist.optNonBlankString("picUrl")
                ?: playlist.optNonBlankString("coverImgUrl"),
            trackCount = playlist.optInt("trackCount", 0).coerceAtLeast(0),
            playCount = playlist.optDouble("playCount", 0.0).toLong().coerceAtLeast(0L),
            kind = kind,
        )
    }

    private fun parseAlbum(album: JSONObject): OnlineAlbum? {
        return parseNeteaseAlbum(album)
    }

    private fun parseArtistIntroduction(section: JSONObject): OnlineArtistIntroduction? {
        val title = section.optNonBlankString("ti") ?: return null
        val text = section.optNonBlankString("txt") ?: return null
        return OnlineArtistIntroduction(
            title = title,
            text = text,
        )
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
