package com.smartisan.music.data.online

import com.smartisan.music.AppDispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 「一起听」客户端协议端点：建房/入房/状态/心跳/同步/命令上报/结束，全走 eapi。 */

internal suspend fun NeteaseCloudMusicClient.createListenTogetherRoom(): ListenTogetherCreateResult = withContext(AppDispatchers.IO) {
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/room/create",
            params = mapOf("refer" to "songplay_more"),
        )
    }
    parseListenTogetherCreateResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.acceptListenTogetherInvitation(
    roomId: String,
    inviterId: String,
): ListenTogetherCreateResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext ListenTogetherCreateResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/play/invitation/accept",
            params = mapOf(
                "refer" to "inbox_invite",
                "roomId" to id,
                "inviterId" to inviterId.trim(),
            ),
        )
    }
    parseListenTogetherCreateResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.checkListenTogetherRoom(roomId: String): ListenTogetherCheckResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext ListenTogetherCheckResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/room/check",
            params = mapOf("roomId" to id),
        )
    }
    parseListenTogetherCheckResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.listenTogetherStatus(): ListenTogetherStatusResult = withContext(AppDispatchers.IO) {
    val response = requestWithLoginRetry {
        callEApi(path = "/listen/together/status/get", params = emptyMap())
    }
    parseListenTogetherStatusResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.listenTogetherHeartbeat(
    roomId: String,
    songId: String,
    playStatus: String,
    progressMs: Long,
): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/heartbeat",
            params = mapOf(
                "roomId" to id,
                "songId" to songId,
                "playStatus" to playStatus,
                "progress" to progressMs.coerceAtLeast(0L).toString(),
            ),
        )
    }
    parseNeteaseAccountActionResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.syncListenTogether(roomId: String): ListenTogetherSyncResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext ListenTogetherSyncResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/sync/playlist/get",
            params = mapOf("roomId" to id),
        )
    }
    parseListenTogetherSyncResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.reportListenTogetherCommand(
    roomId: String,
    commandInfo: String,
): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/play/command/report",
            params = mapOf(
                "roomId" to id,
                "commandInfo" to commandInfo,
            ),
        )
    }
    parseNeteaseAccountActionResponse(response)
}

/**
 * 同步房间播放队列（`sync/list/command/report`）。
 *
 * 房间必须先有队列，`sync/playlist/get` 才会返回 playCommand——只上报命令不上报队列时
 * 轮询永远拿到空 data。建房后需用当前队列 seed 一次。
 */
internal suspend fun NeteaseCloudMusicClient.reportListenTogetherPlaylist(
    roomId: String,
    playlistParam: String,
): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/sync/list/command/report",
            params = mapOf(
                "roomId" to id,
                "playlistParam" to playlistParam,
            ),
        )
    }
    parseNeteaseAccountActionResponse(response)
}

internal suspend fun NeteaseCloudMusicClient.endListenTogetherRoom(roomId: String): NeteaseAccountActionResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/end/v2",
            params = mapOf("roomId" to id),
        )
    }
    parseNeteaseAccountActionResponse(response)
}

/**
 * 拉取房间双方的历史累计时长。`roomUserIds` 必须显式传入两个 uid，缺省会被服务端 400。
 */
internal suspend fun NeteaseCloudMusicClient.getListenTogetherStatistics(
    roomId: String,
    roomUserIds: List<Long>,
): ListenTogetherStatisticsResult = withContext(AppDispatchers.IO) {
    val id = roomId.trim().takeIf(String::isNotEmpty)
        ?: return@withContext ListenTogetherStatisticsResult(NeteaseAccountActionStatus.Failed)
    val idsJson = JSONArray(roomUserIds).toString()
    val response = requestWithLoginRetry {
        callEApi(
            path = "/listen/together/relation/statistics/get/v2",
            params = mapOf(
                "roomId" to id,
                "roomUserIds" to idsJson,
            ),
        )
    }
    parseListenTogetherStatisticsResponse(response)
}

internal fun parseListenTogetherCreateResponse(response: String): ListenTogetherCreateResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return ListenTogetherCreateResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = listenTogetherStatusOf(code)
    val room = root.optJSONObject("data")
        ?.optJSONObject("roomInfo")
        ?.let(::parseListenTogetherRoom)
    return ListenTogetherCreateResult(status, room, code.takeIf { it >= 0 })
}

internal fun parseListenTogetherStatusResponse(response: String): ListenTogetherStatusResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return ListenTogetherStatusResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = listenTogetherStatusOf(code)
    val data = root.optJSONObject("data")
    val roomStatus = data?.let {
        ListenTogetherRoomStatus(
            inRoom = it.optBoolean("inRoom", false),
            room = it.optJSONObject("roomInfo")?.let(::parseListenTogetherRoom),
            status = it.optNonBlankString("status"),
        )
    }
    return ListenTogetherStatusResult(status, roomStatus, code.takeIf { it >= 0 })
}

internal fun parseListenTogetherCheckResponse(response: String): ListenTogetherCheckResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return ListenTogetherCheckResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = listenTogetherStatusOf(code)
    val data = root.optJSONObject("data")
    val check = data?.let {
        ListenTogetherRoomCheck(
            joinable = it.optBoolean("joinable", false),
            type = it.optNonBlankString("type"),
            status = it.optNonBlankString("status"),
        )
    }
    return ListenTogetherCheckResult(status, check, code.takeIf { it >= 0 })
}

internal fun parseListenTogetherSyncResponse(response: String): ListenTogetherSyncResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return ListenTogetherSyncResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = listenTogetherStatusOf(code)
    val data = root.optJSONObject("data") ?: JSONObject()
    val snapshot = ListenTogetherSyncSnapshot(
        command = data.optJSONObject("playCommand")?.let(::parseListenTogetherCommand),
        playlist = data.optJSONObject("playlist")?.let(::parseListenTogetherPlaylist),
    )
    return ListenTogetherSyncResult(status, snapshot, code.takeIf { it >= 0 })
}

internal fun parseListenTogetherStatisticsResponse(response: String): ListenTogetherStatisticsResult {
    val root = runCatching { JSONObject(response) }.getOrNull()
        ?: return ListenTogetherStatisticsResult(NeteaseAccountActionStatus.Failed)
    val code = root.optInt("code", -1)
    val status = listenTogetherStatusOf(code)
    val data = root.optJSONObject("data")
    val statistics = data?.let {
        ListenTogetherStatistics(
            totalConnectionTimeSeconds = it.optLong("totalConnectionTime", 0L),
            listenCount = it.optInt("listenCount", 0),
        )
    }
    return ListenTogetherStatisticsResult(status, statistics, code.takeIf { it >= 0 })
}

private fun parseListenTogetherRoom(room: JSONObject): ListenTogetherRoom? {
    val roomId = room.optNonBlankString("roomId") ?: return null
    return ListenTogetherRoom(
        roomId = roomId,
        creatorId = room.optLong("creatorId", 0L),
        users = room.optJSONArray("roomUsers")
            ?.toJsonObjects()
            ?.mapNotNull(::parseListenTogetherUser)
            .orEmpty(),
        roomCreateTime = room.optLongOrNull("roomCreateTime"),
        effectiveDurationMs = room.optLongOrNull("effectiveDurationMs"),
        roomType = room.optNonBlankString("roomType"),
    )
}

private fun parseListenTogetherUser(user: JSONObject): ListenTogetherUser? {
    val userId = user.optLongOrNull("userId") ?: return null
    return ListenTogetherUser(
        userId = userId,
        nickname = user.optNonBlankString("nickname"),
        avatarUrl = user.optNonBlankString("avatarUrl"),
    )
}

private fun parseListenTogetherCommand(command: JSONObject): ListenTogetherPlayCommand {
    return ListenTogetherPlayCommand(
        commandType = command.optNonBlankString("commandType"),
        progressMs = command.optLong("progress", 0L),
        playStatus = command.optNonBlankString("playStatus"),
        formerSongId = command.optNonBlankString("formerSongId"),
        targetSongId = command.optNonBlankString("targetSongId"),
        clientSeq = command.optLong("clientSeq", 0L),
        serverSeq = command.optLong("serverSeq", 0L),
        userId = command.optLongOrNull("userId"),
    )
}

private fun parseListenTogetherPlaylist(playlist: JSONObject): ListenTogetherPlaylistSnapshot {
    return ListenTogetherPlaylistSnapshot(
        displaySongIds = playlist.optJSONObject("displayList")
            ?.optJSONArray("result")
            ?.toStrings()
            .orEmpty(),
        playMode = playlist.optNonBlankString("playMode"),
        replace = playlist.optBoolean("replace", false),
        versions = playlist.optJSONArray("version")
            ?.toJsonObjects()
            ?.mapNotNull { version ->
                val userId = version.optLongOrNull("userId") ?: return@mapNotNull null
                ListenTogetherPlaylistVersion(userId, version.optInt("version", 0))
            }
            .orEmpty(),
    )
}

private fun listenTogetherStatusOf(code: Int): NeteaseAccountActionStatus {
    return when (code) {
        200 -> NeteaseAccountActionStatus.Success
        301 -> NeteaseAccountActionStatus.RequiresLogin
        else -> NeteaseAccountActionStatus.Failed
    }
}
