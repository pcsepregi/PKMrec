package me.csepregi.pkmrec.recognition

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

/**
 * Runs on-device ML Kit text recognition on camera frames and reports the card reading
 * found in each frame (or null), plus the raw text lines. Results are delivered on the main thread.
 */
class CardTextAnalyzer(
    private val onFrame: (reading: CardReading?, lines: List<OcrLine>) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile var paused = false

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || media == null) {
            image.close()
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        // Bounding boxes are reported in the upright (rotated) image.
        val uprightHeight = if (rotation == 90 || rotation == 270) image.width else image.height
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { text ->
                if (paused) return@addOnSuccessListener
                val lines = text.textBlocks.flatMap { it.lines }.map { line ->
                    val box = line.boundingBox
                    OcrLine(line.text, box?.top ?: 0, box?.bottom ?: 0)
                }
                onFrame(CardTextParser.parse(lines, uprightHeight), lines)
            }
            .addOnCompleteListener { image.close() }
    }

    override fun close() {
        recognizer.close()
    }
}
