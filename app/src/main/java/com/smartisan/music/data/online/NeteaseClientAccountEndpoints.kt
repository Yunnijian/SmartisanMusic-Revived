package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.withContext

/** 账号域端点：用户资料、云盘歌单/专辑/电台、红心、每日推荐、歌单增删与曲目操作。 */

internal suspend fun NeteaseCloudMusicClient.getUserPlaylists(userId: Long, limit: Int): List<NeteasePlaylistSummary> = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getUserAlbums(userId: Long, limit: Int): List<OnlineAlbum> = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getUserRadios(userId: Long, limit: Int): List<OnlineRadio> = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getCurrentUserProfile(): NeteaseAccountProfile? = withContext(AppDispatchers.IO) {
    val response = requestWithLoginRetry {
        callWeApi("/w/nuser/account/get", emptyMap())
    }
    parseNeteaseAccountProfileResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.setSongLiked(trackId: String, liked: Boolean): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getUserLikedTrackIds(userId: Long): NeteaseLikedTrackIdsResult = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.getDailyRecommendedSongs(limit: Int): NeteaseDailyRecommendedTracksResult = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.manipulatePlaylistTracks(
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

internal suspend fun NeteaseCloudMusicClient.createPlaylist(name: String): OnlineAccountPlaylistCreateResult = withContext(AppDispatchers.IO) {
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

internal suspend fun NeteaseCloudMusicClient.deletePlaylist(playlistId: String): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
    val id = playlistId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    requestAccountActionWithSessionRetry {
        callWeApi(
            path = "/playlist/remove",
            params = mapOf("ids" to buildNeteasePlaylistIdsJson(listOf(id))),
        )
    }
}
