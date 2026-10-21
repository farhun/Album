package org.koitharu.album.repository.pagingsource

import android.content.ContentResolver
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import androidx.core.database.getStringOrNull
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.koitharu.album.model.MediaItem
import org.koitharu.album.model.MediaQuery
import org.koitharu.album.model.MediaSortOrder
import org.koitharu.album.repository.Features
import org.koitharu.album.repository.HiddenMediaRepository
import org.koitharu.album.repository.LegacyFavoritesRepository
import org.koitharu.album.repository.OrderDirection.ASC
import org.koitharu.album.repository.OrderDirection.DESC
import org.koitharu.album.repository.queryCompat
import org.koitharu.album.util.getOrDefault
import org.koitharu.album.util.suspendLazy
import java.util.Arrays
import java.util.concurrent.TimeUnit

class GallerySource @AssistedInject constructor(
    @Assisted mediaQuery: MediaQuery,
    @Assisted private val sortOrder: MediaSortOrder = MediaSortOrder.DATE_ADDED,
    private val hiddenMediaRepository: HiddenMediaRepository,
    private val contentResolver: ContentResolver,
    private val legacyFavoritesRepository: LegacyFavoritesRepository,
) : PagingSource<Int, MediaItem>() {

    private val projection = buildList(10) {
        add(FileColumns._ID)
        add(FileColumns.DISPLAY_NAME)
        add(FileColumns.MIME_TYPE)
        add(FileColumns.MEDIA_TYPE)
        add(FileColumns.DATE_ADDED)
        add(FileColumns.DATE_MODIFIED)
        if (Features.isNativeFavoritesSupported) add(FileColumns.IS_FAVORITE)
        if (Features.isRecycleBinSupported) add(FileColumns.IS_TRASHED)
        add(if (Features.isPathColumnSupported) FileColumns.RELATIVE_PATH else FileColumns.DATA)
    }.toTypedArray()

    private val selection = buildString {
        append('(')
        if (mediaQuery.includeImages) append("${FileColumns.MEDIA_TYPE} = ?")
        if (mediaQuery.includeVideos) {
            if (mediaQuery.includeImages) append(" OR ")
            append("${FileColumns.MEDIA_TYPE} = ?")
        }
        append(')')
        if (Features.isRecycleBinSupported) append(" AND ${FileColumns.IS_TRASHED} = ?")
        if (mediaQuery.bucketIds.isNotEmpty()) {
            append(" AND ${FileColumns.BUCKET_ID}")
            if (mediaQuery.bucketIds.size == 1) append(" = ?") else {
                append(mediaQuery.bucketIds.joinToString(prefix = " IN (", postfix = ")", separator = ", ") { "?" })
            }
        }
        if (mediaQuery.isFavorites) {
            append(" AND ")
            if (Features.isNativeFavoritesSupported) {
                append("${FileColumns.IS_FAVORITE} = ?")
            } else {
                append("${FileColumns._ID} IN (")
                repeat(legacyFavoritesRepository.getFavoritesCount()) { index ->
                    if (index > 0) append(',')
                    append('?')
                }
                append(')')
            }
        }
        append(" AND ${FileColumns._ID}")
        if (!mediaQuery.isHidden) append(" NOT")
        append(" IN (")
        repeat(hiddenMediaRepository.getHiddenCount()) { index ->
            if (index > 0) append(',')
            append('?')
        }
        append(')')
        mediaQuery.dateRange?.let {
            append(" AND ${FileColumns.DATE_ADDED} BETWEEN ? AND ?")
        }
        mediaQuery.searchQuery?.let { append(" AND ${FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'") }
        mediaQuery.pathPattern?.let {
            append(" AND ${if (Features.isPathColumnSupported) FileColumns.RELATIVE_PATH else FileColumns.DATA} LIKE ?")
        }
    }

    private val selectionArgs = buildList {
        if (mediaQuery.includeImages) add(FileColumns.MEDIA_TYPE_IMAGE.toString())
        if (mediaQuery.includeVideos) add(FileColumns.MEDIA_TYPE_VIDEO.toString())
        if (Features.isRecycleBinSupported) add(if (mediaQuery.isTrashed) "1" else "0")
        addAll(mediaQuery.bucketIds)
        if (mediaQuery.isFavorites) {
            if (Features.isNativeFavoritesSupported) add("1") else {
                legacyFavoritesRepository.getFavorites().mapTo(this) { it.toString() }
            }
        }
        hiddenMediaRepository.getHiddenIds().mapTo(this) { it.toString() }
        mediaQuery.dateRange?.let {
            add(TimeUnit.MILLISECONDS.toSeconds(it.first).toString())
            add(TimeUnit.MILLISECONDS.toSeconds(it.last).toString())
        }
        mediaQuery.searchQuery?.let {
            add("%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%")
        }
        mediaQuery.pathPattern?.let { add("%$it%") }
    }.toTypedArray()

    private val queryUri: Uri = MediaStore.Files.getContentUri("external")
    private val sourceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mediaIndex = if (sortOrder == MediaSortOrder.DATE_CAPTURED) {
        suspendLazy { queryMediaIndex() }
    } else {
        null
    }
    private val totalCount = suspendLazy {
        when (sortOrder) {
            MediaSortOrder.DATE_CAPTURED -> requireNotNull(mediaIndex).get().ids.size
            MediaSortOrder.DATE_ADDED,
            MediaSortOrder.DATE_MODIFIED -> queryCount()
        }
    }

    init {
        val observerHandler = Handler(Looper.getMainLooper())
        val pendingInvalidation = Runnable { invalidate() }
        val observer = object : ContentObserver(observerHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                observerHandler.removeCallbacks(pendingInvalidation)
                observerHandler.postDelayed(pendingInvalidation, MEDIASTORE_CHANGE_DEBOUNCE_MS)
            }
        }
        contentResolver.registerContentObserver(queryUri, true, observer)
        registerInvalidatedCallback {
            observerHandler.removeCallbacks(pendingInvalidation)
            contentResolver.unregisterContentObserver(observer)
        }
        registerInvalidatedCallback { sourceScope.cancel() }
    }

    override val jumpingSupported: Boolean
        get() = true

    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? {
        val anchorPosition = state.anchorPosition ?: return null
        return (anchorPosition / state.config.pageSize) * state.config.pageSize
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> = withContext(Dispatchers.IO) {
        val limit = params.loadSize
        val offset = params.key ?: 0
        val total = totalCount.getOrDefault(0)
        if (total == 0) {
            return@withContext LoadResult.Page(emptyList(), null, null, 0, 0)
        }

        val result = when (sortOrder) {
            MediaSortOrder.DATE_CAPTURED -> {
                val sortedIndex = requireNotNull(mediaIndex).get()
                queryMediaByIds(sortedIndex, offset, limit)
            }

            MediaSortOrder.DATE_ADDED,
            MediaSortOrder.DATE_MODIFIED -> query(limit, offset).use { cursor ->
                if (!cursor.moveToFirst()) return@use emptyList()
                val idColumn = cursor.getColumnIndexOrThrow(FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
                val mimeTypeColumn = cursor.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
                val mediaTypeColumn = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
                val favoriteColumn =
                    if (Features.isNativeFavoritesSupported) cursor.getColumnIndex(FileColumns.IS_FAVORITE) else -1
                val trashedColumn =
                    if (Features.isRecycleBinSupported) cursor.getColumnIndex(FileColumns.IS_TRASHED) else -1
                val pathColumn =
                    cursor.getColumnIndex(if (Features.isPathColumnSupported) FileColumns.RELATIVE_PATH else FileColumns.DATA)
                buildList(cursor.count) {
                    do {
                        val id = cursor.getLong(idColumn)
                        val dateModified = cursor.getLong(dateModifiedColumn)
                        val isVideo = cursor.getInt(mediaTypeColumn) == FileColumns.MEDIA_TYPE_VIDEO
                        add(
                            MediaItem(
                                index = offset + size,
                                id = id,
                                name = cursor.getString(nameColumn),
                                mimeType = cursor.getString(mimeTypeColumn),
                                uri = ContentUris.withAppendedId(
                                    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    id,
                                ),
                                dateAdded = cursor.getLong(dateAddedColumn),
                                dateModified = dateModified,
                                isFavorite = if (favoriteColumn >= 0) cursor.getInt(favoriteColumn) > 0 else legacyFavoritesRepository.isFavorite(
                                    id
                                ),
                                isTrashed = trashedColumn >= 0 && cursor.getInt(trashedColumn) > 0,
                                isVideo = isVideo,
                                path = if (pathColumn >= 0) cursor.getStringOrNull(pathColumn) else null,
                                sortDateMillis = if (sortOrder == MediaSortOrder.DATE_MODIFIED) {
                                    TimeUnit.SECONDS.toMillis(dateModified)
                                } else {
                                    null
                                },
                            )
                        )
                    } while (cursor.moveToNext())
                }
            }
        }

        val loadedCount = result.size
        LoadResult.Page(
            data = result,
            prevKey = if (offset > 0) (offset - limit).coerceAtLeast(0) else null,
            nextKey = if (sortOrder == MediaSortOrder.DATE_CAPTURED) {
                (offset + limit).takeIf { it < total }
            } else {
                (offset + loadedCount).takeIf { it < total }
            },
            itemsBefore = offset,
            itemsAfter = (total - if (sortOrder == MediaSortOrder.DATE_CAPTURED) (offset + limit).coerceAtMost(total) else offset + loadedCount).coerceAtLeast(
                0
            ),
        )
    }

    private data class MediaIndex(
        val ids: LongArray,
        val effectiveTimes: LongArray,
        val dateTakens: LongArray,
    )

    private suspend fun queryMediaIndex(): MediaIndex = contentResolver.queryCompat(
        uri = queryUri,
        projection = arrayOf(
            FileColumns._ID,
            FileColumns.DATE_TAKEN,
            FileColumns.DATE_MODIFIED,
            FileColumns.DATE_ADDED,
        ),
        selection = selection,
        selectionArgs = selectionArgs,
        orderBy = FileColumns._ID,
        orderDirection = ASC,
    ).use { cursor ->
        val count = cursor.count
        val ids = LongArray(count)
        val effectiveTimes = LongArray(count)
        val dateTakens = LongArray(count)
        if (!cursor.moveToFirst()) return@use MediaIndex(ids, effectiveTimes, dateTakens)

        val idColumn = cursor.getColumnIndexOrThrow(FileColumns._ID)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
        var canPack = count <= MAX_PACKED_ITEM_COUNT
        var position = 0
        do {
            val id = cursor.getLong(idColumn)
            val dateTaken = if (cursor.isNull(dateTakenColumn)) 0L else cursor.getLong(dateTakenColumn)
            val dateModified = if (cursor.isNull(dateModifiedColumn)) 0L else cursor.getLong(dateModifiedColumn)
            val dateAdded = if (cursor.isNull(dateAddedColumn)) 0L else cursor.getLong(dateAddedColumn)
            val effectiveTime = when {
                dateTaken != 0L -> dateTaken
                dateModified != 0L -> TimeUnit.SECONDS.toMillis(dateModified)
                else -> TimeUnit.SECONDS.toMillis(dateAdded)
            }
            ids[position] = id
            effectiveTimes[position] = effectiveTime
            dateTakens[position] = dateTaken
            if (effectiveTime < 0L || effectiveTime > MAX_PACKED_TIMESTAMP) {
                canPack = false
            }
            position++
        } while (cursor.moveToNext())

        sortMediaIndex(ids, effectiveTimes, dateTakens, canPack)
    }

    private fun sortMediaIndex(
        ids: LongArray,
        effectiveTimes: LongArray,
        dateTakens: LongArray,
        canPack: Boolean,
    ): MediaIndex {
        val sortedIds = LongArray(ids.size)
        val sortedTimes = LongArray(ids.size)
        val sortedDateTakens = LongArray(ids.size)
        if (canPack) {
            val packed = LongArray(ids.size) { position ->
                (effectiveTimes[position] shl POSITION_BITS) or position.toLong()
            }
            Arrays.sort(packed)
            for (destination in packed.indices) {
                val position = (packed[packed.lastIndex - destination] and POSITION_MASK).toInt()
                sortedIds[destination] = ids[position]
                sortedTimes[destination] = effectiveTimes[position]
                sortedDateTakens[destination] = dateTakens[position]
            }
        } else {
            val order = Array(ids.size) { it }
            order.sortWith { left, right ->
                val timeComparison = effectiveTimes[right].compareTo(effectiveTimes[left])
                if (timeComparison != 0) {
                    timeComparison
                } else {
                    ids[right].compareTo(ids[left])
                }
            }
            for (destination in order.indices) {
                val position = order[destination]
                sortedIds[destination] = ids[position]
                sortedTimes[destination] = effectiveTimes[position]
                sortedDateTakens[destination] = dateTakens[position]
            }
        }
        return MediaIndex(sortedIds, sortedTimes, sortedDateTakens)
    }

    private suspend fun queryMediaByIds(index: MediaIndex, offset: Int, limit: Int): List<MediaItem> {
        val itemCount = (index.ids.size - offset).coerceAtMost(limit)
        if (itemCount <= 0) return emptyList()
        val result = arrayOfNulls<MediaItem>(itemCount)
        var batchOffset = 0
        while (batchOffset < itemCount) {
            val batchCount = minOf(MAX_IDS_PER_QUERY, itemCount - batchOffset)
            val selection = buildString(batchCount * 2 + 1) {
                append(FileColumns._ID)
                append(" IN (")
                repeat(batchCount) { position ->
                    if (position > 0) append(',')
                    append('?')
                }
                append(')')
            }
            val selectionArgs = Array(batchCount) { position ->
                index.ids[offset + batchOffset + position].toString()
            }
            val cursor = contentResolver.queryCompat(
                uri = queryUri,
                projection = projection,
                selection = selection,
                selectionArgs = selectionArgs,
            )
            cursor.use {
                if (it.moveToFirst()) {
                    val idColumn = it.getColumnIndexOrThrow(FileColumns._ID)
                    val nameColumn = it.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
                    val mimeTypeColumn = it.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
                    val mediaTypeColumn = it.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
                    val dateAddedColumn = it.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
                    val dateModifiedColumn = it.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
                    val favoriteColumn =
                        if (Features.isNativeFavoritesSupported) it.getColumnIndex(FileColumns.IS_FAVORITE) else -1
                    val trashedColumn =
                        if (Features.isRecycleBinSupported) it.getColumnIndex(FileColumns.IS_TRASHED) else -1
                    val pathColumn =
                        it.getColumnIndex(if (Features.isPathColumnSupported) FileColumns.RELATIVE_PATH else FileColumns.DATA)
                    do {
                        val id = it.getLong(idColumn)
                        var pagePosition = batchOffset
                        val batchEnd = batchOffset + batchCount
                        while (pagePosition < batchEnd && index.ids[offset + pagePosition] != id) {
                            pagePosition++
                        }
                        if (pagePosition < batchEnd) {
                            val isVideo = it.getInt(mediaTypeColumn) == FileColumns.MEDIA_TYPE_VIDEO
                            result[pagePosition] = MediaItem(
                                index = offset + pagePosition,
                                id = id,
                                name = it.getString(nameColumn),
                                mimeType = it.getString(mimeTypeColumn),
                                uri = ContentUris.withAppendedId(
                                    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                    id
                                ),
                                dateAdded = it.getLong(dateAddedColumn),
                                dateModified = it.getLong(dateModifiedColumn),
                                isFavorite = if (favoriteColumn >= 0) it.getInt(favoriteColumn) > 0 else legacyFavoritesRepository.isFavorite(
                                    id
                                ),
                                isTrashed = trashedColumn >= 0 && it.getInt(trashedColumn) > 0,
                                isVideo = isVideo,
                                path = if (pathColumn >= 0) it.getStringOrNull(pathColumn) else null,
                                dateTaken = index.dateTakens[offset + pagePosition],
                                sortDateMillis = index.effectiveTimes[offset + pagePosition],
                            )
                        }
                    } while (it.moveToNext())
                }
            }
            batchOffset += batchCount
        }
        return buildList(itemCount) {
            result.forEach { item -> if (item != null) add(item) }
        }
    }

    private suspend fun queryCount() = contentResolver.queryCompat(
        uri = queryUri,
        projection = arrayOf(FileColumns._ID),
        selection = selection,
        selectionArgs = selectionArgs,
    ).use { it.count }

    private suspend fun query(limit: Int, offset: Int) = contentResolver.queryCompat(
        uri = queryUri,
        projection = projection,
        selection = selection,
        selectionArgs = selectionArgs,
        orderBy = when (sortOrder) {
            MediaSortOrder.DATE_ADDED -> FileColumns.DATE_ADDED
            MediaSortOrder.DATE_CAPTURED -> error("Date captured is loaded from the sorted index")
            MediaSortOrder.DATE_MODIFIED -> FileColumns.DATE_MODIFIED
        },
        orderDirection = DESC,
        offset = offset,
        limit = limit,
    )

    @AssistedFactory
    interface Factory {
        fun create(query: MediaQuery, sortOrder: MediaSortOrder = MediaSortOrder.DATE_ADDED): GallerySource
    }

    private companion object {
        const val MAX_IDS_PER_QUERY = 30
        const val MEDIASTORE_CHANGE_DEBOUNCE_MS = 300L
        const val POSITION_BITS = 22
        const val MAX_PACKED_ITEM_COUNT = 4_194_303
        const val MAX_PACKED_TIMESTAMP = (1L shl 41) - 1L
        const val POSITION_MASK = (1L shl POSITION_BITS) - 1L
    }
}
