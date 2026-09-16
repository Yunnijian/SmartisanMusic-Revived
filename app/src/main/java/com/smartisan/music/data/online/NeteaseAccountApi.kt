package com.smartisan.music.data.online

/** 账号域：个人资料、歌单/收藏读、收藏与歌单增删改、账号页聚合。 */

/** 登录失效（业务码 301）按「未登录」返回 null，其余异常照常上抛，避免账号库误显「加载失败」。 */
private suspend fun <T> accountRequiresLoginOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: NeteaseApiException) {
    if (e.reason == NeteaseApiFailureReason.RequiresLogin) null else throw e
}

internal suspend fun NeteaseOnlineMusicRepository.dailyStyleCategoriesPage(): List<NeteaseDailyStyleCategory>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    return accountRequiresLoginOrNull {
        cachedPage(
            key = cacheKey("daily:styles"),
            ttlMs = NeteaseFeaturedCacheTtlMs,
            codec = OnlinePageCacheCodecs.DailyStyles,
        ) {
            client.getDailyStyles().categories
        }
    }?.takeIf(List<NeteaseDailyStyleCategory>::isNotEmpty)
}

internal suspend fun NeteaseOnlineMusicRepository.saveDailyStylePage(
    categoryId: Int,
    tagId: Int,
): NeteaseAccountActionResult = runSuspendCatching {
    client.saveDailyStyle(categoryId = categoryId, tagId = tagId)
}.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))

/**
 * 风格日推曲目 + 当前风格。
 *
 * 刻意不进缓存：风格存在服务端，切换后列表必须立刻变，而当前风格是靠响应的 tags 回显的，
 * 缓存命中就拿不到回显。单次响应约 60KB，可接受。
 */
internal suspend fun NeteaseOnlineMusicRepository.dailyStyleHomePage(
    limit: Int,
): NeteaseDailyStyleHome? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    return accountRequiresLoginOrNull {
        val result = runSuspendCatching {
            client.getDailyStyleSongs(limit = limit)
        }.getOrDefault(NeteaseDailyStyleHomeResult(NeteaseAccountActionStatus.Failed))
        result.home.takeIf { result.status == NeteaseAccountActionStatus.Success }
    }
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserProfile(): NeteaseAccountProfile? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    return NeteaseOnlineMemoryCache.getOrLoad(
        key = cacheKey("account:profile"),
        ttlMs = NeteaseAccountCacheTtlMs,
    ) {
        val profile = runSuspendCatching {
            client.getCurrentUserProfile()
        }.getOrNull()
        if (profile != null) {
            authStore.saveProfile(profile)
        }
        profile ?: state.profile
    }
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserPlaylists(
    limit: Int = AccountPlaylistLimit,
): List<NeteasePlaylistSummary>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    val profile = currentUserProfile() ?: state.profile ?: return null
    return NeteaseOnlineMemoryCache.getOrLoad(
        key = cacheKey("account:playlist-summaries", limit),
        ttlMs = NeteaseAccountCacheTtlMs,
    ) {
        client.getUserPlaylists(
            userId = profile.userId,
            limit = limit,
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserLikedTracks(
    limit: Int = Int.MAX_VALUE,
): List<OnlineTrack>? {
    val likedPlaylist = currentUserPlaylists()
        ?.firstOrNull(NeteasePlaylistSummary::isLikedSongs)
        ?: return null
    return playlistTracks(
        playlist = likedPlaylist,
        limit = likedPlaylist.trackFetchLimit(maxLimit = limit),
    )
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserDailyRecommendedTracksPage(
    limit: Int,
): List<OnlineTrack>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    return cachedNullablePage(
        key = cacheKey("featured:daily", limit),
        ttlMs = NeteaseFeaturedCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        val result = runSuspendCatching {
            client.getDailyRecommendedSongs(limit = limit)
        }.getOrDefault(NeteaseDailyRecommendedTracksResult(NeteaseAccountActionStatus.Failed))
        result.tracks.takeIf { result.status == NeteaseAccountActionStatus.Success }
    }
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserLikedTrackIds(): Set<String>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    val profile = currentUserProfile() ?: state.profile ?: return null
    val result = runSuspendCatching {
        client.getUserLikedTrackIds(profile.userId)
    }.getOrDefault(NeteaseLikedTrackIdsResult(NeteaseAccountActionStatus.Failed))
    if (result.status == NeteaseAccountActionStatus.Success && result.trackIds.isNotEmpty()) {
        return result.trackIds
    }
    val playlistTrackIds = runSuspendCatching {
        currentUserLikedPlaylistTrackIds(profile.userId)
    }.getOrNull()
    return resolveNeteaseLikedTrackIds(result, playlistTrackIds)
}

internal suspend fun NeteaseOnlineMusicRepository.currentUserLikedPlaylistTrackIds(
    userId: Long,
): Set<String>? {
    val likedPlaylist = client.getUserPlaylists(
        userId = userId,
        limit = AccountPlaylistLimit,
    ).firstOrNull(NeteasePlaylistSummary::isLikedSongs) ?: return null
    return client.getPlaylistTrackIds(
        playlistId = likedPlaylist.playlistId,
        limit = likedPlaylist.trackFetchLimit(),
    ).toSet()
}

internal suspend fun NeteaseOnlineMusicRepository.setTrackLiked(
    trackId: String,
    liked: Boolean,
): NeteaseAccountActionResult {
    val normalizedTrackId = trackId.trim().takeIf(String::isNotEmpty)
        ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val state = authStore?.load()
    if (state?.isLoggedIn != true) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
    }
    return runSuspendCatching {
        client.setSongLiked(
            trackId = normalizedTrackId,
            liked = liked,
        )
    }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
        .also { result ->
            if (result.status == NeteaseAccountActionStatus.Success) {
                invalidateAccountCaches()
                invalidatePageCache(cachePrefix("playlist:tracks"))
                invalidatePageCache(cachePrefix("account:playlist-tracks"))
            }
        }
}

internal suspend fun NeteaseOnlineMusicRepository.addTracksToPlaylist(
    playlistId: String,
    trackIds: List<String>,
): NeteaseAccountActionResult {
    val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
        ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val normalizedTrackIds = normalizeNeteasePlaylistTrackIds(trackIds)
    if (normalizedTrackIds.isEmpty()) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    }
    val state = authStore?.load()
    if (state?.isLoggedIn != true) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
    }
    return runSuspendCatching {
        client.manipulatePlaylistTracks(
            playlistId = normalizedPlaylistId,
            trackIds = normalizedTrackIds,
            operation = NeteasePlaylistTrackOperation.Add,
        )
    }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
        .also { result ->
            if (result.status == NeteaseAccountActionStatus.Success) {
                invalidateAccountCaches()
                invalidatePlaylistTrackCaches(normalizedPlaylistId)
            }
        }
}

internal suspend fun NeteaseOnlineMusicRepository.removeTracksFromPlaylist(
    playlistId: String,
    trackIds: List<String>,
): NeteaseAccountActionResult {
    val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
        ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val normalizedTrackIds = normalizeNeteasePlaylistTrackIds(trackIds)
    if (normalizedTrackIds.isEmpty()) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    }
    val state = authStore?.load()
    if (state?.isLoggedIn != true) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
    }
    return runSuspendCatching {
        client.manipulatePlaylistTracks(
            playlistId = normalizedPlaylistId,
            trackIds = normalizedTrackIds,
            operation = NeteasePlaylistTrackOperation.Remove,
        )
    }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
        .also { result ->
            if (result.status == NeteaseAccountActionStatus.Success) {
                invalidateAccountCaches()
                invalidatePlaylistTrackCaches(normalizedPlaylistId)
            }
        }
}

internal suspend fun NeteaseOnlineMusicRepository.deletePlaylist(
    playlistId: String,
): NeteaseAccountActionResult {
    val normalizedPlaylistId = playlistId.trim().takeIf(String::isNotEmpty)
        ?: return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val state = authStore?.load()
    if (state?.isLoggedIn != true) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
    }
    return runSuspendCatching {
        client.deletePlaylist(normalizedPlaylistId)
    }.getOrDefault(NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed))
        .also { result ->
            if (result.status == NeteaseAccountActionStatus.Success) {
                invalidateAccountCaches()
                invalidatePlaylistTrackCaches(normalizedPlaylistId)
            }
        }
}

internal suspend fun NeteaseOnlineMusicRepository.createPlaylist(
    name: String,
): OnlineAccountPlaylistCreateResult {
    val normalizedName = name.trim().takeIf(String::isNotEmpty)
        ?: return OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed)
    val state = authStore?.load()
    if (state?.isLoggedIn != true) {
        return OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.RequiresLogin, code = 301)
    }
    return runSuspendCatching {
        client.createPlaylist(normalizedName)
    }.getOrDefault(OnlineAccountPlaylistCreateResult(NeteaseAccountActionStatus.Failed))
}

internal suspend fun NeteaseOnlineMusicRepository.accountPlaylistsPage(): List<OnlineAccountPlaylist>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    val profile = currentUserProfile() ?: state.profile ?: return null
    return accountRequiresLoginOrNull {
        cachedPage(
            key = cacheKey("account:playlists"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.AccountPlaylists,
        ) {
            client.getUserPlaylists(
                userId = profile.userId,
                limit = AccountPlaylistLimit,
            ).map { playlist ->
                OnlineAccountPlaylist(
                    provider = provider,
                    playlistId = playlist.playlistId,
                    title = playlist.name,
                    trackCount = playlist.trackCount,
                    isLikedSongs = playlist.isLikedSongs,
                    isEditable = playlist.isEditableBy(profile.userId),
                )
            }
        }
    }
}

internal suspend fun NeteaseOnlineMusicRepository.accountAlbumsPage(): List<OnlineAlbum>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    val profile = currentUserProfile() ?: state.profile ?: return null
    return accountRequiresLoginOrNull {
        cachedPage(
            key = cacheKey("account:albums"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.Albums,
        ) {
            client.getUserAlbums(
                userId = profile.userId,
                limit = AccountAlbumLimit,
            )
        }
    }
}

internal suspend fun NeteaseOnlineMusicRepository.accountRadiosPage(): List<OnlineRadio>? {
    val state = authStore?.load() ?: return null
    if (!state.isLoggedIn) {
        return null
    }
    val profile = currentUserProfile() ?: state.profile ?: return null
    return accountRequiresLoginOrNull {
        cachedPage(
            key = cacheKey("account:radios"),
            ttlMs = NeteaseAccountCacheTtlMs,
            codec = OnlinePageCacheCodecs.Radios,
        ) {
            client.getUserRadios(
                userId = profile.userId,
                limit = AccountRadioLimit,
            )
        }
    }
}

internal suspend fun NeteaseOnlineMusicRepository.accountLikedTrackIdsPage(): Set<String>? {
    return cachedNullablePage(
        key = cacheKey("liked:track-ids"),
        ttlMs = NeteaseAccountCacheTtlMs,
        codec = OnlinePageCacheCodecs.TrackIds,
    ) {
        currentUserLikedTrackIds()
    }
}

internal suspend fun NeteaseOnlineMusicRepository.addTracksToAccountPlaylistPage(
    playlist: OnlineAccountPlaylist,
    trackIds: List<String>,
): NeteaseAccountActionResult {
    if (playlist.provider != provider || !playlist.isEditable) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    }
    val result = addTracksToPlaylist(
        playlistId = playlist.playlistId,
        trackIds = trackIds,
    )
    return result
}

internal suspend fun NeteaseOnlineMusicRepository.removeTracksFromAccountPlaylistPage(
    playlist: OnlineAccountPlaylist,
    trackIds: List<String>,
): NeteaseAccountActionResult {
    if (playlist.provider != provider || !playlist.isEditable) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    }
    val result = removeTracksFromPlaylist(
        playlistId = playlist.playlistId,
        trackIds = trackIds,
    )
    return result
}

internal suspend fun NeteaseOnlineMusicRepository.deleteAccountPlaylistPage(
    playlist: OnlineAccountPlaylist,
): NeteaseAccountActionResult {
    if (playlist.provider != provider || !playlist.isEditable) {
        return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    }
    val result = deletePlaylist(playlist.playlistId)
    return result
}

internal suspend fun NeteaseOnlineMusicRepository.createAccountPlaylistPage(
    name: String,
): OnlineAccountPlaylistCreateResult {
    val result = createPlaylist(name)
    if (result.status == NeteaseAccountActionStatus.Success) {
        invalidateAccountCaches()
    }
    return result
}

internal suspend fun NeteaseOnlineMusicRepository.accountPlaylistTracksPage(
    playlist: OnlineAccountPlaylist,
): List<OnlineTrack> {
    if (playlist.provider != provider) {
        return emptyList()
    }
    return cachedPage(
        key = cacheKey("account:playlist-tracks", playlist.playlistId),
        ttlMs = NeteaseAccountCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getPlaylistSongs(
            playlistId = playlist.playlistId,
            limit = playlist.trackFetchLimit(),
        )
    }
}

internal suspend fun NeteaseOnlineMusicRepository.playlistTracks(
    playlist: NeteasePlaylistSummary,
    limit: Int = playlist.trackFetchLimit(),
): List<OnlineTrack> {
    return cachedPage(
        key = cacheKey("playlist:tracks", playlist.playlistId, limit),
        ttlMs = NeteaseDetailCacheTtlMs,
        codec = OnlinePageCacheCodecs.Tracks,
    ) {
        client.getPlaylistSongs(
            playlistId = playlist.playlistId,
            limit = limit,
        )
    }
}
