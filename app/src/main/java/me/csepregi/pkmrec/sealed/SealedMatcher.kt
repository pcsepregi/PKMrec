package me.csepregi.pkmrec.sealed

import me.csepregi.pkmrec.pokedex.NameMatcher
import java.text.Normalizer

/**
 * Suggests sealed products for what the user typed: every typed word has to match a word of the
 * product name (start of the word, or with a small typo from 4 letters on), in any order.
 * "pitch etb", "mini tin ascended" and "kanghaskan box" all find what they should.
 */
object SealedMatcher {

    /** Common short names, expanded before matching. */
    private val ABBREVIATIONS = mapOf(
        "etb" to listOf("elite", "trainer", "box"),
        "upc" to listOf("ultra", "premium", "collection"),
        "spc" to listOf("super", "premium", "collection"),
    )

    private const val MIN_FUZZY_LENGTH = 4

    private val COMBINING_MARKS = Regex("""\p{Mn}+""")

    fun suggest(query: String, products: List<SealedProduct>, limit: Int = 15): List<SealedProduct> {
        val typed = queryWords(query)
        if (typed.isEmpty()) return emptyList()
        return products
            .mapNotNull { product -> score(typed, words(product.name))?.let { product to it } }
            .sortedWith(compareBy({ it.second }, { it.first.name.length }, { it.first.name }))
            .take(limit)
            .map { it.first }
    }

    /** Whether every typed word matches a word of [text], by the same rules; an empty query matches everything. */
    fun matches(query: String, text: String): Boolean {
        val typed = queryWords(query)
        return typed.isEmpty() || score(typed, words(text)) != null
    }

    internal fun queryWords(query: String): List<String> =
        words(query).flatMap { ABBREVIATIONS[it] ?: listOf(it) }.distinct()

    /** Lower case words without accents: "Pokémon Day 2026 Collection" → pokemon, day, 2026, collection. */
    internal fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace("'", "")
            .replace("’", "")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    /** Lower is better, null = some typed word matches nothing. Longer names cost a little more. */
    internal fun score(query: List<String>, name: List<String>): Double? {
        var total = 0.0
        for (word in query) {
            total += name.mapNotNull { wordScore(word, it) }.minOrNull() ?: return null
        }
        return total + (name.size - query.size).coerceAtLeast(0) * 0.02
    }

    private fun wordScore(typed: String, word: String): Double? {
        if (typed == word) return 0.0
        if (word.startsWith(typed)) return 0.3
        if (typed.length < MIN_FUZZY_LENGTH) return null
        val distance = minOf(
            NameMatcher.distance(typed, word),
            if (word.length > typed.length) NameMatcher.distance(typed, word.take(typed.length)) else Int.MAX_VALUE,
        )
        return if (distance <= 1 + typed.length / 5) 1.0 + distance else null
    }
}
