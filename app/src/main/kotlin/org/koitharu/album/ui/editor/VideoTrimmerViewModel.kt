package org.koitharu.album.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.album.repository.editor.VideoTrimProgress
import org.koitharu.album.repository.editor.VideoTrimmer

@HiltViewModel(assistedFactory = VideoTrimmerViewModel.Factory::class)
class VideoTrimmerViewModel @AssistedInject constructor(
    @Assisted sourceUriString: String,
    @ApplicationContext private val context: Context,
    private val videoTrimmer: VideoTrimmer,
) : ViewModel() {

    private val sourceUri: Uri = sourceUriString.toUri()
    private val _state = MutableStateFlow(VideoTrimmerState(sourceUri = sourceUriString))
    val state = _state.asStateFlow()

    private var previewJob: Job? = null
    private var saveJob: Job? = null

    init {
        loadVideo()
    }

    fun onRangeChange(startMs: Long, endMs: Long) {
        val previous = _state.value
        val durationMs = previous.durationMs
        if (!previous.isReady || previous.isSaving || durationMs <= 0L) return

        var newStart = startMs.coerceIn(0L, durationMs)
        var newEnd = endMs.coerceIn(0L, durationMs)
        if (newEnd - newStart < MIN_CLIP_DURATION_MS && durationMs >= MIN_CLIP_DURATION_MS) {
            if (newStart != previous.startMs) {
                newStart = (newEnd - MIN_CLIP_DURATION_MS).coerceAtLeast(0L)
            } else {
                newEnd = (newStart + MIN_CLIP_DURATION_MS).coerceAtMost(durationMs)
            }
        }
        val previewPositionMs = if (newStart != previous.startMs) newStart else newEnd
        _state.update {
            it.copy(
                startMs = newStart,
                endMs = newEnd,
                snappedStartUs = null,
                previewFrame = null,
                seekRequest = it.seekRequest + 1,
                seekPositionMs = previewPositionMs,
                showFramePreview = true,
            )
        }
        requestFramePreview(previewPositionMs)
    }

    fun seekPreviewToStart() {
        _state.update {
            it.copy(
                seekRequest = it.seekRequest + 1,
                seekPositionMs = it.snappedStartUs?.div(1_000L) ?: it.startMs,
                showFramePreview = false,
            )
        }
    }

    fun startSaving() {
        if (saveJob?.isActive == true) return
        val snapshot = _state.value
        if (!snapshot.isReady || snapshot.endMs - snapshot.startMs < MIN_CLIP_DURATION_MS) return

        saveJob = viewModelScope.launch {
            _state.update { it.copy(isSaving = true, progress = null, message = null) }
            try {
                val result = videoTrimmer.trim(
                    source = sourceUri,
                    startUs = snapshot.startMs * 1_000L,
                    endUs = snapshot.endMs * 1_000L,
                ) { progress ->
                    _state.update { it.copy(progress = progress) }
                }
                _state.update {
                    it.copy(
                        isSaving = false,
                        saved = true,
                        skippedTracks = result.skippedTracks,
                        progress = VideoTrimProgress(
                            snappedStartUs = result.snappedStartUs,
                            copiedDurationUs = result.requestedEndUs - result.snappedStartUs,
                            totalDurationUs = result.requestedEndUs - result.snappedStartUs,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(isSaving = false, progress = null, message = "Trim cancelled.")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        progress = null,
                        message = e.message ?: "Could not trim this video.",
                    )
                }
            }
        }
    }

    fun cancelSaving() {
        saveJob?.cancel()
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun loadVideo() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val durationMs = readDurationMs(sourceUri)
                if (durationMs <= 0L) throw IllegalStateException("Could not read this video's duration.")
                val snappedStartUs = videoTrimmer.getSnappedStartUs(sourceUri, 0L)
                _state.update {
                    it.copy(
                        isReady = true,
                        durationMs = durationMs,
                        startMs = 0L,
                        endMs = durationMs,
                        snappedStartUs = snappedStartUs,
                        seekPositionMs = 0L,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        message = e.message ?: "Could not open this video.",
                    )
                }
            }
        }
    }

    private fun requestFramePreview(positionMs: Long) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.IO) {
            delay(FRAME_PREVIEW_DEBOUNCE_MS)
            try {
                val snappedStartUs = videoTrimmer.getSnappedStartUs(
                    sourceUri,
                    _state.value.startMs * 1_000L,
                )
                val retriever = MediaMetadataRetriever()
                val bitmap: Bitmap? = try {
                    retriever.setDataSource(context, sourceUri)
                    retriever.getFrameAtTime(
                        positionMs * 1_000L,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    )
                } finally {
                    retriever.release()
                }
                if (coroutineContext.isActive) {
                    _state.update {
                        it.copy(
                            snappedStartUs = snappedStartUs,
                            previewFrame = bitmap,
                            previewFramePositionMs = positionMs,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the player preview available if frame extraction is unsupported.
            }
        }
    }

    private suspend fun readDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(sourceUri: String): VideoTrimmerViewModel
    }

    private companion object {
        const val MIN_CLIP_DURATION_MS = 1_000L
        const val FRAME_PREVIEW_DEBOUNCE_MS = 120L
    }
}

data class VideoTrimmerState(
    val sourceUri: String,
    val isReady: Boolean = false,
    val durationMs: Long = 0L,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val snappedStartUs: Long? = null,
    val seekRequest: Int = 0,
    val seekPositionMs: Long = 0L,
    val showFramePreview: Boolean = false,
    val previewFrame: Bitmap? = null,
    val previewFramePositionMs: Long = -1L,
    val isSaving: Boolean = false,
    val progress: VideoTrimProgress? = null,
    val skippedTracks: List<String> = emptyList(),
    val saved: Boolean = false,
    val message: String? = null,
)
