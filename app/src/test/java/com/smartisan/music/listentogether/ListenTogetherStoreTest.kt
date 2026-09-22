package com.smartisan.music.listentogether

import com.smartisan.music.data.online.ListenTogetherPlayCommand
import com.smartisan.music.data.online.ListenTogetherRoom
import com.smartisan.music.data.online.ListenTogetherUser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStoreTest {

    private fun command(
        userId: Long? = 565839171L,
        serverSeq: Long = 100L,
        clientSeq: Long = 1L,
        targetSongId: String? = "33894312",
    ) = ListenTogetherPlayCommand(
        commandType = "GOTO",
        progressMs = 0L,
        playStatus = "PLAY",
        formerSongId = "-1",
        targetSongId = targetSongId,
        clientSeq = clientSeq,
        serverSeq = serverSeq,
        userId = userId,
    )

    private fun disposition(
        command: ListenTogetherPlayCommand,
        myUserId: Long? = 42L,
        lastAppliedServerSeq: Long = 0L,
        reportWindow: ListenTogetherReportWindow? = null,
        nowMs: Long = 0L,
    ) = remoteCommandDisposition(
        command = command,
        myUserId = myUserId,
        lastAppliedServerSeq = lastAppliedServerSeq,
        reportWindow = reportWindow,
        nowMs = nowMs,
    )

    private fun room(creatorId: Long, memberIds: List<Long>) =
        ListenTogetherRoom(
            roomId = "room-1",
            creatorId = creatorId,
            users = memberIds.map { ListenTogetherUser(userId = it, nickname = null, avatarUrl = null) },
            roomCreateTime = null,
            effectiveDurationMs = null,
            roomType = null,
        )

    @Test
    fun skipsOwnEchoedCommand() {
        assertEquals(
            RemoteCommandDisposition.SkipEcho,
            disposition(command(userId = 42L), myUserId = 42L),
        )
    }

    @Test
    fun appliesForeignCommand() {
        assertEquals(
            RemoteCommandDisposition.Apply,
            disposition(command(userId = 99L, serverSeq = 5L), myUserId = 42L),
        )
    }

    @Test
    fun skipsAlreadyAppliedServerSeq() {
        assertEquals(
            RemoteCommandDisposition.SkipStale,
            disposition(
                command(userId = 99L, serverSeq = 5L),
                myUserId = 42L,
                lastAppliedServerSeq = 5L,
            ),
        )
    }

    /**
     * 修掉的老行为：myUserId 未知时无条件应用所有命令，于是自己上报的命令被服务端回显后
     * 每秒 replay 一遍（seek 回旧位置、播放状态来回抖）。现在按上报窗口认回声。
     */
    @Test
    fun skipsOwnEchoByReportWindowWhenSelfUnknown() {
        val window = ListenTogetherReportWindow(lastClientSeq = 7L, lastReportAtMs = 1_000L)

        assertEquals(
            RemoteCommandDisposition.SkipEcho,
            disposition(
                command(userId = null, clientSeq = 7L, serverSeq = 9L),
                myUserId = null,
                reportWindow = window,
                nowMs = 1_500L,
            ),
        )
    }

    @Test
    fun appliesForeignCommandWhenSelfUnknownAndReportMisses() {
        val window = ListenTogetherReportWindow(lastClientSeq = 7L, lastReportAtMs = 1_000L)

        // 对端 clientSeq 比自己最近上报的还新：不可能是自己的回显。
        assertEquals(
            RemoteCommandDisposition.Apply,
            disposition(
                command(userId = 99L, clientSeq = 12L, serverSeq = 5L),
                myUserId = null,
                reportWindow = window,
                nowMs = 1_200L,
            ),
        )
    }

    @Test
    fun appliesForeignCommandWhenSelfUnknownAndWindowElapsed() {
        val window = ListenTogetherReportWindow(lastClientSeq = 7L, lastReportAtMs = 1_000L)

        // 窗口过期后不再按 seq 判回声；同一条回显由调用方推高的 serverSeq 水位挡住，不会回来应用。
        assertEquals(
            RemoteCommandDisposition.Apply,
            disposition(
                command(userId = null, clientSeq = 7L, serverSeq = 9L),
                myUserId = null,
                reportWindow = window,
                nowMs = 1_000L + ListenTogetherEchoWindowMs + 1L,
            ),
        )
        // 没有上报窗口（从未上报过）时同样放行。
        assertEquals(
            RemoteCommandDisposition.Apply,
            disposition(
                command(userId = 99L, clientSeq = 1L, serverSeq = 5L),
                myUserId = null,
                reportWindow = null,
                nowMs = 5_000L,
            ),
        )
    }

    @Test
    fun skipsOwnEchoAfterSelfIdBackfilled() {
        // selfId 补齐后回到按 userId 判回声：不依赖上报窗口，窗口过期也不放行。
        assertEquals(
            RemoteCommandDisposition.SkipEcho,
            disposition(
                command(userId = 42L, clientSeq = 7L, serverSeq = 9L),
                myUserId = 42L,
                reportWindow = null,
                nowMs = 60_000L,
            ),
        )
    }

    @Test
    fun infersSelfFromTwoMemberRoomWhenProfileMissing() {
        // 两人房、房主在成员表里：另一个就是自己（资料接口失败时的兜底）。
        assertEquals(42L, inferSelfUserId(room(creatorId = 99L, memberIds = listOf(99L, 42L))))
    }

    @Test
    fun doesNotInferSelfWhenRoomShapeIsAmbiguous() {
        // 人数不是两人、或房主不在成员表里都认不出自己；宁可交给上报窗口兜着，也别猜错。
        assertNull(inferSelfUserId(room(creatorId = 99L, memberIds = listOf(99L, 42L, 7L))))
        assertNull(inferSelfUserId(room(creatorId = 99L, memberIds = listOf(42L))))
    }

    @Test
    fun doesNotOfferSameInviteTwice() {
        val invite = ListenTogetherInvite(roomId = "room-1", inviterId = "42")

        assertTrue(shouldOfferPendingInvite(invite, pendingInvite = null, currentRoomId = null))
        // Activity 重建后深链再解析一次：同一条邀请不重复弹窗。
        assertFalse(shouldOfferPendingInvite(invite, pendingInvite = invite, currentRoomId = null))
        assertFalse(shouldOfferPendingInvite(invite, pendingInvite = null, currentRoomId = "room-1"))
    }

    @Test
    fun pollingPausesOnlyInBackgroundWithoutPlayback() {
        assertTrue(shouldPollListenTogether(appForeground = true, isPlaying = false))
        assertTrue(shouldPollListenTogether(appForeground = true, isPlaying = true))
        // 后台仍在播放：进度还要同步给房间，轮询不能停。
        assertTrue(shouldPollListenTogether(appForeground = false, isPlaying = true))
        assertFalse(shouldPollListenTogether(appForeground = false, isPlaying = false))
    }

    @Test
    fun appliesPlaylistWhenRemoteVersionIsNewer() {
        assertTrue(shouldApplyRemotePlaylist(remoteVersion = 1, appliedVersion = 0))
        assertTrue(shouldApplyRemotePlaylist(remoteVersion = 3, appliedVersion = 2))
    }

    @Test
    fun skipsPlaylistWhenVersionUnchangedOrOlder() {
        // 每轮轮询都会回读同一份快照，判等必须跳过，否则每秒重建一次队列。
        assertFalse(shouldApplyRemotePlaylist(remoteVersion = 2, appliedVersion = 2))
        assertFalse(shouldApplyRemotePlaylist(remoteVersion = 1, appliedVersion = 4))
    }

    @Test
    fun commandInfoCarriesProtocolFields() {
        val info = buildListenTogetherCommandInfo(
            commandType = "GOTO",
            progressMs = 12_345L,
            playStatus = "PLAY",
            targetSongId = "33894312",
            clientSeq = 7L,
        )
        val json = JSONObject(info)
        assertEquals("GOTO", json.getString("commandType"))
        assertEquals(12_345L, json.getLong("progress"))
        assertEquals("PLAY", json.getString("playStatus"))
        assertEquals("33894312", json.getString("targetSongId"))
        assertEquals("-1", json.getString("formerSongId"))
        assertEquals(7L, json.getLong("clientSeq"))
    }

    @Test
    fun inviteUrlBuildsWithRoomAndInviter() {
        val url = buildListenTogetherInviteUrl(roomId = "abc_123", inviterId = "456")
        assertTrue(url.startsWith("https://st.music.163.com/listen-together/share/?"))
        val invite = parseListenTogetherInviteParams(url)!!
        assertEquals("abc_123", invite.roomId)
        assertEquals("456", invite.inviterId)
    }

    @Test
    fun inviteParamsParsesOfficialRedirectShape() {
        val invite = parseListenTogetherInviteParams(
            "https://st.music.163.com/listen-together/share/?songId=376971" +
                "&roomId=9f59e580ad62e4b822e8d9d2dceacf26_1789558141&inviterId=8259701419",
        )!!
        assertEquals("9f59e580ad62e4b822e8d9d2dceacf26_1789558141", invite.roomId)
        assertEquals("8259701419", invite.inviterId)
    }

    @Test
    fun inviteParamsReturnsNullWhenMissingInviter() {
        assertTrue(
            parseListenTogetherInviteParams(
                "https://st.music.163.com/listen-together/share/?roomId=abc",
            ) == null,
        )
    }

    @Test
    fun shortLinkIsDetected() {
        assertTrue(isListenTogetherShortLink("https://163cn.tv/bgujlflK"))
        assertFalse(isListenTogetherShortLink("https://st.music.163.com/listen-together/share/?roomId=x&inviterId=y"))
    }
}
