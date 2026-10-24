package me.csepregi.pkmrec.recognition

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Finds every card in a photo of several cards laid out on a table (Tömeges eladás).
 *
 * The whole photo is read once; every card number (`123/193`) marks a card. The card is
 * estimated around its number from the number's text size, number at the bottom left as on
 * cards from Sun & Moon on, and the card's name and set code are looked for in that area.
 */
object MultiCardReader {

    /** Card height in number-line heights, and the card's width to height ratio. */
    private const val CARD_HEIGHTS = 50
    private const val CARD_ASPECT = 63f / 88f

    /** One card found on a photo. */
    data class Found(val reading: CardReading, val numberLine: OcrLine)

    /** All cards on one photo, in reading order (rows top to bottom, then left to right). */
    suspend fun read(context: Context, photo: Uri, names: PokemonNameFinder?): List<Found> = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, photo)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(image))
            val lines = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.top, box.bottom, box.left, box.right)
            }
            cards(lines, names)
        } finally {
            recognizer.close()
        }
    }

    /** The cards in a photo's OCR lines. */
    fun cards(lines: List<OcrLine>, names: PokemonNameFinder?): List<Found> {
        val anchors = lines.mapNotNull { line ->
            CardTextParser.parseWithLine(listOf(line), line.bottom, null)?.let { Found(it.reading, line) }
        }
        return distinct(anchors).map { anchor ->
            val area = cardArea(anchor.numberLine, lines)
            val parsed = CardTextParser.parseWithLine(area, anchor.numberLine.bottom, names)
            val reading = if (parsed != null && parsed.numberLine == anchor.numberLine) parsed.reading
            else anchor.reading.copy(name = CardTextParser.findName(area, names))
            Found(reading, anchor.numberLine)
        }.sortedWith(compareBy<Found>({ rowOf(it.numberLine) }, { it.numberLine.left }))
    }

    /** One anchor per number: number lines close together (split or read twice) are one card. */
    private fun distinct(anchors: List<Found>): List<Found> {
        val kept = mutableListOf<Found>()
        anchors.sortedByDescending { it.reading.setCode != null }.forEach { a ->
            val near = kept.any { k ->
                val h = max(a.numberLine.height, k.numberLine.height).coerceAtLeast(1)
                abs(a.numberLine.top - k.numberLine.top) < 2 * h && abs(a.numberLine.left - k.numberLine.left) < 6 * h
            }
            if (!near) kept += a
        }
        return kept
    }

    /** The lines inside the card estimated above and to the right of its number line. */
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

    /** Rows of half a card height: number lines at about the same height share one. */
    private fun rowOf(numberLine: OcrLine): Int = numberLine.top / (numberLine.height.coerceAtLeast(1) * CARD_HEIGHTS / 2)
}
