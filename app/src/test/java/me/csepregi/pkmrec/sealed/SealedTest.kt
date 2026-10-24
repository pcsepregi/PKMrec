package me.csepregi.pkmrec.sealed

import me.csepregi.pkmrec.cardmarket.CardmarketResult
import me.csepregi.pkmrec.cardmarket.ProductLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SealedTest {

    private val products = SealedCatalog.parse(File("src/main/assets/sealed_products.tsv").readText())

    private fun first(query: String) = SealedMatcher.suggest(query, products).firstOrNull()?.name

    @Test
    fun `the bundled product list is English sealed products only`() {
        assertTrue(products.size >= SealedCatalog.MIN_COUNT)
        assertTrue(products.none { it.name.startsWith("151C:") || it.name.contains("Chinese") })
        assertTrue(products.none { it.type.isEmpty() })
        val etb = products.first { it.id == 885542 }
        assertEquals(SealedProduct(885542, "Elite Trainer Box", "Pitch Black", "Pitch Black Elite Trainer Box"), etb)
    }

    @Test
    fun `every product of the seed list is in the product list`() {
        val seed = SealedInventory.parseSeed(File("src/main/assets/sealed_seed.tsv").readText())
        assertEquals(54, seed.size)
        assertEquals(82, seed.sumOf { it.second })
        val ids = products.map { it.id }.toSet()
        seed.forEach { (id, _) -> assertTrue("missing $id", id in ids) }
    }

    @Test
    fun `typed words find products in any order, with abbreviations and typos`() {
        assertEquals("Pitch Black Elite Trainer Box", first("pitch black etb"))
        assertEquals("Pitch Black Elite Trainer Box", first("etb pitch"))
        assertEquals("Mega Kangaskhan ex Box", first("mega kanghaskan ex box"))
        assertEquals("Pokémon Day 2026 Collection", first("pokemon day 2026"))
        assertEquals("Ascended Heroes: Pikachu Mini Tin", first("ascended pikachu mini"))
        assertEquals("Prismatic Evolutions Super-Premium Collection", first("prismatic spc"))
        assertTrue(SealedMatcher.suggest("xqzt", products).isEmpty())
        assertTrue(SealedMatcher.suggest("  ", products).isEmpty())
    }

    @Test
    fun `parse skips comments and broken lines`() {
        val text = "# comment\n1\tBooster\tSet\tA Booster\nbroken line\n2\tTin\t\tB Tin\n"
        assertEquals(listOf(1, 2), SealedCatalog.parse(text).map { it.id })
    }

    @Test
    fun `purchase price is averaged by count, unknown prices keep the known one`() {
        assertEquals(15000L, SealedInventory.weightedAverage(1, 10000, 1, 20000))
        assertEquals(12500L, SealedInventory.weightedAverage(3, 10000, 1, 20000))
        assertEquals(10000L, SealedInventory.weightedAverage(3, 10000, 1, null))
        assertEquals(20000L, SealedInventory.weightedAverage(3, null, 1, 20000))
        assertNull(SealedInventory.weightedAverage(3, null, 1, null))
        assertEquals(20000L, SealedInventory.weightedAverage(0, 10000, 1, 20000))
    }

    @Test
    fun `summary counts only what is known`() {
        val items = listOf(
            SealedItem(1, "A", count = 2, purchaseHuf = 10000, price = SealedPrice(SealedPrice.KIND_PRICE, 30.0, 7, 0)),
            SealedItem(2, "B", count = 1, purchaseHuf = 5000),
            SealedItem(3, "C", count = 3, price = SealedPrice(SealedPrice.KIND_FEW, 10.0, 2, 0)),
            SealedItem(4, "D", count = 1, price = SealedPrice(SealedPrice.KIND_NONE, time = 0)),
            SealedItem(5, "E", count = 0, purchaseHuf = 99999),
        )
        val s = SealedInventory.summary(items, rate = 400.0)
        assertEquals(7, s.pieces)
        assertEquals(4, s.products)
        assertEquals(25000L, s.purchaseHuf)
        assertEquals(2, s.withoutPurchase)
        assertEquals(90.0, s.valueEur, 1e-9)
        assertEquals(2, s.withoutPrice)
        assertEquals((30 * 400 - 10000) * 2L, s.differenceHuf)
        assertNull(SealedInventory.summary(items, rate = null).differenceHuf)
    }

    @Test
    fun `merging an import adds counts`() {
        val current = listOf(SealedItem(1, "A", count = 2, purchaseHuf = 10000), SealedItem(2, "B", count = 1))
        val imported = listOf(SealedItem(1, "A", count = 2, purchaseHuf = 20000), SealedItem(3, "C", count = 4))
        val merged = SealedInventory.merge(current, imported).associateBy { it.id }
        assertEquals(4, merged.getValue(1).count)
        assertEquals(15000L, merged.getValue(1).purchaseHuf)
        assertEquals(1, merged.getValue(2).count)
        assertEquals(4, merged.getValue(3).count)
    }

    @Test
    fun `search results are picked by name or by link slug`() {
        val links = listOf(
            ProductLink("Pitch Black 10 Elite Trainer Box Case", "/en/Pokemon/Products/Elite-Trainer-Boxes/Pitch-Black-10-Elite-Trainer-Box-Case"),
            ProductLink("Pitch Black Elite Trainer Box", "/en/Pokemon/Products/Elite-Trainer-Boxes/Pitch-Black-Elite-Trainer-Box"),
        )
        assertEquals(links[1], SealedPrices.pick(links, "Pitch Black Elite Trainer Box"))
        val unlabeled = links.map { it.copy(label = "") }
        assertEquals(unlabeled[1], SealedPrices.pick(unlabeled, "Pitch Black Elite Trainer Box"))
        assertNull(SealedPrices.pick(links, "Pitch Black Booster"))
        assertEquals("team-rockets-mewtwo-ex-tin", SealedPrices.slug("Team Rocket's Mewtwo ex Tin"))
    }

    @Test
    fun `only prices are stored, not failures`() {
        assertNotNull(SealedPrices.toPrice(CardmarketResult.Price(10.0, 5, 9, "u")))
        assertEquals(SealedPrice.KIND_NONE, SealedPrices.toPrice(CardmarketResult.NoOffers("u"))!!.kind)
        assertNull(SealedPrices.toPrice(CardmarketResult.NotFound("u")))
        assertNull(SealedPrices.toPrice(CardmarketResult.Failed(CardmarketResult.Kind.TIMEOUT, "x")))
    }
}
