package org.koitharu.album.ui.viewer

import androidx.compose.runtime.Immutable
import org.koitharu.album.ui.common.AlbumItem

@Immutable
data class ViewerState(
    val currentMedia: AlbumItem.Media,
    val isRotationGestureEnabled: Boolean,
    val isVideosMutedOnStart: Boolean,
    val infoBottomSheetMedia: AlbumItem.Media?,
) {

    constructor(media: AlbumItem.Media) : this(
        currentMedia = media,
        isRotationGestureEnabled = false,
        infoBottomSheetMedia = null,
        isVideosMutedOnStart = false,
    )
}
