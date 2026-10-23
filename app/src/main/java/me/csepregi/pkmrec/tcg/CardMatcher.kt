package me.csepregi.pkmrec.tcg

import me.csepregi.pkmrec.recognition.CardReading
import kotlin.math.max

/** Outcome of matching a camera reading against TCG Collector search results. */
sealed interface MatchResult {
    data class Single(val card: TcgCard) : MatchResult

    /** Several plausible cards, best first; the user picks one. */
    data class Ambiguous(val candidates: List<TcgCard>) : MatchResult
    data object NotFound : MatchResult
}

/**
 * Picks the scanned card among search results:
 * English (International) region only → same number → same set code (if read) →
 * closest name (OCR names are often slightly wrong, e.g. "ArbolivaK" for "Arboliva ex").
 */
object CardMatcher {

    /** A name score must lead the runner-up by this much to decide on its own. */
    private const val NAME_MARGIN = 0.2

    fun match(reading: CardReading, results: List<TcgCard>): MatchResult {
        val english = results.filter { it.regionId == null || it.regionId == TcgIds.REGION_INTERNATIONAL }
        val sameNumber = english.filter { numbersEqual(it.number, reading) }
        if (sameNumber.isEmpty()) return MatchResult.NotFound

        val code = reading.setCode
        val byCode = if (code != null) sameNumber.filter { it.expansionCode.equals(code, ignoreCase = true) } else emptyList()
        // If the code was misread and matches nothing, fall back to all cards with that number.
        val pool = byCode.ifEmpty { sameNumber }
        if (pool.size == 1) return MatchResult.Single(pool.first())

        val name = reading.name
        val ranked = if (name.isNullOrBlank()) pool else pool.sortedByDescending { nameSimilarity(name, it.name) }
        if (!name.isNullOrBlank()) {
            val best = nameSimilarity(name, ranked[0].name)
            val second = nameSimilarity(name, ranked[1].name)
            if (best >= 0.6 && best - second >= NAME_MARGIN) return MatchResult.Single(ranked[0])
        }
        return MatchResult.Ambiguous(ranked)
    }

    /** "015/128" vs card "015/128"; leading zeros are ignored ("57/111" = "057/111"). */
    internal fun numbersEqual(cardNumber: String, reading: CardReading): Boolean {
        val expected = if (reading.total != null) "${reading.number}/${reading.total}" else reading.number
        return normalizeNumber(cardNumber) == normalizeNumber(expected)
    }

    private fun normalizeNumber(text: String): String =
        text.uppercase().replace(" ", "").split("/").joinToString("/") { part ->
            val prefix = part.takeWhile { it.isLetter() }
            val digits = part.drop(prefix.length).trimStart('0').ifEmpty { "0" }
            prefix + digits
        }

    /**
     * 0..1 similarity (normalised edit distance) of an OCR name and a card name,
     * ignoring case, spaces and punctuation: "ArbolivaK" vs "Arboliva ex" → 0.8.
     */
    fun nameSimilarity(ocr: String, cardName: String): Double {
        val a = simplify(ocr)
        val b = simplify(cardName)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / max(a.length, b.length)
    }

    private fun simplify(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
