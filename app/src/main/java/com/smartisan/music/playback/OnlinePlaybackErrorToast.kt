package com.smartisan.music.playback

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.OnlinePlaybackFailureReason
import com.smartisan.music.data.online.isOnlineMediaItem
import com.smartisan.music.data.online.onlinePlaybackFailureReasonOrNull

/**
 * 在线播放失败的提示（从旧仓库 ui/shell 移植的最小等价实现）：
 * 播放在线条目失败时，按失败原因（未登录/仅试听/不可用）弹出 Toast；
 * 同一首歌同一原因在冷却时间内只提示一次，避免错误风暴刷屏。
 * 文案暂以字面量内置（res 层由 UI 侧接管后可迁移到 strings.xml）。
 */
internal data class OnlinePlaybackErrorToastKey(
    val mediaId: String,
    val reason: OnlinePlaybackFailureReason?,
)

internal fun shouldShowOnlinePlaybackErrorToast(
    lastKey: OnlinePlaybackErrorToastKey?,
    lastAtMs: Long,
    nextKey: OnlinePlaybackErrorToastKey,
    nowMs: Long,
    cooldownMs: Long = OnlinePlaybackErrorToastCooldownMs,
): Boolean {
    return lastKey != nextKey || nowMs - lastAtMs >= cooldownMs
}

internal fun onlinePlaybackErrorToastMessage(reason: OnlinePlaybackFailureReason?): String {
    return when (reason) {
        OnlinePlaybackFailureReason.LoginRequired -> "请登录网易云音乐后再播放"
        OnlinePlaybackFailureReason.PreviewOnly -> "当前只返回试听片段，请确认账号权限"
        OnlinePlaybackFailureReason.Unavailable,
        null -> "无法播放该在线音乐"
    }
}

/**
 * 播放服务侧的失败提示器。需在主线程回调（Player.Listener）中调用。
 */
internal class OnlinePlaybackErrorToastNotifier {

    private var lastKey: OnlinePlaybackErrorToastKey? = null
    private var lastAtMs: Long = 0L

    fun onPlaybackError(
        context: Context,
        failedItem: MediaItem?,
        error: Throwable?,
    ) {
        val item = failedItem ?: return
        if (!item.isOnlineMediaItem()) {
            return
        }
        val reason = error?.onlinePlaybackFailureReasonOrNull()
        val toastKey = OnlinePlaybackErrorToastKey(
            mediaId = item.mediaId,
            reason = reason,
        )
        val nowMs = SystemClock.elapsedRealtime()
        if (!shouldShowOnlinePlaybackErrorToast(
                lastKey = lastKey,
                lastAtMs = lastAtMs,
                nextKey = toastKey,
                nowMs = nowMs,
            )
        ) {
            return
        }
        lastKey = toastKey
        lastAtMs = nowMs
        Toast.makeText(
            context.applicationContext,
            onlinePlaybackErrorToastMessage(reason),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private const val OnlinePlaybackErrorToastCooldownMs = 3_500L
