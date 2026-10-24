package me.csepregi.pkmrec.recognition

/**
 * Tells from one frame's OCR lines whether a Pokémon card is in view, for the debug mode's
 * slow recognition log. A card counts as in view when at least [MIN_MARKERS] different
 * kinds of card text were read: other text in view (a web page, a box) rarely has two.
 */
object CardPresence {

    enum class Marker(val label: String) {
        HP("HP"),
        WEAKNESS("weakness/resistance/retreat"),
        ABILITY("ability"),
        NUMBER("number"),
        NAME("name"),
    }

    const val MIN_MARKERS = 2

    // "HP 120", "120 HP", "HP120", also "120HP" glued.
    private val HP = Regex("""(?i)(\bHP\s*\d{2,3}\b)|(\b\d{2,3}\s*HP\b)""")
    // Also as OCR often misreads them ("TEtrat", "weakness2"), like the analyzer's weakness line.
    private val WEAKNESS = Regex("""(?i)weak|resist|retreat|etreat|etrat""")
    private val ABILITY = Regex("""(?i)\b(ability|pok[eé]-?(power|body))\b""")

    // "123/193", "TG05/TG30", a misread "O15/128"; with at least two real digits, and not part of a web address.
    private val NUMBER = Regex("""(?<![\w/])[A-Za-z]{0,2}[0-9OoQDIlSBZGT]{1,3}\s*/\s*[A-Za-z]{0,2}[0-9OoQDIlSBZGT]{1,3}(?![\w/])""")

    fun markers(lines: List<OcrLine>, names: PokemonNameFinder? = null): Set<Marker> {
        val found = mutableSetOf<Marker>()
        for (line in lines) {
            val text = line.text
            if (HP.containsMatchIn(text)) found += Marker.HP
            if (WEAKNESS.containsMatchIn(text)) found += Marker.WEAKNESS
            if (ABILITY.containsMatchIn(text)) found += Marker.ABILITY
            if (NUMBER.findAll(text).any { m -> m.value.count { it.isDigit() } >= 2 }) found += Marker.NUMBER
            if (Marker.NAME !in found && names != null && !names.isEmpty && names.containsPokemonName(text)) {
                found += Marker.NAME
            }
        }
        return found
    }

    fun isCardInView(markers: Set<Marker>): Boolean = markers.size >= MIN_MARKERS
}
