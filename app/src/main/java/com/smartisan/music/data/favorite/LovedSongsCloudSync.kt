package com.smartisan.music.data.favorite

import android.content.Context
import androidx.media3.common.MediaItem
import com.smartisan.music.data.online.NeteaseAuthStore
import com.smartisan.music.data.online.OnlineMusicProvider
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.buildOnlineMediaId
import com.smartisan.music.data.online.onlineTrackIdentityOrNull

/**
 * 「我喜欢的歌曲」与网易云账号的收敛策略。
 *
 * 这条策略原先散在主壳里（判断登录、算差集、写 Room、再拉在线项），现在整体归数据层：主壳只负责
 * 「进入该页时发起一次」，不关心顺序与降级细节。
 *
 * 语义与下沉前逐条一致：
 * - 不在该页 / 未登录：不发起任何请求，在线部分为空，本地收藏照常展示；
 * - 只补不删：云端喜欢只经 [FavoriteSongsRepository.addMissing] 单向写入本地；
 * - 云端拉取失败与「确实没有内容」在 [CloudLovedSongsSource] 的返回值里同样是 null，
 *   因此失败对上层不可见；这里不额外保存错误状态（Router 上一轮已刻意移除不可靠的 lastOnlineError）。
 */

/** 云端喜欢写回本地收藏的唯一入口，转发到 Room 既有的 `addMissing`，不改其签名。 */
internal fun interface MissingFavoriteWriter {
    suspend fun addMissing(mediaIds: Set<String>)
}

/** 账号「我喜欢」的数据来源；测试里用替身即可覆盖整条策略。 */
internal interface CloudLovedSongsSource {
    /** 账号是否已登录；未登录时不发起任何网络请求。 */
    fun isLoggedIn(): Boolean

    /** 云端「我喜欢」的纯数字 trackId 集合；未登录、无内容或拉取失败为 null。 */
    suspend fun likedTrackIds(): Set<String>?

    /** 云端「我喜欢」的可展示媒体项；拉取失败与确实没有内容都是空列表。 */
    suspend fun likedMediaItems(): List<MediaItem>
}

/**
 * 计算「云端喜欢但本地收藏缺失」的 mediaId 集合，用于单向收敛（只补不删）。
 *
 * @param cloudTrackIds 账号「我喜欢」的纯数字 trackId 集合；null/空表示未登录或拉取失败，此时不补。
 */
internal fun missingOnlineLikedMediaIds(
    cloudTrackIds: Set<String>?,
    localFavoriteMediaIds: Set<String>,
    source: String = OnlineMusicProvider.Netease.sourceId,
): Set<String> {
    if (cloudTrackIds.isNullOrEmpty()) {
        return emptySet()
    }
    val localOnlineMediaIds = localFavoriteMediaIds
        .asSequence()
        .filter { mediaId -> mediaId.onlineTrackIdentityOrNull()?.source == source }
        .toSet()
    return cloudTrackIds
        .asSequence()
        .map { trackId -> buildOnlineMediaId(source, trackId) }
        .filterNot { mediaId -> mediaId in localOnlineMediaIds }
        .toSet()
}

internal class LovedSongsCloudSync(
    private val source: CloudLovedSongsSource,
    private val favoriteWriter: MissingFavoriteWriter,
) {

    /**
     * 发起一次「我喜欢」收敛，返回该页可直接展示的在线项。
     *
     * @param lovedSongsPageActive 由调用方在离开该页时传 false，此时完全跳过云端链路。
     * @param localFavoriteMediaIds 本地收藏的 mediaId 快照，用于计算差集。
     */
    suspend fun mediaItemsForLovedSongsPage(
        lovedSongsPageActive: Boolean,
        localFavoriteMediaIds: Set<String>,
    ): List<MediaItem> {
        if (!lovedSongsPageActive || !source.isLoggedIn()) {
            return emptyList()
        }
        val missing = missingOnlineLikedMediaIds(source.likedTrackIds(), localFavoriteMediaIds)
        if (missing.isNotEmpty()) {
            favoriteWriter.addMissing(missing)
        }
        return source.likedMediaItems()
    }

    companion object {
        @Volatile
        private var instance: LovedSongsCloudSync? = null

        fun getInstance(context: Context): LovedSongsCloudSync {
            return instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        internal fun create(context: Context): LovedSongsCloudSync {
            val appContext = context.applicationContext
            return LovedSongsCloudSync(
                source = NeteaseLovedSongsSource(appContext),
                favoriteWriter =
                    MissingFavoriteWriter { mediaIds ->
                        FavoriteSongsRepository.getInstance(appContext).addMissing(mediaIds)
                    },
            )
        }
    }
}

/** 生产实现：与云音乐页面共用同一个 Router 单例，收藏变化才能被读到最新「我喜欢」。 */
private class NeteaseLovedSongsSource(context: Context) : CloudLovedSongsSource {

    private val authStore = NeteaseAuthStore(context)
    private val router = OnlineMusicRepositoryRouter.getInstance(context)

    override fun isLoggedIn(): Boolean {
        return authStore.load().isLoggedIn
    }

    override suspend fun likedTrackIds(): Set<String>? {
        return router.accountLikedTrackIds()
    }

    override suspend fun likedMediaItems(): List<MediaItem> {
        return router.accountLikedTrackMediaItems()
    }
}
