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
 * Runs on-device ML Kit text recognition on camera frames:
 *
 * 1. the whole frame, to find the card's text area (and the name at the top);
 * 2. only when the whole card is in view: a crop of the card's bottom strip, enlarged,
 *    so the small set code and number are read more reliably. When just the card's
 *    corner is shown up close, the first pass is enough.
 *
 * Reports the reading of each frame (or null) and the lines used. Results are delivered on the main thread.
 */
class CardTextAnalyzer(
    private val onFrame: (reading: CardReading?, lines: List<OcrLine>) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private companion object {
        val NUMBER_LIKE = Regex("""\w{1,5}\s*/\s*\w{2,5}""")
    }

    @Volatile var paused = false

    /** One frame at a time: frames arriving while OCR is still running are dropped. */
    @Volatile private var busy = false

    private fun deliver(reading: CardReading?, lines: List<OcrLine>) {
        busy = false
        if (!paused) onFrame(reading, lines)
    }

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || busy || media == null) {
            image.close()
            return
        }
        busy = true
        val rotation = image.imageInfo.rotationDegrees
        val sideways = rotation == 90 || rotation == 270
        val width = if (sideways) image.height else image.width
        val height = if (sideways) image.width else image.height

        // Pass 1: the camera frame as is (no bitmap conversion, fast).
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnCompleteListener { task ->
                if (!task.isSuccessful || paused) {
                    image.close()
                    deliver(null, emptyList())
                    return@addOnCompleteListener
                }
                val fullLines = task.result.toLines()
                val quick = CardTextParser.parse(fullLines, height)

                // Close-up of the card corner: the number is already large, a second pass would only cost time.
                val strip = if (isCloseUp(fullLines, height)) null else bottomStrip(fullLines, width, height)
                if (strip == null) {
                    image.close()
                    deliver(quick, fullLines)
                    return@addOnCompleteListener
                }

                // Pass 2: the card's bottom strip, cropped and enlarged.
                val upright = try {
                    image.toBitmap().rotated(rotation)
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (upright == null || strip.right > upright.width || strip.bottom > upright.height) {
                    deliver(quick, fullLines)
                    return@addOnCompleteListener
                }
                val crop = cropAndEnlarge(upright, strip)
                recognizer.process(InputImage.fromBitmap(crop.bitmap, 0))
                    .addOnSuccessListener { stripText ->
                        val stripLines = stripText.toLines(
                            offsetX = strip.left, offsetY = strip.top, scale = crop.scale, fromStrip = true,
                        )
                        // Both readings of the strip take part: the enlarged crop is usually better,
                        // but on full-art cards the plain frame can be (the parser prefers lines with a set code).
                        val merged = fullLines + stripLines
                        deliver(CardTextParser.parse(merged, height) ?: quick, merged)
                    }
                    .addOnFailureListener { deliver(quick, fullLines) }
            }
    }

    /** A number like "123/193" printed at least ~2.2 % of the frame height tall means the corner is shown up close. */
    private fun isCloseUp(lines: List<OcrLine>, height: Int): Boolean {
        val numberHeight = lines.filter { NUMBER_LIKE.containsMatchIn(it.text) }.maxOfOrNull { it.height } ?: return false
        return numberHeight >= height * 0.022f
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
