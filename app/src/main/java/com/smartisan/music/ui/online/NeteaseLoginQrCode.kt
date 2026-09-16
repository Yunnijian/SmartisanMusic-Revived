package com.smartisan.music.ui.online

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import qrcode.QRCode
import qrcode.render.extensions.drawQRCode

/** 二维码永远黑码白底（不跟随深色主题）：反色会显著降低扫码识别率。 */
private val QrCodeForegroundColor = Color.Black
private val QrCodeBackgroundColor = Color.White

/** qrcode-kotlin 的 builder 取 ARGB int 色值。 */
private const val QrCodeForegroundArgb = 0xFF000000.toInt()
private const val QrCodeBackgroundArgb = 0xFFFFFFFF.toInt()

/**
 * 扫码登录二维码。
 *
 * 用 qrcode-kotlin 的 Compose 扩展直接画在画布上，不落 Bitmap、不引额外渲染层。
 * 二维码内容由调用方给出（云音乐扫码是 `https://music.163.com/login?codekey=<unikey>`）。
 */
@Composable
internal fun NeteaseLoginQrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    // 内容不变就不重算编码矩阵——编码是纯计算，值得复用。
    val qrCode = remember(content) {
        content.takeIf(String::isNotBlank)?.let { data ->
            QRCode.ofSquares()
                .withColor(QrCodeForegroundArgb)
                .withBackgroundColor(QrCodeBackgroundArgb)
                .build(data)
        }
    }
    Canvas(
        modifier = modifier
            .size(size)
            .background(QrCodeBackgroundColor),
    ) {
        qrCode?.let {
            drawQRCode(
                it,
                Offset.Zero,
                Size(this.size.width, this.size.height),
            )
        }
    }
}
