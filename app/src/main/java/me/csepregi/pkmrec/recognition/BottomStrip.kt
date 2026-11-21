package me.csepregi.pkmrec.recognition

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** An area of the upright camera frame, in pixels. */
data class FrameArea(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Finds the card's bottom strip, where set code and number are printed, from the first OCR pass:
 * it is read again enlarged (see [CardTextAnalyzer]).
 *
 * Below the weakness / retreat line when that was read, otherwise the bottom ~16 % of the text area.
 * The weakness line keeps the strip on the card when there is other text below it (e.g. a price under
 * a card shown on a screen). How far below it the number is depends on the card's era: right below on
 * modern cards, under the flavour text (1999 cards) or the GX rule box (Sun & Moon) on older ones. So the
 * strip reaches down to the copyright line when that was read, otherwise a fifth of the text width.
 */
object BottomStrip {

    /** The short "weakness / resistance / retreat" label line just above the number (OCR: "TEtrat", "weakness2"). */
    val WEAKNESS_LINE = Regex("""weak|resist|retreat|etreat|etrat""", RegexOption.IGNORE_CASE)

    /** The copyright line at the very bottom of the card, next to or below the number. */
    private val COPYRIGHT_LINE = Regex("""nintendo|creatures|game\s*freak|[©]\s*(19|20)\d\d|\b[cCG]?(19|20)\d\d\s*pok""", RegexOption.IGNORE_CASE)

    fun find(lines: List<OcrLine>, width: Int, height: Int): FrameArea? {
        if (lines.size < 3) return null
        val top = lines.minOf { it.top }
        val weakness = lines.filter { it.text.length <= 30 && WEAKNESS_LINE.containsMatchIn(it.text) }.maxByOrNull { it.top }
        val (stripTop, stripBottom) = if (weakness != null && weakness.top - top >= height / 6) {
            // The text above may include more than the card (e.g. a web page on a screen), so it only limits the strip.
            val span = weakness.top - top
            val onCardWidth = lines.filter { it.top <= weakness.bottom }.let { above -> above.maxOf { it.right } - above.minOf { it.left } }
            val maxBelow = (span * 0.28f).roundToInt()
            val copyright = lines
                .filter { it.top > weakness.bottom && it.top - weakness.bottom <= maxBelow && COPYRIGHT_LINE.containsMatchIn(it.text) }
                .maxByOrNull { it.bottom }
            val below = if (copyright != null) {
                copyright.bottom - weakness.bottom + copyright.height.coerceAtLeast(10)
            } else {
                max(5 * weakness.height.coerceAtLeast(10), (onCardWidth * 0.2f).roundToInt())
            }
            max(0, weakness.top) to min(height, weakness.bottom + min(below, maxBelow))
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
        return FrameArea(left, stripTop, right, stripBottom)
    }
}
