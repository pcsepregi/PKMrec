package me.csepregi.pkmrec.diagnostics

import me.csepregi.pkmrec.recognition.CardPresence.Marker
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.recognition.SlowScanTracker.Outcome
import me.csepregi.pkmrec.recognition.SlowScanTracker.SlowScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowScanFormatTest {

    private fun scan(n: Int) = SlowScan(
        durationMs = 7_250,
        outcome = Outcome.RECOGNIZED,
        result = CardReading("PAL", "015", "193"),
        readings = mapOf("PAL 016/193" to 1, "PAL 015/193" to 3),
        lastMarkers = setOf(Marker.HP, Marker.NUMBER),
        lastFrame = "PKMrec 0.12.0 OCR\nreading: -\n [10-20 x0-50] frame $n\n",
    )

    @Test
    fun `entry has header, markers, readings and the frame`() {
        val lines = SlowScanFormat.entry("2026-10-24 14:00:00", scan(1)).lines()
        assertEquals("### 2026-10-24 14:00:00 | 7.3 s | recognized PAL 015/193", lines[0])
        assertEquals("markers: HP, number", lines[1])
        assertEquals("readings: PAL 015/193 x3, PAL 016/193 x1", lines[2])
        assertEquals("PKMrec 0.12.0 OCR", lines[3])
        assertEquals(" [10-20 x0-50] frame 1", lines[5])
        assertEquals("", lines[6])
    }

    @Test
    fun `only the newest entries are kept`() {
        val log = (1..60).joinToString("") { SlowScanFormat.entry("2026-10-24 14:00:00", scan(it)) }
        assertEquals(60, SlowScanFormat.countEntries(log))
        val kept = SlowScanFormat.keepLast(log, 50)
        assertEquals(50, SlowScanFormat.countEntries(kept))
        assertTrue(kept.startsWith("### "))
        assertTrue(kept.contains("frame 11\n"))
        assertTrue(!kept.contains("frame 10\n"))
        assertEquals(log, SlowScanFormat.keepLast(log, 60))
    }
}
