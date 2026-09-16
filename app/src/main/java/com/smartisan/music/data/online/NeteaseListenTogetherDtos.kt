package com.smartisan.music.data.online

/**
 * 「一起听」客户端协议 DTO。
 *
 * 端点族 `/api/listen/together/` 全走 eapi（`status/get` 的 weapi 路由已 404 死，不能用），
 * 协议是纯 HTTP 轮询：`sync/playlist/get` 是唯一轮询端点，返回最新播放命令与队列快照。
 */

internal data class ListenTogetherUser(
    val userId: Long,
    val nickname: String?,
    val avatarUrl: String?,
)

internal data class ListenTogetherRoom(
    val roomId: String,
    val creatorId: Long,
    val users: List<ListenTogetherUser>,
    val roomCreateTime: Long?,
    val effectiveDurationMs: Long?,
    val roomType: String?,
)

internal data class ListenTogetherRoomStatus(
    val inRoom: Boolean,
    val room: ListenTogetherRoom?,
    val status: String?,
)

internal data class ListenTogetherRoomCheck(
    val joinable: Boolean,
    val type: String?,
    val status: String?,
)

/** 服务端保留的最新一条播放命令，轮询 `sync/playlist/get` 时随快照一起返回。 */
internal data class ListenTogetherPlayCommand(
    val commandType: String?,
    val progressMs: Long,
    val playStatus: String?,
    val formerSongId: String?,
    val targetSongId: String?,
    val clientSeq: Long,
    val serverSeq: Long,
    val userId: Long?,
)

internal data class ListenTogetherPlaylistVersion(
    val userId: Long,
    val version: Int,
)

internal data class ListenTogetherPlaylistSnapshot(
    val displaySongIds: List<String>,
    val playMode: String?,
    val replace: Boolean,
    val versions: List<ListenTogetherPlaylistVersion>,
)

internal data class ListenTogetherSyncSnapshot(
    val command: ListenTogetherPlayCommand?,
    val playlist: ListenTogetherPlaylistSnapshot?,
)

internal data class ListenTogetherCreateResult(
    val status: NeteaseAccountActionStatus,
    val room: ListenTogetherRoom? = null,
    val code: Int? = null,
)

internal data class ListenTogetherStatusResult(
    val status: NeteaseAccountActionStatus,
    val roomStatus: ListenTogetherRoomStatus? = null,
    val code: Int? = null,
)

internal data class ListenTogetherCheckResult(
    val status: NeteaseAccountActionStatus,
    val check: ListenTogetherRoomCheck? = null,
    val code: Int? = null,
)

internal data class ListenTogetherSyncResult(
    val status: NeteaseAccountActionStatus,
    val snapshot: ListenTogetherSyncSnapshot? = null,
    val code: Int? = null,
)
