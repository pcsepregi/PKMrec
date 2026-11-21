package me.csepregi.pkmrec.recognition

/**
 * For cards whose number never becomes readable (tiny, blurred or out of view) but whose Pokémon name
 * is: once the same name has been read in at least [minFrames] frames over [afterMs] without the card
 * being recognized, the camera screen offers to look the card up by that name only (a picker of all
 * cards with that name). Frames older than [windowMs] are forgotten, so a card moved away drops out.
 * Times are milliseconds from any monotonic clock.
 */
class NameOnlyOffer(
    private val afterMs: Long = 6_000,
    private val minFrames: Int = 4,
    private val windowMs: Long = 10_000,
) {
    private val seen = ArrayDeque<Pair<Long, String>>()

    /** Feeds one frame's Pokémon name (null if none was read); returns the name to offer, or null. */
    fun offer(now: Long, name: String?): String? {
        if (name != null) seen.addLast(now to name)
        while (seen.isNotEmpty() && now - seen.first().first > windowMs) seen.removeFirst()
        val (best, frames) = seen.groupBy { it.second }.maxByOrNull { it.value.size } ?: return null
        if (frames.size < minFrames) return null
        return best.takeIf { now - frames.first().first >= afterMs }
    }

    fun reset() = seen.clear()
}
