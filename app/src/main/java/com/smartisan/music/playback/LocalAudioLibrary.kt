package com.smartisan.music.playback

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.smartisan.music.R
import com.smartisan.music.data.library.LibraryIndexDatabase
import com.smartisan.music.data.playback.PlaybackStatsRecord
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class LocalAudioLibrary(
    internal val context: Context,
    internal val playbackStatsProvider: () -> Map<String, PlaybackStatsRecord> = { emptyMap() },
    internal val playbackStatsByIdsProvider: (Set<String>) -> Map<String, PlaybackStatsRecord> =
        { mediaIds ->
            playbackStatsProvider().filterKeys(mediaIds::contains)
        },
    internal val libraryIndexDatabase: LibraryIndexDatabase =
        LibraryIndexDatabase.getInstance(context),
) {

    internal val audioCacheLock = Any()
    internal val libraryIndexDao = libraryIndexDatabase.libraryIndexDao()
    @Volatile internal var audioCache = AudioCache()

    fun getRootItem(): MediaItem {
        val metadata =
            MediaMetadata.Builder()
                .setTitle(context.getString(R.string.library_root))
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()

        return MediaItem.Builder().setMediaId(ROOT_ID).setMediaMetadata(metadata).build()
    }

    fun getAudioItems(forceRefresh: Boolean = false): List<MediaItem> {
        val currentSnapshot = currentMediaStoreSnapshot()
        synchronized(audioCacheLock) {
            val cache = audioCache
            // generation 负责日常增量变化，version 负责更大范围的媒体库重建。
            if (!forceRefresh && cache.snapshot == currentSnapshot && cache.items.isNotEmpty()) {
                return cache.items
            }
        }

        if (!forceRefresh) {
            val indexedItems = getAudioItemsFromIndexIfFresh(currentSnapshot)
            if (indexedItems.isNotEmpty()) {
                synchronized(audioCacheLock) {
                    audioCache =
                        AudioCache(
                            snapshot = currentSnapshot,
                            items = indexedItems,
                        )
                }
                return indexedItems
            }
        }

        return reconcileAudioIndexAndCache(currentSnapshot)
    }

    fun getAudioItemsByIds(mediaIds: List<String>): List<MediaItem> {
        val requestedIds =
            mediaIds
                .asSequence()
                .mapNotNull { mediaId -> mediaId.trim().toLongOrNull()?.takeIf { it > 0L } }
                .distinct()
                .toList()
        if (requestedIds.isEmpty()) {
            return emptyList()
        }

        val requestedIdStrings = requestedIds.map(Long::toString)
        val cachedItemsById = getValidCachedItemsById(requestedIdStrings.toSet())
        val missingIds = requestedIds.filter { id -> id.toString() !in cachedItemsById }
        if (missingIds.isEmpty()) {
            return requestedIdStrings.mapNotNull(cachedItemsById::get)
        }
        if (!canReadLibraryIndexOnCurrentThread()) {
            return requestedIdStrings.mapNotNull(cachedItemsById::get)
        }

        val missingIdStrings = missingIds.map(Long::toString)
        val indexFresh = isAudioIndexFresh(currentMediaStoreSnapshot())
        val indexedItemsById =
            if (indexFresh) {
                toMediaItems(
                    libraryIndexDao.getValidIndexesByMediaIds(missingIdStrings),
                    playbackStatsForIds(missingIdStrings.toSet()),
                )
                    .associateBy(MediaItem::mediaId)
            } else {
                emptyMap()
            }
        val stillMissingIds = missingIds.filter { id -> id.toString() !in indexedItemsById }
        val playbackStats = playbackStatsForIds(stillMissingIds.map(Long::toString).toSet())
        val queriedItemsById =
            toMediaItems(queryAudioIndexesByIds(stillMissingIds), playbackStats)
                .associateBy(MediaItem::mediaId)
        val itemsById = cachedItemsById + indexedItemsById + queriedItemsById

        return requestedIdStrings.mapNotNull(itemsById::get)
    }

    fun getAudioItemsByQueueKeys(queueKeys: List<PlaybackQueueSnapshotItem>): List<MediaItem> {
        if (queueKeys.isEmpty()) {
            return emptyList()
        }
        val byIds =
            getAudioItemsByIds(queueKeys.map(PlaybackQueueSnapshotItem::mediaId))
                .associateBy(MediaItem::mediaId)
                .toMutableMap()
        val missingStableKeys =
            queueKeys
                .asSequence()
                .filter { key -> key.mediaId !in byIds }
                .map(PlaybackQueueSnapshotItem::stableKey)
                .filter(String::isNotBlank)
                .distinct()
                .toList()
        if (missingStableKeys.isNotEmpty() && canReadLibraryIndexOnCurrentThread()) {
            val currentSnapshot = currentMediaStoreSnapshot()
            if (!isAudioIndexFresh(currentSnapshot)) {
                reconcileAudioIndexAndCache(currentSnapshot)
            }
            val stableItems =
                toMediaItems(libraryIndexDao.getValidIndexesByStableKeys(missingStableKeys))
            stableItems.forEach { item ->
                byIds[item.mediaId] = item
            }
            val stableItemsByKey = stableItems.associateBy { item -> item.stableKey.orEmpty() }
            return queueKeys.mapNotNull { key ->
                byIds[key.mediaId] ?: stableItemsByKey[key.stableKey]
            }
        }
        return queueKeys.mapNotNull { key -> byIds[key.mediaId] }
    }

    fun invalidateAudioItems() {
        synchronized(audioCacheLock) {
            audioCache = AudioCache()
        }
    }

    fun refreshAudioItems(): RefreshResult {
        val indexedSnapshot = queryIndexedAudioSnapshot()
        val pendingAudioPaths = discoverUnindexedAudioPaths(indexedSnapshot)
        val scanResult = scanAudioFiles(pendingAudioPaths)
        val mediaStoreRefreshSucceeded = requestMediaStoreRefresh()
        val items = getAudioItems(forceRefresh = true)
        return RefreshResult(
            items = items,
            scannedFileCount = scanResult.scannedFileCount,
            failedScanCount = scanResult.failedScanCount,
            scanTimedOut = scanResult.timedOut,
            mediaStoreRefreshSucceeded = mediaStoreRefreshSucceeded,
        )
    }

    fun getItem(mediaId: String): MediaItem? {
        if (mediaId == ROOT_ID) {
            return getRootItem()
        }
        return getAudioItemsByIds(listOf(mediaId)).firstOrNull()
    }

    private fun getValidCachedItemsById(mediaIds: Set<String>): Map<String, MediaItem> {
        if (mediaIds.isEmpty()) {
            return emptyMap()
        }
        return synchronized(audioCacheLock) {
            val cache = audioCache
            if (cache.items.isEmpty() || cache.snapshot != currentMediaStoreSnapshot()) {
                return@synchronized emptyMap()
            }
            cache.items
                .asSequence()
                .filter { item -> item.mediaId in mediaIds }
                .associateBy(MediaItem::mediaId)
        }
    }

    private fun playbackStatsForIds(mediaIds: Set<String>): Map<String, PlaybackStatsRecord> {
        if (mediaIds.isEmpty()) {
            return emptyMap()
        }
        return runCatching {
                playbackStatsByIdsProvider(mediaIds)
            }
            .getOrDefault(emptyMap())
    }

    companion object {
        const val ROOT_ID = "root"
        const val StableKeyExtraKey = "com.smartisan.music.extra.STABLE_KEY"
        const val MediaIdExtraKey = "com.smartisan.music.extra.MEDIA_ID"
        const val AlbumIdExtraKey = "com.smartisan.music.extra.ALBUM_ID"
        const val RelativePathExtraKey = "com.smartisan.music.extra.RELATIVE_PATH"
        const val DateAddedExtraKey = "com.smartisan.music.extra.DATE_ADDED"
        const val GenerationAddedExtraKey = "com.smartisan.music.extra.GENERATION_ADDED"
        const val TitleSortKeyExtraKey = "com.smartisan.music.extra.TITLE_SORT_KEY"
        const val TitleSectionExtraKey = "com.smartisan.music.extra.TITLE_SECTION"
        const val AudioQualityBadgeExtraKey = "com.smartisan.music.extra.AUDIO_QUALITY_BADGE"
        const val PlayCountExtraKey = "com.smartisan.music.extra.PLAY_COUNT"
        const val RatingExtraKey = "com.smartisan.music.extra.RATING"
        const val AudioQualityBadgeFlac = "flac"
        const val AudioQualityBadgeApe = "ape"
        const val AudioQualityBadgeWav = "wav"
        const val AudioQualityBadgeAiff = "aiff"
        const val AudioQualityBadgeAlac = "alac"
        const val AudioQualityBadgeCue = "cue"
        private const val MediaScannerWaitTimeoutSeconds = 30L
        internal const val MediaStoreIdSelectionChunkSize = 500
        // 失效索引的宽限期：暂时读不到的曲目（存储卸载、扫描抖动）会先软删，
        // 只有超过宽限期仍未回到 MediaStore 才算真正删除，此时才物理清理。
        internal const val InvalidIndexGraceMillis = 24L * 60L * 60L * 1000L
        internal const val SqlBindParameterChunkSize = 900
        internal const val ExternalVolumeName = "external"
        internal const val MediaStoreFingerprintSeed = 1_125_899_906_842_597L
        internal const val MediaStoreFingerprintMultiplier = 31L
        private val AudioFileExtensions =
            setOf(
                "aac",
                "aif",
                "aiff",
                "alac",
                "amr",
                "ape",
                "flac",
                "m4a",
                "m4b",
                "mid",
                "midi",
                "mka",
                "mp3",
                "oga",
                "ogg",
                "opus",
                "wav",
                "wma",
            )

        fun albumArtworkUri(albumId: Long): Uri {
            return ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                albumId,
            )
        }

        fun trackArtworkUri(mediaId: Long): Uri {
            return Uri.parse("content://media/external/audio/media/$mediaId/albumart")
        }

    }

    data class RefreshResult(
        val items: List<MediaItem>,
        val scannedFileCount: Int,
        val failedScanCount: Int,
        val scanTimedOut: Boolean,
        val mediaStoreRefreshSucceeded: Boolean,
    ) {
        // ContentResolver.refresh() 是 provider best-effort 提示；MediaStore 不支持时会返回 false。
        // 手动重扫是否成功应以 MediaScanner 回调和重新查询结果为准，避免误报失败。
        val successful: Boolean
            get() = !scanTimedOut && failedScanCount == 0
    }

    internal data class AudioCache(
        val snapshot: MediaStoreSnapshot? = null,
        val items: List<MediaItem> = emptyList(),
    )

    internal data class IndexedAudioSnapshot(val fileKeys: Set<String>)

    internal data class ExternalVolumeRoot(
        val root: File,
        val mediaStoreVolumeName: String,
    )

    private data class ScanResult(
        val scannedFileCount: Int,
        val failedScanCount: Int,
        val timedOut: Boolean,
    )

    private fun discoverUnindexedAudioPaths(indexedSnapshot: IndexedAudioSnapshot): List<String> {
        val pendingPaths = linkedSetOf<String>()
        externalVolumeRoots().forEach { volumeRoot ->
            volumeRoot.root
                .walkTopDown()
                .onEnter { directory -> shouldEnterDirectory(volumeRoot.root, directory) }
                .onFail { _, _ -> }
                .forEach { candidate ->
                    if (
                        candidate.name.startsWith('.') ||
                            !candidate.hasAudioCandidateExtension() ||
                            !candidate.isFile ||
                            !candidate.canRead()
                    ) {
                        return@forEach
                    }
                    val relativePathFromRoot =
                        candidate.relativeToOrNull(volumeRoot.root)?.invariantSeparatorsPath
                            ?: return@forEach
                    if (shouldSkipMediaScannerPath(relativePathFromRoot)) {
                        return@forEach
                    }
                    val relativePath = candidate.relativePathFromVolumeRoot(volumeRoot.root)
                    val key =
                        stableAudioLibraryKey(
                            volumeName = volumeRoot.mediaStoreVolumeName,
                            relativePath = relativePath,
                            displayName = candidate.name,
                        ) ?: return@forEach
                    if (key !in indexedSnapshot.fileKeys) {
                        pendingPaths += candidate.absolutePath
                    }
                }
        }
        return pendingPaths.toList()
    }

    internal fun externalVolumeRoots(): Set<ExternalVolumeRoot> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        return context
            .getExternalFilesDirs(null)
            .asSequence()
            .filterNotNull()
            .mapNotNull { appDir ->
                val root =
                    volumeRootFromAppDir(appDir)?.takeIf { it.isDirectory }
                        ?: return@mapNotNull null
                val storageVolume = storageManager?.getStorageVolume(root)
                val mediaStoreVolumeName =
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                            storageVolume?.mediaStoreVolumeName
                        }
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                            Api29.mediaStoreVolumeName(storageVolume, root)
                        }
                        else -> root.absolutePath
                    } ?: root.absolutePath
                ExternalVolumeRoot(
                    root = root,
                    mediaStoreVolumeName = mediaStoreVolumeName,
                )
            }
            .toSet()
    }

    private fun volumeRootFromAppDir(appDir: File): File? {
        val marker = "${File.separator}Android${File.separator}data${File.separator}"
        val path = appDir.absolutePath
        val markerIndex = path.indexOf(marker)
        if (markerIndex <= 0) {
            return null
        }
        return File(path.substring(0, markerIndex))
    }

    private fun shouldEnterDirectory(scanRoot: File, directory: File): Boolean {
        if (directory == scanRoot) {
            return true
        }
        if (!directory.canRead()) {
            return false
        }
        if (directory.name.startsWith('.')) {
            return false
        }
        val relativePath = directory.relativeToOrNull(scanRoot)?.invariantSeparatorsPath.orEmpty()
        if (shouldSkipMediaScannerPath(relativePath)) {
            return false
        }
        return when {
            relativePath == "Android/data" || relativePath.startsWith("Android/data/") -> false
            relativePath == "Android/obb" || relativePath.startsWith("Android/obb/") -> false
            else -> true
        }
    }

    private fun scanAudioFiles(paths: List<String>): ScanResult {
        if (paths.isEmpty()) {
            return ScanResult(
                scannedFileCount = 0,
                failedScanCount = 0,
                timedOut = false,
            )
        }
        val scannedFileCount = AtomicInteger(0)
        val failedScanCount = AtomicInteger(0)
        var timedOut = false

        paths.chunked(128).forEach { batch ->
            val latch = CountDownLatch(batch.size)
            val acceptingCallbacks = AtomicBoolean(true)
            val completedCallbackCount = AtomicInteger(0)
            val mimeTypes = batch.map(::resolveAudioMimeType).toTypedArray()
            MediaScannerConnection.scanFile(
                context,
                batch.toTypedArray(),
                mimeTypes,
            ) { _, uri ->
                if (acceptingCallbacks.get()) {
                    completedCallbackCount.incrementAndGet()
                    if (uri == null) {
                        failedScanCount.incrementAndGet()
                    } else {
                        scannedFileCount.incrementAndGet()
                    }
                }
                latch.countDown()
            }
            if (!latch.await(MediaScannerWaitTimeoutSeconds, TimeUnit.SECONDS)) {
                acceptingCallbacks.set(false)
                timedOut = true
                failedScanCount.addAndGet(batch.size - completedCallbackCount.get())
            }
        }

        return ScanResult(
            scannedFileCount = scannedFileCount.get(),
            failedScanCount = failedScanCount.get(),
            timedOut = timedOut,
        )
    }

    private fun requestMediaStoreRefresh(): Boolean {
        return runCatching {
                context.contentResolver.refresh(
                    audioCollection(),
                    null,
                    null,
                )
            }
            .getOrDefault(false)
    }

    private fun File.hasAudioCandidateExtension(): Boolean {
        val extension = extension.lowercase(Locale.ROOT)
        return extension in AudioFileExtensions
    }

    private fun resolveAudioMimeType(path: String): String? {
        val extension =
            path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        if (extension.isEmpty()) {
            return null
        }
        return when (extension) {
            AudioQualityBadgeApe -> "audio/ape"
            AudioQualityBadgeAlac -> "audio/alac"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        }
    }
}
