package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingStabilizerTest {

    private val pal = CardReading("PAL", "123", "193")
    private val obf = CardReading("OBF", "001", "197")

    @Test
    fun `confirms after three agreeing frames`() {
        val s = ReadingStabilizer(required = 3)
        assertNull(s.offer(pal))
        assertNull(s.offer(pal))
        assertEquals(pal.key, s.offer(pal)!!.key)
    }

    @Test
    fun `a different reading restarts the count`() {
        val s = ReadingStabilizer(required = 3)
        s.offer(pal); s.offer(pal)
        assertNull(s.offer(obf))
        assertNull(s.offer(obf))
        assertNotNull(s.offer(obf))
    }

    @Test
    fun `occasional empty frames do not reset, many do`() {
        val s = ReadingStabilizer(required = 3, maxMisses = 3)
        s.offer(pal); s.offer(null); s.offer(pal)
        assertNotNull(s.offer(pal))

        s.reset()
        s.offer(pal); s.offer(pal)
        repeat(3) { s.offer(null) }
        assertNull(s.offer(pal))
    }

    @Test
    fun `keeps the last read name`() {
        val s = ReadingStabilizer(required = 2)
        s.offer(pal.copy(name = "Pikachu"))
        assertEquals("Pikachu", s.offer(pal)!!.name)
    }
}
