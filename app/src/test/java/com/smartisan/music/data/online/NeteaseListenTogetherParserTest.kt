package com.smartisan.music.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseListenTogetherParserTest {

    private val roomInfoJson = """
        {
          "creatorId": 565839171,
          "roomId": "6ba936e3d382dc1d67890eb3ba6e52bc_1789556445",
          "effectiveDurationMs": 1800000,
          "waitMs": 120000,
          "roomCreateTime": 1789556445075,
          "roomUsers": [
            {
              "userId": 565839171,
              "nickname": "病态ink",
              "avatarUrl": "http://p3.music.126.net/a.jpg"
            }
          ],
          "roomType": "FRIEND"
        }
    """.trimIndent()

    @Test
    fun createResponseParsesRoom() {
        val result = parseListenTogetherCreateResponse(
            """{"code":200,"data":{"type":"NEW_ROOM","roomInfo":$roomInfoJson}}""",
        )
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val room = result.room!!
        assertEquals("6ba936e3d382dc1d67890eb3ba6e52bc_1789556445", room.roomId)
        assertEquals(565839171L, room.creatorId)
        assertEquals(1800000L, room.effectiveDurationMs)
        assertEquals("FRIEND", room.roomType)
        assertEquals(1, room.users.size)
        assertEquals(565839171L, room.users.first().userId)
    }

    @Test
    fun statusResponseParsesInRoom() {
        val result = parseListenTogetherStatusResponse(
            """{"code":200,"data":{"inRoom":true,"roomInfo":$roomInfoJson}}""",
        )
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val roomStatus = result.roomStatus!!
        assertTrue(roomStatus.inRoom)
        assertEquals("6ba936e3d382dc1d67890eb3ba6e52bc_1789556445", roomStatus.room!!.roomId)
    }

    @Test
    fun checkResponseParsesJoinable() {
        val result = parseListenTogetherCheckResponse(
            """{"code":200,"data":{"joinable":true,"type":"NORMAL","status":"AVAILABLE"}}""",
        )
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val check = result.check!!
        assertTrue(check.joinable)
        assertEquals("NORMAL", check.type)
    }

    @Test
    fun syncResponseParsesCommandAndPlaylist() {
        val result = parseListenTogetherSyncResponse(
            """
            {
              "code": 200,
              "data": {
                "playCommand": {
                  "userId": 565839171,
                  "commandType": "GOTO",
                  "formerSongId": "-1",
                  "targetSongId": "33894312",
                  "progress": 0,
                  "playStatus": "PLAY",
                  "clientSeq": 1,
                  "serverSeq": 1789556491185
                },
                "playlist": {
                  "displayList": {"changed": true, "result": ["33894312", "19292984"]},
                  "playMode": null,
                  "replace": true,
                  "version": [{"userId": 565839171, "version": 1}]
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val snapshot = result.snapshot!!
        val command = snapshot.command!!
        assertEquals("GOTO", command.commandType)
        assertEquals("33894312", command.targetSongId)
        assertEquals(1L, command.clientSeq)
        assertEquals(1789556491185L, command.serverSeq)
        assertEquals("PLAY", command.playStatus)
        val playlist = snapshot.playlist!!
        assertEquals(listOf("33894312", "19292984"), playlist.displaySongIds)
        assertTrue(playlist.replace)
        assertEquals(1, playlist.versions.size)
        assertEquals(565839171L, playlist.versions.first().userId)
    }

    @Test
    fun syncResponseWithEmptyDataYieldsEmptySnapshot() {
        val result = parseListenTogetherSyncResponse("""{"code":200,"data":{}}""")
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val snapshot = result.snapshot!!
        assertNull(snapshot.command)
        assertNull(snapshot.playlist)
    }

    @Test
    fun requiresLoginCodeMapsToRequiresLogin() {
        val result = parseListenTogetherStatusResponse("""{"code":301}""")
        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertNull(result.roomStatus)
    }
}
