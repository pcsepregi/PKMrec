package me.csepregi.pkmrec.recognition

import me.csepregi.pkmrec.recognition.CardPresence.Marker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPresenceTest {

    private val names = PokemonNameFinder(listOf("Manaphy", "Pikachu", "Kyurem"))

    private fun lines(vararg text: String) = text.mapIndexed { i, t -> OcrLine(t, i * 10, i * 10 + 8) }

    @Test
    fun `whole card shows several markers`() {
        val found = CardPresence.markers(
            lines("BASIC Manaphy HP 70", "Ability Blessings of the Deep", "weakness resistance retreat", "025/073"),
            names,
        )
        assertEquals(setOf(Marker.HP, Marker.WEAKNESS, Marker.ABILITY, Marker.NUMBER, Marker.NAME), found)
        assertTrue(CardPresence.isCardInView(found))
    }

    @Test
    fun `misread markers still count`() {
        assertEquals(setOf(Marker.HP), CardPresence.markers(lines("120HP")))
        assertEquals(setOf(Marker.WEAKNESS), CardPresence.markers(lines("TEtrat")))
        assertEquals(setOf(Marker.NUMBER), CardPresence.markers(lines("G PAL EN O15/193")))
        assertEquals(setOf(Marker.NAME), CardPresence.markers(lines("Pikachv"), names))
    }

    @Test
    fun `other text is not a card`() {
        val found = CardPresence.markers(lines("www.tcgcollector.com/cards/38896", "Price $0.36", "Kyurem"), names)
        assertEquals(setOf(Marker.NAME), found)
        assertFalse(CardPresence.isCardInView(found))
        assertFalse(CardPresence.isCardInView(CardPresence.markers(emptyList(), names)))
    }

    @Test
    fun `corner close-up with number and weakness line is in view`() {
        assertTrue(CardPresence.isCardInView(CardPresence.markers(lines("weakness", "123/193"))))
    }
}
