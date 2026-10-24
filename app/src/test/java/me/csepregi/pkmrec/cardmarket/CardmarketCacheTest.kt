package me.csepregi.pkmrec.cardmarket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardmarketCacheTest {

    @Test
    fun `stored prices are read back with their time`() {
        val raw = """{"kind":"price","value":8.04,"counted":5,"eligible":9,"productUrl":"https://www.cardmarket.com/x","time":1761300000000}"""
        assertEquals(
            CardmarketResult.Price(8.04, 5, 9, "https://www.cardmarket.com/x") to 1761300000000L,
            CardmarketPrices.decodeCached(raw),
        )
        val few = """{"kind":"few","value":1.5,"eligible":2,"productUrl":"u","time":1}"""
        assertEquals(CardmarketResult.FewOffers(1.5, 2, "u") to 1L, CardmarketPrices.decodeCached(few))
        val none = """{"kind":"none","productUrl":"u","time":2}"""
        assertEquals(CardmarketResult.NoOffers("u") to 2L, CardmarketPrices.decodeCached(none))
    }

    @Test
    fun `missing or unreadable entries are ignored`() {
        assertNull(CardmarketPrices.decodeCached(null))
        assertNull(CardmarketPrices.decodeCached("not json"))
        assertNull(CardmarketPrices.decodeCached("""{"kind":"other","productUrl":"u","time":1}"""))
    }
}
