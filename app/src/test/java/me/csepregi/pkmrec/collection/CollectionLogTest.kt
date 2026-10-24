package me.csepregi.pkmrec.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CollectionLogTest {

    @Test
    fun `an entry survives a round trip, tabs in names do not break the line`() {
        val e = CollectionLog.Entry("2026-10-24 19:50:12", "add", 42394, "Pikachu\tex", "Scarlet & Violet 151", "025/165", "Normal", 0, 1)
        val back = CollectionLog.Entry.parse(e.toLine())!!
        assertEquals(e.copy(name = "Pikachu ex"), back)
        assertEquals(
            "2026-10-24 19:50:12  add  Pikachu ex · Scarlet & Violet 151 · 025/165 · Normal  0 → 1",
            CollectionLog.format(back),
        )
    }

    @Test
    fun `a cut off line is skipped`() {
        assertNull(CollectionLog.Entry.parse("2026-10-24 19:50:12\tadd\t42394\tPikachu"))
    }
}
