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

    private val exact: Set<String> = names.map { NameMatcher.normalize(it) }.filter { it.isNotEmpty() }.toSet()
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

    private fun matches(key: String): Boolean {
        if (key in exact) return true
        if (key.length < MIN_FUZZY_LENGTH) return false
        return (key.length - 1..key.length + 1).any { length ->
            byLength[length].orEmpty().any { NameMatcher.distance(key, it) <= 1 }
        }
    }

    private companion object {
        const val MIN_FUZZY_LENGTH = 5
    }
}
