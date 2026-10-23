package me.csepregi.pkmrec.recognition

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Runs on-device ML Kit text recognition on camera frames in two passes:
 *
 * 1. the whole frame, to find the card's text area (and the name at the top);
 * 2. a crop of the card's bottom strip, enlarged, so the small set code and number
 *    are read more reliably (they are tiny and often partly covered by glare).
 *
 * Reports the reading of each frame (or null) and the lines used. Results are delivered on the main thread.
 */
class CardTextAnalyzer(
    private val onFrame: (reading: CardReading?, lines: List<OcrLine>) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile var paused = false

    /** One frame at a time: frames arriving while OCR is still running are dropped. */
    @Volatile private var busy = false

    private fun deliver(reading: CardReading?, lines: List<OcrLine>) {
        busy = false
        if (!paused) onFrame(reading, lines)
    }

    override fun analyze(image: ImageProxy) {
        if (paused || busy) {
            image.close()
            return
        }
        busy = true
        val rotation = image.imageInfo.rotationDegrees
        val upright: Bitmap = try {
            image.toBitmap().rotated(rotation)
        } catch (e: Exception) {
            image.close()
            busy = false
            return
        }
        image.close()

        recognizer.process(InputImage.fromBitmap(upright, 0))
            .addOnFailureListener { deliver(null, emptyList()) }
            .addOnSuccessListener { text ->
                val fullLines = text.toLines()
                val strip = bottomStrip(fullLines, upright.width, upright.height)
                if (strip == null || paused) {
                    deliver(CardTextParser.parse(fullLines, upright.height), fullLines)
                    return@addOnSuccessListener
                }
                val crop = cropAndEnlarge(upright, strip)
                recognizer.process(InputImage.fromBitmap(crop.bitmap, 0))
                    .addOnSuccessListener { stripText ->
                        val stripLines = stripText.toLines(
                            offsetX = strip.left, offsetY = strip.top, scale = crop.scale, fromStrip = true,
                        )
                        // Text above the strip comes from the full frame, the strip itself from the enlarged crop.
                        val merged = fullLines.filter { it.bottom < strip.top } + stripLines
                        val reading = CardTextParser.parse(merged, upright.height)
                            ?: CardTextParser.parse(fullLines, upright.height)
                        deliver(reading, merged)
                    }
                    .addOnFailureListener { deliver(CardTextParser.parse(fullLines, upright.height), fullLines) }
            }
    }

    override fun close() {
        recognizer.close()
    }

    private data class Strip(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private class Crop(val bitmap: Bitmap, val scale: Float)

    /** The bottom ~16 % of the card's text area, where set code and number are printed. */
    private fun bottomStrip(lines: List<OcrLine>, width: Int, height: Int): Strip? {
        if (lines.size < 3) return null
        val top = lines.minOf { it.top }
        val bottom = lines.maxOf { it.bottom }
        val span = bottom - top
        if (span < height / 5) return null // not a whole card in view
        val stripTop = max(0, bottom - (span * 0.16f).roundToInt())
        val stripBottom = min(height, bottom + (span * 0.04f).roundToInt())
        val left = max(0, lines.minOf { it.left } - width / 40)
        val right = min(width, lines.maxOf { it.right } + width / 40)
        if (right - left < 50 || stripBottom - stripTop < 20) return null
        return Strip(left, stripTop, right, stripBottom)
    }

    private fun cropAndEnlarge(source: Bitmap, strip: Strip): Crop {
        val w = strip.right - strip.left
        val h = strip.bottom - strip.top
        val scale = min(2.5f, 2000f / w).coerceAtLeast(1f)
        val matrix = Matrix().apply { postScale(scale, scale) }
        val bitmap = Bitmap.createBitmap(source, strip.left, strip.top, w, h, matrix, true)
        return Crop(bitmap, scale)
    }

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        if (degrees == 0) return this
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Text.toLines(
        offsetX: Int = 0,
        offsetY: Int = 0,
        scale: Float = 1f,
        fromStrip: Boolean = false,
    ): List<OcrLine> =
        textBlocks.flatMap { it.lines }.map { line ->
            val box = line.boundingBox
            OcrLine(
                text = line.text,
                top = offsetY + ((box?.top ?: 0) / scale).roundToInt(),
                bottom = offsetY + ((box?.bottom ?: 0) / scale).roundToInt(),
                left = offsetX + ((box?.left ?: 0) / scale).roundToInt(),
                right = offsetX + ((box?.right ?: 0) / scale).roundToInt(),
                fromStrip = fromStrip,
            )
        }
}
