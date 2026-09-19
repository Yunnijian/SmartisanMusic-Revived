@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.smartisan.music.playback

import android.Manifest
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.smartisan.music.AppDispatchers
import com.smartisan.music.MainActivity
import com.smartisan.music.data.library.LibraryExclusions
import com.smartisan.music.data.library.LibraryExclusionsStore
import com.smartisan.music.data.online.OnlineMusicRepositoryRouter
import com.smartisan.music.data.online.OnlineTrackIdentity
import com.smartisan.music.data.online.isOnlineMediaItem
import com.smartisan.music.data.online.onlineTrackIdentityOrNull
import com.smartisan.music.data.online.toOnlinePlaybackCacheKey
import com.smartisan.music.data.online.withOnlinePlaybackPlaceholderUri
import com.smartisan.music.data.playback.PlaybackStatsRepository
import com.smartisan.music.data.settings.PlaybackSettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var mediaLibrarySession: MediaLibrarySession? = null
    private lateinit var localAudioLibrary: LocalAudioLibrary
    private lateinit var libraryExecutor: ListeningExecutorService
    private lateinit var libraryRefreshExecutor: ListeningExecutorService
    private lateinit var libraryExclusionsStore: LibraryExclusionsStore
    private lateinit var playbackSettingsStore: PlaybackSettingsStore
    private lateinit var playbackStatsRepository: PlaybackStatsRepository
    private lateinit var playbackSessionStateStore: PlaybackSessionStateStore
    private lateinit var onlineMusicRepository: OnlineMusicRepositoryRouter
    private lateinit var onlinePlaybackUrlRefresher: OnlinePlaybackUrlRefresher
    private lateinit var playbackStartCoordinator: PlaybackStartCoordinator
    private lateinit var playbackStatsSync: PlaybackStatsSync
    private var playbackSessionStateCoordinator: PlaybackSessionStateCoordinator? = null
    private var playbackPlayCountTracker: PlaybackPlayCountTracker? = null
    private var playbackAudioFxController: PlaybackAudioFxController? = null
    private var playbackMetadataPreloader: PlaybackMetadataPreloader? = null
    private var mediaSessionArtworkBitmapLoader: MediaSessionArtworkBitmapLoader? = null
    private val onlinePlaybackErrorToastNotifier = OnlinePlaybackErrorToastNotifier()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackStartFadeController = PlaybackStartFadeController(serviceScope)
    @Volatile private var exclusionsSnapshot: LibraryExclusions = LibraryExclusions()
    private val exclusionsReady = CompletableDeferred<LibraryExclusions>()
    private var superLyricPublisher: SuperLyricPublisher? = null
    private var superLyricPublishJob: Job? = null
    private var superLyricLyrics: EmbeddedLyrics? = null
    private var superLyricLoadJob: Job? = null
    private val audioFxPlayerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            playbackAudioFxController?.setAudioSessionId(audioSessionId)
        }
    }
    private val onlineMediaRefreshListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onlinePlaybackUrlRefresher.resolveAdjacentOnlineMediaItem()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                onlinePlaybackUrlRefresher.refreshCurrentOnlineMediaUrlAfterPreviewEnd()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val currentItem = player?.currentMediaItem
            if (currentItem?.isOnlineMediaItem() == true) {
                onlinePlaybackErrorToastNotifier.onPlaybackError(
                    context = this@PlaybackService,
                    failedItem = currentItem,
                    error = error,
                )
            }
            onlinePlaybackUrlRefresher.refreshCurrentOnlineMediaUrlAfterError()
        }
    }
    private val playbackStartFadePlayerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val playbackPlayer = player ?: return
            playbackStartFadeController.onPlayWhenReadyChanged(playbackPlayer, playWhenReady)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val playbackPlayer = player ?: return
            playbackStartFadeController.onIsPlayingChanged(playbackPlayer, isPlaying)
        }
    }
    private val superLyricPlayerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 新歌先取消上一首还在跑的歌词加载任务，避免慢的旧结果回头覆盖新歌歌词。
            superLyricLoadJob?.cancel()
            superLyricLyrics = null
            val item = mediaItem
            if (item == null) {
                superLyricPublisher?.publish(
                    mediaItem = null,
                    lyrics = null,
                    positionMs = 0L,
                    isPlaying = false,
                )
                return
            }
            // 歌词异步加载（含在线拉取/磁盘缓存），加载完成立即补发一帧。
            superLyricLoadJob = serviceScope.launch {
                superLyricLyrics = loadEmbeddedLyrics(this@PlaybackService, item)
                publishSuperLyricNow()
            }
            publishSuperLyricNow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startSuperLyricPublishLoop()
            } else {
                superLyricPublishJob?.cancel()
                superLyricPublishJob = null
                publishSuperLyricNow()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 就绪但 playWhenReady 为 false（如暂停后 seek）时也同步一次当前帧。
            publishSuperLyricNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        playbackStatsRepository = PlaybackStatsRepository.getInstance(this)
        localAudioLibrary = LocalAudioLibrary(
            context = this,
            playbackStatsProvider = playbackStatsRepository::getStats,
            playbackStatsByIdsProvider = playbackStatsRepository::getStats,
        )
        libraryExclusionsStore = LibraryExclusionsStore(this)
        playbackSettingsStore = PlaybackSettingsStore(this)
        playbackSessionStateStore = PlaybackSessionStateStore(this)
        onlineMusicRepository = OnlineMusicRepositoryRouter.getInstance(applicationContext)
        libraryExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        libraryRefreshExecutor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())
        createPlaybackCollaborators()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // 在线条目以占位 URI（smartisan-online://source/trackId）进入队列，
        // 由 ResolvingDataSource 在真正取数据前解析为短期有效的 http URL；
        // 外层再套 PlaybackStreamingCache，按 customCacheKey 落盘缓存音频流。
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(this),
            OnlinePlaybackDataSpecResolver(onlineMusicRepository),
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(
            PlaybackStreamingCache.createDataSourceFactory(
                context = this,
                upstreamFactory = dataSourceFactory,
            ),
        )
        // 平台 FLAC 解码器（c2.android.flac.decoder）的输入缓冲硬性 32768 字节，
        // 24bit/192kHz 母带等大帧 FLAC（block size 16384，单帧可达 73KB）会触发
        // DecoderInputBuffer$InsufficientCapacityException 永久缓冲；启用 libFLAC 扩展渲染器
        // 走软件解码，绕开平台解码器的缓冲上限。
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(
                // 超高码率（24bit/192kHz 母带）会先占满 DefaultLoadControl 的「字节」上限
                // (DEFAULT_AUDIO_BUFFER_SIZE=12.5MB) 而缓冲时长仍不足，于是加载器停摆、位置冻结。
                // media3 只对本地播放默认开了时间优先（DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK），
                // 网络播放仍是 false，这里显式打开以取得同样的保护。
                DefaultLoadControl.Builder()
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build(),
            )
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PlaylistPreloadDurationUs))
            }
        val artworkBitmapLoader = MediaSessionArtworkBitmapLoader(this)

        player = exoPlayer
        playbackAudioFxController = PlaybackAudioFxController().also { controller ->
            controller.setAudioSessionId(exoPlayer.audioSessionId)
        }
        exoPlayer.addListener(audioFxPlayerListener)
        exoPlayer.addListener(onlineMediaRefreshListener)
        exoPlayer.addListener(playbackStartFadePlayerListener)
        SuperLyricPublisher().also { publisher ->
            publisher.tryRegister()
            superLyricPublisher = publisher
        }
        exoPlayer.addListener(superLyricPlayerListener)
        playbackMetadataPreloader = PlaybackMetadataPreloader(
            context = this,
            player = exoPlayer,
            scope = serviceScope,
        ).also { preloader ->
            preloader.start()
        }
        mediaSessionArtworkBitmapLoader = artworkBitmapLoader
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            PlaybackStartFadePlayer(exoPlayer, playbackStartFadeController),
            PlaybackLibrarySessionCallback(
                packageName = packageName,
                localAudioLibrary = localAudioLibrary,
                libraryExecutor = libraryExecutor,
                libraryRefreshExecutor = libraryRefreshExecutor,
                serviceScope = serviceScope,
                currentPlayer = { player },
                currentLibrarySession = { mediaLibrarySession },
                currentSessionStateCoordinator = { playbackSessionStateCoordinator },
                audioItemsLoader = { forceRefresh -> getAudioItems(forceRefresh) },
                audioPermissionChecker = { hasAudioPermission() },
                audioItemsByIdsLoader = { mediaIds -> getAudioItemsByIds(mediaIds) },
                onlineLibraryItemLoader = { identity -> getOnlineLibraryItemFuture(identity) },
                sessionPlaybackItemsResolver = { items ->
                    playbackStartCoordinator.resolveSessionPlaybackMediaItems(items)
                },
                replaceQueueAndPlayCommandHandler = { args ->
                    playbackStartCoordinator.replaceQueueAndPlayFromSessionCommand(args)
                },
                trackRatingCommandHandler = { mediaId, score ->
                    playbackStatsSync.setTrackRatingFromSessionCommand(mediaId, score)
                },
            ),
        )
            .setSessionActivity(createSessionActivityPendingIntent())
            .setBitmapLoader(artworkBitmapLoader)
            .setPeriodicPositionUpdateEnabled(false)
            .build()

        playbackPlayCountTracker = PlaybackPlayCountTracker(
            player = exoPlayer,
            repository = playbackStatsRepository,
            scope = serviceScope,
            onPlayCountChanged = {
                playbackStatsSync.scheduleStatsLibraryRefresh()
            },
        ).also { tracker ->
            tracker.start()
        }

        playbackSessionStateCoordinator = PlaybackSessionStateCoordinator(
            player = exoPlayer,
            stateStore = playbackSessionStateStore,
            scope = serviceScope,
            canLoadLibraryItems = { hasAudioPermission() },
            loadLibraryItemsByQueueKeys = { queueKeys -> getAudioItemsByQueueKeys(queueKeys) },
        ).also { coordinator ->
            coordinator.start()
        }

        serviceScope.launch(AppDispatchers.IO) {
            libraryExclusionsStore.exclusions.collect { exclusions ->
                exclusionsSnapshot = exclusions
                if (!exclusionsReady.isCompleted) {
                    exclusionsReady.complete(exclusions)
                }
                withContext(Dispatchers.Main.immediate) {
                    removeHiddenQueuedItems(exclusions)
                    mediaLibrarySession?.notifyChildrenChanged(
                        LocalAudioLibrary.ROOT_ID,
                        Int.MAX_VALUE,
                        null,
                    )
                }
            }
        }
        serviceScope.launch(Dispatchers.Main.immediate) {
            playbackSettingsStore.settings.collect { settings ->
                playbackAudioFxController?.setSettings(settings)
            }
        }
    }

    /** 起播/在线刷新/统计同步三个协作类的接线；依赖 onCreate 里先建好的曲库、仓库与 executor。 */
    private fun createPlaybackCollaborators() {
        onlinePlaybackUrlRefresher = OnlinePlaybackUrlRefresher(
            scope = serviceScope,
            onlineMusicRepository = onlineMusicRepository,
            playerProvider = { player },
        )
        playbackStartCoordinator = PlaybackStartCoordinator(
            scope = serviceScope,
            onlineMusicRepository = onlineMusicRepository,
            playerProvider = { player },
            loadLocalItemsByIds = { mediaIds -> getAudioItemsByIds(mediaIds) },
            fadeController = playbackStartFadeController,
        )
        playbackStatsSync = PlaybackStatsSync(
            scope = serviceScope,
            libraryRefreshExecutor = libraryRefreshExecutor,
            statsRepository = playbackStatsRepository,
            localAudioLibrary = localAudioLibrary,
            playerProvider = { player },
            librarySessionProvider = { mediaLibrarySession },
        )
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaLibrarySession? = mediaLibrarySession

    override fun onDestroy() {
        if (!exclusionsReady.isCompleted) {
            exclusionsReady.complete(exclusionsSnapshot)
        }
        // 收尾落盘故意保持同步且不加超时：onDestroy 返回后进程随时可能被杀，异步写会丢队列快照与播放计数；
        // 而任何超时都可能在写请求真正提交进 DataStore 之前取消协程，把「必落盘」变成静默丢失。
        // 主线程在这几毫秒到几十毫秒的阻塞上换来的是杀进程后播放位置不丢。
        playbackSessionStateCoordinator?.let { coordinator ->
            runBlocking { coordinator.saveNow() }
            coordinator.stop()
        }
        playbackSessionStateCoordinator = null
        playbackPlayCountTracker?.let { tracker ->
            runBlocking { tracker.stopAndFlush() }
        }
        playbackPlayCountTracker = null
        playbackStatsSync.cancelPendingRefreshes()
        playbackMetadataPreloader?.stop()
        playbackMetadataPreloader = null
        playbackStartCoordinator.cancelPendingPlaybackStart()
        serviceScope.cancel()
        PlaybackSleepTimer.cancel()
        player?.removeListener(audioFxPlayerListener)
        player?.removeListener(onlineMediaRefreshListener)
        player?.removeListener(playbackStartFadePlayerListener)
        player?.removeListener(superLyricPlayerListener)
        superLyricPublishJob?.cancel()
        superLyricPublishJob = null
        superLyricLoadJob?.cancel()
        superLyricLoadJob = null
        superLyricPublisher?.tryUnregister()
        superLyricPublisher = null
        playbackStartFadeController.release(player)
        onlinePlaybackUrlRefresher.cancel()
        playbackAudioFxController?.release()
        playbackAudioFxController = null
        mediaLibrarySession?.release()
        mediaLibrarySession = null

        player?.release()
        player = null
        mediaSessionArtworkBitmapLoader?.shutdown()
        mediaSessionArtworkBitmapLoader = null
        libraryExecutor.shutdown()
        libraryRefreshExecutor.shutdown()

        super.onDestroy()
    }

    /** 按当前播放器状态发布一帧歌词；publisher 内部做帧去重。 */
    private fun publishSuperLyricNow() {
        val publisher = superLyricPublisher ?: return
        val exoPlayer = player ?: return
        publisher.publish(
            mediaItem = exoPlayer.currentMediaItem,
            lyrics = superLyricLyrics,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            isPlaying = exoPlayer.isPlaying,
        )
    }

    /** 播放时以固定间隔轮询发布，让桌面歌词随逐字/行进度刷新。 */
    private fun startSuperLyricPublishLoop() {
        if (superLyricPublishJob?.isActive == true) {
            return
        }
        superLyricPublishJob = serviceScope.launch {
            while (isActive) {
                publishSuperLyricNow()
                delay(SuperLyricPublishGranularityMs)
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 首帧 DataStore 读数完成前会占住调用线程（libraryExecutor / libraryRefreshExecutor 各为单线程）。
     * 无死锁依据：[exclusionsReady] 由 exclusions 收集协程（IO 线程）在切主线程**之前**完成，
     * 其完成链不经过任何播放层 executor，也不反向等待 executor 的 Future；
     * onDestroy 另有 `complete(exclusionsSnapshot)` 兜底，读数缺失时最坏只等到首帧到达。
     */
    private fun awaitExclusionsSnapshot(): LibraryExclusions {
        if (exclusionsReady.isCompleted) {
            return exclusionsSnapshot
        }
        return runBlocking { exclusionsReady.await() }
    }

    private fun getAudioItems(forceRefresh: Boolean = false): List<MediaItem> {
        if (!hasAudioPermission()) {
            return emptyList()
        }
        val exclusions = awaitExclusionsSnapshot()
        return localAudioLibrary.getAudioItems(forceRefresh = forceRefresh)
            .asSequence()
            .filter { item ->
                val relativePath = item.mediaMetadata.extras
                    ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                !exclusions.isMediaHidden(item.mediaId, relativePath)
            }
            .toList()
    }

    private fun getAudioItemsByIds(mediaIds: List<String>): List<MediaItem> {
        if (!hasAudioPermission() || mediaIds.isEmpty()) {
            return emptyList()
        }
        val exclusions = awaitExclusionsSnapshot()
        return localAudioLibrary.getAudioItemsByIds(mediaIds)
            .asSequence()
            .filter { item ->
                val relativePath = item.mediaMetadata.extras
                    ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                !exclusions.isMediaHidden(item.mediaId, relativePath)
            }
            .toList()
    }

    /**
     * 在线歌曲详情异步取：一次网络调用不该占用 libraryExecutor，也不新建线程池。
     * 结果映射与原先在 executor 任务里返回的一致（缺失 = ERROR_BAD_VALUE，异常 = Future 失败）。
     */
    private fun getOnlineLibraryItemFuture(
        identity: OnlineTrackIdentity,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val resultFuture = SettableFuture.create<LibraryResult<MediaItem>>()
        val fetchJob = serviceScope.launch {
            try {
                val item = withContext(AppDispatchers.IO) {
                    onlineMusicRepository.getMediaItem(identity)
                }
                resultFuture.set(
                    if (item == null) {
                        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    } else {
                        LibraryResult.ofItem(item, null)
                    },
                )
            } catch (error: CancellationException) {
                resultFuture.cancel(false)
            } catch (error: Exception) {
                resultFuture.setException(error)
            }
        }
        // serviceScope 已取消时协程体根本不会执行，兜底完成，避免浏览端的 getItem 永久悬挂。
        fetchJob.invokeOnCompletion {
            if (!resultFuture.isDone) {
                resultFuture.cancel(false)
            }
        }
        return resultFuture
    }

    private suspend fun getAudioItemsByQueueKeys(queueKeys: List<PlaybackQueueSnapshotItem>): List<MediaItem> {
        if (queueKeys.isEmpty()) {
            return emptyList()
        }
        val localQueueKeys = queueKeys.filterNot { key ->
            key.mediaId.onlineTrackIdentityOrNull() != null
        }
        val onlineItems = restoreOnlineItemsByQueueKeys(queueKeys)
        val localItems = if (hasAudioPermission() && localQueueKeys.isNotEmpty()) {
            val exclusions = if (exclusionsReady.isCompleted) {
                exclusionsSnapshot
            } else {
                exclusionsReady.await()
            }
            localAudioLibrary.getAudioItemsByQueueKeys(localQueueKeys)
                .asSequence()
                .filter { item ->
                    val relativePath = item.mediaMetadata.extras
                        ?.getString(LocalAudioLibrary.RelativePathExtraKey)
                    !exclusions.isMediaHidden(item.mediaId, relativePath)
                }
                .toList()
        } else {
            emptyList()
        }
        return localItems + onlineItems
    }

    /**
     * 会话恢复时重建在线队列：快照里带有展示元数据的条目直接重建占位 MediaItem；
     * 缺失元数据的（老版本快照）再经 Router 批量拉取详情补齐。
     */
    private suspend fun restoreOnlineItemsByQueueKeys(
        queueKeys: List<PlaybackQueueSnapshotItem>,
    ): List<MediaItem> {
        val identities = queueKeys
            .asSequence()
            .mapNotNull { key -> key.mediaId.onlineTrackIdentityOrNull() }
            .distinct()
            .toList()
        if (identities.isEmpty()) {
            return emptyList()
        }
        val incompleteIdentities = queueKeys
            .asSequence()
            .filter { key -> !key.hasOnlineDisplayMetadata() }
            .mapNotNull { key -> key.mediaId.onlineTrackIdentityOrNull() }
            .distinct()
            .toList()
        val fetchedItemsById = if (incompleteIdentities.isEmpty()) {
            emptyMap()
        } else {
            onlineMusicRepository.getMediaItems(incompleteIdentities)
                .map(MediaItem::withOnlinePlaybackPlaceholderUri)
                .associateBy(MediaItem::mediaId)
        }
        return queueKeys.mapNotNull { key ->
            val identity = key.mediaId.onlineTrackIdentityOrNull() ?: return@mapNotNull null
            val snapshotItem = key.toOnlineSnapshotMediaItem(identity)
            if (key.hasOnlineDisplayMetadata()) {
                snapshotItem
            } else {
                fetchedItemsById[identity.toOnlinePlaybackCacheKey()] ?: snapshotItem
            }
        }
    }

    private fun createSessionActivityPendingIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            PlaybackSessionActivityRequestCode,
            MainActivity.createOpenPlaybackIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun removeHiddenQueuedItems(exclusions: LibraryExclusions) {
        player.removeMediaItemsMatching { item ->
            val relativePath = item.mediaMetadata.extras
                ?.getString(LocalAudioLibrary.RelativePathExtraKey)
            exclusions.isMediaHidden(item.mediaId, relativePath)
        }
    }

    private companion object {
        private const val PlaybackSessionActivityRequestCode = 1001
        private const val PlaylistPreloadDurationUs = 12_000_000L
    }
}

internal const val PlaybackDiagnosticsTag = "SmartisanPlayback"
