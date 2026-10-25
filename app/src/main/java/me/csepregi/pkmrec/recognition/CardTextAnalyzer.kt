package me.csepregi.pkmrec.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
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
 * Reports the reading of each frame (or null), the lines used and, with a reading, a greyscale crop of the
 * area next to the number where the set symbol is printed (see [SymbolMatcher]). Results are delivered on
 * the main thread.
 *
 * [loadNames] gives the Pokémon names used to tell the card name from attack and ability names;
 * it is called once, on the analysis thread.
 *
 * With [detectPresence] on (debug mode), each frame also reports which kinds of card text were seen
 * (see [CardPresence]); otherwise the set is empty.
 *
 * A frame without a number also reports the set code and name, if both were read ([CardTextParser.codeAndName]).
 */
class CardTextAnalyzer(
    private val loadNames: () -> List<String> = { emptyList() },
    private val onFrame: (
        reading: CardReading?,
        lines: List<OcrLine>,
        symbol: GrayImage?,
        markers: Set<CardPresence.Marker>,
        codeOnly: CardReading?,
    ) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private companion object {
        /**
         * A card number such as "123/193", "TG05/TG30" or a misread "O15/128" or cut "SVI4/SV9": up to two
         * letters, then digits (or letters OCR confuses with digits) on both sides of the slash, with at
         * least two real digits.
         * Not "com/cards" or "cards/38896" from a web address on a screen.
         */
        val NUMBER_LIKE = Regex("""(?<![\w/])[A-Za-z]{0,2}[0-9OoQDIlSBZGT]{1,3}\s*/\s*[A-Za-z]{0,2}[0-9OoQDIlSBZGT]{1,3}(?![\w/])""")

        fun isNumberLike(text: String): Boolean =
            NUMBER_LIKE.findAll(text).any { match -> match.value.count { it.isDigit() } >= 2 }

        /** The short "weakness / resistance / retreat" label line just above the number (OCR: "TEtrat", "weakness2"). */
        val WEAKNESS_LINE = Regex("""weak|resist|retreat|etreat|etrat""", RegexOption.IGNORE_CASE)
    }

    @Volatile private var names: PokemonNameFinder? = null

    @Volatile var paused = false

    @Volatile var detectPresence = false

    /** One frame at a time: frames arriving while OCR is still running are dropped. */
    @Volatile private var busy = false

    private fun deliver(reading: CardReading?, lines: List<OcrLine>, symbol: GrayImage? = null) {
        busy = false
        if (paused) return
        val markers = if (detectPresence) CardPresence.markers(lines, names) else emptySet()
        val codeOnly = if (reading == null) CardTextParser.codeAndName(lines, names) else null
        onFrame(reading, lines, symbol, markers, codeOnly)
    }

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (paused || busy || media == null) {
            image.close()
            return
        }
        busy = true
        if (names == null) {
            names = PokemonNameFinder(runCatching { loadNames() }.getOrDefault(emptyList()))
        }
        val nameFinder = names
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
                val quickParsed = CardTextParser.parseWithLine(fullLines, height, nameFinder)
                val quick = quickParsed?.reading

                // Pass 2 region: the bottom strip when the whole card is in view; otherwise, when no valid
                // number came out (e.g. white outlined digits on a dark full-art card), the area around
                // number-like text. A close-up that already gave a reading needs no second pass.
                val closeUp = isCloseUp(fullLines, height)
                val region = when {
                    closeUp && quick != null -> null
                    closeUp -> numberRegion(fullLines, width, height)
                    else -> bottomStrip(fullLines, width, height) ?: if (quick == null) numberRegion(fullLines, width, height) else null
                }
                if (region == null) {
                    val symbol = quickParsed?.let { parsed ->
                        symbolArea(parsed.numberLine, Strip(0, 0, width, height))?.let { area ->
                            runCatching { luminance(image, rotation, area).forSymbol(parsed.numberLine) }.getOrNull()
                        }
                    }
                    image.close()
                    deliver(quick, fullLines, symbol)
                    return@addOnCompleteListener
                }

                // The region's brightness, kept for cutting out the set symbol once pass 2 found the number.
                val regionLuminance = if (region.right <= width && region.bottom <= height) {
                    runCatching { luminance(image, rotation, region) }.getOrNull()
                } else {
                    null
                }
                val frame = try {
                    image.toBitmap()
                } catch (e: Exception) {
                    null
                } finally {
                    image.close()
                }
                if (frame == null || region.right > width || region.bottom > height) {
                    frame?.recycle()
                    deliver(quick, fullLines)
                    return@addOnCompleteListener
                }

                // Pass 2: the region enlarged, read as is and as inverted high-contrast grey
                // (light text on a dark background becomes dark text on light).
                val images = try {
                    cropAndEnlarge(frame, rotation, region).let { it to invertedHighContrast(it.bitmap) }
                } catch (e: Exception) {
                    null
                } finally {
                    // The full frame is a fresh copy made for this frame only; free it right away.
                    frame.recycle()
                }
                if (images == null) {
                    deliver(quick, fullLines)
                    return@addOnCompleteListener
                }
                val (crop, inverted) = images
                fun linesOf(text: Text) = text.toLines(
                    offsetX = region.left, offsetY = region.top, scale = crop.scale, fromStrip = true,
                )
                recognizer.process(InputImage.fromBitmap(crop.bitmap, 0)).addOnCompleteListener { first ->
                    val plain = if (first.isSuccessful) linesOf(first.result) else emptyList()
                    recognizer.process(InputImage.fromBitmap(inverted, 0)).addOnCompleteListener { second ->
                        val inv = if (second.isSuccessful) linesOf(second.result) else emptyList()
                        // All readings take part; the parser prefers complete lines with a set code.
                        val merged = fullLines + plain + inv
                        val parsed = CardTextParser.parseWithLine(merged, height, nameFinder) ?: quickParsed
                        val symbol = parsed?.let { p ->
                            val area = symbolArea(p.numberLine, region) ?: return@let null
                            regionLuminance?.let { lum ->
                                runCatching {
                                    lum.cropped(area.left - region.left, area.top - region.top, area.width, area.height)
                                        .forSymbol(p.numberLine)
                                }.getOrNull()
                            }
                        }
                        deliver(parsed?.reading, merged, symbol)
                    }
                }
            }
    }

    /** Around the number-like lines, widened to the left to catch a lost leading digit and the set code. */
    private fun numberRegion(lines: List<OcrLine>, width: Int, height: Int): Strip? {
        val numbers = lines.filter { isNumberLike(it.text) }
        if (numbers.isEmpty()) return null
        val h = numbers.maxOf { it.height }.coerceAtLeast(10)
        val left = max(0, numbers.minOf { it.left } - 3 * h)
        val right = min(width, numbers.maxOf { it.right } + h)
        val top = max(0, numbers.minOf { it.top } - h)
        val bottom = min(height, numbers.maxOf { it.bottom } + h)
        if (right - left < 30 || bottom - top < 15) return null
        return Strip(left, top, right, bottom)
    }

    /** Grey, contrast-stretched and inverted: white outlined digits on dark art become dark on light. */
    private val invertPaint = Paint().apply {
        val c = 1.6f
        val offset = 128f + 128f * c
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -c * 0.299f, -c * 0.587f, -c * 0.114f, 0f, offset,
                    -c * 0.299f, -c * 0.587f, -c * 0.114f, 0f, offset,
                    -c * 0.299f, -c * 0.587f, -c * 0.114f, 0f, offset,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /*
     * The enlarged crop and its inverted copy are drawn into the same two bitmaps frame after frame
     * instead of new ones. Safe because frames are processed one at a time: the next frame's second
     * pass only starts after both OCR runs of this one have finished.
     */
    private var cropBuffer: Bitmap? = null
    private var invertedBuffer: Bitmap? = null

    /** Inverted high-contrast copy of [source], in the reused buffer. */
    private fun invertedHighContrast(source: Bitmap): Bitmap {
        val result = reuse(invertedBuffer, source.width, source.height).also { invertedBuffer = it }
        Canvas(result).drawBitmap(source, 0f, 0f, invertPaint)
        return result
    }

    /** [current] resized to [width] × [height] when its memory is large enough, otherwise a new bitmap. */
    private fun reuse(current: Bitmap?, width: Int, height: Int): Bitmap {
        if (current != null && !current.isRecycled && current.allocationByteCount >= width * height * 4) {
            if (current.width != width || current.height != height) {
                current.reconfigure(width, height, Bitmap.Config.ARGB_8888)
            }
            current.eraseColor(android.graphics.Color.TRANSPARENT)
            return current
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /** A number like "123/193" printed at least ~2.2 % of the frame height tall means the corner is shown up close. */
    private fun isCloseUp(lines: List<OcrLine>, height: Int): Boolean {
        val numberHeight = lines.filter { isNumberLike(it.text) }.maxOfOrNull { it.height } ?: return false
        return numberHeight >= height * 0.022f
    }

    override fun close() {
        recognizer.close()
        cropBuffer = null
        invertedBuffer = null
    }

    private data class Strip(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Where the set symbol can be, inside [bounds]: next to the number line, mostly to its left (the
     * regulation mark and the symbol come before the number; OCR sometimes glues them into the line).
     */
    private fun symbolArea(line: OcrLine, bounds: Strip): Strip? {
        val h = line.height.coerceAtLeast(8)
        val area = Strip(
            max(bounds.left, line.left - 3 * h),
            max(bounds.top, line.top - h / 2),
            min(bounds.right, line.left + 2 * h),
            min(bounds.bottom, line.bottom + h / 2),
        )
        return area.takeIf { it.width >= 2 * h && it.height >= h }
    }

    /** Scaled so the number text is [SymbolMatcher.NUMBER_HEIGHT] pixels tall. */
    private fun GrayImage.forSymbol(numberLine: OcrLine): GrayImage {
        val factor = SymbolMatcher.NUMBER_HEIGHT.toFloat() / numberLine.height.coerceAtLeast(8)
        return scaled(max(1, (width * factor).roundToInt()), max(1, (height * factor).roundToInt()))
    }

    /**
     * The brightness (Y plane) of [area], given in upright frame coordinates, read straight from the
     * camera image: no bitmap of the whole frame is needed.
     */
    private fun luminance(image: ImageProxy, rotation: Int, area: Strip): GrayImage {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sensorWidth = image.width
        val sensorHeight = image.height
        val pixels = FloatArray(area.width * area.height)
        for (y in 0 until area.height) {
            val uy = area.top + y
            for (x in 0 until area.width) {
                val ux = area.left + x
                val sx: Int
                val sy: Int
                when (rotation) {
                    90 -> { sx = uy; sy = sensorHeight - 1 - ux }
                    180 -> { sx = sensorWidth - 1 - ux; sy = sensorHeight - 1 - uy }
                    270 -> { sx = sensorWidth - 1 - uy; sy = ux }
                    else -> { sx = ux; sy = uy }
                }
                val index = sy.coerceIn(0, sensorHeight - 1) * rowStride + sx.coerceIn(0, sensorWidth - 1) * pixelStride
                pixels[y * area.width + x] = (buffer.get(index).toInt() and 0xFF) / 255f
            }
        }
        return GrayImage(area.width, area.height, pixels)
    }

    private class Crop(val bitmap: Bitmap, val scale: Float)

    /**
     * The card's bottom strip, where set code and number are printed: below the weakness / retreat line
     * when that was read, otherwise the bottom ~16 % of the text area. The weakness line keeps the strip
     * on the card when there is other text below it (e.g. a price under a card shown on a screen).
     */
    private fun bottomStrip(lines: List<OcrLine>, width: Int, height: Int): Strip? {
        if (lines.size < 3) return null
        val top = lines.minOf { it.top }
        val weakness = lines.filter { it.text.length <= 30 && WEAKNESS_LINE.containsMatchIn(it.text) }.maxByOrNull { it.top }
        val (stripTop, stripBottom) = if (weakness != null && weakness.top - top >= height / 6) {
            // The number is a few label heights below the weakness line; the text above may include
            // more than the card (e.g. a web page on a screen), so it only limits the strip.
            val span = weakness.top - top
            val below = min((span * 0.28f).roundToInt(), 5 * weakness.height.coerceAtLeast(10))
            max(0, weakness.top) to min(height, weakness.bottom + below)
        } else {
            val bottom = lines.maxOf { it.bottom }
            val span = bottom - top
            if (span < height / 5) return null // not a whole card in view
            max(0, bottom - (span * 0.16f).roundToInt()) to min(height, bottom + (span * 0.04f).roundToInt())
        }
        val onCard = lines.filter { it.top <= stripBottom }
        val left = max(0, onCard.minOf { it.left } - width / 40)
        val right = min(width, onCard.maxOf { it.right } + width / 40)
        if (right - left < 50 || stripBottom - stripTop < 20) return null
        return Strip(left, stripTop, right, stripBottom)
    }

    /**
     * The [strip] (in upright frame coordinates) of the camera [frame], enlarged, in the reused buffer.
     * Rotation, crop and scaling are one drawing step, so no rotated copy of the whole frame is made.
     */
    private fun cropAndEnlarge(frame: Bitmap, rotation: Int, strip: Strip): Crop {
        val w = strip.right - strip.left
        val h = strip.bottom - strip.top
        val scale = min(3f, 2000f / w).coerceAtLeast(1f)
        val matrix = Matrix().apply {
            postRotate(rotation.toFloat())
            val rotated = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()).also { mapRect(it) }
            postTranslate(-rotated.left - strip.left, -rotated.top - strip.top)
            postScale(scale, scale)
        }
        val bitmap = reuse(cropBuffer, (w * scale).roundToInt(), (h * scale).roundToInt()).also { cropBuffer = it }
        Canvas(bitmap).drawBitmap(frame, matrix, scalePaint)
        return Crop(bitmap, scale)
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
