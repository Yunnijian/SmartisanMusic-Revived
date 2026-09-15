@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import com.smartisan.music.AppDispatchers
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlinePlaybackFailureReason
import com.smartisan.music.data.online.OnlinePlaybackResolutionException
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.onlinePlaybackUriIdentityOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 占位 URI 的数据源级解析器：ExoPlayer 取数据前把 smartisan-online://source/trackId
 * 解析为短期有效的 http URL。解析失败（未登录/仅试听/下架）时抛出
 * OnlinePlaybackResolutionException，经 PlaybackException 的 cause 链传给错误处理。
 */
internal class OnlinePlaybackDataSpecResolver(
    private val onlineMusicRepository: OnlineMusicRepositoryRouter,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val identity = dataSpec.uri.onlinePlaybackUriIdentityOrNull() ?: return dataSpec
        return dataSpec.withUri(resolvePlaybackUri(identity))
    }

    override fun resolveReportedUri(uri: Uri): Uri {
        return uri
    }

    private fun resolvePlaybackUri(identity: OnlineTrackIdentity): Uri {
        // resolveDataSpec 的 API 契约本身就是同步的（ExoPlayer 取数据前同步等结果），只能就地阻塞。
        // 超时只是兜底：正常慢路径由 OkHttp 自己的 15s connect/read 逐次封顶，
        // 只有协程/合并加载彻底挂死时才会走到这里。超时只放弃本次等待，
        // 不会取消按 key 合并的在途加载（那是 loadScope 的 Deferred，await 取消不影响其他等待者），
        // 解析结果仍会照常写入内存缓存供下次起播命中。
        return runBlocking(AppDispatchers.IO) {
            withTimeoutOrNull(OnlinePlaybackUriResolveTimeoutMs) {
                onlineMusicRepository.resolvePlaybackUri(identity)
            }
        } ?: throw OnlinePlaybackResolutionException(
            reason = OnlinePlaybackFailureReason.Unavailable,
            message = "Timed out resolving online playback uri for " +
                "${identity.source}/${identity.trackId}",
        )
    }
}

/**
 * 正常路径靠 15 分钟 URL 新鲜度直接内存命中、零网络，45s 只用于截断真正挂死的解析链。
 * 慢网下走满「9 档音质回退 × 每档会话重试 × 单请求 15s」会超过这个预算，此时沿用既有的
 * 「无法播放该在线音乐」提示与跳曲行为，与 HEAD 的无上限阻塞相比是更早放弃而非新增失败模式。
 */
private const val OnlinePlaybackUriResolveTimeoutMs = 45_000L
