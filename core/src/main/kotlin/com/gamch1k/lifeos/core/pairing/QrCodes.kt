package com.gamch1k.lifeos.core.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads the pairing QR code from a photo (ZXing, Apache-2.0). The phone
 * takes the photo with the camera app, as Photo capture does, so the app
 * needs no camera permission of its own (README, "Pairing").
 */
object QrCodes {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * The text of the QR code in an image of [width] × [height] ARGB [pixels]
     * (`Bitmap.getPixels`), or null when none can be read. Tries both of
     * ZXing's binarizers, and light on dark as well (a dark-mode screen).
     */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        require(pixels.size >= width * height) { "fewer pixels than ${width}x$height" }
        val source = RGBLuminanceSource(width, height, pixels)
        for (luminance in listOf<LuminanceSource>(source, InvertedLuminanceSource(source))) {
            for (bitmap in listOf(BinaryBitmap(HybridBinarizer(luminance)), BinaryBitmap(GlobalHistogramBinarizer(luminance)))) {
                try {
                    return QRCodeReader().decode(bitmap, hints).text
                } catch (_: ReaderException) {
                    // Not found, unreadable, or a checksum error: try the next way.
                }
            }
        }
        return null
    }
}
