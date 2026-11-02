package me.csepregi.pkmrec.collection

import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgErrorKind
import me.csepregi.pkmrec.tcg.WriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempListTest {

    private val card = TcgCard(
        id = 42394,
        name = "Pikachu",
        expansionName = "Scarlet & Violet 151",
        expansionCode = "MEW",
        number = "025/165",
        rarity = "Common",
        path = "/cards/42394/pikachu-scarlet-violet-151-025-165",
        imageUrl = "https://example.org/pikachu.jpg",
        regionId = 1,
        variants = listOf(CardVariant(1, "Normal", 2, 0), CardVariant(5, "Reverse Holo", 0, 1)),
        energyTypes = listOf("Lightning"),
    )

    @Test
    fun `an item keeps the card and the chosen variant through the file`() {
        val a = TempListItem.of(1000, card, card.variants[1])
        val b = TempListItem.of(1001, card, card.variants[1])
        val back = TempList.decode(TempList.encode(listOf(a, b)))
        assertEquals(listOf(a, b), back)

        val restored = back.first().toCard()
        assertEquals(card.url, restored.url)
        assertEquals(listOf(CardVariant(5, "Reverse Holo", 0, 0)), restored.variants)
        assertEquals(listOf("Lightning"), restored.energyTypes)
    }

    @Test
    fun `a broken file reads as an empty list`() {
        assertEquals(emptyList<TempListItem>(), TempList.decode("{not json"))
    }

    @Test
    fun `ids keep growing even when the clock is behind`() {
        val far = System.currentTimeMillis() + 1_000_000
        val items = listOf(TempListItem.of(far, card, card.variants[0]))
        assertEquals(far + 1, TempList.nextId(items))
        assertTrue(TempList.nextId(emptyList()) > 0)
    }

    @Test
    fun `a bulk add stops only when every card would fail the same way`() {
        assertTrue(stopsBulkAdd(WriteOutcome.NotLoggedIn))
        assertTrue(stopsBulkAdd(WriteOutcome.Error(TcgError(TcgErrorKind.BLOCKED, "HTTP 403"))))
        assertTrue(stopsBulkAdd(WriteOutcome.Error(TcgError(TcgErrorKind.NO_INTERNET, "offline"))))
        assertFalse(stopsBulkAdd(WriteOutcome.Error(TcgError(TcgErrorKind.TIMEOUT, "timeout"))))
        assertFalse(stopsBulkAdd(WriteOutcome.NotApplied))
    }
}
