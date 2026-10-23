package me.csepregi.pkmrec.recognition

/**
 * Accepts a reading only after the same set code + number was read in [required]
 * consecutive frames that contained a reading. A few frames without any reading
 * (motion blur, glare) do not reset the count; [maxMisses] of them in a row do.
 */
class ReadingStabilizer(
    private val required: Int = 3,
    private val maxMisses: Int = 6,
) {
    private var lastKey: String? = null
    private var count = 0
    private var misses = 0
    private var bestName: String? = null

    /** The reading currently being confirmed (for live display), or null. */
    var current: CardReading? = null
        private set

    /** Feeds one frame's reading; returns the confirmed reading once it is stable, otherwise null. */
    fun offer(reading: CardReading?): CardReading? {
        if (reading == null) {
            misses++
            if (misses >= maxMisses) reset()
            return null
        }
        misses = 0
        if (reading.key == lastKey) {
            count++
        } else {
            lastKey = reading.key
            count = 1
            bestName = null
        }
        if (reading.name != null) bestName = reading.name
        current = reading.copy(name = bestName)
        return if (count >= required) current else null
    }

    fun reset() {
        lastKey = null
        count = 0
        misses = 0
        bestName = null
        current = null
    }
}
