package me.csepregi.pkmrec.recognition

/**
 * Combines readings from recent camera frames by voting per field, so glare that hides
 * the number in one frame and the set code in another still gives a complete result.
 *
 * - The number (with total) is confirmed once it won [numberVotes] votes in the window
 *   and at least twice as many as any other number (one misread digit cannot win).
 * - The set code is then the code most often read together with that number, if it got
 *   [codeVotes] votes; if that number was read [noCodeAfter] times without any code
 *   reaching that, the card is taken to have no set code (older cards).
 * - Frames without any reading also enter the window, so old votes age out when the
 *   card is moved away.
 */
class ReadingStabilizer(
    private val window: Int = 15,
    private val numberVotes: Int = 3,
    private val codeVotes: Int = 2,
    private val noCodeAfter: Int = 6,
) {
    private val recent = ArrayDeque<CardReading?>()

    /** Best current guess (for live display), or null. */
    var current: CardReading? = null
        private set

    /** Feeds one frame's reading; returns the confirmed reading once it is stable, otherwise null. */
    fun offer(reading: CardReading?): CardReading? {
        recent.addLast(reading)
        while (recent.size > window) recent.removeFirst()

        val readings = recent.filterNotNull()
        if (readings.isEmpty()) {
            current = null
            return null
        }

        // 1) number + total
        val byNumber = readings.groupBy { it.number to it.total }
        val (numberKey, sameNumber) = byNumber.maxBy { it.value.size }
        val runnerUp = byNumber.values.filter { it !== sameNumber }.maxOfOrNull { it.size } ?: 0

        // 2) set code read together with that number
        val codes = sameNumber.mapNotNull { it.setCode }.groupingBy { it }.eachCount()
        val bestCode = codes.maxByOrNull { it.value }
        val codeDecided = (bestCode != null && bestCode.value >= codeVotes) || sameNumber.size >= noCodeAfter
        val setCode = bestCode?.takeIf { it.value >= codeVotes }?.key ?: bestCode?.key

        // 3) regulation mark and name: most frequent values
        val mark = sameNumber.mapNotNull { it.regulationMark }.mostFrequent()
        val name = sameNumber.mapNotNull { it.name }.mostFrequent()

        val guess = CardReading(setCode, numberKey.first, numberKey.second, mark, name)
        current = guess

        // The winning number must clearly lead: at least twice the votes of any other number.
        val confirmed = sameNumber.size >= numberVotes && sameNumber.size >= 2 * runnerUp && codeDecided
        return if (confirmed) {
            // Older card without set code: do not report a code read only once by mistake.
            if (bestCode == null || bestCode.value < codeVotes) guess.copy(setCode = null) else guess
        } else {
            null
        }
    }

    fun reset() {
        recent.clear()
        current = null
    }

    private fun <T> List<T>.mostFrequent(): T? = groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}
