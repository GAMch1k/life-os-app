package com.gamch1k.lifeos.phone.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns any photo Android can decode (JPEG, PNG, WebP, HEIC/HEIF from the
 * camera) into a JPEG Inbox accepts: at most [MAX_EDGE] pixels on the long
 * side, EXIF rotation applied by ImageDecoder, metadata (GPS) dropped, and
 * well under Inbox's 10 MiB per image.
 */
object ImagePreparer {
    const val MAX_EDGE = 2560
    private const val MAX_BYTES = 9L * 1024 * 1024

    fun prepare(context: Context, uri: Uri, target: File) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > MAX_EDGE) {
                val scale = MAX_EDGE.toDouble() / longest
                decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
            }
            // Software bitmaps can be compressed; hardware ones cannot.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val opaque = if (decoded.hasAlpha()) onWhite(decoded).also { decoded.recycle() } else decoded
        try {
            var quality = 88
            while (true) {
                target.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                if (target.length() <= MAX_BYTES || quality <= 50) break
                quality -= 12
            }
        } finally {
            opaque.recycle()
        }
    }

    /** JPEG has no transparency: a screenshot or PNG with alpha goes onto white, not black. */
    private fun onWhite(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return result
    }
}
