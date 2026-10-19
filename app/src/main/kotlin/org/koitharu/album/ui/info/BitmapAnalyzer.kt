package org.koitharu.album.ui.info


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import androidx.core.graphics.get
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.InputStream

class BitmapAnalyzer {

    private fun luminance(r: Int, g: Int, b: Int): Int = (0.299 * r + 0.587 * g + 0.114 * b)
        .toInt()
        .coerceIn(0, 255)

    suspend fun isTopRegionDark(
        inputStream: InputStream,
        topPercentage: Float = 0.15f,
        sampleSize: Int = 8
    ): Boolean {
        var regionDecoder: BitmapRegionDecoder? = null
        var croppedBitmap: Bitmap? = null

        try {
            regionDecoder = createRegionBitmapDecoder(inputStream)

            val imgWidth = regionDecoder.width
            val imgHeight = regionDecoder.height

            val targetHeight = (imgHeight * topPercentage).toInt()
            val topRegionRect = Rect(0, 0, imgWidth, targetHeight)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            croppedBitmap = withContext(Dispatchers.IO) {
                regionDecoder.decodeRegion(topRegionRect, options)
            } ?: error("Unable to decode bitmap")

            return withContext(Dispatchers.Default) {
                analyzeBitmapLuminance(croppedBitmap)
            }
        } finally {
            croppedBitmap?.recycle()
            regionDecoder?.recycle()
        }
    }

    private fun analyzeBitmapLuminance(bitmap: Bitmap): Boolean {
        var darkPixels = 0
        var totalPixels = 0

        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                val color = bitmap[x, y]

                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                if (luminance(r, g, b) < 125) {
                    darkPixels++
                }
                totalPixels++
            }
        }

        return totalPixels != 0 && (darkPixels.toFloat() / totalPixels) >= 0.45f
    }

    private suspend fun createRegionBitmapDecoder(
        inputStream: InputStream,
    ): BitmapRegionDecoder = runInterruptible(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(inputStream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(inputStream, false)
        }
    } ?: error("Unable to instantiate decoder")
}
