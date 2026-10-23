package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingStabilizerTest {

    private val pal = CardReading("PAL", "123", "193")
    private val palNoCode = CardReading(null, "123", "193")
    private val obf = CardReading("OBF", "001", "197")

    @Test
    fun `confirms after three agreeing frames`() {
        val s = ReadingStabilizer()
        assertNull(s.offer(pal))
        assertNull(s.offer(pal))
        assertEquals("PAL 123/193", s.offer(pal)!!.display)
    }

    @Test
    fun `combines frames where glare hid different fields`() {
        val s = ReadingStabilizer()
        assertNull(s.offer(palNoCode))   // code hidden by glare
        assertNull(s.offer(null))        // whole strip hidden
        assertNull(s.offer(pal))
        val confirmed = s.offer(palNoCode.copy(name = "Pikachu"))
        assertNull(confirmed)            // number has 3 votes, but the code only 1
        val done = s.offer(pal)!!
        assertEquals("PAL", done.setCode)
        assertEquals("Pikachu", done.name)
    }

    @Test
    fun `older cards without set code are confirmed after enough frames`() {
        val s = ReadingStabilizer(noCodeAfter = 6)
        repeat(5) { assertNull(s.offer(palNoCode)) }
        val r = s.offer(palNoCode)!!
        assertNull(r.setCode)
    }

    @Test
    fun `a single wrong code read does not stick on an old card`() {
        val s = ReadingStabilizer(noCodeAfter = 6)
        s.offer(palNoCode.copy(setCode = "XYZ"))
        repeat(4) { s.offer(palNoCode) }
        assertNull(s.offer(palNoCode)!!.setCode)
    }

    @Test
    fun `the majority number wins and old votes age out`() {
        val s = ReadingStabilizer(window = 6)
        repeat(3) { s.offer(obf) }
        s.reset()
        s.offer(pal); s.offer(obf); s.offer(pal)
        assertNotNull(s.offer(pal))
    }
}
