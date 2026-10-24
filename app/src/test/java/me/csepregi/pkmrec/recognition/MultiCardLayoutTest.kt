package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class MultiCardLayoutTest {

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
        val cards = MultiCardLayout.cards(lines.shuffled(java.util.Random(1)), names)
        assertEquals(
            listOf("PAL 063/193 Pikachu", "MEW 004/165 Charmander", "SVI 001/198 Bulbasaur"),
            cards.map { "${it.reading.display} ${it.reading.name}" },
        )
    }

    @Test
    fun `cards lying at different angles keep their own names`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100).turned(-30.0, 100f, 1100f) +
            card("Chandelure", "J PBL EN 115/084", 1100, 1300).turned(25.0, 1100f, 1300f)
        val cards = MultiCardLayout.cards(lines, names)
        assertEquals(
            setOf("PAL 063/193 Pikachu", "PBL 115/084 Chandelure"),
            cards.map { "${it.reading.display} ${it.reading.name}" }.toSet(),
        )
    }

    @Test
    fun `the same card twice is two readings`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100) + card("Pikachu", "G PAL EN 063/193", 900, 1100)
        assertEquals(2, MultiCardLayout.cards(lines, names).size)
    }

    @Test
    fun `a number read as two lines is one card`() {
        val lines = card("Pikachu", "G PAL EN 063/193", 100, 1100) + line("063/193", 160, 1081)
        assertEquals(1, MultiCardLayout.cards(lines, names).size)
    }

    @Test
    fun `no number, no card`() {
        assertEquals(0, MultiCardLayout.cards(listOf(line("Pikachu", 0, 0), line("HP 60", 300, 0)), names).size)
    }

    @Test
    fun `a line read in two overlapping pieces is kept once, the longer reading`() {
        val whole = line("Team Rocket's Ariana", 100, 100, width = 400, h = 40)
        val cut = line("Rocket's Ariana", 200, 102, width = 300, h = 38)
        val number = line("224/182", 100, 900)
        assertEquals(listOf(whole, number), MultiCardLayout.distinctLines(listOf(cut, whole, number)))
    }

    @Test
    fun `pieces overlap and cover the whole photo`() {
        val pieces = MultiCardLayout.pieces(2304, 4096)
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

    /** OCR lines of Pali's five table photos (2.0.1 debug report): "x,y hH angle° text" per line. */
    private fun tablePhotos(file: String = "table-photos-2.0.1.txt"): List<List<PhotoLine>> {
        val text = javaClass.getResourceAsStream("/bulk-sell/$file")!!.bufferedReader().readText()
        val pattern = Regex("""^(\d+),(\d+) h(\d+) (-?\d+)° (.*)$""")
        val photos = mutableListOf<MutableList<PhotoLine>>()
        text.lines().forEach { l ->
            if (l.startsWith("==")) photos += mutableListOf<PhotoLine>()
            else pattern.matchEntire(l)?.destructured?.let { (x, y, h, a, t) ->
                // The report has no line widths; about half a line height per character.
                photos.last() += PhotoLine(t, x.toFloat(), y.toFloat(), t.length * h.toFloat() * 0.55f, h.toFloat(), Math.toRadians(a.toDouble()).toFloat())
            }
        }
        return photos
    }

    private val allNames = PokemonNameFinder(
        listOf("Shroodle", "Chikorita", "Tangrowth", "Mantine", "Miltank", "Meganium", "Garchomp", "Staraptor", "Chandelure", "Cetitan", "Zekrom", "Talonflame", "Delibird", "Terapagos", "Pikachu", "Throh", "Gabite"),
    )

    @Test
    fun `table photos - cards are found by number or by name`() {
        val found = tablePhotos().map { lines -> MultiCardLayout.cards(lines, allNames).map { "${it.reading.display}|${it.reading.name}" } }
        // Photo 1: Ariana by her slash-less number, Mega Chandelure ex by number, the rest by name.
        assertEquals(
            setOf("Rust Syndicate Grunt|Rust Syndicate Grunt", "Garchomp|Garchomp", "224/182|Team Rocket's Ariana", "Staraptor|Staraptor", "PBL 115/084|Chandelure"),
            found[0].toSet(),
        )
        // Photo 3: two copies of Philippe; "110/01" (total cut off) is not a number.
        assertEquals(2, found[2].count { it == "Philippe|Philippe" })
        assertTrue(found[2].none { it.startsWith("110/01") })
        // Photo 5: Zekrom with "BASIC" glued to it; "Overhead Throw" is not Throh.
        assertTrue("Zekrom|Zekrom" in found[4])
        assertTrue(found[4].none { it.contains("Throh") })
        assertTrue("168/132|Fighting Gong" in found[4])
    }

    @Test
    fun `trainer labels are recognised when misread or cut off`() {
        listOf("SuPPogtes", "upportec", "Dorter", "Iterm", "Pokémon Tool7", "SUpporter").forEach {
            assertTrue(it, MultiCardLayout.isTrainerLabel(it))
        }
        listOf("Philippe", "Counter Gain", "TRAINER", "your turn").forEach {
            assertFalse(it, MultiCardLayout.isTrainerLabel(it))
        }
    }

    @Test
    fun `Mega Evolution photo - set codes glued to other letters, unreadable number, titles of neighbouring cards`() {
        val found = MultiCardLayout.cards(tablePhotos("table-photo-mega-2.0.2.txt")[0], allNames, setOf("MEG", "PBL", "ASC", "SVI", "30C"))
            .map { "${it.reading.display}|${it.reading.name}" }
        assertEquals(
            setOf(
                "Premium Power Pro|Premium Power Pro",
                "OMEG 091/132|Shroodle",
                "Chikorita (MEG)|Chikorita",
                "Mantine|Mantine",
                "MEG 007/132|Tangrowth",
                "MEG 106/132|Miltank",
                "106/132|Miltank",
                "MEG 010/132|Meganium",
            ),
            found.toSet(),
        )
        assertEquals(8, found.size)
    }

    @Test
    fun `set codes are found inside OCR noise`() {
        val codes = setOf("MEG", "PBL", "ASC")
        assertEquals("MEG", MultiCardLayout.codeIn("IMEGB 007", codes))
        assertEquals("MEG", MultiCardLayout.codeIn("0MEG D O10", codes))
        assertEquals("ASC", MultiCardLayout.codeIn("wASCM 259", codes))
        assertEquals(null, MultiCardLayout.codeIn("Illus. REND", codes))
    }
}
