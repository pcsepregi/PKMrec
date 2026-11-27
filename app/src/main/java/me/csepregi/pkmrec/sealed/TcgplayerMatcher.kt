package me.csepregi.pkmrec.sealed

/**
 * Pairs a Sealed catalog product (named as on Cardmarket) with its TCGplayer product. The two
 * shops name products differently ("Ascended Heroes: Pikachu Mini Tin" against "Ascended Heroes
 * Mini Tin [Pikachu & Tepig]"), so words are compared, not names: a TCGplayer product scores by
 * how many of the Cardmarket words it has, minus a little for each extra word in its name.
 */
object TcgplayerMatcher {

    /** Words that say nothing about which product it is. */
    private val IGNORED = setOf("pokemon", "tcg", "the", "and", "of", "a")

    /** Different words for the same thing. */
    private val SAME = mapOf("single" to "1")

    /** Cost of a word in the TCGplayer name that is not in the Cardmarket name. */
    private const val EXTRA_WORD = 0.04

    /** The best product is taken without asking only this far ahead of the next one. */
    private const val LEAD = 0.035

    private val INTERNATIONAL = setOf("international", "version")

    class Scored(val product: TcgplayerProduct, val score: Double, val complete: Boolean)

    /** Lower case words, without accents and plural s: "Team Rocket's Tins" → team, rocket, tin. */
    internal fun words(text: String): List<String> = SealedMatcher.words(text)
        .filter { it !in IGNORED }
        .map { SAME[it] ?: it }
        .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

    private val cache = HashMap<TcgplayerProduct, Pair<Set<String>, List<String>>>()

    private fun productWords(p: TcgplayerProduct): Pair<Set<String>, List<String>> = synchronized(cache) {
        cache.getOrPut(p) {
            val name = words(p.name)
            (name + words(p.set)).toSet() to name
        }
    }

    internal fun score(name: String, set: String, product: TcgplayerProduct): Scored {
        val wanted = words("$name $set").distinct()
        val (all, nameWords) = productWords(product)
        val found = wanted.count { it in all }
        val extra = nameWords.count { it !in wanted }
        val coverage = if (wanted.isEmpty()) 0.0 else found.toDouble() / wanted.size
        return Scored(product, coverage - EXTRA_WORD * extra, complete = wanted.isNotEmpty() && found == wanted.size)
    }

    /** The best candidates first, for picking by hand. */
    fun rank(name: String, set: String, products: List<TcgplayerProduct>, limit: Int = 15): List<TcgplayerProduct> =
        ranked(name, set, products).filter { it.score > 0 }.take(limit).map { it.product }

    private fun ranked(name: String, set: String, products: List<TcgplayerProduct>): List<Scored> =
        products.map { score(name, set, it) }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.product.name.length })

    /**
     * The product to pair without asking, or null when it is not clear: every Cardmarket word has
     * to be in it, and it has to lead the next candidate. Where TCGplayer has an "(International
     * Version)" of a product (sold in Europe, e.g. the Team Rocket tins) that version is taken.
     */
    fun autoMatch(name: String, set: String, products: List<TcgplayerProduct>): TcgplayerProduct? {
        val candidates = ranked(name, set, products).take(30)
        val international = candidates.filter { INTERNATIONAL.all { w -> w in productWords(it.product).second } }
        val replaced = international.mapNotNull { intl ->
            val plain = productWords(intl.product).second.filter { it !in INTERNATIONAL }.toSet()
            candidates.firstOrNull { it !== intl && productWords(it.product).second.toSet() == plain && it.complete == intl.complete }
        }.toSet()
        val list = candidates.filter { it !in replaced }
        val best = list.firstOrNull()?.takeIf { it.complete } ?: return null
        val next = list.getOrNull(1)
        if (next != null && best.score - next.score < LEAD) return null
        return best.product
    }
}
