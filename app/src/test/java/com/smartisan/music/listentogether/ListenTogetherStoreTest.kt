package com.smartisan.music.listentogether

import com.smartisan.music.data.online.ListenTogetherPlayCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherStoreTest {

    private fun command(
        userId: Long? = 565839171L,
        serverSeq: Long = 100L,
        targetSongId: String? = "33894312",
    ) = ListenTogetherPlayCommand(
        commandType = "GOTO",
        progressMs = 0L,
        playStatus = "PLAY",
        formerSongId = "-1",
        targetSongId = targetSongId,
        clientSeq = 1L,
        serverSeq = serverSeq,
        userId = userId,
    )

    @Test
    fun skipsOwnEchoedCommand() {
        assertFalse(
            shouldApplyRemoteCommand(
                command = command(userId = 42L),
                myUserId = 42L,
                lastAppliedServerSeq = 0L,
            ),
        )
    }

    @Test
    fun appliesForeignCommand() {
        assertTrue(
            shouldApplyRemoteCommand(
                command = command(userId = 99L, serverSeq = 5L),
                myUserId = 42L,
                lastAppliedServerSeq = 0L,
            ),
        )
    }

    @Test
    fun skipsAlreadyAppliedServerSeq() {
        assertFalse(
            shouldApplyRemoteCommand(
                command = command(userId = 99L, serverSeq = 5L),
                myUserId = 42L,
                lastAppliedServerSeq = 5L,
            ),
        )
    }

    @Test
    fun appliesForeignCommandEvenWithoutKnownSelf() {
        assertTrue(
            shouldApplyRemoteCommand(
                command = command(userId = 99L, serverSeq = 1L),
                myUserId = null,
                lastAppliedServerSeq = 0L,
            ),
        )
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
