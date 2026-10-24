package me.csepregi.pkmrec.recognition

/**
 * Times recognition attempts for the debug mode: an attempt starts with the first frame in which
 * a card is in view (see [CardPresence]) and ends when the card is recognized, the card has been
 * out of view for [goneAfterMs], or the scan stops. Attempts longer than [slowAfterMs] are
 * returned as a [SlowScan] to be logged. Times are milliseconds from any monotonic clock.
 */
class SlowScanTracker(
    private val slowAfterMs: Long = 5_000,
    private val goneAfterMs: Long = 2_000,
) {
    enum class Outcome(val label: String) {
        RECOGNIZED("recognized"),
        CARD_GONE("card out of view"),
        STOPPED("scan stopped"),
        MANUAL("manual entry"),
    }

    /** A finished slow attempt; [lastFrame] is the OCR report of the last frame with the card in view. */
    data class SlowScan(
        val durationMs: Long,
        val outcome: Outcome,
        val result: CardReading?,
        val readings: Map<String, Int>,
        val lastMarkers: Set<CardPresence.Marker>,
        val lastFrame: String,
    )

    private var startedAt: Long? = null
    private var lastSeenAt = 0L
    private var lastMarkers: Set<CardPresence.Marker> = emptySet()
    private var lastFrame = ""
    private val readings = linkedMapOf<String, Int>()

    /** Milliseconds since the current attempt started, or null when no card has been in view. */
    fun elapsed(now: Long): Long? = startedAt?.let { now - it }

    /**
     * Feeds one frame. [frameReport] is only built for frames with the card in view.
     * Returns a slow attempt that ended because the card was out of view long enough, otherwise null.
     */
    fun offer(now: Long, markers: Set<CardPresence.Marker>, reading: CardReading?, frameReport: () -> String): SlowScan? {
        // Also after a pause without frames (e.g. the manual entry dialog was open).
        val ended = if (startedAt != null && now - lastSeenAt >= goneAfterMs) finish(lastSeenAt, Outcome.CARD_GONE, null) else null
        val inView = CardPresence.isCardInView(markers)
        if (inView) {
            if (startedAt == null) startedAt = now
            lastSeenAt = now
            lastMarkers = markers
            lastFrame = frameReport()
        }
        if (startedAt != null) reading?.let { readings[it.display] = (readings[it.display] ?: 0) + 1 }
        return ended
    }

    /** Ends the current attempt (recognized, manual entry or scan stopped); returns it if it was slow. */
    fun finish(now: Long, outcome: Outcome, result: CardReading?): SlowScan? {
        val start = startedAt
        // The card left the view before: the attempt ended when it was last seen.
        val gone = start != null && now - lastSeenAt >= goneAfterMs
        val end = if (gone) lastSeenAt else now
        val scan = if (start != null && end - start > slowAfterMs) {
            if (gone) {
                SlowScan(end - start, Outcome.CARD_GONE, null, readings.toMap(), lastMarkers, lastFrame)
            } else {
                SlowScan(end - start, outcome, result, readings.toMap(), lastMarkers, lastFrame)
            }
        } else {
            null
        }
        reset()
        return scan
    }

    fun reset() {
        startedAt = null
        lastSeenAt = 0L
        lastMarkers = emptySet()
        lastFrame = ""
        readings.clear()
    }
}
