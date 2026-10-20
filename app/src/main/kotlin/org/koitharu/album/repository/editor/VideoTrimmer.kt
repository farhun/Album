package org.koitharu.album.repository.editor

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.album.repository.Features
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

interface VideoTrimmer {

    /** Returns the video sync-sample time selected by SEEK_TO_PREVIOUS_SYNC. */
    suspend fun getSnappedStartUs(source: Uri, requestedStartUs: Long): Long

    /** Writes a new MP4 and leaves the source untouched. Progress is reported on Dispatchers.IO. */
    suspend fun trim(
        source: Uri,
        startUs: Long,
        endUs: Long,
        onProgress: (VideoTrimProgress) -> Unit = {},
    ): VideoTrimResult
}

data class VideoTrimProgress(
    val snappedStartUs: Long,
    val copiedDurationUs: Long,
    val totalDurationUs: Long,
) {
    val fraction: Float
        get() = if (totalDurationUs <= 0L) 0f
        else (copiedDurationUs.toDouble() / totalDurationUs).toFloat().coerceIn(0f, 1f)
}

data class VideoTrimResult(
    val uri: Uri,
    val snappedStartUs: Long,
    val requestedEndUs: Long,
    val skippedTracks: List<String>,
)

class VideoTrimException(
    message: String,
    cause: Throwable? = null,
    val skippedTracks: List<String> = emptyList(),
) : Exception(message, cause)

@Reusable
class MediaExtractorVideoTrimmer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
) : VideoTrimmer {

    override suspend fun getSnappedStartUs(source: Uri, requestedStartUs: Long): Long =
        withContext(Dispatchers.IO) {
            if (requestedStartUs < 0L) {
                throw VideoTrimException("Start time cannot be negative.")
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, source, null)
                val videoTrack = findVideoTrack(extractor)
                    ?: throw VideoTrimException("This file has no video track.")
                extractor.selectTrack(videoTrack)
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val timeUs = extractor.sampleTime
                if (
                    timeUs < 0L || extractor.sampleTrackIndex != videoTrack ||
                    (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0
                ) {
                    throw VideoTrimException("Could not find a video keyframe at the selected time.")
                }
                timeUs
            } catch (e: CancellationException) {
                throw e
            } catch (e: VideoTrimException) {
                throw e
            } catch (e: Exception) {
                throw createTrimException(e)
            } finally {
                extractor.release()
            }
        }

    override suspend fun trim(
        source: Uri,
        startUs: Long,
        endUs: Long,
        onProgress: (VideoTrimProgress) -> Unit,
    ): VideoTrimResult = withContext(Dispatchers.IO) {
        if (startUs !in 0L until endUs) {
            throw VideoTrimException("Choose a valid start and end time.")
        }
        if (endUs - startUs < MIN_CLIP_DURATION_US) {
            throw VideoTrimException("The selected clip must be at least 1 second long.")
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outputDescriptor: ParcelFileDescriptor? = null
        var temporaryOutput: File? = null
        var outputUri: Uri? = null
        val skippedTracks = mutableListOf<String>()

        try {
            currentCoroutineContext().ensureActive()
            extractor.setDataSource(context, source, null)
            val tracks = (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    SourceTrack(index, format, mime)
                } else {
                    null
                }
            }
            val videoTrack = tracks.firstOrNull { it.mimeType.startsWith("video/") }
                ?: throw VideoTrimException("This file has no video track.")

            val snappedStartUs = findSnappedStartUs(source, startUs)
            val metadata = readSourceMetadata(source)
            val output = createOutput(metadata)
            outputUri = output.uri

            val metadataRetriever = MediaMetadataRetriever()
            val metadataValues: Pair<Int?, VideoLocation?> = try {
                metadataRetriever.setDataSource(context, source)
                val rotation = metadataRetriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
                )?.toIntOrNull()
                val location = parseLocation(
                    metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                )
                rotation to location
            } catch (_: Exception) {
                null to null
            } finally {
                metadataRetriever.release()
            }
            val (rotation, location) = metadataValues

            val useTemporaryFile = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            val activeMuxer = if (useTemporaryFile) {
                val tempFile = File(context.cacheDir, "trim-${UUID.randomUUID()}.mp4")
                temporaryOutput = tempFile
                MediaMuxer(
                    tempFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
            } else {
                val descriptor = contentResolver.openFileDescriptor(output.uri, "w")
                    ?: throw IOException("Could not open the new video for writing")
                outputDescriptor = descriptor
                MediaMuxer(
                    descriptor.fileDescriptor,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                )
            }
            muxer = activeMuxer

            rotation?.takeIf { it in VALID_ROTATIONS }?.let { value ->
                runCatching { activeMuxer.setOrientationHint(value) }
            }
            location?.let { value ->
                runCatching { activeMuxer.setLocation(value.latitude, value.longitude) }
            }

            val muxerTrackIndices = mutableMapOf<Int, Int>()
            for ((sourceIndex, format, mimeType) in tracks) {
                try {
                    muxerTrackIndices[sourceIndex] = activeMuxer.addTrack(format)
                } catch (e: Exception) {
                    skippedTracks += "$mimeType (track $sourceIndex)"
                    Log.w(TAG, "Skipping unsupported $mimeType track $sourceIndex", e)
                }
            }
            if (videoTrack.sourceIndex !in muxerTrackIndices) {
                throw VideoTrimException(
                    "This video track cannot be written to an MP4 file.",
                    skippedTracks = skippedTracks.toList(),
                )
            }

            activeMuxer.start()
            muxerStarted = true
            for (track in tracks) {
                if (track.sourceIndex in muxerTrackIndices) {
                    extractor.selectTrack(track.sourceIndex)
                }
            }
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val maxInputSize = tracks.asSequence()
                .filter { it.sourceIndex in muxerTrackIndices }
                .mapNotNull { track ->
                    if (track.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        track.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                    } else {
                        null
                    }
                }
                .maxOrNull()
                ?: DEFAULT_SAMPLE_BUFFER_SIZE
            val buffer = ByteBuffer.allocateDirect(maxInputSize.coerceIn(64 * 1024, MAX_SAMPLE_BUFFER_SIZE))
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var lastProgressUs = 0L
            var lastProgressAtMs = android.os.SystemClock.elapsedRealtime()
            var copiedVideoSample = false

            while (true) {
                currentCoroutineContext().ensureActive()
                val sourceTrackIndex = extractor.sampleTrackIndex
                if (sourceTrackIndex < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0L) break
                if (sampleTimeUs >= endUs) break

                if (sampleTimeUs < snappedStartUs) {
                    extractor.advance()
                    continue
                }

                if (
                    sourceTrackIndex == videoTrack.sourceIndex && !copiedVideoSample &&
                    (sampleTimeUs != snappedStartUs ||
                            (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0)
                ) {
                    throw VideoTrimException(
                        "Could not start the output at the selected keyframe.",
                        skippedTracks = skippedTracks.toList(),
                    )
                }

                val muxerTrackIndex = muxerTrackIndices[sourceTrackIndex]
                if (muxerTrackIndex != null) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                        throw VideoTrimException(
                            "Encrypted video samples cannot be copied into an MP4 file.",
                            skippedTracks = skippedTracks.toList(),
                        )
                    }
                    val sampleFlags = if (
                        (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    ) {
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    bufferInfo.set(
                        0,
                        sampleSize,
                        sampleTimeUs - snappedStartUs,
                        sampleFlags,
                    )
                    activeMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    if (sourceTrackIndex == videoTrack.sourceIndex) copiedVideoSample = true

                    val copiedUs = (sampleTimeUs - snappedStartUs).coerceIn(0L, endUs - snappedStartUs)
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (copiedUs - lastProgressUs >= PROGRESS_INTERVAL_US || nowMs - lastProgressAtMs >= PROGRESS_INTERVAL_MS) {
                        onProgress(
                            VideoTrimProgress(
                                snappedStartUs = snappedStartUs,
                                copiedDurationUs = copiedUs,
                                totalDurationUs = endUs - snappedStartUs,
                            )
                        )
                        lastProgressUs = copiedUs
                        lastProgressAtMs = nowMs
                    }
                }
                extractor.advance()
            }

            if (!copiedVideoSample) {
                throw VideoTrimException(
                    "No video samples were found in the selected range.",
                    skippedTracks = skippedTracks.toList(),
                )
            }
            currentCoroutineContext().ensureActive()
            activeMuxer.stop()
            muxerStarted = false
            activeMuxer.release()
            muxer = null
            outputDescriptor?.close()
            outputDescriptor = null

            temporaryOutput?.let { tempFile ->
                val coroutineContext = currentCoroutineContext()
                tempFile.inputStream().use { input ->
                    val stream = contentResolver.openOutputStream(output.uri, "w")
                        ?: throw IOException("Could not open the new video for writing")
                    stream.use { destination ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            destination.write(buffer, 0, count)
                        }
                    }
                }
            }

            if (Features.isPathColumnSupported) {
                val values = ContentValues(1).apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                if (contentResolver.update(output.uri, values, null, null) <= 0) {
                    throw IOException("Could not finish saving the new video")
                }
            }
            contentResolver.notifyChange(output.uri, null)
            onProgress(
                VideoTrimProgress(
                    snappedStartUs = snappedStartUs,
                    copiedDurationUs = endUs - snappedStartUs,
                    totalDurationUs = endUs - snappedStartUs,
                )
            )
            VideoTrimResult(
                uri = output.uri,
                snappedStartUs = snappedStartUs,
                requestedEndUs = endUs,
                skippedTracks = skippedTracks.toList(),
            )
        } catch (e: Throwable) {
            runCatching {
                if (muxerStarted) muxer?.stop()
            }
            runCatching { muxer?.release() }
            runCatching { outputDescriptor?.close() }
            outputUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            if (e is CancellationException) throw e
            if (e is VideoTrimException) throw e
            if (e is OutOfMemoryError) {
                throw VideoTrimException("There is not enough memory to trim this video.", e, skippedTracks)
            }
            throw createTrimException(e, skippedTracks)
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
            runCatching { outputDescriptor?.close() }
            temporaryOutput?.delete()
        }
    }

    private suspend fun findSnappedStartUs(source: Uri, requestedStartUs: Long): Long =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, source, null)
                val videoTrack = findVideoTrack(extractor)
                    ?: throw VideoTrimException("This file has no video track.")
                extractor.selectTrack(videoTrack)
                extractor.seekTo(requestedStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val timeUs = extractor.sampleTime
                if (
                    timeUs < 0L || extractor.sampleTrackIndex != videoTrack ||
                    (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0
                ) {
                    throw VideoTrimException("Could not find a video keyframe at the selected time.")
                }
                timeUs
            } finally {
                extractor.release()
            }
        }

    private fun findVideoTrack(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                return index
            }
        }
        return null
    }

    private fun readSourceMetadata(source: Uri): SourceMetadata {
        val projection = buildList {
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.DATE_TAKEN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.VOLUME_NAME)
            } else {
                add(MediaStore.Video.Media.DATA)
            }
        }.toTypedArray()
        val row = runCatching {
            contentResolver.query(source, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                fun getString(column: String): String? {
                    val index = cursor.getColumnIndex(column)
                    return if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
                }

                fun getLong(column: String): Long? {
                    val index = cursor.getColumnIndex(column)
                    return if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
                }
                SourceMetadata(
                    name = getString(MediaStore.Video.Media.DISPLAY_NAME),
                    dateTakenMillis = getLong(MediaStore.Video.Media.DATE_TAKEN),
                    relativePath = getString(MediaStore.MediaColumns.RELATIVE_PATH),
                    dataPath = getString(MediaStore.Video.Media.DATA),
                    volumeName = getString(MediaStore.MediaColumns.VOLUME_NAME),
                )
            }
        }.getOrNull()
        val uriVolume = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { MediaStore.getVolumeName(source) }.getOrNull()
        } else {
            null
        }
        val volume = row?.volumeName
            ?.takeIf { it.isNotBlank() && it != MediaStore.VOLUME_EXTERNAL }
            ?: uriVolume?.takeIf { it.isNotBlank() && it != MediaStore.VOLUME_EXTERNAL }
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return (row ?: SourceMetadata()).copy(
            name = row?.name?.takeIf { it.isNotBlank() }
                ?: source.lastPathSegment?.takeIf { it.isNotBlank() }
                ?: "video",
            volumeName = volume,
        )
    }

    private fun createOutput(metadata: SourceMetadata): TrimOutput {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(metadata.volumeName ?: MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val relativePath = metadata.relativePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizedRelativePath)
            ?: "${Environment.DIRECTORY_MOVIES}/Album/"
        val legacyDirectory = metadata.dataPath
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).parentFile }
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Album")
        if (!Features.isPathColumnSupported) {
            legacyDirectory.mkdirs()
        }
        val originalBaseName = metadata.name
            ?.substringBeforeLast('.', metadata.name)
            ?.ifBlank { "video" }
            ?: "video"
        val baseName = "${originalBaseName}_trimmed"
        val displayName = chooseUniqueName(collection, relativePath, legacyDirectory, baseName)
        val targetFile = File(legacyDirectory, displayName)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, now / 1000L)
            put(MediaStore.Video.Media.DATE_MODIFIED, now / 1000L)
            metadata.dateTakenMillis?.takeIf { it > 0L }?.let {
                put(MediaStore.Video.Media.DATE_TAKEN, it)
            }
            if (Features.isPathColumnSupported) {
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Video.Media.TITLE, displayName.substringBeforeLast('.'))
                put(MediaStore.Video.Media.DATA, targetFile.absolutePath)
            }
        }
        val uri = contentResolver.insert(collection, values)
            ?: throw IOException("Could not create a MediaStore video entry")
        return TrimOutput(uri)
    }

    private fun chooseUniqueName(
        collection: Uri,
        relativePath: String,
        legacyDirectory: File,
        baseName: String,
    ): String {
        for (index in 0 until MAX_NAME_ATTEMPTS) {
            val name = if (index == 0) "$baseName.mp4" else "$baseName ($index).mp4"
            val exists = if (Features.isPathColumnSupported) {
                runCatching {
                    contentResolver.query(
                        collection,
                        arrayOf(MediaStore.Video.Media._ID),
                        "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                        arrayOf(name, relativePath),
                        null,
                    )?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
            } else {
                File(legacyDirectory, name).exists()
            }
            if (!exists) return name
        }
        throw IOException("Could not find an unused name for the trimmed video")
    }

    private fun normalizedRelativePath(path: String): String =
        path.trim().trim('/') + "/"

    private fun parseLocation(value: String?): VideoLocation? {
        if (value.isNullOrBlank()) return null
        val match = LOCATION_REGEX.find(value.trim()) ?: return null
        val latitude = match.groupValues[1].toFloatOrNull() ?: return null
        val longitude = match.groupValues[2].toFloatOrNull() ?: return null
        return if (latitude in -90f..90f && longitude in -180f..180f) {
            VideoLocation(latitude, longitude)
        } else {
            null
        }
    }

    private fun createTrimException(
        cause: Throwable,
        skippedTracks: List<String> = emptyList(),
    ): VideoTrimException {
        val messageChain = generateSequence(cause) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val message = when {
            "enospc" in messageChain || "no space left" in messageChain || "not enough space" in messageChain ->
                "There is not enough storage space to save the trimmed video."

            cause is IOException -> "Could not read or write the video. Check storage space and try again."
            else -> "Could not trim this video. It may be corrupted or use an unsupported format."
        }
        return VideoTrimException(message, cause, skippedTracks.toList())
    }

    private data class SourceTrack(
        val sourceIndex: Int,
        val format: MediaFormat,
        val mimeType: String,
    )

    private data class SourceMetadata(
        val name: String? = null,
        val dateTakenMillis: Long? = null,
        val relativePath: String? = null,
        val dataPath: String? = null,
        val volumeName: String? = null,
    )

    private data class TrimOutput(
        val uri: Uri,
    )

    private data class VideoLocation(
        val latitude: Float,
        val longitude: Float,
    )

    private companion object {
        const val TAG = "VideoTrimmer"
        const val MIN_CLIP_DURATION_US = 1_000_000L
        const val DEFAULT_SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024
        const val MAX_SAMPLE_BUFFER_SIZE = 64 * 1024 * 1024
        const val COPY_BUFFER_SIZE = 256 * 1024
        const val PROGRESS_INTERVAL_US = 100_000L
        const val PROGRESS_INTERVAL_MS = 150L
        const val MAX_NAME_ATTEMPTS = 10_000
        val VALID_ROTATIONS = setOf(0, 90, 180, 270)
        val LOCATION_REGEX =
            Regex("([+-][0-9]+(?:\\.[0-9]+)?)([+-][0-9]+(?:\\.[0-9]+)?)(?:[+-][0-9]+(?:\\.[0-9]+)?)?/?")
    }
}
