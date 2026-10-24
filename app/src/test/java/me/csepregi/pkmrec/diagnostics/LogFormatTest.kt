package me.csepregi.pkmrec.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class LogFormatTest {

    @Test
    fun `entry has time, place, message and the exception with its cause`() {
        val e = IOException("lookup failed", UnknownHostException("www.tcgcollector.com"))
        val entry = LogFormat.entry("2026-10-24 10:00:00", "lookup", "reading PAL 015/193", e)
        val lines = entry.lines()
        assertEquals("2026-10-24 10:00:00 [lookup] reading PAL 015/193", lines[0])
        assertEquals("  java.io.IOException: lookup failed", lines[1])
        assertTrue(lines.any { it == "  Caused by: java.net.UnknownHostException: www.tcgcollector.com" })
        assertTrue(entry.endsWith("\n\n"))
    }

    @Test
    fun `entries are counted and trimming keeps whole newest entries`() {
        val log = (1..10).joinToString("") { LogFormat.entry("2026-10-24 10:00:%02d".format(it), "test", "entry $it", null) }
        assertEquals(10, LogFormat.countEntries(log))
        assertEquals(0, LogFormat.countEntries(""))

        val trimmed = LogFormat.trim(log, log.length / 2)
        assertTrue(trimmed.length <= log.length / 2)
        assertTrue(trimmed.startsWith("2026-10-24 10:00:"))
        assertTrue(trimmed.contains("entry 10"))
        assertTrue(!trimmed.contains("entry 1\n"))
        assertEquals(log, LogFormat.trim(log, log.length))
    }
}
