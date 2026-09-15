package com.smartisan.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import com.smartisan.music.data.library.LibraryIndexEntity
import com.smartisan.music.data.library.LibraryIndexSnapshotEntity
import com.smartisan.music.data.playback.PlaybackStatsRecord

/**
 * 曲库索引的落库与 diff：把 MediaStore 当前行与 [LibraryIndexEntity] 对齐（软删失效行、
 * 保留未变行、按宽限期物理清理），并把索引实体物化为 [MediaItem]。
 *
 * 从 [LocalAudioLibrary] 搬出；MediaStore 侧读取见 [MediaStoreLibraryReader]。
 */

internal fun LocalAudioLibrary.getAudioItemsFromIndexIfFresh(
    currentSnapshot: MediaStoreSnapshot
): List<MediaItem> {
    if (!isAudioIndexFresh(currentSnapshot)) {
        return emptyList()
    }
    return toMediaItems(libraryIndexDao.getValidIndexes())
}

internal fun LocalAudioLibrary.isAudioIndexFresh(currentSnapshot: MediaStoreSnapshot): Boolean {
    val snapshotKey = libraryIndexDao.getSnapshotKey() ?: return false
    return snapshotKey == currentSnapshot.storageKey && libraryIndexDao.getValidIndexCount() > 0
}

internal fun LocalAudioLibrary.canReadLibraryIndexOnCurrentThread(): Boolean {
    return Looper.myLooper() != Looper.getMainLooper()
}

internal fun LocalAudioLibrary.reconcileAudioIndexAndCache(currentSnapshot: MediaStoreSnapshot): List<MediaItem> {
    val items = reconcileAudioIndex(currentSnapshot)
    synchronized(audioCacheLock) {
        audioCache =
            LocalAudioLibrary.AudioCache(
                snapshot = currentSnapshot,
                items = items,
            )
    }
    return items
}

private fun LocalAudioLibrary.reconcileAudioIndex(currentSnapshot: MediaStoreSnapshot): List<MediaItem> {
    val indexedAt = System.currentTimeMillis()
    val existingIndexes = libraryIndexDao.getAllIndexes()
    val existingByStableKey = existingIndexes.associateBy(LibraryIndexEntity::stableKey)
    val existingByMediaId = existingIndexes.associateBy(LibraryIndexEntity::mediaId)
    val currentRows =
        queryAudioCursorRows(
            selection = audioSelection(),
            selectionArgs = null,
            sortOrder = audioSortOrder(),
        )
    val currentStableKeys = currentRows.mapTo(linkedSetOf(), AudioCursorRow::stableKey)
    val nextIndexes = currentRows.map { row ->
        val previous = existingByStableKey[row.stableKey] ?: existingByMediaId[row.mediaId]
        if (previous != null && previous.matches(row)) {
            previous.copy(
                mediaId = row.mediaId,
                uri = row.uri,
                valid = true,
            )
        } else {
            row.toLibraryIndexEntity(this, indexedAt)
        }
    }
    val invalidStableKeys =
        existingIndexes
            .asSequence()
            .filter(LibraryIndexEntity::valid)
            .map(LibraryIndexEntity::stableKey)
            .filter { stableKey -> stableKey !in currentStableKeys }
            .toList()

    libraryIndexDatabase.runInTransaction {
        if (nextIndexes.isNotEmpty()) {
            libraryIndexDao.upsertIndexes(nextIndexes)
        }
        invalidStableKeys.chunked(LocalAudioLibrary.SqlBindParameterChunkSize).forEach { chunk ->
            libraryIndexDao.markInvalid(chunk, indexedAt)
        }
        libraryIndexDao.purgeInvalidBefore(indexedAt - LocalAudioLibrary.InvalidIndexGraceMillis)
        libraryIndexDao.upsertSnapshot(
            LibraryIndexSnapshotEntity(
                snapshotKey = currentSnapshot.storageKey,
                updatedAt = indexedAt,
            )
        )
    }

    return toMediaItems(libraryIndexDao.getValidIndexes())
}

private fun LibraryIndexEntity.matches(row: AudioCursorRow): Boolean {
    return stableKey == row.stableKey &&
        dateModified == row.dateModified &&
        generationModified == row.generationModified &&
        durationMs == row.durationMs &&
        relativePath == row.relativePath &&
        displayName == row.displayName &&
        mimeType == row.mimeType
}

internal fun LocalAudioLibrary.toMediaItems(
    indexes: List<LibraryIndexEntity>,
    playbackStats: Map<String, PlaybackStatsRecord>? = null,
): List<MediaItem> {
    val resolvedPlaybackStats =
        playbackStats ?: runCatching(playbackStatsProvider).getOrDefault(emptyMap())
    return indexes.map { index -> index.toMediaItem(resolvedPlaybackStats[index.mediaId]) }
}

private fun LibraryIndexEntity.toMediaItem(stats: PlaybackStatsRecord?): MediaItem {
    val playCount = stats?.playCount?.takeIf { it > 0L }
    val score = stats?.score?.takeIf { it > 0 }
    val extras =
        Bundle().apply {
            putString(LocalAudioLibrary.MediaIdExtraKey, mediaId)
            putString(LocalAudioLibrary.StableKeyExtraKey, stableKey)
            putString(LocalAudioLibrary.TitleSortKeyExtraKey, titleSortKey)
            putString(LocalAudioLibrary.TitleSectionExtraKey, titleSection)
            if (relativePath.isNotBlank()) {
                putString(LocalAudioLibrary.RelativePathExtraKey, relativePath)
            }
            albumId?.let { putLong(LocalAudioLibrary.AlbumIdExtraKey, it) }
            dateAdded?.let { putLong(LocalAudioLibrary.DateAddedExtraKey, it) }
            generationAdded?.let { putLong(LocalAudioLibrary.GenerationAddedExtraKey, it) }
            if (!qualityBadge.isNullOrBlank()) {
                putString(LocalAudioLibrary.AudioQualityBadgeExtraKey, qualityBadge)
            }
            if (playCount != null) {
                putLong(LocalAudioLibrary.PlayCountExtraKey, playCount)
            }
            if (score != null) {
                putLong(LocalAudioLibrary.RatingExtraKey, score.toLong())
            }
        }

    val metadataBuilder =
        MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(artist)
            .setSubtitle(artist)
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(extras)

    if (!album.isNullOrBlank()) {
        metadataBuilder.setAlbumTitle(album)
    }
    if (!albumArtist.isNullOrBlank()) {
        metadataBuilder.setAlbumArtist(albumArtist)
    }
    track?.let(metadataBuilder::setTrackNumber)
    year?.let(metadataBuilder::setReleaseYear)
    mediaId.toLongOrNull()?.let { id ->
        metadataBuilder.setArtworkUri(LocalAudioLibrary.trackArtworkUri(id))
    }

    return MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(Uri.parse(uri))
        .setMediaMetadata(metadataBuilder.build())
        .build()
}
