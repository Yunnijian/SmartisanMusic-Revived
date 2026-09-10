package com.smartisan.music.data.online

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem

internal class OnlineMusicRepositoryRouter(
    context: Context,
    private val neteaseRepository: NeteaseOnlineMusicRepository =
        NeteaseOnlineMusicRepository(context.applicationContext),
) {

    fun repositoryFor(provider: OnlineMusicProvider): OnlineMusicProviderRepository {
        return when (provider) {
            OnlineMusicProvider.Netease -> neteaseRepository
        }
    }

    suspend fun resolvePlayableMediaItem(
        mediaItem: MediaItem,
        includeLyrics: Boolean = true,
        forceRefresh: Boolean = false,
    ): MediaItem? {
        val identity = mediaItem.onlineIdentityOrNull() ?: return null
        return when (identity.source) {
            OnlineMusicProvider.Netease.sourceId ->
                neteaseRepository.resolvePlayableMediaItem(
                    mediaItem = mediaItem,
                    includeLyrics = includeLyrics,
                    forceRefresh = forceRefresh,
                )
            else -> null
        }
    }

    suspend fun resolvePlayableMediaItems(
        mediaItems: List<MediaItem>,
        includeLyrics: Boolean,
    ): List<MediaItem> {
        return neteaseRepository.resolvePlayableMediaItems(
            mediaItems = mediaItems,
            includeLyrics = includeLyrics,
        )
    }

    suspend fun resolvePlaybackUri(identity: OnlineTrackIdentity): Uri {
        return when (identity.source) {
            OnlineMusicProvider.Netease.sourceId -> neteaseRepository.resolvePlaybackUri(identity)
            else -> throw OnlinePlaybackResolutionException(
                reason = OnlinePlaybackFailureReason.Unavailable,
                message = "Unsupported online source ${identity.source}",
            )
        }
    }

    suspend fun resolvePlayableItems(
        identities: List<OnlineTrackIdentity>,
        includeLyrics: Boolean,
    ): List<MediaItem> {
        val uniqueIdentities = identities.distinct()
        if (uniqueIdentities.isEmpty()) {
            return emptyList()
        }
        return resolveNeteaseItems(
            trackIds = uniqueIdentities
                .filter { identity -> identity.source == OnlineMusicProvider.Netease.sourceId }
                .map(OnlineTrackIdentity::trackId),
            includeLyrics = includeLyrics,
        )
    }

    suspend fun getMediaItem(identity: OnlineTrackIdentity): MediaItem? {
        val track = when (identity.source) {
            OnlineMusicProvider.Netease.sourceId -> neteaseRepository.getTrack(identity.trackId)
            else -> null
        }
        return track?.toMediaItem()
    }

    suspend fun getMediaItems(identities: List<OnlineTrackIdentity>): List<MediaItem> {
        val neteaseTrackIds = identities
            .asSequence()
            .filter { identity -> identity.source == OnlineMusicProvider.Netease.sourceId }
            .map(OnlineTrackIdentity::trackId)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (neteaseTrackIds.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            neteaseRepository.getTracks(neteaseTrackIds).map(OnlineTrack::toMediaItem)
        }.getOrDefault(emptyList())
    }

    suspend fun lyrics(identity: OnlineTrackIdentity): OnlineLyrics? {
        return when (identity.source) {
            OnlineMusicProvider.Netease.sourceId -> neteaseRepository.lyrics(identity)
            else -> null
        }
    }

    suspend fun setTrackLiked(
        identity: OnlineTrackIdentity,
        liked: Boolean,
    ): NeteaseAccountActionResult {
        return when (identity.source) {
            OnlineMusicProvider.Netease.sourceId -> neteaseRepository.setTrackLiked(
                trackId = identity.trackId,
                liked = liked,
            )
            else -> NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
    }

    /** 账号「我喜欢」的纯数字 trackId 集合；未登录或失败返回 null。 */
    suspend fun accountLikedTrackIds(): Set<String>? {
        return runCatching { neteaseRepository.accountLikedTrackIds() }.getOrNull()
    }

    /**
     * 账号「我喜欢」的可展示媒体项。
     *
     * 与 [accountLikedTrackIds] 分别走不同端点：这里需要标题/艺人/封面等元数据，
     * 因此复用整张「我喜欢」歌单，而不是用 id 集合再反查。
     */
    suspend fun accountLikedTrackMediaItems(): List<MediaItem> {
        return runCatching {
            neteaseRepository.currentUserLikedTracks()
                .orEmpty()
                .map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
        }.getOrDefault(emptyList())
    }

    suspend fun addTracksToAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        identities: List<OnlineTrackIdentity>,
    ): NeteaseAccountActionResult {
        val trackIds = identities
            .asSequence()
            .filter { identity -> identity.source == playlist.provider.sourceId }
            .map(OnlineTrackIdentity::trackId)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (trackIds.isEmpty()) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        return repositoryFor(playlist.provider).addTracksToAccountPlaylist(
            playlist = playlist,
            trackIds = trackIds,
        )
    }

    suspend fun removeTracksFromAccountPlaylist(
        playlist: OnlineAccountPlaylist,
        identities: List<OnlineTrackIdentity>,
    ): NeteaseAccountActionResult {
        val trackIds = identities
            .asSequence()
            .filter { identity -> identity.source == playlist.provider.sourceId }
            .map(OnlineTrackIdentity::trackId)
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (trackIds.isEmpty()) {
            return NeteaseAccountActionResult(NeteaseAccountActionStatus.Failed)
        }
        return repositoryFor(playlist.provider).removeTracksFromAccountPlaylist(
            playlist = playlist,
            trackIds = trackIds,
        )
    }

    suspend fun deleteAccountPlaylist(
        playlist: OnlineAccountPlaylist,
    ): NeteaseAccountActionResult {
        return repositoryFor(playlist.provider).deleteAccountPlaylist(playlist)
    }

    private suspend fun resolveNeteaseItems(
        trackIds: List<String>,
        includeLyrics: Boolean,
    ): List<MediaItem> {
        val normalizedTrackIds = trackIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (normalizedTrackIds.isEmpty()) {
            return emptyList()
        }
        return runCatching {
            neteaseRepository.getTracks(normalizedTrackIds).mapNotNull { track ->
                neteaseRepository.resolvePlayableTrack(
                    track = track,
                    includeLyrics = includeLyrics,
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        @Volatile
        private var instance: OnlineMusicRepositoryRouter? = null

        /**
         * 进程级共享实例。
         *
         * Router 内部持有 OkHttp 客户端、内存页缓存与常驻 CoroutineScope，逐次 `new` 会重复创建这些资源；
         * 更关键的是 [setTrackLiked] 成功后只失效自身实例的缓存，多实例并存会让「我喜欢」列表读到旧数据。
         */
        fun getInstance(context: Context): OnlineMusicRepositoryRouter {
            return instance ?: synchronized(this) {
                instance ?: OnlineMusicRepositoryRouter(context.applicationContext)
                    .also { instance = it }
            }
        }
    }
}
