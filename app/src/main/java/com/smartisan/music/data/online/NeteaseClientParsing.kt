package com.smartisan.music.data.online

import org.json.JSONObject

/** 响应解析域：将 NetEase 各接口的 JSON 片段解析为在线 DTO。 */

internal fun parseSong(song: JSONObject): OnlineTrack? = parseNeteaseSong(song)

internal fun parseProgramSong(program: JSONObject): OnlineTrack? {
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

internal fun parseHotSearchKeyword(item: JSONObject): OnlineSearchHotKeyword? {
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

internal fun parseArtist(artist: JSONObject): OnlineArtist? {
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

internal fun parseRadio(radio: JSONObject): OnlineRadio? {
    return parseNeteaseRadio(radio)
}

internal fun parseBanner(banner: JSONObject, index: Int): OnlineBanner? {
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

internal fun parsePlaylist(
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

internal fun parseAlbum(album: JSONObject): OnlineAlbum? {
    return parseNeteaseAlbum(album)
}

internal fun parseArtistIntroduction(section: JSONObject): OnlineArtistIntroduction? {
    val title = section.optNonBlankString("ti") ?: return null
    val text = section.optNonBlankString("txt") ?: return null
    return OnlineArtistIntroduction(
        title = title,
        text = text,
    )
}

internal fun parseLyricsResponse(response: String): OnlineLyrics {
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
