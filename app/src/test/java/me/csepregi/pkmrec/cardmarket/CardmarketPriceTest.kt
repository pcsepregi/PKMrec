package me.csepregi.pkmrec.cardmarket

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardmarketPriceTest {

    private fun offers(vararg prices: Double, reverse: Boolean = false) =
        prices.map { CardmarketOffer(it, "NM", "English", reverse) }

    @Test
    fun `prices with comma decimals and thousands separators are read`() {
        assertEquals(0.10, CardmarketPrice.parsePrice("0,10 €")!!, 1e-9)
        assertEquals(1234.56, CardmarketPrice.parsePrice("1.234,56 €")!!, 1e-9)
        assertEquals(12.0, CardmarketPrice.parsePrice("From 12 €")!!, 1e-9)
        assertEquals(3.5, CardmarketPrice.parsePrice("3,5€")!!, 1e-9)
        assertNull(CardmarketPrice.parsePrice("no price"))
        assertNull(CardmarketPrice.parsePrice(null))
    }

    @Test
    fun `good or better means MT, NM, EX and GD`() {
        listOf("MT", "NM", "EX", "GD", "gd").forEach { assertTrue(it, CardCondition.isGoodOrBetter(it)) }
        listOf("LP", "PL", "PO", "", "??").forEach { assertFalse(it, CardCondition.isGoodOrBetter(it)) }
    }

    @Test
    fun `two cheapest are skipped and the next five averaged`() {
        val estimate = CardmarketPrice.estimate(offers(9.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
        assertEquals(listOf(3.0, 4.0, 5.0, 6.0, 7.0), estimate.counted)
        assertEquals(5.0, estimate.average!!, 1e-9)
    }

    @Test
    fun `lechonk from the first phone report costs 8,04`() {
        val estimate = CardmarketPrice.estimate(offers(6.49, 7.00, 7.39, 7.84, 7.99, 8.49, 8.50, 8.99, 9.00))
        assertEquals("8,04 €", CardmarketPrice.format(estimate.average!!))
    }

    @Test
    fun `other languages, worse conditions and unreadable prices are left out`() {
        val offers = listOf(
            CardmarketOffer(0.5, "NM", "German"),
            CardmarketOffer(0.6, "LP", "English"),
            CardmarketOffer(null, "NM", "English"),
            CardmarketOffer(1.0, "GD", "English"),
            CardmarketOffer(2.0, "EX", "English"),
            CardmarketOffer(3.0, "MT", null),
        )
        val estimate = CardmarketPrice.estimate(offers)
        assertEquals(listOf(1.0, 2.0, 3.0), estimate.eligible)
        assertEquals(listOf(3.0), estimate.counted)
    }

    @Test
    fun `reverse holo offers count only for a reverse holo variant`() {
        val mixed = offers(1.0, 2.0, 3.0) + offers(10.0, 20.0, 30.0, reverse = true)
        assertEquals(listOf(1.0, 2.0, 3.0), CardmarketPrice.estimate(mixed).eligible)
        assertEquals(listOf(10.0, 20.0, 30.0), CardmarketPrice.estimate(mixed, reverseHolo = true).eligible)
    }

    @Test
    fun `three or more offers give an average, one or two the cheapest, none no price`() {
        fun page(vararg prices: Double) = CardmarketPage("u", "t", false, null, null, prices.size, offers(*prices), emptyList(), emptyList(), "")
        val price = CardmarketPrices.fromOffers(page(1.0, 2.0, 4.0), false, "p") as CardmarketResult.Price
        assertEquals(4.0, price.average, 1e-9)
        assertEquals(3, price.eligible)
        assertEquals(CardmarketResult.FewOffers(2.0, 2, "p"), CardmarketPrices.fromOffers(page(3.0, 2.0), false, "p"))
        assertEquals(CardmarketResult.NoOffers("p"), CardmarketPrices.fromOffers(page(), false, "p"))
    }

    @Test
    fun `rows without any price mean the page changed`() {
        val page = CardmarketPage("u", "t", false, null, null, 2, listOf(CardmarketOffer(null, "NM", "English"), CardmarketOffer(null, null, null)), emptyList(), emptyList(), "")
        val result = CardmarketPrices.fromOffers(page, false, "p") as CardmarketResult.Failed
        assertEquals(CardmarketResult.Kind.SITE_CHANGED, result.kind)
    }

    @Test
    fun `filter is applied to the product url`() {
        assertEquals(
            "https://www.cardmarket.com/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209?language=1&minCondition=4",
            Cardmarket.filteredOffersUrl("/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209?sellerType=1#x"),
        )
        assertEquals(
            "https://www.cardmarket.com/en/Pokemon/Products/Search?category=-1&searchString=Charizard+ex&site=2",
            Cardmarket.searchUrl(" Charizard ex ", page = 2),
        )
    }

    @Test
    fun `products are matched by set code and number from search result labels`() {
        val links = listOf(
            ProductLink("Lechonk (OBF 180) From 0,02 €", "/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V1-OBF180"),
            ProductLink("Lechonk (OBF 209) From 1,00 €", "/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209"),
            ProductLink("Lechonk (PAF 071) From 0,02 €", "/en/Pokemon/Products/Singles/Paldean-Fates/Lechonk-V1-PAF071"),
            ProductLink("Pikachu (HIF 19)", "/en/Pokemon/Products/Singles/Hidden-Fates/Pikachu-V1-HIF19"),
        )
        assertEquals("/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209", CardmarketMatcher.find(links, "OBF", "209/197")?.href)
        assertEquals("/en/Pokemon/Products/Singles/Paldean-Fates/Lechonk-V1-PAF071", CardmarketMatcher.find(links, "paf", "71/91")?.href)
        assertEquals("/en/Pokemon/Products/Singles/Hidden-Fates/Pikachu-V1-HIF19", CardmarketMatcher.find(links, "HIF", "019/068")?.href)
        assertNull(CardmarketMatcher.find(links, "OBF", "20"))
        assertNull(CardmarketMatcher.find(links, "SVI", "209"))
    }

    @Test
    fun `numbers are compared without leading zeros, letters kept`() {
        assertEquals("71", CardmarketMatcher.normalizeNumber("071/091"))
        assertEquals("TG5", CardmarketMatcher.normalizeNumber("TG05/TG30"))
        assertEquals("SV49", CardmarketMatcher.normalizeNumber("SV049"))
        assertEquals("Lechonk (OBF 209)".let { CardmarketMatcher.codeAndNumber(it) }, "OBF" to "209")
        assertNull(CardmarketMatcher.codeAndNumber("Obsidian Flames"))
    }

    @Test
    fun `script result is parsed`() {
        val inner = """{"url":"https://www.cardmarket.com/en/Pokemon/Products/Singles/A/B","title":"B","challenge":"false",""" +
            """"idProduct":123,"productName":"B","rowCount":2,""" +
            """"offers":[{"price":"1,20 €","condition":"NM","language":"English","reverse":"true"},{"price":"","condition":"","language":"","reverse":"false"}],""" +
            """"links":[{"label":"Lechonk (OBF 209)\n\nFrom 1,00 €","href":"/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209"},""" +
            """{"label":"Deutsch","href":"/de/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209"},""" +
            """{"label":"Lechonk (OBF 209)","href":"/en/Pokemon/Products/Singles/Obsidian-Flames/Lechonk-V2-OBF209"}],"sample":"<div>",""" +
            """"image":"https://product-images.s3.cardmarket.com/51/OBF/123/123.jpg"}"""
        val page = CardmarketPage.fromScriptResult(JsonPrimitive(inner).toString())!!
        assertEquals(123, page.idProduct)
        assertEquals(2, page.rowCount)
        assertEquals(CardmarketOffer(1.2, "NM", "English", reverseHolo = true), page.offers[0])
        assertEquals(CardmarketOffer(null, null, null), page.offers[1])
        assertFalse(page.challenge)
        assertEquals(listOf("Lechonk (OBF 209) From 1,00 €"), page.productLinks.map { it.label })
        assertEquals("https://product-images.s3.cardmarket.com/51/OBF/123/123.jpg", page.imageUrl)
    }

    @Test
    fun `sealed product pages are told apart from singles, categories and search`() {
        assertTrue(Cardmarket.isSealedProductPage("/en/Pokemon/Products/Elite-Trainer-Boxes/Surging-Sparks-Elite-Trainer-Box"))
        assertTrue(Cardmarket.isSealedProductPage("https://www.cardmarket.com/en/Pokemon/Products/Tins/Mega-Charizard-X-ex-Tin?language=1"))
        assertFalse(Cardmarket.isSealedProductPage("/en/Pokemon/Products/Elite-Trainer-Boxes"))
        assertFalse(Cardmarket.isSealedProductPage("/en/Pokemon/Products/Singles/Surging-Sparks/Pikachu-ex-V1-SSP057"))
        assertFalse(Cardmarket.isSealedProductPage("/en/Pokemon/Products/Search?searchString=etb"))
        assertFalse(Cardmarket.isSealedProductPage("/en/Pokemon"))
    }

    @Test
    fun `sealed offers are filtered by language only`() {
        assertEquals(
            "https://www.cardmarket.com/en/Pokemon/Products/Tins/X-Tin?language=1",
            Cardmarket.englishOffersUrl("/en/Pokemon/Products/Tins/X-Tin?language=3#tabs"),
        )
    }
}
