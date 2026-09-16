package com.smartisan.music.listentogether

import androidx.media3.common.Player
import com.smartisan.music.data.online.ListenTogetherPlayCommand
import com.smartisan.music.data.online.ListenTogetherPlaylistSnapshot
import com.smartisan.music.data.online.ListenTogetherRoom
import com.smartisan.music.data.online.ListenTogetherUser
import com.smartisan.music.data.online.NeteaseAccountActionStatus
import com.smartisan.music.data.online.NeteaseSourceId
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.onlineIdentityOrNull
import com.smartisan.music.data.online.runSuspendCatching
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
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
    val otherMember: ListenTogetherUser? = null,
    val accumulatedSeconds: Long = 0L,
    val thisRoomSeconds: Long = 0L,
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
    private var appliedPlaylistVersion: Int = 0
    private var suppressReportsUntilMs: Long = 0L

    private var pollJob: Job? = null
    private var playlistReportJob: Job? = null
    private var durationJob: Job? = null

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
                otherMember = otherMemberOf(room.users),
                message = null,
            )
        }
        // 房间必须先有队列，sync/playlist/get 才会返回命令；随后把自己的当前歌曲推一次。
        runSuspendCatching { seedPlaylistSnapshot(room.roomId) }
        runSuspendCatching { reportPlaybackCommand("GOTO", force = true) }
        startPolling()
        startLocalDurationTimer()
        scope.launch { refreshStatistics(room.roomId) }
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
                otherMember = otherMemberOf(room.users),
                message = null,
            )
        }
        startPolling()
        startLocalDurationTimer()
        scope.launch { refreshStatistics(room.roomId) }
    }

    private fun startLocalDurationTimer() {
        durationJob?.cancel()
        durationJob = scope.launch {
            while (isActive) {
                delay(LocalDurationTickMs)
                if (roomId == null) {
                    return@launch
                }
                _state.update { it.copy(thisRoomSeconds = it.thisRoomSeconds + LocalDurationStepSeconds) }
            }
        }
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
        result.snapshot?.playlist?.let { applyRemotePlaylist(it) }
        result.snapshot?.command?.let { applyRemoteCommand(it) }
    }

    /**
     * 把房间队列拉到本地播放器。
     *
     * 房主把队列 REPLACE 进房间后，加入方只能从这里拿到歌单——只消费 playCommand 的话，
     * 房主没切过歌（无命令）时本地队列就一直是空的。以 version 判新旧避免每轮重复重建，
     * 且只在确实变新时才动播放器，保住当前播放进度。
     *
     * 只给加入方用：房主自己的队列就是房间队列（建房时已 seed），再采纳一次只会白重建；
     * 更要紧的是别让对端上报的版本反过来顶掉房主已经在用的队列。
     */
    private suspend fun applyRemotePlaylist(playlist: ListenTogetherPlaylistSnapshot) {
        val player = player ?: return
        if (_state.value.isHost) {
            return
        }
        // versions 是各端各自的版本号，只认对方的；自己那份是自己 seed 的，回读会白重建一次。
        val remoteVersion =
            playlist.versions.filterNot { it.userId == myUserId }.maxOfOrNull { it.version } ?: return
        if (!shouldApplyRemotePlaylist(remoteVersion, appliedPlaylistVersion)) {
            return
        }
        val songIds = playlist.displaySongIds.map(String::trim).filter(String::isNotEmpty)
        if (songIds.isEmpty()) {
            return
        }
        appliedPlaylistVersion = remoteVersion

        val currentTrackId = player.currentMediaItem
            ?.onlineIdentityOrNull()
            ?.takeIf { it.source == NeteaseSourceId }
            ?.trackId
        val items = router.getMediaItems(
            songIds.map { OnlineTrackIdentity(source = NeteaseSourceId, trackId = it) },
        ).map { it.withOnlinePlaybackPlaceholderUri() }
        if (items.isEmpty()) {
            return
        }
        // 队列重建会让播放器回到第一首，按当前歌在新队列里的位置复位，听感上不跳。
        val resumeIndex = items.indexOfFirst { item ->
            item.onlineIdentityOrNull()?.takeIf { it.source == NeteaseSourceId }?.trackId == currentTrackId
        }
        val startIndex = resumeIndex.coerceAtLeast(0)
        val startPositionMs =
            if (resumeIndex >= 0) player.currentPosition.coerceAtLeast(0L) else 0L
        suppressReportsUntilMs = System.currentTimeMillis() + EchoSuppressMs
        player.setMediaItems(items, startIndex, startPositionMs)
        player.prepare()
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
            val newOtherMember = otherMemberOf(room.users)
            val previousOtherUserId = _state.value.otherMember?.userId
            _state.update {
                it.copy(
                    connectionState = ListenTogetherConnectionState.Connected,
                    isHost = room.creatorId == myUserId,
                    users = room.users,
                    otherMember = newOtherMember,
                )
            }
            if (newOtherMember?.userId != null && newOtherMember.userId != previousOtherUserId) {
                scope.launch { refreshStatistics(id) }
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

    /** 房间里除自己以外的那个成员；自己是唯一成员或未确认 selfId 时为空。 */
    private fun otherMemberOf(users: List<ListenTogetherUser>): ListenTogetherUser? {
        val selfId = myUserId ?: return null
        return users.firstOrNull { it.userId != selfId }
    }

    /**
     * 拉取双方历史累计时长。失败时静默降级：保持现状（只显示本地计时），
     * 不弹 Toast、不打断同步——统计属于展示辅助，不是会话主线。
     */
    private suspend fun refreshStatistics(roomId: String) {
        val state = _state.value
        val selfId = state.selfUserId ?: return
        val otherId = state.otherMember?.userId ?: return
        val roomUserIds = if (state.isHost) listOf(selfId, otherId) else listOf(otherId, selfId)
        val result = runSuspendCatching {
            router.getListenTogetherStatistics(roomId, roomUserIds)
        }.getOrNull()
        if (result?.status != NeteaseAccountActionStatus.Success) {
            return
        }
        result.statistics?.let { statistics ->
            _state.update { it.copy(accumulatedSeconds = statistics.totalConnectionTimeSeconds) }
        }
    }

    private fun endSession(message: String) {
        pollJob?.cancel()
        pollJob = null
        playlistReportJob?.cancel()
        playlistReportJob = null
        durationJob?.cancel()
        durationJob = null
        roomId = null
        lastAppliedServerSeq = 0L
        clientSeq = 0L
        playlistVersion = 0L
        appliedPlaylistVersion = 0
        suppressReportsUntilMs = 0L
        _state.update { state ->
            state.copy(
                connectionState = ListenTogetherConnectionState.Disconnected,
                roomId = null,
                selfUserId = null,
                isHost = false,
                users = emptyList(),
                otherMember = null,
                accumulatedSeconds = 0L,
                thisRoomSeconds = 0L,
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
            val queueIndex = player.onlineTrackIndex(targetId)
            if (queueIndex >= 0) {
                // 目标歌已在房间队列里：只切索引，别把整队换成单曲。
                player.seekTo(queueIndex, 0L)
            } else {
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
        }
        player.seekTo(command.progressMs.coerceAtLeast(0L))
        when (command.playStatus) {
            "PLAY" -> player.play()
            "PAUSE" -> player.pause()
        }
    }

    /** 目标曲目在当前播放队列里的下标；不在队列中返回 -1。 */
    private fun Player.onlineTrackIndex(trackId: String): Int {
        for (index in 0 until mediaItemCount) {
            val identity = getMediaItemAt(index).onlineIdentityOrNull() ?: continue
            if (identity.source == NeteaseSourceId && identity.trackId == trackId) {
                return index
            }
        }
        return -1
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

/**
 * 房间队列是否比本地已应用的更新。版本号由各端自己上报、服务端只回显，
 * 因此「同版本」是常态（每轮轮询都会读回上次的快照），必须判等跳过，
 * 否则每秒都会重建一次播放队列。
 */
internal fun shouldApplyRemotePlaylist(
    remoteVersion: Int,
    appliedVersion: Int,
): Boolean {
    return remoteVersion > appliedVersion
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
private const val LocalDurationTickMs = 60_000L
private const val LocalDurationStepSeconds = 60L
