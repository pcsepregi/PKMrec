package me.csepregi.pkmrec.cardmarket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EurHufTest {

    @Test
    fun `huf rate and date are read from the ecb daily xml`() {
        val xml = """<gesmes:Envelope><Cube><Cube time='2026-10-23'><Cube currency='USD' rate='1.0812'/>""" +
            """<Cube currency='HUF' rate='366.73'/><Cube currency='PLN' rate='4.25'/></Cube></Cube></gesmes:Envelope>"""
        assertEquals(366.73 to "2026-10-23", EurHuf.parse(xml))
        assertNull(EurHuf.parse("<Cube currency='USD' rate='1.08'/>"))
    }

    @Test
    fun `forints are rounded and grouped by thousands`() {
        assertEquals("2 949 Ft", EurHuf.formatHuf(8.04, 366.73))
        assertEquals("37 Ft", EurHuf.formatHuf(0.10, 366.73))
        assertEquals("366 730 Ft", EurHuf.formatHuf(1000.0, 366.73))
    }
}
