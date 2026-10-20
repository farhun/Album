package org.koitharu.album.ui.editor

import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.koitharu.album.R
import org.koitharu.album.util.formatTimeSeconds
import kotlin.math.max
import kotlin.math.roundToLong

@Composable
fun VideoTrimmerScreen(
    state: VideoTrimmerState,
    viewModel: VideoTrimmerViewModel,
    snackbarHostState: SnackbarHostState,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trim_video)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            VideoTrimmerPreview(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = state,
                onSeekToStart = viewModel::seekPreviewToStart,
            )
            Spacer(Modifier.height(16.dp))

            val sliderMax = max(state.durationMs.toFloat(), 1f)
            val range = if (state.isReady) {
                state.startMs.toFloat().coerceIn(0f, sliderMax)..state.endMs.toFloat().coerceIn(0f, sliderMax)
            } else {
                0f..sliderMax
            }
            RangeSlider(
                modifier = Modifier.fillMaxWidth(),
                value = range,
                onValueChange = { values ->
                    viewModel.onRangeChange(
                        startMs = values.start.roundToLong(),
                        endMs = values.endInclusive.roundToLong(),
                    )
                },
                valueRange = 0f..sliderMax,
                enabled = state.isReady && !state.isSaving && state.durationMs >= MIN_CLIP_DURATION_MS,
            )

            val snappedStartMs = state.snappedStartUs?.div(1_000L) ?: state.startMs
            val selectedDurationMs = (state.endMs - snappedStartMs).coerceAtLeast(0L)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.trim_start_time, state.startMs.formatMillis()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.snappedStartUs?.let { snapped ->
                    Text(
                        text = stringResource(R.string.trim_keyframe_start, (snapped / 1_000L).formatMillis()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.trim_end_time, state.endMs.formatMillis()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.trim_duration, selectedDurationMs.formatMillis()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (state.isSaving) {
                if (state.progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { state.progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = viewModel::cancelSaving,
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.isReady && state.endMs - state.startMs >= MIN_CLIP_DURATION_MS,
                    onClick = viewModel::startSaving,
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun VideoTrimmerPreview(
    modifier: Modifier,
    state: VideoTrimmerState,
    onSeekToStart: () -> Unit,
) {
    val context = LocalContext.current
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(state.showFramePreview) {
        if (state.showFramePreview) isPlaying = false
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                VideoView(context).apply {
                    setVideoURI(Uri.parse(state.sourceUri))
                    setOnPreparedListener {
                        isPrepared = true
                        seekTo(state.seekPositionMs.toInt())
                    }
                    setOnCompletionListener { isPlaying = false }
                    videoView = this
                }
            },
            update = { player ->
                if (player.tag != state.seekRequest) {
                    player.tag = state.seekRequest
                    player.seekTo(state.seekPositionMs.toInt())
                }
                if (isPrepared) {
                    if (isPlaying && !player.isPlaying) player.start()
                    else if (!isPlaying && player.isPlaying) player.pause()
                }
            },
            onRelease = { player ->
                player.stopPlayback()
                videoView = null
                isPrepared = false
                isPlaying = false
            },
        )
        if (state.showFramePreview && state.previewFrame != null) {
            Image(
                bitmap = state.previewFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        IconButton(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
            onClick = {
                if (isPlaying) {
                    isPlaying = false
                } else {
                    onSeekToStart()
                    isPlaying = true
                }
            },
            enabled = isPrepared && !state.isSaving,
        ) {
            Icon(
                modifier = Modifier.size(42.dp),
                painter = painterResource(
                    if (isPlaying) R.drawable.ic_pause_circle else R.drawable.ic_play_circle
                ),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!state.isReady || !isPrepared) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }

    LaunchedEffect(isPlaying, isPrepared, state.endMs) {
        if (!isPlaying || !isPrepared) return@LaunchedEffect
        while (true) {
            delay(80L)
            val player = videoView ?: break
            if (player.currentPosition.toLong() >= state.endMs) {
                player.pause()
                isPlaying = false
                break
            }
        }
    }
}

private fun Long.formatMillis(): String =
    coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().formatTimeSeconds()

private const val MIN_CLIP_DURATION_MS = 1_000L
