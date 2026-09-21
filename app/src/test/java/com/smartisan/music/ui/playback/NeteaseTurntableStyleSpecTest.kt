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
        // 可见黑胶沿用真机校准值；图像容器按位图透明边补偿。
        assertEquals(0.78f, NeteaseVinylVisibleDiameterRatio, 0.0001f)
        assertEquals(
            NeteaseVinylVisibleDiameterRatio,
            NeteaseDiscDiameterRatio * NeteaseVinylBitmapVisibleDiameterRatio,
            0.0005f,
        )
        // 官方 CenterImg 基准 400、封面 214/0.85，封面与图像容器之比为 0.6294。
        assertEquals(0.6294f, NeteaseCoverDiameterRatio / NeteaseDiscDiameterRatio, 0.0001f)
        // 唱针几何与碟径解耦：针按手机版标定的参考直径缩放，不随碟放大。
        assertEquals(0.72f, NeteaseNeedleReferenceDiameterRatio, 0.0001f)
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
    fun `netease needle tracks progress while playing and lifts when paused`() {
        // 播放中指针随进度扫动：0% 落在最低点、100% 抬到最高点。
        assertEquals(
            0f,
            NeteaseTurntableStyleSpec.needleTargetRotation(true, true, false, 0f),
            0.001f,
        )
        assertEquals(
            -17.5f,
            NeteaseTurntableStyleSpec.needleTargetRotation(true, true, false, 0.5f),
            0.001f,
        )
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(true, true, false, 1f),
            0.001f,
        )
        // 暂停抬针，与播放态形成可见变化。
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(true, false, false, 0.5f),
            0.001f,
        )
        // 无媒体抬起。
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleTargetRotation(false, false, false, 0f),
            0.001f,
        )
    }

    @Test
    fun `netease drag start angle comes from progress not from lifted pose`() {
        val durationMs = 200_000L

        // 暂停时指针抬在最高点，直接拿它反推会得到满进度；起步角必须由进度给出。
        assertEquals(
            0f,
            NeteaseTurntableStyleSpec.needleRotationForProgress(0L, durationMs),
            0.001f,
        )
        assertEquals(
            -35f,
            NeteaseTurntableStyleSpec.needleRotationForProgress(200_000L, durationMs),
            0.001f,
        )
        val angle = NeteaseTurntableStyleSpec.needleRotationForProgress(100_000L, durationMs)
        assertEquals(-17.5f, angle, 0.001f)
        // 起步角与进度互逆：按下瞬间进度保持不跳。
        assertEquals(100_000L, NeteaseTurntableStyleSpec.needleDragPosition(angle, durationMs))
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
    fun `netease drag maps needle angle straight to progress`() {
        val durationMs = 200_000L

        // 最低点（0°）= 0%，最高点（-35°）= 100%；向上拖即角度变小、进度前进。
        assertEquals(0L, NeteaseTurntableStyleSpec.needleDragPosition(0f, durationMs))
        assertEquals(100_000L, NeteaseTurntableStyleSpec.needleDragPosition(-17.5f, durationMs))
        assertEquals(200_000L, NeteaseTurntableStyleSpec.needleDragPosition(-35f, durationMs))
        // 超出范围按边界收敛。
        assertEquals(0L, NeteaseTurntableStyleSpec.needleDragPosition(8f, durationMs))
        assertEquals(200_000L, NeteaseTurntableStyleSpec.needleDragPosition(-50f, durationMs))
        // 时长未就绪时不产生进度。
        assertNull(NeteaseTurntableStyleSpec.needleDragPosition(-10f, 0L))
    }

    @Test
    fun `original drag keeps absolute angle to progress mapping`() {
        val durationMs = 200_000L

        assertEquals(
            0L,
            OriginalTurntableStyleSpec.needleDragPosition(
                rotationDegrees = NeedlePlaybackStartRotationDegrees,
                durationMs = durationMs,
            ),
        )
        assertEquals(
            200_000L,
            OriginalTurntableStyleSpec.needleDragPosition(
                rotationDegrees = NeedlePlaybackEndRotationDegrees,
                durationMs = durationMs,
            ),
        )
    }

    @Test
    fun `netease disc and visible vinyl fit inside stage page area`() {
        // 页面区高度按 Netease 比例加高后，唱片图像容器与可见黑胶底边都应落在页面区内；
        // 否则超出部分会被 AnimatedContent 裁成一条水平直线（切页时最明显）。
        val discBottomRatio = NeteaseDiscCenterYRatio + (NeteaseDiscDiameterRatio / 2f)
        val visibleVinylBottomRatio =
            NeteaseDiscCenterYRatio + (NeteaseVinylVisibleDiameterRatio / 2f)

        assertTrue(
            "唱片图像容器底边应落在页面区内",
            discBottomRatio <= NeteaseTurntableHeightToWidthRatio,
        )
        assertTrue(
            "可见黑胶底边应落在页面区内",
            visibleVinylBottomRatio <= NeteaseTurntableHeightToWidthRatio,
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
        val needleDiameter = containerSize.width * NeteaseNeedleReferenceDiameterRatio
        val expectedPivotY =
            containerSize.height * NeteaseDiscCenterYRatio -
                NeteaseNeedlePivotOffsetToDiscRatio * needleDiameter

        assertEquals(containerSize.width / 2f, geometry.pivot.x, 0.01f)
        assertEquals(expectedPivotY, geometry.pivot.y, 0.01f)

        // 支点 → 唱头中心（官方 SVG 内相距 146.6 单位）应等于 0.5087 参考碟径。
        val svgScale = geometry.width / NeteaseNeedleSvgViewBoxWidth
        val headCenter =
            Offset(
                x = geometry.left + NeteaseNeedleHeadCenterSvgX * svgScale,
                y = geometry.top + NeteaseNeedleHeadCenterSvgY * svgScale,
            )
        assertEquals(
            0.5087f * needleDiameter,
            distanceBetween(headCenter, geometry.pivot),
            0.5f,
        )
    }

    @Test
    fun `netease needle tip rests on vinyl ring at cover edge boundary`() {
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
        val discRadius = containerSize.width * NeteaseVinylVisibleDiameterRatio / 2f
        val coverRadius = containerSize.width * NeteaseCoverDiameterRatio / 2f
        val tipRadius = distanceBetween(tip, discCenter)

        // 针尖贴合「黑胶内侧与封面交界」：落点半径≈封面半径（亚像素误差内），
        // 不深入封面、也不越出碟面。
        assertTrue("针尖应贴合封面边缘（不深入封面）", tipRadius >= coverRadius - 0.5f)
        assertTrue("针尖不应偏离封面边缘过远", tipRadius <= coverRadius + 0.5f)
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
        val discRadius = containerSize.width * NeteaseVinylVisibleDiameterRatio / 2f

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
