package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    @Test
    fun `reads the first frame at once`() {
        assertTrue(FramePacer().tryStart(0))
    }

    @Test
    fun `skips frames within the active gap while card text is seen`() {
        val p = FramePacer(activeMs = 250, idleMs = 600)
        assertTrue(p.tryStart(1_000))
        p.finished(cardText = true)
        assertFalse(p.tryStart(1_100))
        assertTrue(p.tryStart(1_250))
    }

    @Test
    fun `waits longer while no card text is seen`() {
        val p = FramePacer(activeMs = 250, idleMs = 600)
        assertTrue(p.tryStart(1_000))
        p.finished(cardText = false)
        assertFalse(p.tryStart(1_300))
        assertTrue(p.tryStart(1_600))
        p.finished(cardText = true)
        assertTrue(p.tryStart(1_850))
    }
}
