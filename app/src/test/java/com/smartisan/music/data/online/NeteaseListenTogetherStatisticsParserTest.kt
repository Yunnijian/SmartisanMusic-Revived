package com.smartisan.music.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseListenTogetherStatisticsParserTest {

    @Test
    fun parsesTotalConnectionTime() {
        val result = parseListenTogetherStatisticsResponse(
            """
            {
              "code": 200,
              "data": {
                "totalConnectionTime": 60,
                "currentConnectionTime": 0,
                "historyTotalConnectionTime": 0,
                "listenCount": 1,
                "currentSongCount": 3,
                "subjectId": "565839171_8259701419",
                "scene": "FRIEND",
                "createTime": "2026-09-16T12:00:00"
              }
            }
            """.trimIndent(),
        )
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        val statistics = result.statistics!!
        assertEquals(60L, statistics.totalConnectionTimeSeconds)
        assertEquals(1, statistics.listenCount)
    }

    @Test
    fun requiresLoginCodeMapsToRequiresLogin() {
        val result = parseListenTogetherStatisticsResponse("""{"code":301}""")
        assertEquals(NeteaseAccountActionStatus.RequiresLogin, result.status)
        assertNull(result.statistics)
    }

    @Test
    fun malformedJsonFails() {
        val result = parseListenTogetherStatisticsResponse("not json at all")
        assertEquals(NeteaseAccountActionStatus.Failed, result.status)
        assertNull(result.statistics)
    }

    @Test
    fun missingDataYieldsNullStatistics() {
        val result = parseListenTogetherStatisticsResponse("""{"code":200}""")
        assertEquals(NeteaseAccountActionStatus.Success, result.status)
        assertNull(result.statistics)
    }
}
