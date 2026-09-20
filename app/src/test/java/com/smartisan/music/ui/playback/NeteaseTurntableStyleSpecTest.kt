package com.smartisan.music.ui.playback

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.smartisan.music.data.settings.TurntableStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseTurntableStyleSpecTest {

    @Test
    fun `netease style constants match official measurements`() {
        assertSame(
            NeteaseTurntableStyleSpec,
            turntableStyleSpec(TurntableStyle.Netease),
        )
        assertEquals(20000f, NeteaseTurntableStyleSpec.discCycleDurationMs, 0.001f)
        assertEquals(0.685f, NeteaseCoverHoleDiameterRatio, 0.0001f)
        assertEquals(300, NeteaseTurntableStyleSpec.needleAnimationDurationMs)
        assertEquals(-35f, NeteaseTurntableStyleSpec.needleDragMinRotationDegrees, 0.001f)
        assertEquals(0f, NeteaseTurntableStyleSpec.needleDragMaxRotationDegrees, 0.001f)
    }

    @Test
    fun `original style spec keeps legacy playback constants`() {
        assertSame(
            OriginalTurntableStyleSpec,
            turntableStyleSpec(TurntableStyle.Original),
        )
        assertEquals(
            PlaybackDiscCycleDurationMs,
            OriginalTurntableStyleSpec.discCycleDurationMs,
            0.001f,
        )
        assertEquals(
            NeedlePlaybackStartRotationDegrees,
            OriginalTurntableStyleSpec.needleProgressStartRotationDegrees,
            0.001f,
        )
        assertEquals(
            NeedlePlaybackSweepDegrees,
            OriginalTurntableStyleSpec.needleProgressSweepDegrees,
            0.001f,
        )
    }

    @Test
    fun `netease needle drops only while playing`() {
        assertEquals(
            0f,
            NeteaseTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = true,
                isPlaying = true,
                needleParkedOutside = false,
                progress = 0f,
            ),
            0.001f,
        )
        assertEquals(
            0f,
            NeteaseTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = true,
                isPlaying = true,
                needleParkedOutside = false,
                progress = 0.9f,
            ),
            0.001f,
        )
        // 暂停必须抬针：忽略 isPlaying 会让唱针停在播放角，播放/暂停看不出变化。
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = true,
                isPlaying = false,
                needleParkedOutside = false,
                progress = 0.9f,
            ),
            0.001f,
        )
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = false,
                isPlaying = false,
                needleParkedOutside = false,
                progress = 0f,
            ),
            0.001f,
        )
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = true,
                isPlaying = false,
                needleParkedOutside = true,
                progress = 0.9f,
            ),
            0.001f,
        )
    }

    @Test
    fun `original needle keeps legacy progress driven angle while paused`() {
        assertEquals(
            NeedlePlaybackStartRotationDegrees + (0.5f * NeedlePlaybackSweepDegrees),
            OriginalTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = true,
                isPlaying = false,
                needleParkedOutside = false,
                progress = 0.5f,
            ),
            0.001f,
        )
        assertEquals(
            NeedleRestRotationDegrees,
            OriginalTurntableStyleSpec.needleTargetRotation(
                hasMediaItem = false,
                isPlaying = false,
                needleParkedOutside = false,
                progress = 0.5f,
            ),
            0.001f,
        )
    }

    @Test
    fun `netease needle arc maps rotation to media position`() {
        val durationMs = 200_000L

        assertNull(
            NeteaseTurntableStyleSpec.needlePositionFromRotation(
                rotationDegrees = -35f,
                durationMs = durationMs,
            ),
        )
        assertEquals(
            100_000L,
            NeteaseTurntableStyleSpec.needlePositionFromRotation(
                rotationDegrees = -17.5f,
                durationMs = durationMs,
            ),
        )
        assertEquals(
            200_000L,
            NeteaseTurntableStyleSpec.needlePositionFromRotation(
                rotationDegrees = 0f,
                durationMs = durationMs,
            ),
        )
        assertNull(
            NeteaseTurntableStyleSpec.needlePositionFromRotation(
                rotationDegrees = -20f,
                durationMs = 0L,
            ),
        )
    }

    @Test
    fun `netease needle pivot sits above disc center with measured arm length`() {
        val containerSize = IntSize(width = 360, height = 357)
        val geometry =
            NeteaseTurntableStyleSpec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            )
        val discDiameter = containerSize.width * NeteaseDiscDiameterRatio
        val expectedPivotY =
            containerSize.height * NeteaseDiscCenterYRatio -
                NeteaseNeedlePivotOffsetToDiscRatio * discDiameter

        assertEquals(containerSize.width / 2f, geometry.pivot.x, 0.01f)
        assertEquals(expectedPivotY, geometry.pivot.y, 0.01f)

        // 支点 → 唱头中心（官方 SVG 内相距 146.6 单位）应等于 0.5087 碟径。
        val svgScale = geometry.width / NeteaseNeedleSvgViewBoxWidth
        val headCenter =
            Offset(
                x = geometry.left + NeteaseNeedleHeadCenterSvgX * svgScale,
                y = geometry.top + NeteaseNeedleHeadCenterSvgY * svgScale,
            )
        assertEquals(
            0.5087f * discDiameter,
            distanceBetween(headCenter, geometry.pivot),
            0.5f,
        )
    }

    @Test
    fun `netease needle tip rests on vinyl ring between cover edge and disc edge`() {
        val containerSize = IntSize(width = 360, height = 357)
        val geometry =
            NeteaseTurntableStyleSpec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            )
        val svgScale = geometry.width / NeteaseNeedleSvgViewBoxWidth
        // 唱头中心（SVG 坐标），即播放态针尖落点。
        val tip =
            Offset(
                x = geometry.left + NeteaseNeedleTipSvgX * svgScale,
                y = geometry.top + NeteaseNeedleTipSvgY * svgScale,
            )
        val discCenter =
            Offset(
                containerSize.width / 2f,
                containerSize.height * NeteaseDiscCenterYRatio,
            )
        val discRadius = containerSize.width * NeteaseDiscDiameterRatio / 2f
        val coverRadius = discRadius * NeteaseCoverHoleDiameterRatio
        val tipRadius = distanceBetween(tip, discCenter)

        assertTrue("针尖应落在封面圆外", tipRadius > coverRadius)
        assertTrue("针尖应落在碟面内", tipRadius < discRadius)
    }

    @Test
    fun `netease needle lifts clear of disc when paused`() {
        val containerSize = IntSize(width = 360, height = 357)
        val geometry =
            NeteaseTurntableStyleSpec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            )
        val svgScale = geometry.width / NeteaseNeedleSvgViewBoxWidth
        val tipLocal =
            Offset(
                x = NeteaseNeedleTipSvgX - NeteaseNeedlePivotSvgX,
                y = NeteaseNeedleTipSvgY - NeteaseNeedlePivotSvgY,
            )
        val pausedTip = geometry.pivot + rotateOffsetForTest(tipLocal, -35f) * svgScale
        val discCenter =
            Offset(
                containerSize.width / 2f,
                containerSize.height * NeteaseDiscCenterYRatio,
            )
        val discRadius = containerSize.width * NeteaseDiscDiameterRatio / 2f

        assertTrue(
            "暂停态针尖应抬离碟面",
            distanceBetween(pausedTip, discCenter) > discRadius,
        )
    }

    @Test
    fun `netease needle rotation from point stays inside drag arc`() {
        val containerSize = IntSize(width = 360, height = 357)
        val geometry =
            NeteaseTurntableStyleSpec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            )
        val neutral = Offset(
            x = geometry.left + geometry.width / 2f,
            y = geometry.top + geometry.height * OriginalNeedleTouchStartRatio,
        )
        val belowNeutral = Offset(neutral.x, neutral.y + 80f)
        val aboveNeutral = Offset(neutral.x, neutral.y - 200f)

        assertEquals(
            0f,
            NeteaseTurntableStyleSpec.needleRotationFromPoint(
                point = belowNeutral,
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            ),
            0.5f,
        )
        assertEquals(
            NeteaseTurntableStyleSpec.needleDragMinRotationDegrees,
            NeteaseTurntableStyleSpec.needleRotationFromPoint(
                point = aboveNeutral,
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            ),
            0.5f,
        )
    }

    @Test
    fun `netease needle hit region covers head area only`() {
        val containerSize = IntSize(width = 360, height = 357)
        val geometry =
            NeteaseTurntableStyleSpec.needleGeometry(
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
            )
        val headPoint = Offset(
            x = geometry.left + geometry.width / 2f,
            y = geometry.top + geometry.height * 0.9f,
        )
        val armPoint = Offset(
            x = geometry.left + geometry.width / 2f,
            y = geometry.top + geometry.height * 0.5f,
        )

        assertTrue(
            NeteaseTurntableStyleSpec.isWithinNeedleSeekRegion(
                point = headPoint,
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
                rotationDegrees = 0f,
            ),
        )
        assertTrue(
            !NeteaseTurntableStyleSpec.isWithinNeedleSeekRegion(
                point = armPoint,
                containerSize = containerSize,
                densityPxPerDp = 1f,
                turntableScale = 1f,
                rotationDegrees = 0f,
            ),
        )
    }

    private fun rotateOffsetForTest(
        offset: Offset,
        rotationDegrees: Float,
    ): Offset {
        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cos = kotlin.math.cos(radians).toFloat()
        val sin = kotlin.math.sin(radians).toFloat()
        return Offset(
            x = (offset.x * cos) - (offset.y * sin),
            y = (offset.x * sin) + (offset.y * cos),
        )
    }
}
