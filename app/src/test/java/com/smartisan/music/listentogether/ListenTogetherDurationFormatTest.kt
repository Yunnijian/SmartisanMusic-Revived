package com.smartisan.music.listentogether

import com.smartisan.music.ui.listentogether.formatTogetherDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenTogetherDurationFormatTest {

    private fun format(seconds: Long): String =
        formatTogetherDuration(
            seconds = seconds,
            oneMinute = "一起听了1分钟",
            minutes = "一起听了%1\$d分钟",
            hoursMinutes = "一起听了%1\$d小时%2\$d分钟",
        )

    @Test
    fun belowOneMinuteClampsToOneMinute() {
        assertEquals("一起听了1分钟", format(0L))
        assertEquals("一起听了1分钟", format(59L))
    }

    @Test
    fun exactMinuteBoundary() {
        assertEquals("一起听了1分钟", format(60L))
        assertEquals("一起听了1分钟", format(61L))
    }

    @Test
    fun minuteGranularityTruncatesSeconds() {
        assertEquals("一起听了59分钟", format(3_599L))
    }

    @Test
    fun hourBoundaryDropsSecondsInMinutesPart() {
        assertEquals("一起听了1小时0分钟", format(3_600L))
    }

    @Test
    fun fullHoursAndRemainingMinutes() {
        assertEquals("一起听了1小时47分钟", format(6_420L))
    }
}
