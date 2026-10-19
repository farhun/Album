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
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
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
import org.koitharu.album.repository.OrderDirection.DESC
import org.koitharu.album.repository.queryCompat
import org.koitharu.album.util.getOrDefault
import org.koitharu.album.util.suspendLazy
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
        if (mediaIndex != null) mediaIndex.get().size else queryCount()
    }

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                invalidate()
            }
        }
        contentResolver.registerContentObserver(queryUri, true, observer)
        registerInvalidatedCallback { contentResolver.unregisterContentObserver(observer) }
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

        val result = if (mediaIndex != null) {
            val sortedIndex = mediaIndex.get()
            val pageIndex = sortedIndex.subList(offset, (offset + limit).coerceAtMost(total))
            queryMediaByIds(pageIndex, offset)
        } else {
            query(limit, offset).use { cursor ->
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
                                dateModified = cursor.getLong(dateModifiedColumn),
                                isFavorite = if (favoriteColumn >= 0) cursor.getInt(favoriteColumn) > 0 else legacyFavoritesRepository.isFavorite(
                                    id
                                ),
                                isTrashed = trashedColumn >= 0 && cursor.getInt(trashedColumn) > 0,
                                isVideo = isVideo,
                                path = if (pathColumn >= 0) cursor.getStringOrNull(pathColumn) else null,
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
            nextKey = if (mediaIndex != null) {
                (offset + limit).takeIf { it < total }
            } else {
                (offset + loadedCount).takeIf { it < total }
            },
            itemsBefore = offset,
            itemsAfter = (total - if (mediaIndex != null) (offset + limit).coerceAtMost(total) else offset + loadedCount).coerceAtLeast(
                0
            ),
        )
    }

    private data class SortIndexEntry(
        val id: Long,
        val isVideo: Boolean,
        val dateTaken: Long,
        val dateModified: Long,
        val dateAdded: Long,
    ) {
        val effectiveDateMillis: Long
            get() = when {
                dateTaken != 0L -> dateTaken
                dateModified != 0L -> TimeUnit.SECONDS.toMillis(dateModified)
                else -> TimeUnit.SECONDS.toMillis(dateAdded)
            }
    }

    private suspend fun queryMediaIndex(): List<SortIndexEntry> = contentResolver.queryCompat(
        uri = queryUri,
        projection = arrayOf(
            FileColumns._ID,
            FileColumns.MEDIA_TYPE,
            FileColumns.DATE_TAKEN,
            FileColumns.DATE_MODIFIED,
            FileColumns.DATE_ADDED
        ),
        selection = selection,
        selectionArgs = selectionArgs,
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use emptyList()
        val idColumn = cursor.getColumnIndexOrThrow(FileColumns._ID)
        val mediaTypeColumn = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val dateTakenColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_TAKEN)
        val dateModifiedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        val dateAddedColumn = cursor.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
        buildList(cursor.count) {
            do {
                add(
                    SortIndexEntry(
                        id = cursor.getLong(idColumn),
                        isVideo = cursor.getInt(mediaTypeColumn) == FileColumns.MEDIA_TYPE_VIDEO,
                        dateTaken = if (cursor.isNull(dateTakenColumn)) 0L else cursor.getLong(dateTakenColumn),
                        dateModified = if (cursor.isNull(dateModifiedColumn)) 0L else cursor.getLong(dateModifiedColumn),
                        dateAdded = if (cursor.isNull(dateAddedColumn)) 0L else cursor.getLong(dateAddedColumn),
                    )
                )
            } while (cursor.moveToNext())
        }.sortedWith(compareByDescending<SortIndexEntry> { it.effectiveDateMillis }.thenByDescending { it.id })
    }

    private suspend fun queryMediaByIds(pageIndex: List<SortIndexEntry>, offset: Int): List<MediaItem> {
        if (pageIndex.isEmpty()) return emptyList()
        val entriesById = pageIndex.associateBy { it.id }
        val positionsById = pageIndex.mapIndexed { index, entry -> entry.id to index }.toMap()
        val resultById = HashMap<Long, MediaItem>(pageIndex.size)
        pageIndex.chunked(MAX_IDS_PER_QUERY).forEach { batch ->
            val cursor = contentResolver.queryCompat(
                uri = queryUri,
                projection = projection,
                selection = "${FileColumns._ID} IN (${batch.joinToString(",") { "?" }})",
                selectionArgs = batch.map { it.id.toString() }.toTypedArray(),
            )
            cursor.use {
                if (!it.moveToFirst()) return@use
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
                    val entry = entriesById[id] ?: continue
                    val isVideo = it.getInt(mediaTypeColumn) == FileColumns.MEDIA_TYPE_VIDEO
                    resultById[id] = MediaItem(
                        index = offset + positionsById.getValue(id),
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
                        dateTaken = entry.dateTaken,
                        sortDateMillis = entry.effectiveDateMillis,
                    )
                } while (it.moveToNext())
            }
        }
        return pageIndex.mapNotNull { resultById[it.id] }
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
        orderBy = FileColumns.DATE_ADDED,
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
    }
}
