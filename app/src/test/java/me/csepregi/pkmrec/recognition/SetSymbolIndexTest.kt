package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetSymbolIndexTest {

    private val index = SetSymbolIndex.parse(
        """
        base1		Base
        base4		Base Set 2
        sm35	SLG	Shining Legends
        swsh45	CPA	Champion's Path
        sm115	HIF	Hidden Fates
        sma	HIF	Shiny Vault
        swsh9	BRS	Brilliant Stars
        sv3pt5	MEW	151
        """.trimIndent(),
    )

    @Test
    fun `parses lines with and without set code`() {
        assertEquals(8, index.entries.size)
        assertNull(index.entries.first { it.id == "base1" }.code)
    }

    @Test
    fun `finds sets by code, by name and by a contained name`() {
        assertEquals("sm35", index.find("Shining Legends", "SLG")?.id)
        assertEquals("swsh45", index.find("Champion's Path", null)?.id)
        assertEquals("swsh9", index.find("Brilliant Stars", "brs")?.id)
        // Two sets share HIF: the name decides.
        assertEquals("sma", index.find("Shiny Vault", "HIF")?.id)
        assertEquals("sm115", index.find("Hidden Fates", "HIF")?.id)
        // "Base Set" is "Base", not "Base Set 2".
        assertEquals("base1", index.find("Base Set", null)?.id)
        assertEquals("sv3pt5", index.find("Scarlet & Violet—151", null)?.id)
        assertNull(index.find("Unknown Expansion", null))
    }
}
