package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** 浏览/发现域端点：搜索、首页推荐、榜单、新碟、歌手、电台、专辑、歌单。 */

internal suspend fun NeteaseCloudMusicClient.searchSongs(query: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, SearchLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.searchArtists(query: String, limit: Int): List<OnlineArtist> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, ArtistSearchLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.searchAlbums(query: String, limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, AlbumSearchLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.searchPlaylists(query: String, limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, PlaylistSearchLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.getHotSearchKeywords(limit: Int): List<OnlineSearchHotKeyword> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, HotSearchLimit)
    val response = parseNeteaseApiResponse(callWeApi("/hotsearchlist/get", emptyMap()))
    response.optJSONArray("data")
        ?.toJsonObjects()
        ?.mapNotNull(::parseHotSearchKeyword)
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getTopArtists(limit: Int): List<OnlineArtist> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedArtistLimit)
    val url = "https://music.163.com/api/artist/top?limit=$safeLimit&offset=0"
    val response = parseNeteaseApiResponse(readText(url))
    response.optJSONArray("artists")
        ?.toJsonObjects()
        ?.mapNotNull(::parseArtist)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getBanners(limit: Int): List<OnlineBanner> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedBannerLimit)
    val url = "https://music.163.com/api/v2/banner/get?clientType=pc"
    val response = parseNeteaseApiResponse(readText(url))
    response.optJSONArray("banners")
        ?.toJsonObjects()
        ?.mapIndexedNotNull { index, banner -> parseBanner(banner, index) }
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getPersonalizedPlaylists(limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedPlaylistLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.getToplists(limit: Int): List<OnlinePlaylist> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedChartLimit)
    val url = "https://music.163.com/api/toplist/detail"
    val response = parseNeteaseApiResponse(readText(url))
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

internal suspend fun NeteaseCloudMusicClient.getNewAlbums(limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedAlbumLimit)
    val url = "https://music.163.com/api/album/new?area=ALL&limit=$safeLimit&offset=0"
    val response = parseNeteaseApiResponse(readText(url))
    response.optJSONArray("albums")
        ?.toJsonObjects()
        ?.mapNotNull(::parseAlbum)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getArtistTopSongs(artistId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
    val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val safeLimit = limit.coerceIn(1, ArtistTopTracksLimit)
    val url = "https://music.163.com/api/artist/${id.urlEncoded()}"
    val response = parseNeteaseApiResponse(readText(url))
    response.optJSONArray("hotSongs")
        ?.toJsonObjects()
        ?.mapNotNull(::parseSong)
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getArtistAlbums(artistId: String, expectedCount: Int?): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
    val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val albums = mutableListOf<OnlineAlbum>()
    val seenAlbumIds = linkedSetOf<String>()
    var expectedTotalCount = expectedCount
    var offset = 0
    var more = true
    while (more) {
        val url = "https://music.163.com/api/artist/albums/${id.urlEncoded()}" +
            "?limit=$ArtistAlbumPageSize&offset=$offset"
        val response = parseNeteaseApiResponse(readText(url))
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

internal suspend fun NeteaseCloudMusicClient.getArtistIntroduction(artistId: String): List<OnlineArtistIntroduction> = withContext(AppDispatchers.IO) {
    val id = artistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val url = "https://music.163.com/api/artist/introduction?id=${id.urlEncoded()}"
    val response = parseNeteaseApiResponse(readText(url))
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

internal suspend fun NeteaseCloudMusicClient.getRecommendedRadioPrograms(limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedRadioTrackLimit)
    val response = runSuspendCatching {
        parseNeteaseApiResponse(readText("https://music.163.com/api/program/recommend/v1?limit=$safeLimit&offset=0"))
    }.getOrNull()
    val recommendedPrograms = response
        ?.optJSONArray("programs")
        ?.toJsonObjects()
        ?.mapNotNull(::parseProgramSong)
        .orEmpty()
    if (recommendedPrograms.isNotEmpty()) {
        return@withContext recommendedPrograms.take(safeLimit)
    }
    val fallbackResponse = parseNeteaseApiResponse(readText("https://music.163.com/api/personalized/djprogram"))
    fallbackResponse.optJSONArray("result")
        ?.toJsonObjects()
        ?.mapNotNull { item -> item.optJSONObject("program") }
        ?.mapNotNull(::parseProgramSong)
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getRecommendedRadios(limit: Int): List<OnlineRadio> = withContext(AppDispatchers.IO) {
    val safeLimit = limit.coerceIn(1, FeaturedRadioLimit)
    val response = parseNeteaseApiResponse(readText("https://music.163.com/api/djradio/recommend/v1"))
    response.optJSONArray("djRadios")
        ?.toJsonObjects()
        ?.mapNotNull(::parseRadio)
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getRadioPrograms(radioId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
    val id = radioId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val safeLimit = limit.coerceIn(1, RadioTracksLimit)
    val url = "https://music.163.com/api/dj/program/byradio" +
        "?radioId=${id.urlEncoded()}&limit=$safeLimit&offset=0&asc=false"
    val response = parseNeteaseApiResponse(readText(url))
    response.optJSONArray("programs")
        ?.toJsonObjects()
        ?.mapNotNull(::parseProgramSong)
        ?.take(safeLimit)
        .orEmpty()
}

internal suspend fun NeteaseCloudMusicClient.getAlbumSongs(albumId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
    val id = albumId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val safeLimit = limit.coerceIn(1, AlbumTracksLimit)
    val response = parseNeteaseApiResponse(
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

internal suspend fun NeteaseCloudMusicClient.getPlaylistSongs(playlistId: String, limit: Int): List<OnlineTrack> = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getPlaylistTrackIds(playlistId: String, limit: Int): List<String> = withContext(AppDispatchers.IO) {
    val id = playlistId.trim().takeIf(String::isNotEmpty) ?: return@withContext emptyList()
    val safeLimit = limit.coerceAtLeast(1)
    val url = "https://music.163.com/api/v6/playlist/detail" +
        "?id=${id.urlEncoded()}&n=$safeLimit&s=$PlaylistDetailSubscriberCount"
    parseNeteasePlaylistDetailResponse(readText(url)).trackIds.take(safeLimit)
}
