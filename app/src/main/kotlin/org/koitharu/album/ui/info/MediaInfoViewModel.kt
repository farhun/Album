package org.koitharu.album.ui.info

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.album.ui.common.AlbumItem
import org.koitharu.album.ui.common.MviViewModel
import org.koitharu.album.util.lets
import org.koitharu.album.util.runCatchingCancellable

@HiltViewModel(assistedFactory = MediaInfoViewModel.Factory::class)
class MediaInfoViewModel @AssistedInject constructor(
    @Assisted media: AlbumItem.Media,
    private val contentResolver: ContentResolver,
    @ApplicationContext private val context: Context,
) : MviViewModel<MediaInfoState, Nothing, Nothing>(MediaInfoState(media)) {

    @Volatile
    private var loadGeneration = 0L
    private var currentMedia = media
    private var fileInfoJob: Job? = null
    private var detailsJob: Job? = null

    fun load(media: AlbumItem.Media) {
        val generation = ++loadGeneration
        currentMedia = media
        fileInfoJob?.cancel()
        detailsJob?.cancel()
        state.value = MediaInfoState(media)
        fileInfoJob = viewModelScope.launch(Dispatchers.IO) {
            readFileInfo(media, generation)
        }
    }

    fun onMediaLocationPermissionResult(isGranted: Boolean) {
        val media = currentMedia
        if (media is AlbumItem.Image) {
            val generation = loadGeneration
            detailsJob?.cancel()
            detailsJob = viewModelScope.launch(Dispatchers.IO) {
                readImageDetails(media, locationAllowed = isGranted, generation = generation)
            }
        }
    }

    override fun handleIntent(intent: Nothing) {
    }

    private fun readFileInfo(media: AlbumItem.Media, generation: Long) {
        val info = runCatchingCancellable {
            val cursor = contentResolver.query(
                media.uri,
                arrayOf(
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATA,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                ),
                null,
                null,
                null,
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val sizeColumn = it.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dataColumn = it.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val relativePathColumn = it.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameColumn = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dataPath = if (dataColumn >= 0) it.getString(dataColumn) else null
                    val relativePath = if (relativePathColumn >= 0) it.getString(relativePathColumn) else null
                    val displayName = if (nameColumn >= 0) it.getString(nameColumn) else media.name
                    val path = dataPath ?: buildFullPath(media, relativePath, displayName)
                    val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        runCatching { MediaStore.getVolumeName(media.uri) }.getOrNull()
                    } else {
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    }
                    Triple(
                        if (sizeColumn >= 0) it.getLong(sizeColumn).takeIf { size -> size >= 0L } else null,
                        path,
                        relativePath to volumeName,
                    )
                } else null
            }
        }.getOrNull()
        val sizeBytes = info?.first ?: runCatchingCancellable {
            contentResolver.openAssetFileDescriptor(media.uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        state.update {
            if (generation == loadGeneration) {
                it.copy(
                    fileSizeBytes = sizeBytes,
                    path = info?.second,
                    relativePath = info?.third?.first,
                    volumeName = info?.third?.second,
                )
            } else {
                it
            }
        }
    }

    fun getContainingFolderDocumentUri(path: String?): Pair<android.net.Uri?, String?> {
        path ?: return null to null
        val normalizedPath = when {
            path.startsWith("/storage/emulated/0/") -> "primary:${path.removePrefix("/storage/emulated/0/")}"
            path.startsWith("/sdcard/") -> "primary:${path.removePrefix("/sdcard/")}"
            path.startsWith("/storage/self/primary/") -> "primary:${path.removePrefix("/storage/self/primary/")}"
            path.startsWith("/storage/") -> {
                val remainder = path.removePrefix("/storage/")
                val volumeId = remainder.substringBefore('/')
                if (volumeId.isEmpty() || volumeId == "emulated" || volumeId == "self") {
                    return null to null
                }
                "$volumeId:${remainder.substringAfter('/', "")}"
            }

            else -> return null to null
        }
        val volumeId = normalizedPath.substringBefore(':')
        val filePath = normalizedPath.substringAfter(':')
        val folderPath = filePath.substringBeforeLast('/', "")
        val documentId = "$volumeId:$folderPath"
        val uri = runCatching {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                documentId,
            )
        }.getOrNull()
        return uri to documentId
    }


    private fun readImageDetails(
        media: AlbumItem.Image,
        locationAllowed: Boolean,
        generation: Long,
    ) {
        runCatchingCancellable {
            val imageUri = if (locationAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { MediaStore.setRequireOriginal(media.uri) }.getOrNull()
            } else {
                null
            }
            val stream = (imageUri?.let { runCatching { contentResolver.openInputStream(it) }.getOrNull() }
                ?: contentResolver.openInputStream(media.uri)) ?: return@runCatchingCancellable
            stream.use {
                val exif = ExifInterface(it)
                state.update { currentState ->
                    if (generation == loadGeneration) {
                        currentState.copy(
                            latLng = if (locationAllowed) {
                                exif.latLong?.let { latLng -> latLng[0] to latLng[1] }
                            } else {
                                null
                            },
                            model = exif.getAttribute(ExifInterface.TAG_MODEL),
                            size = lets(
                                exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)?.toIntOrNull(),
                                exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH)?.toIntOrNull(),
                            ) { w, h -> Size(w, h) },
                        )
                    } else {
                        currentState
                    }
                }
            }
        }
    }

    private fun buildFullPath(
        media: AlbumItem.Media,
        relativePath: String?,
        displayName: String?,
    ): String? {
        if (displayName.isNullOrEmpty()) return null
        val volumeName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getVolumeName(media.uri) }.getOrNull()
        } else {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        }
        val root = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val storageManager = context.getSystemService(StorageManager::class.java)
            storageManager.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volumeName }
                ?.directory?.absolutePath
        } else if (volumeName == MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            null
        } ?: return null
        val directory = relativePath.orEmpty().trim('/')
        return if (directory.isEmpty()) {
            "$root/$displayName"
        } else {
            "$root/$directory/$displayName"
        }
    }

    private fun readVideoLocation(media: AlbumItem.Video, generation: Long) {
        val coordinates = runCatchingCancellable {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, media.uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    ?.let(::parseVideoLocation)
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (coordinates != null) {
            state.update {
                if (generation == loadGeneration) it.copy(latLng = coordinates) else it
            }
        }
    }

    fun readLocationMetadata() {
        val media = currentMedia
        if (media is AlbumItem.Video) {
            val generation = loadGeneration
            detailsJob?.cancel()
            detailsJob = viewModelScope.launch(Dispatchers.IO) {
                readVideoLocation(media, generation)
            }
        }
    }

    private fun parseVideoLocation(value: String): Pair<Double, Double>? {
        val coordinates = Regex("([+-][0-9]+(?:\\.[0-9]+)?)([+-][0-9]+(?:\\.[0-9]+)?)").find(value)
            ?: return null
        val latitude = coordinates.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = coordinates.groupValues[2].toDoubleOrNull() ?: return null
        return latitude to longitude
    }

    @AssistedFactory
    interface Factory {
        fun create(media: AlbumItem.Media): MediaInfoViewModel
    }
}
