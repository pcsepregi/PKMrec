package me.csepregi.pkmrec.cardmarket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardmarketPriceTest {

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
        val offers = listOf(9.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0).map { CardmarketOffer(it, "NM", "English") }
        val estimate = CardmarketPrice.estimate(offers)
        assertEquals(listOf(3.0, 4.0, 5.0, 6.0, 7.0), estimate.counted)
        assertEquals(5.0, estimate.average!!, 1e-9)
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
    fun `two or fewer offers give no price`() {
        val offers = listOf(1.0, 2.0).map { CardmarketOffer(it, "NM", "English") }
        assertNull(CardmarketPrice.estimate(offers).average)
    }

    @Test
    fun `filter is applied to the product url`() {
        assertEquals(
            "https://www.cardmarket.com/en/Pokemon/Products/Singles/Obsidian-Flames/Charizard-ex-V1-OBF125?language=1&minCondition=4",
            Cardmarket.filteredOffersUrl("https://www.cardmarket.com/en/Pokemon/Products/Singles/Obsidian-Flames/Charizard-ex-V1-OBF125?sellerType=1#x"),
        )
    }

    @Test
    fun `script result is parsed`() {
        val inner = """{"url":"https://www.cardmarket.com/en/Pokemon/Products/Singles/A/B","title":"B","challenge":"false",""" +
            """"idProduct":123,"productName":"B","rowCount":2,""" +
            """"offers":[{"price":"1,20 €","condition":"NM","language":"English"},{"price":"","condition":"","language":""}],""" +
            """"links":["B -> /x"],"sample":"<div>"}"""
        val result = kotlinx.serialization.json.JsonPrimitive(inner).toString()
        val page = ProbePage.fromScriptResult(result)!!
        assertEquals(123, page.idProduct)
        assertEquals(2, page.rowCount)
        assertEquals(CardmarketOffer(1.2, "NM", "English"), page.offers[0])
        assertEquals(CardmarketOffer(null, null, null), page.offers[1])
        assertFalse(page.challenge)
    }

    @Test
    fun `price guide entry is found by product id`() {
        val json = """{"version":1,"priceGuides":[{"idProduct":12,"avg":1.5,"trend":2},{"idProduct":123,"avg":0.4,"low":0.02,"trend":0.35}]}"""
        val entry = PriceGuide.find(json, 123)!!
        assertEquals("0.35", entry.fields["trend"])
        assertEquals("2", PriceGuide.find(json, 12)!!.fields["trend"])
        assertNull(PriceGuide.find(json, 1))
        assertEquals(2, PriceGuide.count(json))
    }
}
