package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases from the 2.4.0 slow recognition log (OnePlus, 2026-11-21): OCR lines as the log printed them. */
class SlowScanCasesTest {

    private val names = PokemonNameFinder(listOf("Bronzong", "Bronzor", "Nidorino", "Nidoran♀", "Tapu Lele", "Crobat", "Golurk", "Heliolisk"))

    private val sets = KnownSets(
        codes = setOf("LOR", "ASR", "TWM", "MEG", "PAL", "EVO", "GRI", "JU"),
        totals = setOf(108, 64, 145, 196, 189, 193),
    )

    private val logLine = Regex("""^([* ])\[(\d+)-(\d+) x(-?\d+)-(\d+)] (.*)$""")

    /** Lines in the log's format: `[top-bottom xleft-right] text`, `*` for the enlarged strip. */
    private fun frame(text: String): List<OcrLine> = text.trimIndent().lines().filter { it.isNotBlank() }.map { raw ->
        val m = logLine.matchEntire(if (raw.startsWith("*") || raw.startsWith(" ")) raw else " $raw")!!.groupValues
        OcrLine(m[6], m[2].toInt(), m[3].toInt(), m[4].toInt(), m[5].toInt(), fromStrip = m[1] == "*")
    }

    @Test
    fun `Bronzong TG11 of TG30 is confirmed without waiting for a set code`() {
        val s = ReadingStabilizer()
        val tg = CardReading(null, "TG11", "TG30")
        assertNull(s.offer(tg))
        assertNull(s.offer(null))
        assertNull(s.offer(tg))
        assertEquals("TG11/TG30", s.offer(tg)!!.display)
    }

    @Test
    fun `gallery number with the slash read as A`() {
        val lines = frame(
            """
            *[1374-1397 x507-611] ETGILIG30
            *[1377-1396 x501-625] ETG11AG30
            """,
        )
        val r = CardTextParser.parse(lines, 1920, names, sets)!!
        assertEquals("TG11", r.number)
        assertEquals("TG30", r.total)
    }

    @Test
    fun `often and When are no set codes`() {
        val bronzong = frame(
            """
            [298-375 x916-1335] Metal Transfer
            [358-440 x541-1444] As often as you like during your turn, Y
            [409-485 x595-1456] EneEY from of your Pokemen to E
            [616-692 x937-1380] Zen Headbutt
            """,
        )
        assertNull(CardTextParser.codeAndName(bronzong, names, sets))
        val tapuLele = frame(
            """
            [584-615 x443-682] BASIC Tapu Lele
            [1281-1303 x824-980] Wh en your Pokéfon GXis
            """,
        )
        assertNull(CardTextParser.codeAndName(tapuLele, names, sets))
    }

    @Test
    fun `an Energy sentence in the attack text does not make a Pokemon an Energy card`() {
        val lines = frame(
            """
            [731-761 x482-692] STAGE BronZONg
            [1047-1076 x502-793] AbIIEY Metal Transfer
            [1096-1122 x513-897] Energy from of yourRokémon to another
            [1177-1210 x635-800] Zen Headbutt
            [1300-1321 x623-755] resistance -30
            [1391-1414 x621-877] ©2022 Pokémon / Nintendo/ Creatures /GAME FREAK
            """,
        )
        assertEquals("BronZONg", CardTextParser.findName(lines, names))
    }

    @Test
    fun `SWSH promo with a zero read as O`() {
        val lines = frame(
            """
            [805-856 x488-765] weakness x
            *[1007-1064 x612-870] D SwSHO98
            [1016-1064 x649-872] SWSHO98
            """,
        )
        val r = CardTextParser.parse(lines, 1920, names, sets)!!
        assertEquals("SWSH", r.setCode)
        assertEquals("098", r.number)
    }

    @Test
    fun `regulation mark glued to a promo code is dropped`() {
        val svp = CardTextParser.parse(frame("[1300-1320 x400-600] GSVP EN 167"), 1920, null, sets)!!
        assertEquals("SVP", svp.setCode)
        assertEquals('G', svp.regulationMark)
        assertEquals("167", svp.number)
        val mep = CardTextParser.parse(frame("[1300-1320 x400-600] JMEP EN 063"), 1920, null, sets)!!
        assertEquals("MEP", mep.setCode)
        assertEquals("063", mep.number)
    }

    @Test
    fun `words split around en are no promo codes`() {
        assertNull(CardTextParser.parse(frame("[1300-1320 x400-600] Golurk URK EN 350"), 1920, null, sets))
        assertNull(CardTextParser.parse(frame("[1300-1320 x400-600] KART EN O"), 1920, null, sets))
    }

    @Test
    fun `known codes still work, with a stray mark glued to them`() {
        val r = CardTextParser.parse(frame("[1300-1320 x400-600] GPAL EN 123/193"), 1920, null, sets)!!
        assertEquals("PAL", r.setCode)
        assertEquals('G', r.regulationMark)
    }

    @Test
    fun `slash-less number splits only into a known total`() {
        val lines = frame(
            """
            [1236-1253 x477-595] weakness 2
            *[1315-1333 x948-986] 44108
            """,
        )
        assertEquals("44/108", CardTextParser.parse(lines, 1920, names, sets)!!.display)
        val misread = frame(
            """
            [1236-1253 x477-595] weakness 2
            *[1318-1332 x940-983] 44104
            """,
        )
        assertNull(CardTextParser.parse(misread, 1920, names, sets))
    }

    @Test
    fun `copyright year before 2023 means no set code is waited for`() {
        val lines = frame(
            """
            [558-603 x616-768] Nidorino
            [1236-1253 x477-595] weakness 2
            [1314-1330 x694-773] G2016 Pokémon
            [1315-1332 x949-991] 44/108
            """,
        )
        val r = CardTextParser.parse(lines, 1920, names, sets)!!
        assertEquals(2016, r.year)
        val s = ReadingStabilizer()
        assertNull(s.offer(r))
        assertNull(s.offer(r))
        assertEquals("44/108", s.offer(r)!!.display)
    }

    @Test
    fun `old card strip reaches the number below the flavour text`() {
        // Nidoran♀ 57/64 (Jungle): the number is under the flavour text, well below the weakness line.
        val lines = frame(
            """
            [477-493 x530-597] Pokémon
            [491-527 x499-670] Nidoran 9
            [869-898 x530-958] Poison Pin Pokémon. Length: I' 4", Weight: 15 Ibs.
            [902-930 x558-891] Fury Swipes Flip3 coins.This attack
            [1115-1132 x916-1010] retreat cost
            [1116-1135 x701-785] resistance
            [1116-1137 x485-565] weakness
            [1174-1204 x503-1007] Although small, its venomous barbs make thís Pokémon
            [1193-1224 x499-957] dangerous. The female has smaller horns, LV. I3 #29
            [1220-1239 x991-1027] 57/64
            [1223-1245 x611-951] CI95, 96, 98 Nintendo, Creatures, GAMEFREAK. CI99 Ward
            """,
        )
        val strip = BottomStrip.find(lines, 1080, 1920)!!
        assertTrue("strip ends at ${strip.bottom}", strip.bottom >= 1239 + 5)
        assertTrue(strip.left <= 991 && strip.right >= 1027)
    }

    @Test
    fun `GX card strip without copyright line is a fifth of the card width tall`() {
        val lines = frame(
            """
            [579-619 x852-915] 170
            [584-615 x443-682] BASIC Tapu Lele
            [1146-1190 x461-993] Heal, al damage from 2of your Benched Pokemon,
            [1249-1264 x619-683] resistance
            [1254-1269 x423-493] weakness
            [1305-1327 x650-835] Knocked Out, your ep onettake
            """,
        )
        val strip = BottomStrip.find(lines, 1080, 1920)!!
        assertTrue("strip ends at ${strip.bottom}", strip.bottom >= 1269 + 100)
    }

    @Test
    fun `name only offer after six seconds with the same name`() {
        val offer = NameOnlyOffer()
        var t = 0L
        assertNull(offer.offer(t, "Nidorino"))
        repeat(10) { t += 600; offer.offer(t, if (it % 3 == 0) null else "Nidorino") }
        assertNotNull(offer.offer(t, "Nidorino"))
        assertEquals("Nidorino", offer.offer(t, null))
        // Card taken away: the old frames age out.
        assertNull(offer.offer(t + 11_000, null))
    }
}
