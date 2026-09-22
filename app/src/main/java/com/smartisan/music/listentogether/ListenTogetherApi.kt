package com.smartisan.music.listentogether

import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.ListenTogetherCreateResult
import com.smartisan.music.data.online.ListenTogetherStatisticsResult
import com.smartisan.music.data.online.ListenTogetherStatusResult
import com.smartisan.music.data.online.ListenTogetherSyncResult
import com.smartisan.music.data.online.NeteaseAccountActionResult
import com.smartisan.music.data.online.NeteaseAccountProfile
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrackIdentity

/**
 * [ListenTogetherStore] 用到的在线接口子集。
 *
 * 抽接口的理由与账号数据面的 [com.smartisan.music.data.online.OnlineAccountRepository] 一致：
 * 房间协议要登录联网才有结果，而状态机的关键行为（入房由用户确认、回声水位、上报窗口）
 * 必须能用假实现跑起来——否则把修复改回缺陷，测试照样全绿。
 * 生产实现 [RouterListenTogetherApi] 逐条委托给在线音乐分派层，行为不变。
 */
internal interface ListenTogetherApi {
    suspend fun currentUserProfile(): NeteaseAccountProfile?

    suspend fun createRoom(): ListenTogetherCreateResult

    suspend fun acceptInvitation(roomId: String, inviterId: String): ListenTogetherCreateResult

    suspend fun endRoom(roomId: String): NeteaseAccountActionResult

    suspend fun sync(roomId: String): ListenTogetherSyncResult

    suspend fun status(): ListenTogetherStatusResult

    suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): NeteaseAccountActionResult

    suspend fun reportCommand(roomId: String, commandInfo: String): NeteaseAccountActionResult

    suspend fun reportPlaylist(roomId: String, playlistParam: String): NeteaseAccountActionResult

    suspend fun statistics(roomId: String, roomUserIds: List<Long>): ListenTogetherStatisticsResult

    suspend fun mediaItems(identities: List<OnlineTrackIdentity>): List<MediaItem>

    suspend fun playableItems(
        identities: List<OnlineTrackIdentity>,
        includeLyrics: Boolean,
    ): List<MediaItem>
}

/** [ListenTogetherApi] 的生产实现：不做任何加工，逐条转发给 [router]。 */
internal class RouterListenTogetherApi(
    private val router: OnlineMusicRepositoryRouter,
) : ListenTogetherApi {

    override suspend fun currentUserProfile(): NeteaseAccountProfile? = router.currentUserProfile()

    override suspend fun createRoom(): ListenTogetherCreateResult = router.createListenTogetherRoom()

    override suspend fun acceptInvitation(
        roomId: String,
        inviterId: String,
    ): ListenTogetherCreateResult = router.acceptListenTogetherInvitation(roomId, inviterId)

    override suspend fun endRoom(roomId: String): NeteaseAccountActionResult =
        router.endListenTogetherRoom(roomId)

    override suspend fun sync(roomId: String): ListenTogetherSyncResult =
        router.syncListenTogether(roomId)

    override suspend fun status(): ListenTogetherStatusResult = router.listenTogetherStatus()

    override suspend fun heartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): NeteaseAccountActionResult =
        router.listenTogetherHeartbeat(roomId, songId, playStatus, progressMs)

    override suspend fun reportCommand(
        roomId: String,
        commandInfo: String,
    ): NeteaseAccountActionResult = router.reportListenTogetherCommand(roomId, commandInfo)

    override suspend fun reportPlaylist(
        roomId: String,
        playlistParam: String,
    ): NeteaseAccountActionResult = router.reportListenTogetherPlaylist(roomId, playlistParam)

    override suspend fun statistics(
        roomId: String,
        roomUserIds: List<Long>,
    ): ListenTogetherStatisticsResult = router.getListenTogetherStatistics(roomId, roomUserIds)

    override suspend fun mediaItems(identities: List<OnlineTrackIdentity>): List<MediaItem> =
        router.getMediaItems(identities)

    override suspend fun playableItems(
        identities: List<OnlineTrackIdentity>,
        includeLyrics: Boolean,
    ): List<MediaItem> = router.resolvePlayableItems(identities, includeLyrics)
}
