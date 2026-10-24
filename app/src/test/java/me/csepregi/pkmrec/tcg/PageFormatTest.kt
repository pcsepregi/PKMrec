package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A changed TCG Collector page must be reported as such, not as "card not found". */
class PageFormatTest {

    private val appState = AppStateExtractor.extract(
        """
        <script> window.tcgcollector = { appState: {
          "userDto": {"id": 1, "displayName": "T", "isPremiumEnabled": false},
          "cardIdToTcgRegionIdMap": {"89541": 1},
          "cardIdToCardVariantTypeIdsMap": {"89541": [363]},
          "idToCardVariantTypeDtoMap": {"363": {"id": 363, "name": "Normal Holo"}},
          "cardIdToCardCollectionCardDtoMap": {}
        }, }; </script>
        """.trimIndent(),
    )

    @Test
    fun `an empty result page is just no result`() {
        val html = "<html><body><main><p>No cards found</p></main></body></html>"
        assertTrue(TcgSearchParser.parseListPage(html, appState).isEmpty())
    }

    @Test
    fun `result rows that can no longer be read mean the site changed`() {
        // The name link got a new class name.
        val html = """
            <div class="card-list-item" data-card-id="89541">
              <a href="/cards/89541/fuecoco-ex" class="card-row-title"> Fuecoco ex </a>
            </div>
        """.trimIndent()
        assertThrows(PageFormatException::class.java) { TcgSearchParser.parseListPage(html, appState) }
    }

    @Test
    fun `results without variant data in appState mean the site changed`() {
        val html = """
            <div class="card-list-item" data-card-id="89541">
              <a href="/cards/89541/fuecoco-ex" class="card-list-item-name-entry"> Fuecoco ex </a>
            </div>
        """.trimIndent()
        assertEquals(1, TcgSearchParser.parseListPage(html, appState).size)

        val renamed = AppStateExtractor.extract(
            """<script>window.tcgcollector = { appState: {"userDto": null, "cardVariants": {}}, };</script>""",
        )
        assertThrows(PageFormatException::class.java) { TcgSearchParser.parseListPage(html, renamed) }
    }
}
