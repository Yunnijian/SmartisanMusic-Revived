package com.smartisan.music.ui.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBackNavigationTest {

    @Test
    fun sleepTimerDialogConsumesBackFirst() {
        assertEquals(
            PlaybackBackTarget.DismissSleepTimerDialog,
            playbackBackTarget(
                showSleepTimerDialog = true,
                showMorePanel = true,
                queueVisible = true,
            ),
        )
    }

    @Test
    fun morePanelConsumesBackWhenNoDialogIsVisible() {
        assertEquals(
            PlaybackBackTarget.DismissMorePanel,
            playbackBackTarget(
                showSleepTimerDialog = false,
                showMorePanel = true,
                queueVisible = true,
            ),
        )
    }

    @Test
    fun visibleQueueHidesQueueInsteadOfCollapsingPlayback() {
        assertEquals(
            PlaybackBackTarget.DismissQueue,
            playbackBackTarget(
                showSleepTimerDialog = false,
                showMorePanel = false,
                queueVisible = true,
            ),
        )
    }

    @Test
    fun backWithoutOverlaysCollapsesPlayback() {
        assertEquals(
            PlaybackBackTarget.Collapse,
            playbackBackTarget(
                showSleepTimerDialog = false,
                showMorePanel = false,
                queueVisible = false,
            ),
        )
    }

    /** 三个布尔输入的全部 8 种组合，逐条钉住优先级：弹层 > 更多面板 > 队列 > 收起播放页。 */
    @Test
    fun arbitrationIsExhaustiveAndNeverLeavesBackUnconsumed() {
        val expected =
            mapOf(
                Triple(false, false, false) to PlaybackBackTarget.Collapse,
                Triple(false, false, true) to PlaybackBackTarget.DismissQueue,
                Triple(false, true, false) to PlaybackBackTarget.DismissMorePanel,
                Triple(false, true, true) to PlaybackBackTarget.DismissMorePanel,
                Triple(true, false, false) to PlaybackBackTarget.DismissSleepTimerDialog,
                Triple(true, false, true) to PlaybackBackTarget.DismissSleepTimerDialog,
                Triple(true, true, false) to PlaybackBackTarget.DismissSleepTimerDialog,
                Triple(true, true, true) to PlaybackBackTarget.DismissSleepTimerDialog,
            )

        assertEquals(8, expected.size)
        expected.forEach { (state, target) ->
            val (showSleepTimerDialog, showMorePanel, queueVisible) = state
            assertEquals(
                "$state 的返回目标",
                target,
                playbackBackTarget(
                    showSleepTimerDialog = showSleepTimerDialog,
                    showMorePanel = showMorePanel,
                    queueVisible = queueVisible,
                ),
            )
        }
    }
}
