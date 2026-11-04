package io.github.dwicki.hamatterpair.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR decoding/encoding with ZXing core (Apache-2.0, pure Java, fully offline). No ML Kit.
 */
object QrCodes {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decode(source: LuminanceSource): String? {
        val reader = MultiFormatReader().apply { setHints(hints) }
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            // Inverted (light-on-dark) codes exist on some labels.
            runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source.invert()))).text }.getOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Decode from a picked photo. Large photos are scaled down first for speed. */
    fun decode(bitmap: Bitmap): String? {
        val scaled = if (maxOf(bitmap.width, bitmap.height) > 1600) {
            val f = 1600f / maxOf(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * f).toInt(), (bitmap.height * f).toInt(), true)
        } else bitmap
        val px = IntArray(scaled.width * scaled.height)
        scaled.getPixels(px, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        return decode(RGBLuminanceSource(scaled.width, scaled.height, px))
    }

    fun encode(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        val px = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    /** CameraX analyzer reading the Y plane only (fast, no color conversion). */
    class Analyzer(
        /** Which decoded texts to accept; default: Matter payloads. */
        private val accept: (String) -> Boolean = { it.contains("MT:", ignoreCase = true) },
        private val onResult: (String) -> Unit,
    ) : ImageAnalysis.Analyzer {
        @Volatile private var done = false
        override fun analyze(image: ImageProxy) {
            image.use {
                if (done) return
                val plane = it.planes[0]
                val buf = plane.buffer
                val data = ByteArray(buf.remaining()).also { b -> buf.get(b) }
                val source = PlanarYUVLuminanceSource(data, plane.rowStride, it.height, 0, 0, it.width, it.height, false)
                val text = decode(source) ?: return
                if (accept(text)) {
                    done = true
                    onResult(text)
                }
            }
        }
    }
}
