package org.koitharu.album.ui.info

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.koitharu.album.R
import org.koitharu.album.ui.common.AlbumItem
import org.koitharu.album.ui.theme.AlbumTheme

@Composable
fun MediaInfoBottomSheet(
    media: AlbumItem.Media,
    onDismissRequest: () -> Unit,
) {
    val viewModel = hiltViewModel<MediaInfoViewModel, MediaInfoViewModel.Factory> {
        it.create(media)
    }
    val state by viewModel.collectState()
    val context = LocalContext.current
    var hasRequestedLocationPermission by remember(media.id) { mutableStateOf(false) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { isGranted ->
        hasRequestedLocationPermission = true
        if (!isGranted) LocationPermissionSession.wasDenied = true
        viewModel.onMediaLocationPermissionResult(isGranted)
    }
    LaunchedEffect(media.id) {
        if (media is AlbumItem.Video) {
            viewModel.readLocationMetadata()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val permission = android.Manifest.permission.ACCESS_MEDIA_LOCATION
            val alreadyGranted =
                ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) {
                viewModel.onMediaLocationPermissionResult(true)
            } else if (!hasRequestedLocationPermission && !LocationPermissionSession.wasDenied) {
                hasRequestedLocationPermission = true
                locationPermissionLauncher.launch(permission)
            } else {
                viewModel.onMediaLocationPermissionResult(false)
            }
        } else {
            viewModel.onMediaLocationPermissionResult(true)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
    ) {
        MediaInfoContent(
            state = state,
            onOpenFolder = { viewModel.getContainingFolderDocumentUri(state.path) }
        )
    }
}

private fun launchFolderIntent(
    context: Context,
    action: String,
    folderUri: Uri,
    packageName: String? = null,
): Boolean {
    val intent = Intent(action)
        .setDataAndType(folderUri, DocumentsContract.Document.MIME_TYPE_DIR)
        .apply { packageName?.let(::setPackage) }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun copyPath(context: Context, label: String, path: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, path))
}

private object LocationPermissionSession {
    var wasDenied = false
}

@Composable
private fun MediaInfoContent(
    state: MediaInfoState,
    onOpenFolder: () -> Pair<Uri?, String?>,
) {
    val context = LocalContext.current
    TableRow(
        title = stringResource(R.string.name),
        content = state.fileName,
    )
    TableRow(
        title = stringResource(R.string.file_size),
        content = state.fileSizeBytes?.let {
            android.text.format.Formatter.formatFileSize(LocalContext.current, it)
        },
    )
    val pathTitle = stringResource(R.string.path)
    TableRow(
        title = pathTitle,
        content = state.path,
        onClick = state.path?.let { path ->
            {
                val (folderUri, _) = onOpenFolder()
                val launched = folderUri != null && run {
                    val browseLaunched = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            launchFolderIntent(
                                context = context,
                                action = "android.provider.action.BROWSE",
                                folderUri = folderUri,
                            )
                    browseLaunched || launchFolderIntent(
                        context = context,
                        action = Intent.ACTION_VIEW,
                        folderUri = folderUri,
                    ) || listOf(
                        "com.google.android.documentsui",
                        "com.android.documentsui",
                    ).any { packageName ->
                        launchFolderIntent(
                            context = context,
                            action = Intent.ACTION_VIEW,
                            folderUri = folderUri,
                            packageName = packageName,
                        )
                    }
                }
                if (!launched) {
                    copyPath(context, pathTitle, path)
                    android.widget.Toast.makeText(
                        context,
                        R.string.no_file_manager_found_path_copied,
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        },
        onLongClick = state.path?.let { path ->
            {
                copyPath(context, pathTitle, path)
                android.widget.Toast.makeText(context, R.string.copied, android.widget.Toast.LENGTH_SHORT).show()
            }
        },
    )
    if (state.isImage) {
        TableRow(
            title = stringResource(R.string.width),
            content = state.size?.width?.toString(),
        )
        TableRow(
            title = stringResource(R.string.height),
            content = state.size?.height?.toString(),
        )
        TableRow(
            title = stringResource(R.string.resolution),
            content = state.sizeMp?.let {
                stringResource(R.string.size_megapixels_template, it)
            },
        )
        TableRow(
            title = stringResource(R.string.camera),
            content = state.model,
        )
    }
    state.latLng?.let { coordinates ->
        val latLng = "${coordinates.first}, ${coordinates.second}"
        TableRow(
            title = stringResource(R.string.location),
            content = latLng,
            onClick = {
                val uri =
                    Uri.parse("geo:${coordinates.first},${coordinates.second}?q=${coordinates.first},${coordinates.second}")
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                } catch (_: ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, R.string.no_maps_app, android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            },
        )
    }
}

@Composable
private fun TableRow(
    title: String,
    content: String?,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) = if (!content.isNullOrEmpty()) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        onClick = onClick ?: {},
                        onLongClick = onLongClick,
                    )
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            modifier = Modifier
                .alignByBaseline()
                .weight(2f),
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            modifier = Modifier
                .alignByBaseline()
                .weight(3f),
            text = content,
            style = MaterialTheme.typography.bodySmall,
            softWrap = true,
            overflow = TextOverflow.Clip,
        )
    }
} else {
    Unit
}

@Composable
@Preview
private fun PreviewMediaInfoContent() = AlbumTheme {
    Column {
        MediaInfoContent(
            state = MediaInfoState(
                fileName = "013042.jpg",
                latLng = 32.04234 to 34.1231,
                model = "Camera 40000",
                size = Size(1600, 900),
                fileSizeBytes = 3_200_000,
                path = "/storage/emulated/0/DCIM/Camera/013042.jpg",
                relativePath = "DCIM/Camera/",
                volumeName = MediaStore.VOLUME_EXTERNAL_PRIMARY,
                isImage = true,
            ),
            onOpenFolder = { null to null },
        )
    }
}
