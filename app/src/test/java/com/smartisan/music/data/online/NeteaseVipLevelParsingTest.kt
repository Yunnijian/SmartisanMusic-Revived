package com.smartisan.music.data.online

import com.smartisan.music.data.settings.NeteaseVipLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NeteaseVipLevelParsingTest {

    private val nowMs = 1_700_000_000_000L
    private val future = nowMs + 86_400_000L
    private val past = nowMs - 86_400_000L

    @Test
    fun redplusActiveMeansSvip() {
        val level = parseNeteaseVipLevelResponse(
            """{"code":200,"data":{"redplus":{"expireTime":$future},
               "musicPackage":{"expireTime":$future}}}""",
            nowMs,
        )
        assertEquals(NeteaseVipLevel.Svip, level)
    }

    @Test
    fun musicPackageWithoutRedplusMeansVip() {
        val level = parseNeteaseVipLevelResponse(
            """{"code":200,"data":{"redplus":{"expireTime":0},
               "musicPackage":{"expireTime":$future}}}""",
            nowMs,
        )
        assertEquals(NeteaseVipLevel.Vip, level)
    }

    @Test
    fun associatorWithoutRedplusMeansVip() {
        val level = parseNeteaseVipLevelResponse(
            """{"code":200,"data":{"associator":{"expireTime":$future}}}""",
            nowMs,
        )
        assertEquals(NeteaseVipLevel.Vip, level)
    }

    @Test
    fun expiredPackagesMeanFree() {
        val level = parseNeteaseVipLevelResponse(
            """{"code":200,"data":{"redplus":{"expireTime":$past},
               "musicPackage":{"expireTime":$past}}}""",
            nowMs,
        )
        assertEquals(NeteaseVipLevel.Free, level)
    }

    @Test
    fun missingDataMeansFree() {
        assertEquals(
            NeteaseVipLevel.Free,
            parseNeteaseVipLevelResponse("""{"code":200,"data":{}}""", nowMs),
        )
    }

    @Test
    fun errorCodeOrUnparsableBodyReturnsNull() {
        assertNull(parseNeteaseVipLevelResponse("""{"code":301,"data":{}}""", nowMs))
        assertNull(parseNeteaseVipLevelResponse("not json", nowMs))
        assertNull(parseNeteaseVipLevelResponse("""{"code":200}""", nowMs))
    }
}
