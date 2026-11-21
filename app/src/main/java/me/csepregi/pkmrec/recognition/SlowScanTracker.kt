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
        BY_NAME("search by name"),
    }

    /**
     * A finished slow attempt; [lastFrame] is the OCR report of the last frame with the card in view.
     * [frames] were read during the attempt, [framesWithReading] of them gave a number; [frameMs] is
     * the total time spent reading them (0 when not measured).
     */
    data class SlowScan(
        val durationMs: Long,
        val outcome: Outcome,
        val result: CardReading?,
        val readings: Map<String, Int>,
        val lastMarkers: Set<CardPresence.Marker>,
        val lastFrame: String,
        val frames: Int = 0,
        val framesWithReading: Int = 0,
        val frameMs: Long = 0,
    ) {
        /** Average time to read one frame, or null when not measured. */
        val averageFrameMs: Long? get() = if (frames > 0 && frameMs > 0) frameMs / frames else null
    }

    private var startedAt: Long? = null
    private var lastSeenAt = 0L
    private var lastMarkers: Set<CardPresence.Marker> = emptySet()
    private var lastFrame = ""
    private val readings = linkedMapOf<String, Int>()
    private var frames = 0
    private var framesWithReading = 0
    private var frameMs = 0L

    /** Milliseconds since the current attempt started, or null when no card has been in view. */
    fun elapsed(now: Long): Long? = startedAt?.let { now - it }

    /**
     * Feeds one frame. [frameReport] is only built for frames with the card in view.
     * Returns a slow attempt that ended because the card was out of view long enough, otherwise null.
     */
    fun offer(
        now: Long,
        markers: Set<CardPresence.Marker>,
        reading: CardReading?,
        readMs: Long = 0,
        frameReport: () -> String,
    ): SlowScan? {
        // Also after a pause without frames (e.g. the manual entry dialog was open).
        val ended = if (startedAt != null && now - lastSeenAt >= goneAfterMs) finish(lastSeenAt, Outcome.CARD_GONE, null) else null
        val inView = CardPresence.isCardInView(markers)
        if (inView) {
            if (startedAt == null) startedAt = now
            lastSeenAt = now
            lastMarkers = markers
            lastFrame = frameReport()
        }
        if (startedAt != null) {
            reading?.let { readings[it.display] = (readings[it.display] ?: 0) + 1 }
            frames++
            if (reading != null) framesWithReading++
            frameMs += readMs
        }
        return ended
    }

    /** Ends the current attempt (recognized, manual entry or scan stopped); returns it if it was slow. */
    fun finish(now: Long, outcome: Outcome, result: CardReading?): SlowScan? {
        val start = startedAt
        // The card left the view before: the attempt ended when it was last seen.
        val gone = start != null && now - lastSeenAt >= goneAfterMs
        val end = if (gone) lastSeenAt else now
        val scan = if (start != null && end - start > slowAfterMs) {
            SlowScan(
                durationMs = end - start,
                outcome = if (gone) Outcome.CARD_GONE else outcome,
                result = if (gone) null else result,
                readings = readings.toMap(),
                lastMarkers = lastMarkers,
                lastFrame = lastFrame,
                frames = frames,
                framesWithReading = framesWithReading,
                frameMs = frameMs,
            )
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
        frames = 0
        framesWithReading = 0
        frameMs = 0
    }
}
