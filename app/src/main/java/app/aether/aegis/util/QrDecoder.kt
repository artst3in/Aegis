package app.aether.aegis.util

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Decode a QR (or any other format zxing knows) out of a still
 * image picked by the user. Lives in util/ rather than peer/ because
 * the QR text could be any of the link shapes we accept (invite,
 * group, safety code), not just peer pairing.
 *
 * Returns null on any failure: file not readable, image too large
 * to decode, no QR found, ambiguous result. The caller surfaces a
 * "no QR found" toast — we don't differentiate causes because the
 * remediation is the same (pick a different image).
 */
object QrDecoder {

    private const val TAG = "QrDecoder"
    private const val MAX_DIMENSION = 4096

    fun decode(context: Context, uri: Uri): String? {
        return runCatching {
            // Two-pass decode: read EXIF dimensions first, then subsample
            // if the image is larger than MAX_DIMENSION. zxing is happy
            // up to a couple of megapixels but slows quadratically; a
            // 12 MP phone screenshot would otherwise spin for several
            // seconds before producing the same result a 4 MP downscale
            // does instantly.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val longSide = maxOf(opts.outWidth, opts.outHeight)
            opts.inSampleSize = if (longSide > MAX_DIMENSION) {
                var s = 1
                while (longSide / s > MAX_DIMENSION) s *= 2
                s
            } else 1
            opts.inJustDecodeBounds = false
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val width = bmp.width
            val height = bmp.height
            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            bmp.recycle()
            val source = RGBLuminanceSource(width, height, pixels)
            val binary = BinaryBitmap(HybridBinarizer(source))
            val reader = MultiFormatReader().apply {
                setHints(
                    mapOf(
                        DecodeHintType.TRY_HARDER to true,
                        DecodeHintType.POSSIBLE_FORMATS to listOf(
                            com.google.zxing.BarcodeFormat.QR_CODE,
                        ),
                    ),
                )
            }
            try {
                reader.decodeWithState(binary).text
            } catch (_: NotFoundException) {
                null
            }
        }.onFailure { Log.w(TAG, "decode failed: ${it.message}") }
            .getOrNull()
    }
}
