package org.koitharu.album.ui.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.album.R
import org.koitharu.album.ui.common.ComposeActivity
import org.koitharu.album.ui.common.EmptyState
import org.koitharu.album.ui.theme.AlbumTheme
import org.koitharu.album.util.rememberPermissionCheck

@AndroidEntryPoint
class VideoTrimmerActivity : ComposeActivity() {

    @Composable
    override fun Content() {
        val sourceUri = remember { intent.dataString }
        LaunchedEffect(sourceUri) {
            if (sourceUri.isNullOrBlank()) finishAfterTransition()
        }
        if (sourceUri.isNullOrBlank()) return

        val hasWritePermission by if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            rememberPermissionCheck(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            remember { mutableStateOf(true) }
        }
        if (!hasWritePermission) {
            EmptyState(
                modifier = Modifier.fillMaxSize(),
                iconResId = R.drawable.ic_folder_alert,
                title = stringResource(R.string.no_permissions),
                message = stringResource(R.string.no_permissions_message),
            ) {
                Button(onClick = ::openAppSettings) {
                    Text(stringResource(R.string.settings))
                }
            }
            return
        }

        AlbumTheme {
            val viewModel = hiltViewModel<VideoTrimmerViewModel, VideoTrimmerViewModel.Factory> {
                it.create(sourceUri)
            }
            val state by viewModel.state.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(state.saved) {
                if (state.saved) {
                    val message = if (state.skippedTracks.isEmpty()) {
                        getString(R.string.video_saved)
                    } else {
                        getString(R.string.video_saved_skipped, state.skippedTracks.joinToString())
                    }
                    Toast.makeText(this@VideoTrimmerActivity, message, Toast.LENGTH_SHORT).show()
                    finishAfterTransition()
                }
            }
            VideoTrimmerScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onClose = { finishAfterTransition() },
            )
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}
