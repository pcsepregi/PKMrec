package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameInSetFallbackTest {

    private val museum = CardReading("MEP", "", null, name = "Pikachu at the Museum")

    @Test
    fun `the same code and name without any number decide after enough frames`() {
        val fallback = NameInSetFallback(votes = 5)
        repeat(4) { assertNull(fallback.offer(null, museum)) }
        assertEquals(museum, fallback.offer(null, museum))
    }

    @Test
    fun `a number read in the window wins`() {
        val fallback = NameInSetFallback(window = 15, votes = 5)
        fallback.offer(CardReading("MEP", "500", null), null)
        repeat(10) { assertNull(fallback.offer(null, museum)) }
    }

    @Test
    fun `lines from a promo card's bottom strip give code and name`() {
        val lines = listOf(
            OcrLine("Pikachu at the Museum", 100, 140, 120, 600),
            OcrLine("Thunderbolt", 600, 640, 160, 400),
            OcrLine("MEPEN", 1280, 1305, 490, 520),
        )
        assertEquals(museum, CardTextParser.codeAndName(lines, PokemonNameFinder(listOf("Pikachu"))))
    }

    @Test
    fun `promo number without EN`() {
        assertEquals("SVP 209", CardTextParser.parse(listOf(OcrLine("D SVP 209", 0, 20)), 20)?.display)
        assertEquals("SVP 209", CardTextParser.parse(listOf(OcrLine("0 SVPm 209", 0, 20)), 20)?.display)
    }
}
