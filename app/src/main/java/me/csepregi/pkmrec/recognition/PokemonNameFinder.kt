package me.csepregi.pkmrec.recognition

import me.csepregi.pkmrec.pokedex.NameMatcher

/**
 * Tells whether an OCR line contains a Pokémon's name, so the card name is not confused with
 * attack or ability names ("Blessings of the Deep", "Cablegram") printed near the top of the card.
 *
 * Matches one to three consecutive words ("Mr. Mime Jr."), ignoring case, accents and punctuation.
 * Names of 5 letters or more may have one OCR error ("ArbolivaK", "DracovishV"); shorter ones must match exactly.
 */
class PokemonNameFinder(names: Collection<String>) {

    private val original: Map<String, String> = names.associateBy { NameMatcher.normalize(it) }.filterKeys { it.isNotEmpty() }
    private val exact: Set<String> = original.keys
    private val byLength: Map<Int, List<String>> = exact.filter { it.length >= MIN_FUZZY_LENGTH }.groupBy { it.length }

    val isEmpty: Boolean get() = exact.isEmpty()

    fun containsPokemonName(text: String): Boolean {
        val words = text.split(Regex("\\s+")).map { NameMatcher.normalize(it) }.filter { it.isNotEmpty() }
        for (size in 1..3) {
            for (start in 0..words.size - size) {
                if (matches(words.subList(start, start + size).joinToString(""))) return true
            }
        }
        return false
    }

    private fun matches(key: String): Boolean = matching(key) != null

    /** The normalised name [key] is (allowing one OCR error in longer names), or null. */
    private fun matching(key: String): String? {
        if (key in exact) return key
        if (key.length < MIN_FUZZY_LENGTH) return null
        return (key.length - 1..key.length + 1).firstNotNullOfOrNull { length ->
            byLength[length].orEmpty().firstOrNull { NameMatcher.distance(key, it) <= 1 }
        }
    }

    /**
     * The Pokémon whose name a card's title line contains, also when OCR glued it to the stage
     * label, HP or "ex" ("CASIGNZekrom", "Garchomp330", "ChandelureeX"); null if none.
     */
    fun find(text: String): String? {
        val words = text.split(Regex("\\s+")).map { NameMatcher.normalize(it).filter { c -> c.isLetter() } }.filter { it.isNotEmpty() }
        for (size in 3 downTo 1) {
            for (start in 0..words.size - size) {
                val key = words.subList(start, start + size).joinToString("")
                // Only longer names may have an OCR error here: attack names such as "Throw" are just as tall.
                val found = withoutSuffix(key).firstNotNullOfOrNull { k -> if (k.length >= MIN_TITLE_FUZZY_LENGTH) matching(k) else k.takeIf { it in exact } }
                    ?: withoutSuffix(key).firstNotNullOfOrNull { k -> gluedAtEnd(k) }
                if (found != null) return original[found]
            }
        }
        return null
    }

    /** The word as read, and without a card-kind suffix ("chandelureex" → "chandelure"). */
    private fun withoutSuffix(key: String): List<String> =
        listOf(key) + SUFFIXES.filter { key.endsWith(it) && key.length - it.length >= MIN_FUZZY_LENGTH }.map { key.dropLast(it.length) }

    /** A name of 5+ letters at the end of a longer word: a stage label glued in front ("casignzekrom"). */
    private fun gluedAtEnd(key: String): String? =
        (key.length - 1 downTo MIN_FUZZY_LENGTH).firstNotNullOfOrNull { length -> key.takeLast(length).takeIf { it in exact } }

    private companion object {
        const val MIN_FUZZY_LENGTH = 5
        const val MIN_TITLE_FUZZY_LENGTH = 7
        val SUFFIXES = listOf("vstar", "vmax", "ex", "gx", "v")
    }
}
