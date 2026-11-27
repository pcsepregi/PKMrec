package me.csepregi.pkmrec.sealed

import me.csepregi.pkmrec.cardmarket.EurHuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TcgplayerTest {

    // Real TCGplayer names (TCGCSV, 2026-11-27); set = group name without its code prefix.
    private val products = listOf(
        TcgplayerProduct(668496, 24541, "Ascended Heroes", "Ascended Heroes Elite Trainer Box"),
        TcgplayerProduct(668497, 24541, "Ascended Heroes", "Ascended Heroes Pokemon Center Elite Trainer Box (Exclusive)"),
        TcgplayerProduct(679558, 24541, "Ascended Heroes", "Ascended Heroes Elite Trainer Box Case"),
        TcgplayerProduct(1, 24541, "Ascended Heroes", "Ascended Heroes Mini Tin [Pikachu & Tepig]"),
        TcgplayerProduct(2, 24541, "Ascended Heroes", "Ascended Heroes Mini Tin [Riolu & Darumaka]"),
        TcgplayerProduct(3, 24541, "Ascended Heroes", "Ascended Heroes Mini Tin Display"),
        TcgplayerProduct(4, 24541, "Ascended Heroes", "Mega Evolution: Ascended Heroes Tech Sticker Collection - Charmander"),
        TcgplayerProduct(5, 24541, "Ascended Heroes", "Mega Evolution: Ascended Heroes Tech Sticker Collection - Gastly"),
        TcgplayerProduct(6, 24541, "Ascended Heroes", "Premium Poster Collection: Mega Lucario"),
        TcgplayerProduct(7, 24541, "Ascended Heroes", "Premium Poster Collection: Mega Gardevoir"),
        TcgplayerProduct(8, 24541, "Ascended Heroes", "Ascended Heroes Booster Pack"),
        TcgplayerProduct(10, 1, "Miscellaneous Cards & Products", "Team Rocket Tin [Team Rocket's Mewtwo ex]"),
        TcgplayerProduct(11, 1, "Miscellaneous Cards & Products", "Team Rocket Tin [Team Rocket's Mewtwo ex] (International Version)"),
        TcgplayerProduct(12, 1, "Miscellaneous Cards & Products", "Team Rocket Tin [Team Rocket's Persian ex]"),
        TcgplayerProduct(13, 1, "Miscellaneous Cards & Products", "Mega Charizard Tin (Mega Charizard X)"),
        TcgplayerProduct(14, 1, "Miscellaneous Cards & Products", "Mega Charizard X ex Ultra Premium Collection"),
        TcgplayerProduct(15, 1, "Miscellaneous Cards & Products", "Pokemon Day 2026 Collection"),
        TcgplayerProduct(16, 1, "Miscellaneous Cards & Products", "Pokemon Day 2026 Collection Case"),
        TcgplayerProduct(20, 2, "Chaos Rising", "Chaos Rising Single Pack Blister [Toxel]"),
        TcgplayerProduct(21, 2, "Chaos Rising", "Chaos Rising 3-Pack Blister [Charmeleon]"),
        TcgplayerProduct(30, 3, "Mega Evolution", "Mega Heroes Mini Tin [Mega Gardevoir]"),
        TcgplayerProduct(31, 3, "Mega Evolution", "Mega Heroes Mini Tin Display"),
        TcgplayerProduct(40, 4, "First Partner Collection 2026", "First Partner Illustration Collection (Series 1)"),
        TcgplayerProduct(41, 4, "First Partner Collection 2026", "First Partner Illustration Collection Case (Series 1)"),
        TcgplayerProduct(42, 4, "First Partner Collection 2026", "First Partner Illustration Collection (Series 2)"),
    )

    private fun auto(name: String, set: String) = TcgplayerMatcher.autoMatch(name, set, products)?.id

    @Test
    fun `clear names are paired without asking`() {
        assertEquals(668496, auto("Ascended Heroes Elite Trainer Box", "Ascended Heroes"))
        assertEquals(1, auto("Ascended Heroes: Pikachu Mini Tin", "Ascended Heroes"))
        assertEquals(4, auto("Ascended Heroes: Charmander Tech Sticker Collection", "Ascended Heroes"))
        assertEquals(6, auto("Ascended Heroes: Mega Lucario Premium Poster Collection", "Ascended Heroes"))
        assertEquals(15, auto("Pokémon Day 2026 Collection", ""))
        assertEquals(20, auto("Chaos Rising: Toxel 1-Pack Blister", "Chaos Rising"))
        assertEquals(30, auto("Mega Heroes: Mega Gardevoir Mini Tin", "Mega Evolution"))
        assertEquals(40, auto("First Partner Illustration Collection Series 1", ""))
        assertEquals(42, auto("First Partner Illustration Collection Series 2", ""))
    }

    @Test
    fun `the international version is taken where there is one`() {
        assertEquals(11, auto("Team Rocket Tins: Team Rocket's Mewtwo ex Tin", "Team Rocket Tins"))
        assertEquals(12, auto("Team Rocket Tins: Team Rocket's Persian ex Tin", "Team Rocket Tins"))
    }

    @Test
    fun `unclear or missing products are left to pick by hand`() {
        // "ex" is not in the TCGplayer name: picked by hand.
        assertNull(auto("Mega Charizard X ex Tin", ""))
        // Not on TCGplayer (European products): nothing has every word.
        assertNull(auto("Ascended Heroes: Erika's Tangela 2-Pack Blister", "Ascended Heroes"))
        assertNull(auto("Mega Heroes: Mega Latios Mini Tin", "Mega Evolution"))
        // The hand pick still lists the right one first.
        assertEquals(13, TcgplayerMatcher.rank("Mega Charizard X ex Tin", "", products).first().id)
    }

    @Test
    fun `words drop plural s, apostrophes and filler words`() {
        assertEquals(listOf("team", "rocket", "tin"), TcgplayerMatcher.words("The Team Rocket's Tins"))
        assertEquals(listOf("1", "pack", "blister"), TcgplayerMatcher.words("Single Pack Blister"))
        assertEquals(listOf("heroe", "glass"), TcgplayerMatcher.words("Heroes Glass"))
    }

    @Test
    fun `the catalogue file is read`() {
        val text = "# header\n# productId\tgroupId\tset\tname\n668496\t24541\tAscended Heroes\tAscended Heroes Elite Trainer Box\nbad line\n5\tx\ty\tz\n"
        assertEquals(listOf(TcgplayerProduct(668496, 24541, "Ascended Heroes", "Ascended Heroes Elite Trainer Box")), TcgplayerCatalog.parse(text))
    }

    @Test
    fun `market prices are read from a tcgcsv prices file, normal first`() {
        val json = """{"success":true,"errors":[],"totalItems":4,"results":[
            {"productId":668496,"lowPrice":119.69,"midPrice":161.78,"highPrice":555,"marketPrice":126.59,"directLowPrice":null,"subTypeName":"Normal"},
            {"productId":7,"marketPrice":null,"lowPrice":4.0,"subTypeName":"Normal"},
            {"productId":8,"marketPrice":3.5,"subTypeName":"Holofoil"},
            {"productId":8,"marketPrice":1.25,"subTypeName":"Normal"}]}"""
        val prices = TcgplayerPrices.parse(json)
        assertEquals(126.59, prices.getValue(668496), 0.0001)
        assertFalse(prices.containsKey(7))
        assertEquals(1.25, prices.getValue(8), 0.0001)
    }

    @Test
    fun `dollar prices are kept and turned into euros`() {
        val price = SealedPrices.toTcgplayerPrice(126.59, usdPerEur = 1.1, now = 5)
        assertTrue(price.isTcgplayer)
        assertEquals(126.59, price.usd!!, 0.0001)
        assertEquals(115.08, price.value!!, 0.01)
        assertEquals("126,59 $", formatUsd(126.59))
    }

    @Test
    fun `the usd rate is read from the ecb daily xml`() {
        val xml = """<Cube time='2026-11-27'><Cube currency='USD' rate='1.0812'/><Cube currency='HUF' rate='366.73'/></Cube>"""
        assertEquals(1.0812, EurHuf.parseUsd(xml)!!, 0.00001)
        assertNull(EurHuf.parseUsd("<Cube currency='HUF' rate='366.73'/>"))
    }

    @Test
    fun `the trend compares only prices from the same shop`() {
        val cardmarket = SealedPrice(SealedPrice.KIND_PRICE, 60.0, 7, time = 1)
        val tcgplayer = SealedPrice(SealedPrice.KIND_PRICE, 100.0, time = 2, source = SealedPrice.SOURCE_TCGPLAYER, usd = 110.0)
        val switched = SealedItem(1, "ETB", count = 1, price = tcgplayer, previousPrices = listOf(cardmarket))
        assertNull(switched.trend)
        val later = tcgplayer.copy(eur = 103.0, time = 3)
        assertEquals(PriceTrend.UP, switched.copy(price = later, previousPrices = listOf(tcgplayer, cardmarket)).trend)
    }

    @Test
    fun `an import keeps the pairing`() {
        val paired = SealedItem(1, "ETB", count = 1, tcgplayerId = 668496, tcgplayerGroupId = 24541, tcgplayerName = "Ascended Heroes Elite Trainer Box")
        val merged = SealedInventory.merge(listOf(SealedItem(1, "ETB", count = 2)), listOf(paired)).single()
        assertEquals(3, merged.count)
        assertTrue(merged.tcgplayerPaired)
        assertFalse(merged.noTcgplayer)
    }
}
