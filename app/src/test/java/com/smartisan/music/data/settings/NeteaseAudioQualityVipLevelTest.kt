package com.smartisan.music.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseAudioQualityVipLevelTest {

    @Test
    fun freeTiersRequireNothing() {
        assertEquals(NeteaseVipLevel.Free, NeteaseAudioQuality.Standard.requiredVipLevel)
        assertEquals(NeteaseVipLevel.Free, NeteaseAudioQuality.Higher.requiredVipLevel)
    }

    @Test
    fun losslessFamilyRequiresVip() {
        listOf(
            NeteaseAudioQuality.ExHigh,
            NeteaseAudioQuality.Lossless,
            NeteaseAudioQuality.HiRes,
            NeteaseAudioQuality.HdSurround,
        ).forEach { quality ->
            assertEquals(quality.name, NeteaseVipLevel.Vip, quality.requiredVipLevel)
        }
    }

    @Test
    fun masterFamilyRequiresSvip() {
        listOf(
            NeteaseAudioQuality.Surround,
            NeteaseAudioQuality.Master,
        ).forEach { quality ->
            assertEquals(quality.name, NeteaseVipLevel.Svip, quality.requiredVipLevel)
        }
    }

    @Test
    fun freeAccountOnlyGetsFreeTiers() {
        assertTrue(NeteaseAudioQuality.Standard.isAvailableFor(NeteaseVipLevel.Free))
        assertTrue(NeteaseAudioQuality.Higher.isAvailableFor(NeteaseVipLevel.Free))
        assertFalse(NeteaseAudioQuality.ExHigh.isAvailableFor(NeteaseVipLevel.Free))
        assertFalse(NeteaseAudioQuality.Lossless.isAvailableFor(NeteaseVipLevel.Free))
        assertFalse(NeteaseAudioQuality.Master.isAvailableFor(NeteaseVipLevel.Free))
    }

    @Test
    fun vipAccountGetsEverythingBelowSvip() {
        assertTrue(NeteaseAudioQuality.Lossless.isAvailableFor(NeteaseVipLevel.Vip))
        assertTrue(NeteaseAudioQuality.HdSurround.isAvailableFor(NeteaseVipLevel.Vip))
        assertFalse(NeteaseAudioQuality.Surround.isAvailableFor(NeteaseVipLevel.Vip))
        assertFalse(NeteaseAudioQuality.Master.isAvailableFor(NeteaseVipLevel.Vip))
    }

    @Test
    fun svipAccountGetsEveryTier() {
        NeteaseAudioQuality.entries.forEach { quality ->
            assertTrue(quality.name, quality.isAvailableFor(NeteaseVipLevel.Svip))
        }
    }
}
