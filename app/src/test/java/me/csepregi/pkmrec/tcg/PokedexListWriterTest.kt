package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Test

class PokedexListWriterTest {

    @Test
    fun `body matches the request the website sends`() {
        // Recorded on 2026-10-28 with the debug request recorder (adding a card to the Pokedex list).
        assertEquals(
            """[{"cardId":89509,"isCardInUserCardList":true,"exclusiveCardVariantTypeIds":[]}]""",
            PokedexListWriter.body(89509, inList = true),
        )
        assertEquals(
            """[{"cardId":33630,"isCardInUserCardList":false,"exclusiveCardVariantTypeIds":[]}]""",
            PokedexListWriter.body(33630, inList = false),
        )
    }

    @Test
    fun `url is the list's bulk-replace endpoint`() {
        assertEquals("https://www.tcgcollector.com/user-card-lists/65678/entries/bulk-replace", PokedexListWriter.url(65678))
    }
}
