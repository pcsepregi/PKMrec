package me.csepregi.pkmrec.recognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A line of text read from a photo, in photo pixels: its centre, its size along ([width]) and
 * across ([height]) the text, and the text's slope ([angle], radians, 0 = level).
 * Cards on a table lie at any angle, so a level bounding box is not enough.
 */
data class PhotoLine(
    val text: String,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angle: Float = 0f,
) {
    /** The line as seen from a frame turned by [frameAngle] around ([ox], [oy]): level lines for a card at that angle. */
    fun inFrame(ox: Float, oy: Float, frameAngle: Float): OcrLine {
        val c = cos(-frameAngle)
        val s = sin(-frameAngle)
        val x = ox + (cx - ox) * c - (cy - oy) * s
        val y = oy + (cx - ox) * s + (cy - oy) * c
        return OcrLine(
            text,
            (y - height / 2).roundToInt(),
            (y + height / 2).roundToInt(),
            (x - width / 2).roundToInt(),
            (x + width / 2).roundToInt(),
        )
    }

    companion object {
        /** From ML Kit's corner points (top left, top right, bottom right, bottom left, as the text reads). */
        fun fromCorners(text: String, corners: List<Pair<Float, Float>>): PhotoLine? {
            if (corners.size != 4) return null
            val (tl, tr, br, bl) = corners
            val width = (dist(tl, tr) + dist(bl, br)) / 2
            val height = (dist(tl, bl) + dist(tr, br)) / 2
            if (width <= 0f || height <= 0f) return null
            val angle = atan2((tr.second - tl.second) + (br.second - bl.second), (tr.first - tl.first) + (br.first - bl.first))
            return PhotoLine(text, corners.sumOf { it.first.toDouble() }.toFloat() / 4, corners.sumOf { it.second.toDouble() }.toFloat() / 4, width, height, angle)
        }

        private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)
    }
}

/**
 * Finds every card in a photo of several cards laid out on a table (Tömeges eladás).
 *
 * Card numbers are tiny on such a photo (about 20 px on a 4096 px photo), too small for one OCR
 * pass over the whole photo, so the photo is also read in overlapping pieces enlarged twice.
 * Every card number (`123/193`) marks a card. The card is estimated around its number from the
 * number's text size and slope, number at the bottom left as on cards from Sun & Moon on, and the
 * card's name and set code are looked for in that area.
 */
object MultiCardReader {

    /** Card height in number-line heights, and the card's width to height ratio. */
    private const val CARD_HEIGHTS = 50
    private const val CARD_ASPECT = 63f / 88f

    /** Larger photos are scaled down to this before reading; phone photos are about 4000 px. */
    private const val MAX_PHOTO_SIDE = 4096

    /** Pieces are a quarter of the photo's long side, overlap by a quarter and are enlarged up to this. */
    private const val PIECES_PER_SIDE = 4
    private const val MIN_PIECE = 640
    private const val PIECE_SCALE = 2f
    private const val MAX_PIECE_BITMAP = 2048

    /** Lines of one card are about as level as its number; other cards on the table lie at other angles. */
    private const val MAX_ANGLE_DIFFERENCE = (20 * PI / 180).toFloat()

    /** One card found on a photo. */
    data class Found(val reading: CardReading, val numberLine: PhotoLine)

    /** What was read from one photo: the cards and every line of text (for the debug report). */
    data class PhotoReading(val cards: List<Found>, val lines: List<PhotoLine>)

    suspend fun read(context: Context, photo: Uri, names: PokemonNameFinder?): PhotoReading = withContext(Dispatchers.IO) {
        val bitmap = decode(context, photo)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val lines = mutableListOf<PhotoLine>()
            lines += recognize(recognizer, bitmap, 0, 0, 1f)
            for (piece in pieces(bitmap.width, bitmap.height).map { Rect(it.left, it.top, it.right, it.bottom) }) {
                ensureActive()
                val scale = min(PIECE_SCALE, MAX_PIECE_BITMAP.toFloat() / max(piece.width(), piece.height()))
                val enlarged = Bitmap.createBitmap((piece.width() * scale).roundToInt(), (piece.height() * scale).roundToInt(), Bitmap.Config.ARGB_8888)
                try {
                    Canvas(enlarged).drawBitmap(bitmap, piece, Rect(0, 0, enlarged.width, enlarged.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    lines += recognize(recognizer, enlarged, piece.left, piece.top, scale)
                } finally {
                    enlarged.recycle()
                }
            }
            val distinct = distinctLines(lines)
            PhotoReading(cards(distinct, names), distinct)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    /** The photo upright (EXIF rotation applied), at most [MAX_PHOTO_SIDE] on its long side. */
    private fun decode(context: Context, photo: Uri): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, photo)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = max(info.size.width, info.size.height)
            if (side > MAX_PHOTO_SIDE) {
                val f = MAX_PHOTO_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * f).roundToInt(), (info.size.height * f).roundToInt())
            }
        }

    /** The lines ML Kit reads in [image], which shows the photo from ([left], [top]) enlarged [scale] times. */
    private fun recognize(recognizer: TextRecognizer, image: Bitmap, left: Int, top: Int, scale: Float): List<PhotoLine> {
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)))
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val corners = line.cornerPoints?.map { p: Point -> left + p.x / scale to top + p.y / scale }
                ?: line.boundingBox?.let { b ->
                    listOf(b.left to b.top, b.right to b.top, b.right to b.bottom, b.left to b.bottom)
                        .map { (x, y) -> left + x / scale to top + y / scale }
                }
                ?: return@mapNotNull null
            PhotoLine.fromCorners(line.text, corners)
        }
    }

    /** A part of the photo, in photo pixels (right and bottom exclusive). */
    internal data class Piece(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Overlapping pieces covering a [width] x [height] photo. */
    internal fun pieces(width: Int, height: Int): List<Piece> {
        val size = max(MIN_PIECE, max(width, height) / PIECES_PER_SIDE)
        val step = size * 3 / 4
        fun starts(length: Int): List<Int> {
            if (length <= size) return listOf(0)
            val count = (length - size + step - 1) / step + 1
            return (0 until count).map { i -> min(i * step, length - size) }
        }
        return starts(height).flatMap { y -> starts(width).map { x -> Piece(x, y, min(x + size, width), min(y + size, height)) } }
    }

    /**
     * The same text read in several pieces (or in a piece and the whole photo) is kept once: a line
     * lying inside a longer line of about the same size and slope is dropped.
     */
    internal fun distinctLines(lines: List<PhotoLine>): List<PhotoLine> {
        val kept = mutableListOf<PhotoLine>()
        lines.sortedByDescending { it.text.length }.forEach { line ->
            val duplicate = kept.any { k ->
                val sizeRatio = max(k.height, line.height) / min(k.height, line.height)
                if (sizeRatio > 1.6f || angleDifference(k.angle, line.angle) > MAX_ANGLE_DIFFERENCE) return@any false
                val inK = line.inFrame(k.cx, k.cy, k.angle)
                val cx = (inK.left + inK.right) / 2f
                val cy = (inK.top + inK.bottom) / 2f
                abs(cx - k.cx) <= k.width / 2 + line.height / 2 && abs(cy - k.cy) <= k.height / 2
            }
            if (!duplicate) kept += line
        }
        return kept
    }

    /** The cards in a photo's lines. */
    fun cards(lines: List<PhotoLine>, names: PokemonNameFinder?): List<Found> {
        val anchors = lines.mapNotNull { line ->
            val level = line.inFrame(line.cx, line.cy, line.angle)
            CardTextParser.parseWithLine(listOf(level), level.bottom, null)?.let { Found(it.reading, line) }
        }
        return distinct(anchors).map { anchor ->
            val number = anchor.numberLine
            // Every line seen level with the card, as if the card lay straight.
            val numberLine = number.inFrame(number.cx, number.cy, number.angle)
            val framed = lines.filter { it !== number && angleDifference(it.angle, number.angle) <= MAX_ANGLE_DIFFERENCE }
                .map { it.inFrame(number.cx, number.cy, number.angle) } + numberLine
            val area = cardArea(numberLine, framed)
            val parsed = CardTextParser.parseWithLine(area, numberLine.bottom, names)
            val reading = if (parsed != null && parsed.numberLine == numberLine) parsed.reading
            else anchor.reading.copy(name = CardTextParser.findName(area, names))
            Found(reading, number)
        }.sortedWith(compareBy<Found>({ rowOf(it.numberLine) }, { it.numberLine.cx }))
    }

    /** One anchor per number: number lines close together (split or read twice) are one card. */
    private fun distinct(anchors: List<Found>): List<Found> {
        val kept = mutableListOf<Found>()
        anchors.sortedWith(compareByDescending<Found> { it.reading.setCode != null }.thenByDescending { it.numberLine.text.length }).forEach { a ->
            val near = kept.any { k ->
                val h = max(a.numberLine.height, k.numberLine.height).coerceAtLeast(1f)
                hypot(a.numberLine.cx - k.numberLine.cx, a.numberLine.cy - k.numberLine.cy) < 6 * h
            }
            if (!near) kept += a
        }
        return kept
    }

    /** The lines inside the card estimated above and to the right of its number line (all level). */
    internal fun cardArea(numberLine: OcrLine, lines: List<OcrLine>): List<OcrLine> {
        val h = numberLine.height.coerceAtLeast(1)
        val height = CARD_HEIGHTS * h
        val width = (height * CARD_ASPECT).toInt()
        val left = numberLine.left - width / 10
        val right = numberLine.left + width
        val top = numberLine.bottom - height
        val bottom = numberLine.bottom + h
        return lines.filter { line ->
            val cx = (line.left + line.right) / 2
            val cy = (line.top + line.bottom) / 2
            cx in left..right && cy in top..bottom
        }
    }

    private fun angleDifference(a: Float, b: Float): Float {
        var d = abs(a - b) % (2 * PI).toFloat()
        if (d > PI) d = (2 * PI).toFloat() - d
        return d
    }

    /** Rows of half a card height: number lines at about the same height share one. */
    private fun rowOf(numberLine: PhotoLine): Int = (numberLine.cy / (numberLine.height.coerceAtLeast(1f) * CARD_HEIGHTS / 2)).toInt()
}
