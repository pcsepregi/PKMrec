package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MultiCardReaderTest {

    private val names = PokemonNameFinder(listOf("Pikachu", "Charmander", "Bulbasaur", "Chandelure"))

    /** A level line [h] px tall starting at ([left], [top]); with h = 20 a card is about 1000 x 716 px. */
    private fun line(text: String, left: Int, top: Int, width: Int = 200, h: Int = 20) =
        PhotoLine(text, left + width / 2f, top + h / 2f, width.toFloat(), h.toFloat())

    /** One card whose number line starts at ([x], [bottom] - 20). */
    private fun card(name: String, number: String, x: Int, bottom: Int) = listOf(
        line(name, x + 40, bottom - 960, h = 34),
        line("HP 60", x + 520, bottom - 958),
        line("Thunder Shock", x + 60, bottom - 400),
        line("weakness resistance retreat", x + 20, bottom - 80, width = 600),
        line(number, x, bottom - 20, width = 220),
    )

    /** The lines turned by [degrees] around ([ox], [oy]), as on a card lying at an angle. */
    private fun List<PhotoLine>.turned(degrees: Double, ox: Float, oy: Float): List<PhotoLine> {
        val a = Math.toRadians(degrees)
        return map { l ->
            val x = ox + (l.cx - ox) * cos(a) - (l.cy - oy) * sin(a)
            val y = oy + (l.cx - ox) * sin(a) + (l.cy - oy) * cos(a)
            l.copy(cx = x.toFloat(), cy = y.toFloat(), angle = l.angle + a.toFloat())
        }
    }

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
    fun `cards lying at different angles keep their own names`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100).turned(-30.0, 100f, 1100f) +
            card("Chandelure", "J PBL EN 115/084", 1100, 1300).turned(25.0, 1100f, 1300f)
        val cards = MultiCardReader.cards(lines, names)
        assertEquals(
            setOf("PAL 063/193 Pikachu", "PBL 115/084 Chandelure"),
            cards.map { "${it.reading.display} ${it.reading.name}" }.toSet(),
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

    @Test
    fun `a line read in two overlapping pieces is kept once, the longer reading`() {
        val whole = line("Team Rocket's Ariana", 100, 100, width = 400, h = 40)
        val cut = line("Rocket's Ariana", 200, 102, width = 300, h = 38)
        val number = line("224/182", 100, 900)
        assertEquals(listOf(whole, number), MultiCardReader.distinctLines(listOf(cut, whole, number)))
    }

    @Test
    fun `pieces overlap and cover the whole photo`() {
        val pieces = MultiCardReader.pieces(2304, 4096)
        assertEquals(15, pieces.size)
        assertEquals(2304, pieces.maxOf { it.right })
        assertEquals(4096, pieces.maxOf { it.bottom })
    }

    @Test
    fun `corner points give the line's slope and size`() {
        val l = PhotoLine.fromCorners("x", listOf(0f to 0f, 100f to 100f, 90f to 110f, -10f to 10f))!!
        assertEquals(45.0, Math.toDegrees(l.angle.toDouble()), 0.01)
        assertEquals(141.4f, l.width, 0.1f)
        assertEquals(14.1f, l.height, 0.1f)
    }
}
