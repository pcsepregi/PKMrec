package me.csepregi.pkmrec.recognition

/**
 * For cards whose number never becomes readable (a tiny promo number on a screen, e.g. MEP 500):
 * when recent frames keep showing the same set code and name but no number at all, the card is
 * looked up by its name in that set instead ([CardReading.isNameOnly] with the set code).
 *
 * Gives way to the number as soon as any frame in the window reads one.
 */
class NameInSetFallback(
    private val window: Int = 15,
    private val votes: Int = 5,
) {
    private val recent = ArrayDeque<Pair<CardReading?, CardReading?>>()

    /** Feeds one frame (its number reading, and its code and name when no number was read). */
    fun offer(reading: CardReading?, codeOnly: CardReading?): CardReading? {
        recent.addLast(reading to codeOnly)
        while (recent.size > window) recent.removeFirst()
        if (recent.any { it.first != null }) return null
        val best = recent.mapNotNull { it.second }
            .groupingBy { it.setCode to it.name }
            .eachCount()
            .maxByOrNull { it.value } ?: return null
        if (best.value < votes) return null
        val (code, name) = best.key
        return CardReading(code, "", null, name = name)
    }

    fun reset() = recent.clear()
}
