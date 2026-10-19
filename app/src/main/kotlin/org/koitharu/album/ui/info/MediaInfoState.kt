package org.koitharu.album.ui.info

import android.util.Size
import androidx.compose.runtime.Immutable
import org.koitharu.album.ui.common.AlbumItem
import kotlin.math.roundToLong

@Immutable
data class MediaInfoState(
    val fileName: String?,
    val latLng: Pair<Double, Double>?,
    val model: String?,
    val size: Size?,
    val fileSizeBytes: Long?,
    val path: String?,
    val relativePath: String?,
    val volumeName: String?,
    val isImage: Boolean,
) {

    constructor(media: AlbumItem.Media) : this(
        fileName = media.name,
        latLng = null,
        model = null,
        size = null,
        fileSizeBytes = null,
        path = null,
        relativePath = null,
        volumeName = null,
        isImage = media is AlbumItem.Image,
    )

    val sizeMp: Double? = size?.run {
        val totalPixels = width.toLong() * height.toLong()
        (totalPixels / 100_000.0).roundToLong() / 10.0
    }
}
