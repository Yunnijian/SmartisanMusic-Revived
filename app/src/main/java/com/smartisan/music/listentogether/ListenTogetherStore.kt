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
import kotlinx.coroutines.flow.first
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
 * 上报后被服务端回显的命令，避免回声；资料接口失败拿不到 `userId` 时退回上报窗口判回声。
 *
 * 入房一定经过用户确认（[offerJoinFromUrl] → [confirmPendingInvite]）：邀请链接是 BROWSABLE
 * 深链，任意网页/App 都能拉起，静默入房等于把本地播放控制权交出去。
 * 轮询只在「前台或正在播放」时跑（[pollingAllowed]），后台挂起时不发请求。
 *
 * 外部依赖（房间接口 [ListenTogetherApi]、播放器 [ListenTogetherPlayback]、时钟、轮询间隔）
 * 都可注入：协议要登录联网、`Player` 有上百个成员，不换假实现就没法在 JVM 单测里
 * 把状态机跑起来，关键行为也就守不住。默认值全是生产值。
 */
internal class ListenTogetherStore(
    private val api: ListenTogetherApi,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollIntervalMs: Long = PollIntervalMs,
) {

    constructor(router: OnlineMusicRepositoryRouter) : this(RouterListenTogetherApi(router))

    private val _state = MutableStateFlow(ListenTogetherSessionState())
    val state: StateFlow<ListenTogetherSessionState> = _state.asStateFlow()

    /**
     * 待确认的邀请。挂在应用级 Store 上而不是 Activity/ViewModel：Activity 因旋转重建、
     * 深链意图被重放时，同一个房间不会再弹一次。
     */
    private val _pendingInvite = MutableStateFlow<ListenTogetherInvite?>(null)
    val pendingInvite: StateFlow<ListenTogetherInvite?> = _pendingInvite.asStateFlow()

    private var playback: ListenTogetherPlayback? = null

    private var roomId: String? = null
    private var myUserId: Long? = null
    private var appForeground: Boolean = true
    private var lastAppliedServerSeq: Long = 0L
    private var clientSeq: Long = 0L

    /** 自己最近一次上报的播放命令，myUserId 未知时靠它认回声（见 [remoteCommandDisposition]）。 */
    private var reportWindow: ListenTogetherReportWindow? = null
    private var playlistVersion: Long = 0L
    private var appliedPlaylistVersion: Int = 0
    private var suppressReportsUntilMs: Long = 0L

    /** 轮询闸门：后台且未在播放时置 false，轮询协程挂在它上面等，而不是每秒空转。 */
    private val pollingAllowed = MutableStateFlow(true)

    private var pollJob: Job? = null
    private var playlistReportJob: Job? = null
    private var durationJob: Job? = null

    /** 建房/入房/退出这类会话切换：同一时刻只允许一个在跑。 */
    private var transitionJob: Job? = null

    private val playbackListener = object : ListenTogetherPlaybackListener {
        override fun onEvents(playback: ListenTogetherPlayback, events: ListenTogetherPlaybackEvents) {
            if (roomId == null) {
                return
            }
            if (events.isPlayingChanged) {
                // 播放状态是轮询闸门的一半：后台停轮询后，重新播放要立刻恢复同步。
                updatePollingGate()
            }
            if (clock() < suppressReportsUntilMs) {
                return
            }
            if (events.timelineChanged) {
                schedulePlaylistSnapshotReport()
            }
            val type = when {
                events.mediaItemTransition -> "GOTO"
                events.isPlayingChanged -> if (playback.isPlaying) "PLAY" else "PAUSE"
                events.positionDiscontinuity -> "PROGRESS"
                else -> null
            } ?: return
            reportPlaybackCommand(type)
        }
    }

    /** UI 层拿到 [Player]（MediaController）后接入；离开房间/销毁时调用 [detach]。 */
    fun attach(player: Player) {
        val current = playback
        if (current is PlayerListenTogetherPlayback && current.wraps(player)) {
            return
        }
        attachPlayback(PlayerListenTogetherPlayback(player))
    }

    /** 窄接口接入点：JVM 单测实现不了 [Player]，测试从 [ListenTogetherPlayback] 挂假播放器。 */
    internal fun attachPlayback(playback: ListenTogetherPlayback) {
        if (this.playback === playback) {
            return
        }
        detach()
        this.playback = playback
        playback.addListener(playbackListener)
        updatePollingGate()
    }

    fun detach() {
        playback?.removeListener(playbackListener)
        playback = null
        updatePollingGate()
    }

    /**
     * 前后台切换（壳层生命周期 ON_START/ON_STOP）。房间轮询是常驻 1 req/s 的流量，
     * 退到后台且没在播放时没有同步语义，挂起即可；回前台或恢复播放再继续。
     */
    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) {
            return
        }
        appForeground = foreground
        updatePollingGate()
    }

    private fun updatePollingGate() {
        pollingAllowed.value = shouldPollListenTogether(appForeground, playback?.isPlaying == true)
    }

    /** 创建房间并返回邀请链接；失败返回 null。供「一起听」入口一步完成创建 + 分享。 */
    suspend fun createRoomInviteUrl(): String? {
        val room = doCreateRoom() ?: return null
        val inviterId = myUserId?.toString() ?: room.creatorId.toString()
        return buildListenTogetherInviteUrl(roomId = room.roomId, inviterId = inviterId)
    }

    /**
     * 从邀请链接入房：短链先解出长链，解析出房间信息后只挂起 [pendingInvite] 等用户确认，
     * [confirmPendingInvite] 才真正入房。
     *
     * 邀请链接是 BROWSABLE 深链，任意网页/App 都能拉起它；静默入房会让链接发起方
     * 直接获得本地播放控制权，所以入房这一步必须由用户点。
     */
    fun offerJoinFromUrl(url: String) {
        scope.launch {
            val target = resolveListenTogetherInviteUrl(url)
            val invite = parseListenTogetherInviteParams(target)
            if (invite == null) {
                _state.update { it.copy(message = "一起听邀请链接无效") }
                return@launch
            }
            if (!shouldOfferPendingInvite(invite, _pendingInvite.value, roomId)) {
                return@launch
            }
            _pendingInvite.value = invite
        }
    }

    /** 用户确认加入邀请里的房间；已经在别的房间里时先按退出处理。 */
    fun confirmPendingInvite() {
        val invite = _pendingInvite.value ?: return
        _pendingInvite.value = null
        startTransition {
            if (roomId != null) {
                leaveRoomAndWait()
            }
            doJoinRoom(invite.roomId, invite.inviterId)
        }
    }

    /** 用户忽略邀请：没入过房，服务端无需清理。 */
    fun dismissPendingInvite() {
        _pendingInvite.value = null
    }

    /** 退出房间：结束服务端房间并清空本地会话（轮询、计时、命令水位一并停）。 */
    fun leaveRoom() {
        startTransition { leaveRoomAndWait() }
    }

    /** 建房/入房/退出互斥：新的一次会话切换进来时，上一次未落定的操作作废。 */
    private fun startTransition(block: suspend () -> Unit) {
        transitionJob?.cancel()
        transitionJob = scope.launch { block() }
    }

    private suspend fun leaveRoomAndWait() {
        val id = roomId
        if (id != null) {
            runSuspendCatching { api.endRoom(id) }
        }
        endSession("已退出一起听")
    }

    private suspend fun doCreateRoom(): ListenTogetherRoom? {
        _state.update { it.copy(connectionState = ListenTogetherConnectionState.Creating, message = null) }
        val profile = runSuspendCatching { api.currentUserProfile() }.getOrNull()
        val result = runSuspendCatching { api.createRoom() }.getOrNull()
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
        val profile = runSuspendCatching { api.currentUserProfile() }.getOrNull()
        val result = runSuspendCatching {
            api.acceptInvitation(roomIdArg.trim(), inviterIdArg.trim())
        }.getOrNull()
        val room = result?.takeIf { it.status == NeteaseAccountActionStatus.Success }?.room
        if (room == null) {
            _state.update {
                it.copy(connectionState = ListenTogetherConnectionState.Disconnected, message = "加入房间失败")
            }
            return
        }
        // 资料接口偶发失败会拿不到 selfId：先从房间成员里认自己（[inferSelfUserId]），
        // 仍认不出就交给轮询期的补拉与上报窗口兜底，别让会话停在这一步。
        myUserId = profile?.userId ?: inferSelfUserId(room)
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
        updatePollingGate()
        pollJob = scope.launch {
            var tick = 0L
            while (isActive) {
                val id = roomId ?: return@launch
                if (!pollingAllowed.value) {
                    // 后台且未在播放：挂在这里等闸门打开，不在后台空转；恢复后 tick 归零，
                    // 第一轮就把 sync/status/heartbeat 补回来。
                    pollingAllowed.first { it }
                    tick = 0L
                    continue
                }
                tick += 1
                pollSync(id)
                if (tick == 1L || tick % StatusEveryTicks == 0L) {
                    refreshRoomStatus(id)
                }
                if (tick == 1L || tick % HeartbeatEveryTicks == 0L) {
                    sendHeartbeat(id)
                }
                delay(pollIntervalMs)
            }
        }
    }

    private suspend fun pollSync(id: String) {
        val result = runSuspendCatching { api.sync(id) }.getOrNull()
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
        val playback = playback ?: return
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

        val currentTrackId = playback.currentTrackId
        val items = api.mediaItems(
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
            if (resumeIndex >= 0) playback.currentPositionMs.coerceAtLeast(0L) else 0L
        suppressReportsUntilMs = clock() + EchoSuppressMs
        playback.setQueue(items, startIndex, startPositionMs)
        playback.prepare()
    }

    private suspend fun refreshRoomStatus(id: String) {
        val result = runSuspendCatching { api.status() }.getOrNull()
        if (result?.status != NeteaseAccountActionStatus.Success) {
            markReconnecting()
            return
        }
        val roomStatus = result.roomStatus ?: return
        if (!roomStatus.inRoom) {
            endSession("一起听已结束")
            return
        }
        val room = roomStatus.room ?: return
        backfillSelfUserId(room)
        val newOtherMember = otherMemberOf(room.users)
        val previousOtherUserId = _state.value.otherMember?.userId
        _state.update {
            it.copy(
                connectionState = ListenTogetherConnectionState.Connected,
                selfUserId = myUserId,
                isHost = room.creatorId == myUserId,
                users = room.users,
                otherMember = newOtherMember,
            )
        }
        if (newOtherMember?.userId != null && newOtherMember.userId != previousOtherUserId) {
            scope.launch { refreshStatistics(id) }
        }
    }

    /**
     * selfId 补拉。入房时资料接口失败会让 myUserId 空着：对方成员认不出来、回声只能靠上报窗口兜。
     * 搭在 5s 一次的状态刷新上，拿到就补上，之后回到按 userId 判回声。
     */
    private suspend fun backfillSelfUserId(room: ListenTogetherRoom) {
        if (myUserId != null) {
            return
        }
        val profile = runSuspendCatching { api.currentUserProfile() }.getOrNull()
        myUserId = profile?.userId ?: inferSelfUserId(room)
    }

    private suspend fun sendHeartbeat(id: String) {
        val playback = playback ?: return
        val trackId = playback.currentTrackId ?: return
        runSuspendCatching {
            api.heartbeat(
                roomId = id,
                songId = trackId,
                playStatus = if (playback.isPlaying) "PLAY" else "PAUSE",
                progressMs = playback.currentPositionMs.coerceAtLeast(0L),
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
        val playback = playback ?: return
        val displayIds = playback.queueTrackIds.filterNotNull()
        if (displayIds.isEmpty()) {
            return
        }
        val userId = myUserId ?: return
        val playlistParam = buildListenTogetherPlaylistParam(
            displaySongIds = displayIds,
            userId = userId,
            version = (++playlistVersion).toInt(),
        )
        api.reportPlaylist(roomId, playlistParam)
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
            api.statistics(roomId, roomUserIds)
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
        myUserId = null
        lastAppliedServerSeq = 0L
        clientSeq = 0L
        reportWindow = null
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
        when (
            val disposition =
                remoteCommandDisposition(
                    command = command,
                    myUserId = myUserId,
                    lastAppliedServerSeq = lastAppliedServerSeq,
                    reportWindow = reportWindow,
                    nowMs = clock(),
                )
        ) {
            // 回声也要把水位推过去：同一条命令每秒都会随快照回来，
            // 不推水位的话上报窗口一过期它就被当成新命令再应用一遍（seek 回旧位置、播放状态来回抖）。
            RemoteCommandDisposition.SkipEcho -> {
                lastAppliedServerSeq = maxOf(lastAppliedServerSeq, command.serverSeq)
                return
            }
            RemoteCommandDisposition.SkipStale -> return
            RemoteCommandDisposition.Apply -> Unit
        }
        lastAppliedServerSeq = command.serverSeq
        val targetId = command.targetSongId ?: return
        val playback = playback ?: return
        suppressReportsUntilMs = clock() + EchoSuppressMs
        if (playback.currentTrackId != targetId) {
            val queueIndex = playback.trackIndex(targetId)
            if (queueIndex >= 0) {
                // 目标歌已在房间队列里：只切索引，别把整队换成单曲。
                playback.seekToTrack(queueIndex, 0L)
            } else {
                val items = api.playableItems(
                    listOf(OnlineTrackIdentity(source = NeteaseSourceId, trackId = targetId)),
                    includeLyrics = false,
                )
                val item = items.firstOrNull()
                if (item == null) {
                    _state.update { it.copy(message = "无法播放对方点播的歌曲") }
                    return
                }
                playback.setCurrentItem(item)
                playback.prepare()
            }
        }
        playback.seekToPosition(command.progressMs.coerceAtLeast(0L))
        when (command.playStatus) {
            "PLAY" -> playback.play()
            "PAUSE" -> playback.pause()
        }
    }

    private fun reportPlaybackCommand(type: String, force: Boolean = false) {
        val playback = playback ?: return
        val id = roomId ?: return
        if (!force && clock() < suppressReportsUntilMs) {
            return
        }
        val trackId = playback.currentTrackId ?: return
        val seq = ++clientSeq
        val commandInfo = buildListenTogetherCommandInfo(
            commandType = type,
            progressMs = playback.currentPositionMs.coerceAtLeast(0L),
            playStatus = if (playback.isPlaying) "PLAY" else "PAUSE",
            targetSongId = trackId,
            clientSeq = seq,
        )
        // 记在发送前：服务端回显可能在请求返回前就随快照到达。
        reportWindow =
            ListenTogetherReportWindow(
                lastClientSeq = seq,
                lastReportAtMs = clock(),
            )
        scope.launch {
            runSuspendCatching { api.reportCommand(id, commandInfo) }
        }
    }
}

/**
 * 「一起听」深链入口（MainActivity 拉起邀请链接时转发到这里）。
 *
 * 只登记待确认邀请，绝不入房：是 BROWSABLE 深链，任意网页/App 都能拉起它，
 * 入房必须由用户在确认弹层上点一下（[ListenTogetherStore.confirmPendingInvite]），
 * 否则链接发起方直接获得本地播放控制权。
 */
internal fun dispatchListenTogetherInviteDeepLink(store: ListenTogetherStore, url: String) {
    store.offerJoinFromUrl(url)
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

/** 自己最近一次上报的播放命令，myUserId 未知时靠它判回声（见 [isEchoOfOwnReport]）。 */
internal data class ListenTogetherReportWindow(
    val lastClientSeq: Long,
    val lastReportAtMs: Long,
)

/** 远端命令的处置结论。 */
internal enum class RemoteCommandDisposition {
    /** 应用到本地播放器。 */
    Apply,
    /** 自己上报后被服务端回显：跳过，调用方同时要把命令水位推过去。 */
    SkipEcho,
    /** 已经应用过的旧命令。 */
    SkipStale,
}

/**
 * 远端命令怎么处置：先认回声，再看 serverSeq 是否比已应用的新。
 *
 * 回声的判定基准是「发起者是不是自己」；资料接口失败导致 myUserId 未知时没有 userId 可比，
 * 退到 [ListenTogetherReportWindow]：clientSeq 不超过自己最近上报的值、且落在上报后
 * [ListenTogetherEchoWindowMs] 内就算自己的回显。代价是双方同一秒内各自操作时，
 * 对方那条会被当回声吞掉一次——相比每秒 replay 自己的命令（seek 回旧位置、播放状态来回抖），
 * 这个方向更划算，selfId 补齐后也不再走这条路。
 */
internal fun remoteCommandDisposition(
    command: ListenTogetherPlayCommand,
    myUserId: Long?,
    lastAppliedServerSeq: Long,
    reportWindow: ListenTogetherReportWindow?,
    nowMs: Long,
): RemoteCommandDisposition {
    val echoed =
        if (myUserId != null) {
            command.userId == myUserId
        } else {
            isEchoOfOwnReport(command, reportWindow, nowMs)
        }
    if (echoed) {
        return RemoteCommandDisposition.SkipEcho
    }
    return if (command.serverSeq > lastAppliedServerSeq) {
        RemoteCommandDisposition.Apply
    } else {
        RemoteCommandDisposition.SkipStale
    }
}

/**
 * 没有 selfId 时判回声：命令的 clientSeq 与自己最近上报的值对得上（可能更旧，有两条在飞），
 * 且落在 [windowMs] 窗口内。
 *
 * 两侧的 clientSeq 各自从 0 开始数，光比 seq 必然误伤，所以时间窗是这条例外的必要部分。
 */
internal fun isEchoOfOwnReport(
    command: ListenTogetherPlayCommand,
    reportWindow: ListenTogetherReportWindow?,
    nowMs: Long,
    windowMs: Long = ListenTogetherEchoWindowMs,
): Boolean {
    if (reportWindow == null || reportWindow.lastClientSeq <= 0L) {
        return false
    }
    if (command.clientSeq <= 0L || command.clientSeq > reportWindow.lastClientSeq) {
        return false
    }
    val elapsedMs = nowMs - reportWindow.lastReportAtMs
    return elapsedMs in 0..windowMs
}

/**
 * 是否把这条邀请挂成待确认：同一条邀请重复到达（Activity 重建后深链再解析一次）不重复弹窗，
 * 已经在同一个房间里也不再问。
 */
internal fun shouldOfferPendingInvite(
    invite: ListenTogetherInvite,
    pendingInvite: ListenTogetherInvite?,
    currentRoomId: String?,
): Boolean {
    if (invite.roomId == currentRoomId) {
        return false
    }
    return pendingInvite != invite
}

/**
 * 入房时资料接口没给出 userId 的兜底推断：两个人、房主在成员表里，剩下那个就是自己。
 *
 * 只在房间形态唯一可定时下结论：猜错会把对方的命令全当回声丢掉，
 * 比暂时认不出自己更糟（认不出还有上报窗口兜着）。
 */
internal fun inferSelfUserId(room: ListenTogetherRoom): Long? {
    val members = room.users
    if (members.size != ListenTogetherRoomMemberCount) {
        return null
    }
    if (members.none { it.userId == room.creatorId }) {
        return null
    }
    return members.firstOrNull { it.userId != room.creatorId }?.userId
}

/** 后台是否继续轮询：前台照常；后台只在还播放着时继续，其余时间停发请求。 */
internal fun shouldPollListenTogether(appForeground: Boolean, isPlaying: Boolean): Boolean {
    return appForeground || isPlaying
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

/** 没有 selfId 时认回声的时间窗：自己上报后这 1s 内回显的命令不应用。 */
internal const val ListenTogetherEchoWindowMs = 1_000L

/** 两人房是唯一能从成员表里认出自己的房间形态。 */
private const val ListenTogetherRoomMemberCount = 2
