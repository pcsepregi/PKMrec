package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardTextParserTest {

    private val height = 1000

    private fun line(text: String, top: Int, h: Int = 20) = OcrLine(text, top, top + h)

    private fun parse(vararg lines: OcrLine) = CardTextParser.parse(lines.toList(), height)

    @Test
    fun `reads modern bottom strip with regulation mark and set code`() {
        val r = parse(line("G PAL EN 123/193", 940))!!
        assertEquals("PAL", r.setCode)
        assertEquals("123", r.number)
        assertEquals("193", r.total)
        assertEquals('G', r.regulationMark)
        assertEquals("PAL 123/193", r.display)
    }

    @Test
    fun `tolerates spaces around the slash and OCR letter confusions`() {
        val r = parse(line("J 30C EN O15 / 128", 950))!!
        assertEquals("30C", r.setCode)
        assertEquals("015", r.number)
        assertEquals("128", r.total)
    }

    @Test
    fun `reads secret rare numbers above the set total`() {
        val r = parse(line("H TEF EN 203/162", 950))!!
        assertEquals("203", r.number)
        assertEquals("162", r.total)
    }

    @Test
    fun `reads gallery numbers with prefixes`() {
        val r = parse(line("TG05/TG30", 950))!!
        assertEquals(null, r.setCode)
        assertEquals("TG05", r.number)
        assertEquals("TG30", r.total)
    }

    @Test
    fun `reads older cards without set code`() {
        val r = parse(line("Illus. 5ban Graphics", 930), line("057/111", 955))!!
        assertNull(r.setCode)
        assertEquals("057", r.number)
        assertEquals("111", r.total)
    }

    @Test
    fun `reads promos`() {
        val r = parse(line("SVP EN 045", 950))!!
        assertEquals("SVP", r.setCode)
        assertEquals("045", r.number)
        assertNull(r.total)
        assertEquals("SVP 045", r.display)

        val glued = parse(line("SWSH123", 950))!!
        assertEquals("SWSH", glued.setCode)
        assertEquals("123", glued.number)
    }

    @Test
    fun `finds set code on a neighbouring line at the same height`() {
        val r = parse(line("G PAL EN", 950), line("123/193", 952))!!
        assertEquals("PAL", r.setCode)
        assertEquals('G', r.regulationMark)
        assertEquals("123", r.number)
    }

    @Test
    fun `prefers the bottom of the card and reads the name from the top`() {
        val r = parse(
            line("Fuecoco ex", 60, h = 60),
            line("HP 210", 65, h = 50),
            line("Basic", 40, h = 15),
            line("Cheerful Flame 70x", 600),
            line("J 30C EN 015/128", 950),
        )!!
        assertEquals("Fuecoco ex", r.name)
        assertEquals("30C 015/128", r.display)
    }

    @Test
    fun `splits set code glued to the language marker`() {
        val r = parse(line("G PALEN 123/193", 950))!!
        assertEquals("PAL", r.setCode)
        assertEquals('G', r.regulationMark)

        val r2 = parse(line("J 30CEN", 948), line("015/128", 950))!!
        assertEquals("30C", r2.setCode)
    }

    @Test
    fun `finds set code in a separate block a bit higher or lower`() {
        val r = parse(
            line("Illus. 5ban Graphics", 905),
            line("PAL EN", 930),
            line("123/193", 952),
        )!!
        assertEquals("PAL", r.setCode)
        assertEquals("123", r.number)
    }

    @Test
    fun `finds set code after the number and ignores separator noise`() {
        val r = parse(line("123/193 · PAL·EN", 950))!!
        assertEquals("PAL", r.setCode)
    }

    @Test
    fun `name is found relative to the card, not the camera frame`() {
        // The card occupies only the middle of the frame: name at 30 %, bottom strip at 75 %.
        val r = parse(
            line("STAGE 1", 280, h = 15),
            line("Charmeleon HP100", 300, h = 50),
            line("Evolves from Charmander", 340, h = 12),
            line("Combustion 50", 500),
            line("H PAF EN 008/091", 750),
        )!!
        assertEquals("Charmeleon", r.name)
        assertEquals("PAF", r.setCode)
    }

    @Test
    fun `returns null when no number is visible`() {
        assertNull(parse(line("Fuecoco ex", 60), line("Singe", 500)))
    }

    @Test
    fun `key ignores the name`() {
        val a = CardReading("PAL", "123", "193", name = "A")
        val b = CardReading("PAL", "123", "193", name = "B")
        assertEquals(a.key, b.key)
    }
}
