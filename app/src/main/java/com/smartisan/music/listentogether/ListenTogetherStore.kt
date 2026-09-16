package com.smartisan.music.listentogether

import androidx.media3.common.Player
import com.smartisan.music.data.online.ListenTogetherPlayCommand
import com.smartisan.music.data.online.ListenTogetherRoom
import com.smartisan.music.data.online.ListenTogetherUser
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseSourceId
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.onlineIdentityOrNull
import com.smartisan.music.data.online.runSuspendCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 「一起听」房间连接状态。 */
internal enum class ListenTogetherConnectionState {
    Disconnected,
    Creating,
    Joining,
    Connected,
    Reconnecting,
}

/** 暴露给 UI 的会话状态快照。 */
internal data class ListenTogetherSessionState(
    val connectionState: ListenTogetherConnectionState = ListenTogetherConnectionState.Disconnected,
    val roomId: String? = null,
    val selfUserId: Long? = null,
    val isHost: Boolean = false,
    val users: List<ListenTogetherUser> = emptyList(),
    val message: String? = null,
)

/**
 * 「一起听」状态机：建房/入房、1s 轮询同步、本地播放事件上报、远端命令应用、房散检测。
 *
 * 协议是纯 HTTP 轮询（无长连接）：`sync/playlist/get` 带回最新播放命令与队列快照，
 * 这里只消费命令（路线 A 不含队列同步）。命令带 `userId` 标识发起者，据此跳过自己
 * 上报后被服务端回显的命令，避免回声。
 */
internal class ListenTogetherStore(
    private val router: OnlineMusicRepositoryRouter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {

    private val _state = MutableStateFlow(ListenTogetherSessionState())
    val state: StateFlow<ListenTogetherSessionState> = _state.asStateFlow()

    private var player: Player? = null

    private var roomId: String? = null
    private var myUserId: Long? = null
    private var lastAppliedServerSeq: Long = 0L
    private var clientSeq: Long = 0L
    private var playlistVersion: Long = 0L
    private var suppressReportsUntilMs: Long = 0L

    private var pollJob: Job? = null
    private var playlistReportJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (roomId == null || System.currentTimeMillis() < suppressReportsUntilMs) {
                return
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                schedulePlaylistSnapshotReport()
            }
            val type = when {
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) -> "GOTO"
                events.contains(Player.EVENT_IS_PLAYING_CHANGED) ->
                    if (player.isPlaying) "PLAY" else "PAUSE"
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) -> "PROGRESS"
                else -> null
            } ?: return
            reportPlaybackCommand(type)
        }
    }

    /** UI 层拿到 [Player]（MediaController）后接入；离开房间/销毁时调用 [detach]。 */
    fun attach(player: Player) {
        if (this.player === player) {
            return
        }
        detach()
        this.player = player
        player.addListener(playerListener)
    }

    fun detach() {
        player?.removeListener(playerListener)
        player = null
    }

    /** 创建房间并返回邀请链接；失败返回 null。供「一起听」入口一步完成创建 + 分享。 */
    suspend fun createRoomInviteUrl(): String? {
        val room = doCreateRoom() ?: return null
        val inviterId = myUserId?.toString() ?: room.creatorId.toString()
        return buildListenTogetherInviteUrl(roomId = room.roomId, inviterId = inviterId)
    }

    /** 从邀请链接入房：短链先解出长链再解析 roomId/inviterId。 */
    fun joinRoomFromUrl(url: String) {
        scope.launch {
            val target = resolveListenTogetherInviteUrl(url)
            val invite = parseListenTogetherInviteParams(target) ?: return@launch
            doJoinRoom(invite.roomId, invite.inviterId)
        }
    }

    fun leaveRoom() {
        scope.launch {
            val id = roomId
            if (id != null) {
                runSuspendCatching { router.endListenTogetherRoom(id) }
            }
            endSession("已退出一起听")
        }
    }

    private suspend fun doCreateRoom(): ListenTogetherRoom? {
        _state.update { it.copy(connectionState = ListenTogetherConnectionState.Creating, message = null) }
        val profile = runSuspendCatching { router.currentUserProfile() }.getOrNull()
        val result = runSuspendCatching { router.createListenTogetherRoom() }.getOrNull()
        val room = result?.takeIf { it.status == NeteaseAccountActionStatus.Success }?.room
        if (room == null) {
            _state.update {
                it.copy(connectionState = ListenTogetherConnectionState.Disconnected, message = "创建房间失败")
            }
            return null
        }
        myUserId = profile?.userId ?: room.creatorId
        roomId = room.roomId
        _state.update {
            it.copy(
                connectionState = ListenTogetherConnectionState.Connected,
                roomId = room.roomId,
                selfUserId = myUserId,
                isHost = true,
                users = room.users,
                message = null,
            )
        }
        // 房间必须先有队列，sync/playlist/get 才会返回命令；随后把自己的当前歌曲推一次。
        runSuspendCatching { seedPlaylistSnapshot(room.roomId) }
        runSuspendCatching { reportPlaybackCommand("GOTO", force = true) }
        startPolling()
        return room
    }

    private suspend fun doJoinRoom(roomIdArg: String, inviterIdArg: String) {
        _state.update { it.copy(connectionState = ListenTogetherConnectionState.Joining, message = null) }
        val profile = runSuspendCatching { router.currentUserProfile() }.getOrNull()
        val result = runSuspendCatching {
            router.acceptListenTogetherInvitation(roomIdArg.trim(), inviterIdArg.trim())
        }.getOrNull()
        val room = result?.takeIf { it.status == NeteaseAccountActionStatus.Success }?.room
        if (room == null) {
            _state.update {
                it.copy(connectionState = ListenTogetherConnectionState.Disconnected, message = "加入房间失败")
            }
            return
        }
        myUserId = profile?.userId
        roomId = room.roomId
        _state.update {
            it.copy(
                connectionState = ListenTogetherConnectionState.Connected,
                roomId = room.roomId,
                selfUserId = myUserId,
                isHost = room.creatorId == myUserId,
                users = room.users,
                message = null,
            )
        }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var tick = 0L
            while (isActive) {
                tick += 1
                val id = roomId ?: return@launch
                pollSync(id)
                if (tick == 1L || tick % StatusEveryTicks == 0L) {
                    refreshRoomStatus(id)
                }
                if (tick == 1L || tick % HeartbeatEveryTicks == 0L) {
                    sendHeartbeat(id)
                }
                delay(PollIntervalMs)
            }
        }
    }

    private suspend fun pollSync(id: String) {
        val result = runSuspendCatching { router.syncListenTogether(id) }.getOrNull()
        if (result?.status != NeteaseAccountActionStatus.Success) {
            return
        }
        result.snapshot?.command?.let { applyRemoteCommand(it) }
    }

    private suspend fun refreshRoomStatus(id: String) {
        val result = runSuspendCatching { router.listenTogetherStatus() }.getOrNull()
        if (result?.status != NeteaseAccountActionStatus.Success) {
            markReconnecting()
            return
        }
        val roomStatus = result.roomStatus ?: return
        if (!roomStatus.inRoom) {
            endSession("一起听已结束")
            return
        }
        roomStatus.room?.let { room ->
            _state.update {
                it.copy(
                    connectionState = ListenTogetherConnectionState.Connected,
                    isHost = room.creatorId == myUserId,
                    users = room.users,
                )
            }
        }
    }

    private suspend fun sendHeartbeat(id: String) {
        val player = player ?: return
        val trackId = player.currentMediaItem
            ?.onlineIdentityOrNull()
            ?.takeIf { it.source == NeteaseSourceId }
            ?.trackId
            ?: return
        runSuspendCatching {
            router.listenTogetherHeartbeat(
                roomId = id,
                songId = trackId,
                playStatus = if (player.isPlaying) "PLAY" else "PAUSE",
                progressMs = player.currentPosition.coerceAtLeast(0L),
            )
        }
    }

    /** 队列变化后防抖上报快照（避免拖动/连切时的高频上报）。 */
    private fun schedulePlaylistSnapshotReport() {
        playlistReportJob?.cancel()
        playlistReportJob = scope.launch {
            delay(PlaylistReportDebounceMs)
            val id = roomId ?: return@launch
            runSuspendCatching { seedPlaylistSnapshot(id) }
        }
    }

    /** 上报当前播放队列快照（房间必须持有队列，轮询才会带回命令）。 */
    private suspend fun seedPlaylistSnapshot(roomId: String) {
        val player = player ?: return
        val displayIds = buildList {
            for (index in 0 until player.mediaItemCount) {
                player.getMediaItemAt(index)
                    .onlineIdentityOrNull()
                    ?.takeIf { it.source == NeteaseSourceId }
                    ?.trackId
                    ?.let(::add)
            }
        }
        if (displayIds.isEmpty()) {
            return
        }
        val userId = myUserId ?: return
        val playlistParam = buildListenTogetherPlaylistParam(
            displaySongIds = displayIds,
            userId = userId,
            version = (++playlistVersion).toInt(),
        )
        router.reportListenTogetherPlaylist(roomId, playlistParam)
    }

    private fun markReconnecting() {
        if (_state.value.connectionState != ListenTogetherConnectionState.Reconnecting) {
            _state.update { it.copy(connectionState = ListenTogetherConnectionState.Reconnecting) }
        }
    }

    private fun endSession(message: String) {
        pollJob?.cancel()
        pollJob = null
        playlistReportJob?.cancel()
        playlistReportJob = null
        roomId = null
        lastAppliedServerSeq = 0L
        clientSeq = 0L
        playlistVersion = 0L
        suppressReportsUntilMs = 0L
        _state.update { state ->
            state.copy(
                connectionState = ListenTogetherConnectionState.Disconnected,
                roomId = null,
                selfUserId = null,
                isHost = false,
                users = emptyList(),
                message = message,
            )
        }
    }

    private suspend fun applyRemoteCommand(command: ListenTogetherPlayCommand) {
        if (!shouldApplyRemoteCommand(command, myUserId, lastAppliedServerSeq)) {
            return
        }
        lastAppliedServerSeq = command.serverSeq
        val targetId = command.targetSongId ?: return
        val player = player ?: return
        suppressReportsUntilMs = System.currentTimeMillis() + EchoSuppressMs
        val current = player.currentMediaItem?.onlineIdentityOrNull()
        if (current?.trackId != targetId) {
            val items = router.resolvePlayableItems(
                listOf(OnlineTrackIdentity(source = NeteaseSourceId, trackId = targetId)),
                includeLyrics = false,
            )
            val item = items.firstOrNull()
            if (item == null) {
                _state.update { it.copy(message = "无法播放对方点播的歌曲") }
                return
            }
            player.setMediaItem(item)
            player.prepare()
        }
        player.seekTo(command.progressMs.coerceAtLeast(0L))
        when (command.playStatus) {
            "PLAY" -> player.play()
            "PAUSE" -> player.pause()
        }
    }

    private fun reportPlaybackCommand(type: String, force: Boolean = false) {
        val player = player ?: return
        val id = roomId ?: return
        if (!force && System.currentTimeMillis() < suppressReportsUntilMs) {
            return
        }
        val trackId = player.currentMediaItem
            ?.onlineIdentityOrNull()
            ?.takeIf { it.source == NeteaseSourceId }
            ?.trackId
            ?: return
        val commandInfo = buildListenTogetherCommandInfo(
            commandType = type,
            progressMs = player.currentPosition.coerceAtLeast(0L),
            playStatus = if (player.isPlaying) "PLAY" else "PAUSE",
            targetSongId = trackId,
            clientSeq = ++clientSeq,
        )
        scope.launch {
            runSuspendCatching { router.reportListenTogetherCommand(id, commandInfo) }
        }
    }
}

internal fun buildListenTogetherPlaylistParam(
    displaySongIds: List<String>,
    userId: Long,
    version: Int,
): String {
    val idsArray = JSONArray(displaySongIds)
    val versionArray = JSONArray().put(
        JSONObject()
            .put("userId", userId)
            .put("version", version),
    )
    return JSONObject()
        .put("commandType", "REPLACE")
        .put("version", versionArray)
        .put("anchorSongId", "")
        .put("anchorPosition", -1)
        .put("randomList", idsArray)
        .put("displayList", idsArray)
        .toString()
}

/**
 * 远端命令是否要应用：跳过自己上报后被服务端回显的命令（同 userId），
 * 以及已应用过的旧命令（serverSeq 单调递增）。
 */
internal fun shouldApplyRemoteCommand(
    command: ListenTogetherPlayCommand,
    myUserId: Long?,
    lastAppliedServerSeq: Long,
): Boolean {
    if (myUserId != null && command.userId == myUserId) {
        return false
    }
    return command.serverSeq > lastAppliedServerSeq
}

internal fun buildListenTogetherCommandInfo(
    commandType: String,
    progressMs: Long,
    playStatus: String,
    targetSongId: String,
    clientSeq: Long,
): String {
    return JSONObject()
        .put("commandType", commandType)
        .put("progress", progressMs.coerceAtLeast(0L))
        .put("playStatus", playStatus)
        .put("formerSongId", "-1")
        .put("targetSongId", targetSongId)
        .put("clientSeq", clientSeq)
        .toString()
}

private const val PollIntervalMs = 1_000L
private const val StatusEveryTicks = 5L
private const val HeartbeatEveryTicks = 30L
private const val EchoSuppressMs = 1_000L
private const val PlaylistReportDebounceMs = 350L
