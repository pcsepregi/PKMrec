package me.csepregi.pkmrec.recognition

/**
 * Limits how often camera frames are read, to save battery: OCR runs back to back otherwise.
 *
 * At most one frame per [activeMs] while card text is seen, and one per [idleMs] while nothing
 * that looks like a card number was read (camera pointed away, card not yet in view). The
 * stabilizers vote over frames, so a few readings a second are enough.
 *
 * [tryStart] runs on the analysis thread, [finished] on the main thread.
 */
class FramePacer(
    private val activeMs: Long = 250,
    private val idleMs: Long = 600,
) {
    @Volatile private var lastStart = Long.MIN_VALUE / 2
    @Volatile private var idle = false

    /** True when a frame arriving at [now] (ms) should be read; marks it as started. */
    fun tryStart(now: Long): Boolean {
        val gap = if (idle) idleMs else activeMs
        if (now - lastStart < gap) return false
        lastStart = now
        return true
    }

    /** Result of the last read frame: [cardText] when it gave a reading or number-like text. */
    fun finished(cardText: Boolean) {
        idle = !cardText
    }
}
