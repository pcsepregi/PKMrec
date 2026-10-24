package me.csepregi.pkmrec.recognition

import me.csepregi.pkmrec.recognition.CardPresence.Marker
import me.csepregi.pkmrec.recognition.SlowScanTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SlowScanTrackerTest {

    private val card = setOf(Marker.HP, Marker.NAME)
    private val noCard = setOf(Marker.NAME)
    private val reading = CardReading("PAL", "015", "193")

    /** Frames with the card in view at [times]; real frames come a few times a second. */
    private fun SlowScanTracker.cardAt(vararg times: Long) = times.forEach { offer(it, card, null) { "frame" } }

    @Test
    fun `fast recognition is not logged`() {
        val tracker = SlowScanTracker()
        tracker.offer(1_000, card, null) { "frame" }
        assertNull(tracker.finish(5_900, Outcome.RECOGNIZED, reading))
    }

    @Test
    fun `slow recognition is logged with the last card frame and readings`() {
        val tracker = SlowScanTracker()
        assertNull(tracker.offer(0, noCard, null) { error("not built without a card") })
        tracker.offer(1_000, card, null) { "frame 1" }
        tracker.offer(2_500, card, reading) { "frame 2" }
        tracker.offer(4_000, noCard, reading) { "no card" }
        tracker.offer(4_400, card, reading.copy(number = "016")) { "frame 3" }
        val scan = tracker.finish(6_200, Outcome.RECOGNIZED, reading)
        assertNotNull(scan)
        assertEquals(5_200, scan!!.durationMs)
        assertEquals(Outcome.RECOGNIZED, scan.outcome)
        assertEquals("frame 3", scan.lastFrame)
        assertEquals(mapOf("PAL 015/193" to 2, "PAL 016/193" to 1), scan.readings)
        assertNull(tracker.elapsed(8_000))
    }

    @Test
    fun `timer starts only when a card is in view`() {
        val tracker = SlowScanTracker()
        tracker.offer(0, noCard, null) { "" }
        assertNull(tracker.elapsed(0))
        tracker.offer(10_000, card, null) { "" }
        assertEquals(1_000L, tracker.elapsed(11_000))
        assertNull(tracker.finish(14_000, Outcome.RECOGNIZED, reading))
    }

    @Test
    fun `card out of view ends the attempt when it was last seen`() {
        val tracker = SlowScanTracker()
        tracker.cardAt(0, 1_500, 3_000, 4_500, 6_000)
        assertNull(tracker.offer(7_000, noCard, null) { "" })
        val scan = tracker.offer(8_000, noCard, null) { "" }
        assertEquals(Outcome.CARD_GONE, scan?.outcome)
        assertEquals(6_000L, scan?.durationMs)
        assertNull(tracker.elapsed(8_000))
    }

    @Test
    fun `a pause without frames also ends the attempt`() {
        val tracker = SlowScanTracker()
        tracker.cardAt(0, 1_500, 3_000, 4_500, 5_500)
        val scan = tracker.offer(9_000, card, null) { "new" }
        assertEquals(Outcome.CARD_GONE, scan?.outcome)
        assertEquals(5_500L, scan?.durationMs)
        assertEquals(0L, tracker.elapsed(9_000))
    }

    @Test
    fun `stopping long after the card left counts as card out of view`() {
        val tracker = SlowScanTracker()
        tracker.cardAt(0, 1_500, 3_000, 4_500, 5_200)
        val scan = tracker.finish(20_000, Outcome.STOPPED, null)
        assertEquals(Outcome.CARD_GONE, scan?.outcome)
        assertEquals(5_200L, scan?.durationMs)

        tracker.cardAt(30_000, 31_500, 33_000, 34_500, 35_500)
        assertEquals(Outcome.MANUAL, tracker.finish(36_000, Outcome.MANUAL, reading)?.outcome)
    }
}
