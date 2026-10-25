package me.csepregi.pkmrec.tcg

import me.csepregi.pkmrec.recognition.CardReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CardSearchTest {

    // Trimmed copy of a TCG Collector list-view search page (structure as on the live site).
    private val listHtml = """
        <html><body><main>
        <div class="card-list-item card-search-result-item" data-card-id="89541">
          <a href="/cards/89541/fuecoco-ex-30th-celebration-015-128" class="card-list-item-entry card-list-item-name-entry"> Fuecoco ex </a>
          <div class="card-list-item-entry card-list-item-expansion-entry">
            <a href="/sets/11820/30th-celebration" class="card-list-item-expansion-name"> 30th Celebration </a>
            <a href="/sets/11820/30th-celebration" class="card-list-item-expansion-code"> 30C </a>
          </div>
          <div class="card-list-item-entry card-list-item-number-entry"> 015/128 </div>
          <div class="card-list-item-entry card-list-item-rarity-entry"><img src="x.svg" alt="Double Rare" class="card-rarity-symbol"></div>
        </div>
        <div class="card-list-item card-search-result-item" data-card-id="59618">
          <a href="/cards/59618/beautifly" class="card-list-item-entry card-list-item-name-entry"> Beautifly </a>
          <div class="card-list-item-entry card-list-item-expansion-entry">
            <a class="card-list-item-expansion-name"> Brave Stars </a>
            <a class="card-list-item-expansion-code"> BS </a>
          </div>
          <div class="card-list-item-entry card-list-item-number-entry"> 015/128 </div>
        </div>
        </main></body></html>
    """.trimIndent()

    private val appState = AppStateExtractor.extract(
        """
        <script> window.tcgcollector = { appState: {
          "userDto": {"id": 1, "displayName": "T", "isPremiumEnabled": true},
          "cardIdToTcgRegionIdMap": {"89541": 1, "59618": 5},
          "cardIdToCardVariantTypeIdsMap": {"89541": [363, 2], "59618": [363]},
          "idToCardVariantTypeDtoMap": {"2": {"id": 2, "name": "Reverse Holo"}, "363": {"id": 363, "name": "Normal Holo"}},
          "cardIdToCardCollectionCardDtoMap": {
            "89541": {"cardId": 89541, "entries": [
              {"cardVariantTypeId": 363, "cardLanguageId": 1, "cardConditionId": 8, "cardCount": 1},
              {"cardVariantTypeId": 2, "cardLanguageId": 3, "cardConditionId": 8, "cardCount": 2}
            ]},
            "59618": {"cardId": 59618, "entries": []}
          }
        }, }; </script>
        """.trimIndent(),
    )

    private val imageHtml = """
        <a href="/cards/89541/fuecoco-ex-30th-celebration-015-128" class="card-image-grid-item-link">
          <img src="https://static.example/fuecoco.webp" class="card-image-grid-item-image">
        </a>
    """.trimIndent()

    private val cards = TcgSearchParser.parseListPage(listHtml, appState)

    @Test
    fun `parses cards with region, variants and collection`() {
        assertEquals(2, cards.size)
        val fuecoco = cards.first { it.id == 89541 }
        assertEquals("Fuecoco ex", fuecoco.name)
        assertEquals("30th Celebration", fuecoco.expansionName)
        assertEquals("30C", fuecoco.expansionCode)
        assertEquals("015/128", fuecoco.number)
        assertEquals("Double Rare", fuecoco.rarity)
        assertEquals(null, cards.first { it.id == 59618 }.rarity)
        assertEquals(1, fuecoco.regionId)
        assertEquals("https://www.tcgcollector.com/cards/89541/fuecoco-ex-30th-celebration-015-128", fuecoco.url)

        val holo = fuecoco.variants.first { it.name == "Normal Holo" }
        assertEquals(1, holo.ownedEnglish)
        assertTrue(holo.isOwned)
        val reverse = fuecoco.variants.first { it.name == "Reverse Holo" }
        assertEquals(0, reverse.ownedEnglish)
        assertEquals(2, reverse.ownedOtherLanguages)
    }

    @Test
    fun `parses image urls`() {
        assertEquals("https://static.example/fuecoco.webp", TcgSearchParser.parseImagePage(imageHtml)[89541])
    }

    @Test
    fun `matches by number, english region and set code`() {
        val result = CardMatcher.match(CardReading("30C", "015", "128"), cards)
        assertEquals(89541, (result as MatchResult.Single).card.id)
    }

    @Test
    fun `non-english cards are ignored even without set code`() {
        val result = CardMatcher.match(CardReading(null, "015", "128"), cards)
        assertEquals(89541, (result as MatchResult.Single).card.id)
    }

    @Test
    fun `misread set code falls back to number`() {
        val result = CardMatcher.match(CardReading("3OC", "15", "128"), cards)
        assertEquals(89541, (result as MatchResult.Single).card.id)
    }

    private fun card(id: Int, name: String, code: String, number: String) =
        TcgCard(id, name, "Set $code", code, number, path = "/cards/$id/x", imageUrl = null, regionId = 1, variants = emptyList())

    @Test
    fun `old cards without set code are decided by name or left to the user`() {
        val pool = listOf(
            card(1, "Buzzwole-GX", "30C", "57/111"),
            card(2, "Buzzwole-GX", "CIN", "57/111"),
            card(3, "Mienshao", "FFI", "57/111"),
            card(4, "Aron", "RR", "57/111"),
        )
        val byName = CardMatcher.match(CardReading(null, "57", "111", name = "Mienshao"), pool)
        assertEquals(3, (byName as MatchResult.Single).card.id)

        val twins = CardMatcher.match(CardReading(null, "57", "111", name = "BuzzwoleGX"), pool)
        assertTrue(twins is MatchResult.Ambiguous)
        assertEquals(setOf(1, 2), (twins as MatchResult.Ambiguous).candidates.take(2).map { it.id }.toSet())
    }

    @Test
    fun `ocr name errors still match`() {
        assertTrue(CardMatcher.nameSimilarity("ArbolivaK", "Arboliva ex") >= 0.6)
        assertTrue(CardMatcher.nameSimilarity("Arbolivaex", "Arboliva ex") == 1.0)
        assertTrue(CardMatcher.nameSimilarity("TeamRockets Wobufet", "Team Rocket's Wobbuffet") >= 0.8)
    }

    @Test
    fun `read names agree with longer card names but not with other cards`() {
        assertTrue(CardMatcher.namesAgree("Garchomp", "Cynthia's Garchomp ex"))
        assertTrue(CardMatcher.namesAgree("Pikachu at", "Pikachu at the Museum"))
        assertTrue(!CardMatcher.namesAgree("Pikachu at the Museum", "Pikachu"))
        assertTrue(!CardMatcher.namesAgree("Wobbuffet", "Nidorina"))
    }

    @Test
    fun `glued promos are searched as printed`() {
        val (query, reading) = CardLookup.queryFor(CardReading("SWSH", "123", null))
        assertEquals("SWSH123", query)
        assertEquals("SWSH123", reading.number)
        assertEquals(null, reading.setCode)
    }

    @Test
    fun `set codes glued to the regulation mark or misread still match`() {
        assertTrue(CardMatcher.codeMatchesLoosely("OMEG", "MEG"))
        assertTrue(CardMatcher.codeMatchesLoosely("MEC", "MEG"))
        assertTrue(CardMatcher.codeMatchesLoosely("3OC", "30C"))
        assertTrue(!CardMatcher.codeMatchesLoosely("PAL", "OBF"))
        val pool = listOf(card(1, "Clawitzer", "MEG", "141/132"), card(2, "Other", "XYZ", "141/132"))
        val r = CardMatcher.match(CardReading("OMEG", "141", "132"), pool)
        assertEquals(1, (r as MatchResult.Single).card.id)
    }
}
