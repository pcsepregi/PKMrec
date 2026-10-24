package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiCardReaderTest {

    private val names = PokemonNameFinder(listOf("Pikachu", "Charmander", "Bulbasaur"))

    /** A line [h] px tall; with h = 20 a card is about 1000 x 716 px. */
    private fun line(text: String, left: Int, top: Int, width: Int = 200, h: Int = 20) =
        OcrLine(text, top, top + h, left, left + width)

    /** One card whose number line starts at ([x], [bottom] - 20). */
    private fun card(name: String, number: String, x: Int, bottom: Int) = listOf(
        line(name, x + 40, bottom - 960, h = 34),
        line("HP 60", x + 520, bottom - 958),
        line("Thunder Shock", x + 60, bottom - 400),
        line("weakness resistance retreat", x + 20, bottom - 80, width = 600),
        line(number, x, bottom - 20, width = 220),
    )

    @Test
    fun `three cards in two rows are three readings with their own names`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100) +
            card("Charmander", "G MEW EN 004/165", 900, 1100) +
            card("Bulbasaur", "H SVI EN 001/198", 100, 2200)
        val cards = MultiCardReader.cards(lines.shuffled(java.util.Random(1)), names)
        assertEquals(
            listOf("PAL 063/193 Pikachu", "MEW 004/165 Charmander", "SVI 001/198 Bulbasaur"),
            cards.map { "${it.reading.display} ${it.reading.name}" },
        )
    }

    @Test
    fun `the same card twice is two readings`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100) + card("Pikachu", "G PAL EN 063/193", 900, 1100)
        assertEquals(2, MultiCardReader.cards(lines, names).size)
    }

    @Test
    fun `a number read as two lines is one card`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100) + line("063/193", 160, 1081)
        assertEquals(1, MultiCardReader.cards(lines, names).size)
    }

    @Test
    fun `no number, no card`() {
        assertEquals(0, MultiCardReader.cards(listOf(line("Pikachu", 0, 0), line("HP 60", 300, 0)), names).size)
    }
}
