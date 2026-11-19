package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Test

class MyCardsTest {

    private fun card(id: Int, english: Int, other: Int = 0, region: Int? = TcgIds.REGION_INTERNATIONAL) = TcgCard(
        id = id, name = "Pikachu", expansionName = "Set $id", expansionCode = null, number = "$id",
        rarity = null, path = "/cards/$id/x", imageUrl = null, regionId = region,
        variants = listOf(CardVariant(1, "Normal", english, other), CardVariant(2, "Reverse Holo", 0, 0)),
    )

    @Test
    fun `keeps owned English cards in page order`() {
        val page = listOf(card(1, 0), card(2, 1), card(3, 0, other = 2), card(4, 3), card(5, 1, region = 2), card(2, 1))
        assertEquals(listOf(2, 4), MyCards.pick(page).map { it.id })
    }
}
