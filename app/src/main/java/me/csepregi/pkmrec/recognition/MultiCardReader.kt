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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads a photo of several cards laid out on a table (Tömeges eladás) and finds the cards on it
 * with [MultiCardLayout].
 *
 * Card numbers are tiny on such a photo (about 20 px on a 4096 px photo), too small for one OCR
 * pass over the whole photo, so the photo is also read in overlapping pieces enlarged twice.
 */
object MultiCardReader {

    /** Larger photos are scaled down to this before reading; phone photos are about 4000 px. */
    private const val MAX_PHOTO_SIDE = 4096

    /** Pieces are enlarged this much, up to this bitmap size. */
    private const val PIECE_SCALE = 2f
    private const val MAX_PIECE_BITMAP = 2048

    /** What was read from one photo: the cards and every line of text (for the debug report). */
    data class PhotoReading(val cards: List<MultiCardLayout.Found>, val lines: List<PhotoLine>)

    suspend fun read(context: Context, photo: Uri, names: PokemonNameFinder?): PhotoReading = withContext(Dispatchers.IO) {
        val bitmap = decode(context, photo)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val lines = mutableListOf<PhotoLine>()
            lines += recognize(recognizer, bitmap, 0, 0, 1f)
            for (piece in MultiCardLayout.pieces(bitmap.width, bitmap.height).map { Rect(it.left, it.top, it.right, it.bottom) }) {
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
            val distinct = MultiCardLayout.distinctLines(lines)
            PhotoReading(MultiCardLayout.cards(distinct, names), distinct)
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
}
