package com.smartisan.music.data.online

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import com.smartisan.music.data.settings.NeteaseVipLevel
import kotlinx.coroutines.CancellationException

internal class OnlineMusicRepositoryRouter(
    context: Context,
    private val neteaseRepository: NeteaseOnlineMusicRepository =
        NeteaseOnlineMusicRepository(context.applicationContext),
) {

    /**
     * 执行一次在线拉取：失败时返回 [fallback]。
     * [CancellationException] 必须原样上抛，否则协程取消信号会丢失。
     */
    private suspend fun <T> runOnlineFetch(
        fallback: T,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fallback
        }
    }

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
        return runOnlineFetch(emptyList()) {
            neteaseRepository.getTracks(neteaseTrackIds).map(OnlineTrack::toMediaItem)
        }
    }

    suspend fun lyrics(identity: OnlineTrackIdentity): OnlineLyrics? {
        return when (identity.source) {
            OnlineMusicProvider.Netease.sourceId -> neteaseRepository.lyrics(identity)
            else -> null
        }
    }

    /** 登录页打开时调一次：清掉上一轮登录残留的会话 Cookie。 */
    fun beginLoginSession() {
        neteaseRepository.beginLoginSession()
    }

    suspend fun sendLoginSmsCode(phone: String): NeteaseLoginOutcome {
        return neteaseRepository.sendLoginSmsCode(phone)
    }

    suspend fun loginWithPhone(phone: String, smsCode: String): NeteaseLoginOutcome {
        return neteaseRepository.loginWithPhone(phone = phone, smsCode = smsCode)
    }

    suspend fun getLoginQrKey(): NeteaseQrKeyResult {
        return neteaseRepository.getLoginQrKey()
    }

    suspend fun pollLoginQrStatus(unikey: String): NeteaseQrPollResult {
        return neteaseRepository.pollLoginQrStatus(unikey)
    }

    suspend fun completeQrLogin(): NeteaseLoginOutcome {
        return neteaseRepository.completeQrLogin()
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

    /**
     * 账号「我喜欢」的纯数字 trackId 集合；未登录、无内容或失败返回 null。
     */
    suspend fun accountLikedTrackIds(): Set<String>? {
        return runOnlineFetch(null) { neteaseRepository.accountLikedTrackIds() }
    }

    /** 当前登录账号的音质权益档次；未登录或失败返回 null（调用方按未知处理）。 */
    suspend fun vipLevel(): NeteaseVipLevel? {
        return runOnlineFetch(null) { neteaseRepository.vipLevel() }
    }

    /** 当前登录账号资料；未登录或失败返回 null。 */
    suspend fun currentUserProfile(): NeteaseAccountProfile? {
        return runOnlineFetch(null) { neteaseRepository.currentUserProfile() }
    }

    /**
     * 账号「我喜欢」的可展示媒体项。
     *
     * 与 [accountLikedTrackIds] 分别走不同端点：这里需要标题/艺人/封面等元数据，
     * 因此复用整张「我喜欢」歌单，而不是用 id 集合再反查。
     *
     * 拉取失败时返回空列表，与「确实没有内容」不可区分。
     */
    suspend fun accountLikedTrackMediaItems(): List<MediaItem> {
        return runOnlineFetch(emptyList()) {
            neteaseRepository.currentUserLikedTracks()
                .orEmpty()
                .map { track -> track.toMediaItem().withOnlinePlaybackPlaceholderUri() }
        }
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

    suspend fun createListenTogetherRoom(): ListenTogetherCreateResult {
        return neteaseRepository.createListenTogetherRoom()
    }

    suspend fun checkListenTogetherRoom(roomId: String): ListenTogetherCheckResult {
        return neteaseRepository.checkListenTogetherRoom(roomId)
    }

    suspend fun acceptListenTogetherInvitation(
        roomId: String,
        inviterId: String,
    ): ListenTogetherCreateResult {
        return neteaseRepository.acceptListenTogetherInvitation(roomId, inviterId)
    }

    suspend fun listenTogetherStatus(): ListenTogetherStatusResult {
        return neteaseRepository.listenTogetherStatus()
    }

    suspend fun listenTogetherHeartbeat(
        roomId: String,
        songId: String,
        playStatus: String,
        progressMs: Long,
    ): NeteaseAccountActionResult {
        return neteaseRepository.listenTogetherHeartbeat(roomId, songId, playStatus, progressMs)
    }

    suspend fun syncListenTogether(roomId: String): ListenTogetherSyncResult {
        return neteaseRepository.syncListenTogether(roomId)
    }

    suspend fun reportListenTogetherCommand(
        roomId: String,
        commandInfo: String,
    ): NeteaseAccountActionResult {
        return neteaseRepository.reportListenTogetherCommand(roomId, commandInfo)
    }

    suspend fun reportListenTogetherPlaylist(
        roomId: String,
        playlistParam: String,
    ): NeteaseAccountActionResult {
        return neteaseRepository.reportListenTogetherPlaylist(roomId, playlistParam)
    }

    suspend fun getListenTogetherStatistics(
        roomId: String,
        roomUserIds: List<Long>,
    ): ListenTogetherStatisticsResult {
        return neteaseRepository.getListenTogetherStatistics(roomId, roomUserIds)
    }

    suspend fun endListenTogetherRoom(roomId: String): NeteaseAccountActionResult {
        return neteaseRepository.endListenTogetherRoom(roomId)
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
        return runOnlineFetch(emptyList()) {
            neteaseRepository.getTracks(normalizedTrackIds).mapNotNull { track ->
                neteaseRepository.resolvePlayableTrack(
                    track = track,
                    includeLyrics = includeLyrics,
                )
            }
        }
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
