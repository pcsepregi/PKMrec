package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real TCG Collector pages saved from a logged-in browser (October 2026), anonymised with
 * `tools/anonymize-tcg-page.py`. If the site changes, save the pages again and update the expectations.
 */
class RealPagesTest {

    private fun page(name: String): String =
        requireNotNull(javaClass.getResource("/tcg/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `search list page for 025-165 has the english pikachu and three asian ones`() {
        val html = page("search-025-165-list.html")
        val state = AppStateExtractor.extract(html)!!
        assertEquals(TcgUser(1, "Test User", true), TcgUser.fromAppState(state))

        val cards = TcgSearchParser.parseListPage(html, state)
        assertEquals(listOf(42394, 72089, 85855, 41518), cards.map { it.id })
        assertEquals(listOf(42394), cards.filter { it.regionId == TcgIds.REGION_INTERNATIONAL }.map { it.id })

        val mew = cards.first()
        assertEquals("Pikachu", mew.name)
        assertEquals("Scarlet & Violet 151", mew.expansionName)
        assertEquals("MEW", mew.expansionCode)
        assertEquals("025/165", mew.number)
        assertEquals("Common", mew.rarity)
        assertEquals("/cards/42394/pikachu-scarlet-and-violet-151-025-165", mew.path)
        assertEquals(
            listOf("Normal", "Reverse Holo", "Confetti Holo", "Cosmos Reverse Holo", "Holiday Calendar 2025", "Pokémon Together"),
            mew.variants.map { it.name },
        )
        assertEquals(0, mew.variants.count { it.isOwned })
    }

    @Test
    fun `card page gives energy type, hp and the pokedex entry`() {
        val html = page("card-page-pikachu-mew-025.html")
        assertEquals(CardDetails(listOf("Lightning"), 60), CardPageParser.parseDetails(html))
        assertEquals(listOf(PokedexEntry(25, "pikachu", "/pokedex/25/pikachu/intl")), PokedexList.parsePokedexEntries(html))
    }

    @Test
    fun `pokedex page filtered to the list has the owned list card`() {
        val html = page("pokedex-pikachu-in-list.html")
        val state = AppStateExtractor.extract(html)!!
        val cards = TcgSearchParser.parseListPage(html, state)
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(35591, card.id)
        assertEquals("Surfing Pikachu V", card.name)
        assertEquals("Celebrations", card.expansionName)
        assertEquals("CEL", card.expansionCode)
        assertEquals("008/025", card.number)
        assertEquals(TcgIds.REGION_INTERNATIONAL, card.regionId)
        assertEquals(listOf(CardVariant(363, "Normal Holo", ownedEnglish = 1, ownedOtherLanguages = 0)), card.variants)
    }
}
