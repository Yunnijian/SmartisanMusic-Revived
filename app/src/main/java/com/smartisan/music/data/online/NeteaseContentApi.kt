package com.smartisan.music.data.online

/** 详情页内容域：歌单、专辑、歌手作品与简介、电台节目列表。 */

internal suspend fun NeteaseOnlineMusicRepository.playlistTracksPage(
    playlist: OnlinePlaylist,
): List<OnlineTrack> {
    if (playlist.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("playlist:tracks", playlist.playlistId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getPlaylistSongs(
            playlistId = playlist.playlistId,
            limit = playlist.trackFetchLimit(),
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.albumTracksPage(
    album: OnlineAlbum,
): List<OnlineTrack> {
    if (album.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("album:tracks", album.albumId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getAlbumSongs(
            albumId = album.albumId,
            limit = AlbumTracksLimit,
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.artistTopTracksPage(
    artist: OnlineArtist,
): List<OnlineTrack> {
    if (artist.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("artist:tracks", artist.artistId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getArtistTopSongs(
            artistId = artist.artistId,
            limit = ArtistTopTracksLimit,
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.artistAlbumsPage(
    artist: OnlineArtist,
): List<OnlineAlbum> {
    if (artist.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("artist:albums", artist.artistId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Albums,
    ) {
        client.getArtistAlbums(
            artistId = artist.artistId,
            expectedCount = artist.albumCount.takeIf { albumCount -> albumCount > 0 },
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.artistIntroductionPage(
    artist: OnlineArtist,
): List<OnlineArtistIntroduction> {
    if (artist.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("artist:introduction", artist.artistId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.ArtistIntroductions,
    ) {
        client.getArtistIntroduction(artist.artistId)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.radioTracksPage(
    radio: OnlineRadio,
): List<OnlineTrack> {
    if (radio.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("radio:tracks", radio.radioId),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getRadioPrograms(
            radioId = radio.radioId,
            limit = RadioTracksLimit,
        )
    }
}
