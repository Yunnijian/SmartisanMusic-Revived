package com.smartisan.music.data.online

import kotlinx.coroutines.CancellationException

/** 推荐域：首页分节、轮播、歌单/榜单/新碟/歌手、电台与电台首页。 */

internal suspend fun NeteaseOnlineMusicRepository.featuredTracksPage(): List<OnlineTrack> {
    return cachedPage(
        key = cacheKey("featured:tracks"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        currentUserDailyRecommendedTracks(limit = FeaturedLimit)
            ?.takeIf(List<OnlineTrack>::isNotEmpty)
            ?: featuredSongs()
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredHomePage(): OnlineMusicHome {
    return cachedPage(
        key = cacheKey("featured:home"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.MusicHome,
    ) {
        val failures = mutableListOf<Throwable>()
        val tracks = featuredSection(failures) {
            featuredTracks().take(FeaturedHomeTrackLimit)
        }
        val playlists = featuredSection(failures) {
            featuredPlaylists()
        }
        val charts = featuredSection(failures) {
            featuredCharts()
        }
        val albums = featuredSection(failures) {
            featuredAlbums()
        }
        val artists = featuredSection(failures) {
            featuredArtists()
        }
        // 全部 5 个分节都失败 = 整页不可用，上抛让 UI 显示 Error 而不是静默的空态；
        // 只要有一节成功就照常渲染（部分成功仍是 Success）。
        if (failures.size == FeaturedHomeSectionCount) {
            throw failures.last()
        }
        OnlineMusicHome(
            tracks = tracks,
            playlists = playlists,
            charts = charts,
            albums = albums,
            artists = artists,
        )
    }
}

/**
 * 推荐页单个分节的加载：失败退化为空列表并记录下来，供上层判断是否整页都失败了。
 *
 * 不能用 runCatching——它会吞掉 CancellationException 导致协程取消信号丢失。
 */
internal suspend fun <T> NeteaseOnlineMusicRepository.featuredSection(
    failures: MutableList<Throwable>,
    block: suspend () -> List<T>,
): List<T> {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        failures += e
        emptyList()
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredBannersPage(): List<OnlineBanner> {
    return cachedPage(
        key = cacheKey("featured:banners"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Banners,
    ) {
        client.getBanners(limit = FeaturedBannerLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredPlaylistsPage(): List<OnlinePlaylist> {
    return cachedPage(
        key = cacheKey("featured:playlists"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Playlists,
    ) {
        client.getPersonalizedPlaylists(limit = FeaturedPlaylistLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredChartsPage(): List<OnlinePlaylist> {
    return cachedPage(
        key = cacheKey("featured:charts"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Playlists,
    ) {
        client.getToplists(limit = FeaturedChartLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredAlbumsPage(): List<OnlineAlbum> {
    return cachedPage(
        key = cacheKey("featured:albums"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Albums,
    ) {
        client.getNewAlbums(limit = FeaturedAlbumLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredArtistsPage(): List<OnlineArtist> {
    return cachedPage(
        key = cacheKey("featured:artists"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Artists,
    ) {
        client.getTopArtists(limit = FeaturedArtistLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredRadioTracksPage(): List<OnlineTrack> {
    return cachedPage(
        key = cacheKey("radio:tracks:featured"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getRecommendedRadioPrograms(limit = FeaturedRadioTrackLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredRadiosPage(): List<OnlineRadio> {
    return cachedPage(
        key = cacheKey("radio:list:featured"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Radios,
    ) {
        client.getRecommendedRadios(limit = FeaturedRadioLimit)
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredRadioHomePage(): OnlineRadioHome {
    return cachedPage(
        key = cacheKey("radio:home"),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.RadioHome,
    ) {
        OnlineRadioHome(
            tracks = runSuspendCatching {
                featuredRadioTracks()
            }.getOrDefault(emptyList()),
            radios = runSuspendCatching {
                featuredRadios()
            }.getOrDefault(emptyList()),
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.featuredSongs(): List<OnlineTrack> {
    return client.getPlaylistSongs(playlistId = FeaturedPlaylistId, limit = FeaturedLimit)
}
