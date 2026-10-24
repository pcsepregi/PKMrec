package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRareCardsTest {

    private fun card(id: Int, rarity: String?, region: Int? = TcgIds.REGION_INTERNATIONAL) = TcgCard(
        id = id, name = "Pikachu", expansionName = "Set $id", expansionCode = null, number = "$id",
        rarity = rarity, path = "/cards/$id/x", imageUrl = null, regionId = region, variants = emptyList(),
    )

    @Test
    fun `only common, uncommon and rare list cards get the section`() {
        assertTrue(LatestRareCards.shouldShow(listOf(card(1, "Common"))))
        assertTrue(LatestRareCards.shouldShow(listOf(card(1, "Rare"))))
        assertFalse(LatestRareCards.shouldShow(listOf(card(1, "Double Rare"))))
        assertFalse(LatestRareCards.shouldShow(listOf(card(1, "Rare Holo"))))
        assertFalse(LatestRareCards.shouldShow(emptyList()))
    }

    @Test
    fun `newest five full art or double rare cards in page order, older eras included`() {
        // Rarities in the order the Pikachu Pokédex page lists them (newest first).
        val page = listOf(
            card(1, "Pikachu Rare"), card(2, "Double Rare"), card(3, "Special Illustration Rare"),
            card(4, "Classic Collection"), card(5, null), card(6, "Common"), card(7, "Promo"),
            card(8, "Hyper Rare"), card(9, "Ultra Rare", region = 2), card(10, "Illustration Rare"),
            card(11, "Holo Rare V"), card(12, "Rare Ultra"),
        )
        assertEquals(listOf(2, 3, 8, 10, 11), LatestRareCards.pick(page).map { it.id })
    }
}
