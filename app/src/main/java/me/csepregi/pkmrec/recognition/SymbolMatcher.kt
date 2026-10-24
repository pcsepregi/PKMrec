package me.csepregi.pkmrec.recognition

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Compares the set symbol printed next to a card's number with the symbols of the candidate sets.
 *
 * The camera crop around the number is scaled so the number text is [NUMBER_HEIGHT] pixels tall; each
 * symbol is then slid over it at a few sizes relative to the number, and the best normalised
 * cross-correlation is its score. Symbols are drawn over white and over black and the absolute
 * correlation is used, so dark symbols on light cards and light ones on full-art cards both match.
 *
 * Only the few candidates of one search are compared, which is what keeps this small and fast.
 */
object SymbolMatcher {

    /** Height of the number text in the crop given to [score]. */
    const val NUMBER_HEIGHT = 20

    /** Symbol heights tried, relative to the number text height. */
    private val SYMBOL_SIZES = listOf(0.8f, 0.95f, 1.1f, 1.25f, 1.4f)

    /** A decision needs at least this score… */
    const val MIN_SCORE = 0.5f

    /** …and this lead over the next set. */
    const val MIN_MARGIN = 0.1f

    /** 0..1: how well [symbol] (any size, with transparency) appears anywhere in [region]. */
    fun score(region: GrayImage, symbol: GrayImage): Float {
        val trimmed = symbol.trimmed()
        val integral = Integral(region)
        var best = 0f
        for (size in SYMBOL_SIZES) {
            val h = (NUMBER_HEIGHT * size).roundToInt()
            if (h > region.height) continue
            val scaled = trimmed.scaledToHeight(h)
            if (scaled.width > region.width) continue
            for (background in floatArrayOf(1f, 0f)) {
                best = max(best, bestCorrelation(region, integral, scaled.over(background)))
            }
        }
        return best
    }

    /** Scores of all [symbols], best first. */
    fun <K> rank(region: GrayImage, symbols: Map<K, GrayImage>): List<Pair<K, Float>> =
        symbols.map { (key, symbol) -> key to score(region, symbol) }.sortedByDescending { it.second }

    /** The clear winner of [ranked] (best first), or null when the symbols are too alike or none fits. */
    fun <K> decide(ranked: List<Pair<K, Float>>): K? {
        val best = ranked.firstOrNull() ?: return null
        val second = ranked.getOrNull(1)?.second ?: 0f
        return if (best.second >= MIN_SCORE && best.second - second >= MIN_MARGIN) best.first else null
    }

    private fun bestCorrelation(region: GrayImage, integral: Integral, template: GrayImage): Float {
        val tw = template.width
        val th = template.height
        val n = tw * th
        val mean = template.pixels.average().toFloat()
        val centered = FloatArray(n) { template.pixels[it] - mean }
        val templateNorm = sqrt(centered.fold(0f) { acc, v -> acc + v * v })
        if (templateNorm < 1e-3f) return 0f
        var best = 0f
        for (y in 0..region.height - th) {
            for (x in 0..region.width - tw) {
                val sum = integral.sum(x, y, tw, th)
                val sumSquares = integral.sumSquares(x, y, tw, th)
                val variance = sumSquares - sum * sum / n
                if (variance < 1e-4f * n) continue
                var dot = 0f
                for (ty in 0 until th) {
                    val row = (y + ty) * region.width + x
                    val trow = ty * tw
                    for (tx in 0 until tw) dot += region.pixels[row + tx] * centered[trow + tx]
                }
                best = max(best, abs(dot) / (sqrt(variance) * templateNorm))
            }
        }
        return best
    }

    /** Summed-area tables of the pixels and their squares, for the window mean and variance. */
    private class Integral(image: GrayImage) {
        private val w = image.width + 1
        private val sums = DoubleArray(w * (image.height + 1))
        private val squares = DoubleArray(w * (image.height + 1))

        init {
            for (y in 0 until image.height) {
                var row = 0.0
                var rowSquares = 0.0
                for (x in 0 until image.width) {
                    val v = image.pixels[y * image.width + x].toDouble()
                    row += v
                    rowSquares += v * v
                    sums[(y + 1) * w + x + 1] = sums[y * w + x + 1] + row
                    squares[(y + 1) * w + x + 1] = squares[y * w + x + 1] + rowSquares
                }
            }
        }

        fun sum(x: Int, y: Int, width: Int, height: Int): Float = window(sums, x, y, width, height)
        fun sumSquares(x: Int, y: Int, width: Int, height: Int): Float = window(squares, x, y, width, height)

        private fun window(table: DoubleArray, x: Int, y: Int, width: Int, height: Int): Float =
            (table[(y + height) * w + x + width] - table[y * w + x + width] - table[(y + height) * w + x] + table[y * w + x]).toFloat()
    }
}
