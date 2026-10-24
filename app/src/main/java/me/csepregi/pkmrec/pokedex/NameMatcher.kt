package me.csepregi.pkmrec.pokedex

import java.text.Normalizer

/**
 * Suggests Pokémon names for what the user typed, forgiving typos ("Eve" → Eevee)
 * and Hungarian phonetic spelling ("Pikacsu" → Pikachu). A number ("133", "#133") finds that Pokédex entry.
 */
object NameMatcher {

    /** Hungarian spellings of English sounds, longest first so "dzs" is not read as "d" + "zs". */
    private val HUNGARIAN_SPELLING = listOf("dzs" to "j", "cs" to "ch", "sz" to "s")

    private val NUMBER_QUERY = Regex("""^#?\s*0*(\d{1,4})$""")

    /** Below this length only names starting with the query are suggested; fuzzy matching would match almost anything. */
    private const val MIN_FUZZY_LENGTH = 3

    fun suggest(query: String, names: List<PokemonName>, limit: Int = 5): List<PokemonName> {
        val text = query.trim()
        NUMBER_QUERY.find(text)?.let { match ->
            val number = match.groupValues[1].toInt()
            return names.filter { it.number == number }
        }
        val variants = queryVariants(text)
        if (variants.isEmpty()) return emptyList()
        return names
            .mapNotNull { name ->
                val key = normalize(name.name)
                variants.mapNotNull { score(it, key) }.minOrNull()?.let { name to it }
            }
            .sortedWith(compareBy({ it.second }, { it.first.number }))
            .take(limit)
            .map { it.first }
    }

    /** The query as typed and, if different, read with Hungarian spelling. */
    internal fun queryVariants(query: String): List<String> {
        var hungarian = query.lowercase()
        HUNGARIAN_SPELLING.forEach { (from, to) -> hungarian = hungarian.replace(from, to) }
        return listOf(normalize(query), normalize(hungarian)).filter { it.isNotEmpty() }.distinct()
    }

    /** Lower case letters and digits only, without accents: "Flabébé" → "flabebe", "Nidoran ♀" → "nidoranf". */
    internal fun normalize(text: String): String {
        val symbols = text.lowercase().replace("♀", "f").replace("♂", "m")
        return Normalizer.normalize(symbols, Normalizer.Form.NFD).filter { it in 'a'..'z' || it in '0'..'9' }
    }

    /**
     * Lower is better, null = no match. Exact match 0, name starting with the query just above 1,
     * typos 2 and above (distance to the whole name, or to its beginning plus a half step).
     */
    internal fun score(query: String, name: String): Double? {
        if (name == query) return 0.0
        if (name.startsWith(query)) return 1.0 + (name.length - query.length) * 0.001
        if (query.length < MIN_FUZZY_LENGTH) return null
        val full = distance(query, name)
        val prefix = if (name.length > query.length) distance(query, name.take(query.length)) + 0.5 else Double.MAX_VALUE
        val best = minOf(full.toDouble(), prefix)
        val allowed = 1 + query.length / 3
        if (best > allowed) return null
        return 1.0 + best + full * 0.01
    }

    /** Edit distance with adjacent transpositions (optimal string alignment). */
    internal fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
