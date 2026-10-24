package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnedByNameTest {

    private fun card(id: Int, name: String, owned: Int, regionId: Int = TcgIds.REGION_INTERNATIONAL) =
        TcgCard(id, name, "Set", "SET", "001/100", path = "/cards/$id/x", imageUrl = null, regionId = regionId, variants = listOf(CardVariant(1, "Holo", owned, 0)))

    @Test
    fun `only owned English cards with the name count`() {
        val cards = listOf(
            card(1, "Philippe", owned = 0),
            card(2, "Philippe", owned = 2),
            card(3, "Philippe", owned = 1, regionId = TcgIds.REGION_INTERNATIONAL + 1),
            card(4, "Professor's Research", owned = 3),
        )
        assertEquals(MatchResult.Single(cards[1]), CardLookup.ownedByName("Philippe", cards))
    }

    @Test
    fun `several owned prints are left to the user, best name first`() {
        val cards = listOf(card(1, "Cynthia's Garchomp ex", owned = 1), card(2, "Garchomp", owned = 1), card(3, "Gabite", owned = 1))
        assertEquals(MatchResult.Ambiguous(listOf(cards[1], cards[0])), CardLookup.ownedByName("Garchomp", cards))
    }

    @Test
    fun `nothing owned is not found`() {
        assertEquals(MatchResult.NotFound, CardLookup.ownedByName("Zekrom", listOf(card(1, "Zekrom", owned = 0))))
    }
}
