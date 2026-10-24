package me.csepregi.pkmrec.tcg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CollectionWriterTest {

    private fun page(name: String): String =
        requireNotNull(javaClass.getResource("/tcg/$name")) { "missing fixture $name" }.readText()

    private fun state(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `real card page without the card in the collection`() {
        val appState = AppStateExtractor.extract(page("card-page-pikachu-mew-025.html"))!!
        val s = CollectionWriter.parseState(appState, 42394)
        assertEquals(emptyList<CollectionEntry>(), s.entries)
        assertEquals(TcgIds.LANGUAGE_ENGLISH, s.languageId)
        assertEquals(8, s.conditionId)
        assertEquals("X-CSRF-Token", s.csrfHeader)
        assertEquals(0, s.count(1))
    }

    @Test
    fun `real page with an owned card keeps its entry exactly`() {
        val appState = AppStateExtractor.extract(page("pokedex-pikachu-in-list.html"))!!
        val s = CollectionWriter.parseState(appState, 35591)
        assertEquals(listOf(CollectionEntry(363, 1, 8, null, 1)), s.entries)
        assertEquals(1, s.count(363))
        assertEquals(
            """[{"cardId":35591,"entries":[{"cardVariantTypeId":363,"cardLanguageId":1,"cardConditionId":8,"note":null,"cardCount":1}]}]""",
            CollectionWriter.body(35591, s.entries),
        )
    }

    @Test
    fun `count is per variant and language, notes and conditions add up`() {
        val s = CollectionWriter.parseState(
            state(
                """{"cardIdToCardCollectionCardDtoMap": {"7": {"cardId": 7, "entries": [
                     {"cardVariantTypeId": 1, "cardLanguageId": 1, "cardConditionId": 8, "note": null, "cardCount": 2},
                     {"cardVariantTypeId": 1, "cardLanguageId": 1, "cardConditionId": 3, "note": "binder", "cardCount": 1},
                     {"cardVariantTypeId": 1, "cardLanguageId": 5, "cardConditionId": 8, "note": null, "cardCount": 4},
                     {"cardVariantTypeId": 2, "cardLanguageId": 1, "cardConditionId": 8, "note": null, "cardCount": 1}]}},
                   "cardIdToTcgRegionIdMap": {"7": 1},
                   "defaultCardCollectionParametersDto": {"tcgRegionIdToCardLanguageIdMap": {"1": 1}, "cardConditionId": 8},
                   "csrfTokenHttpHeaderKey": "X-CSRF-Token", "csrfToken": "t"}""",
            ),
            7,
        )
        assertEquals(3, s.count(1))
        assertEquals(1, s.count(2))
        assertEquals("binder", s.entries[1].note)
        assertEquals(
            """[{"cardId":7,"entries":[{"cardVariantTypeId":1,"cardLanguageId":1,"cardConditionId":3,"note":"binder","cardCount":1}]}]""",
            CollectionWriter.body(7, listOf(s.entries[1])),
        )
    }

    @Test
    fun `a japanese card uses the region's default language`() {
        val s = CollectionWriter.parseState(
            state(
                """{"cardIdToCardCollectionCardDtoMap": {"9": {"cardId": 9, "entries": []}},
                   "cardIdToTcgRegionIdMap": {"9": 2},
                   "defaultCardCollectionParametersDto": {"tcgRegionIdToCardLanguageIdMap": {"1": 1, "2": 12}, "cardConditionId": 8},
                   "csrfTokenHttpHeaderKey": "X-CSRF-Token", "csrfToken": "t"}""",
            ),
            9,
        )
        assertEquals(12, s.languageId)
    }

    @Test
    fun `missing data means no write`() {
        val full = """"cardIdToTcgRegionIdMap": {"7": 1},
            "defaultCardCollectionParametersDto": {"tcgRegionIdToCardLanguageIdMap": {"1": 1}, "cardConditionId": 8},
            "csrfTokenHttpHeaderKey": "X-CSRF-Token", "csrfToken": "t""""
        // The card is not on the page.
        assertThrows(PageFormatException::class.java) {
            CollectionWriter.parseState(state("""{"cardIdToCardCollectionCardDtoMap": {}, $full}"""), 7)
        }
        // An entry without its condition could not be written back unchanged.
        assertThrows(PageFormatException::class.java) {
            CollectionWriter.parseState(
                state(
                    """{"cardIdToCardCollectionCardDtoMap": {"7": {"cardId": 7, "entries": [
                         {"cardVariantTypeId": 1, "cardLanguageId": 1, "note": null, "cardCount": 2}]}}, $full}""",
                ),
                7,
            )
        }
        // No CSRF token.
        assertThrows(PageFormatException::class.java) {
            CollectionWriter.parseState(
                state(
                    """{"cardIdToCardCollectionCardDtoMap": {"7": {"cardId": 7, "entries": []}},
                       "defaultCardCollectionParametersDto": {"cardConditionId": 8}, "csrfTokenHttpHeaderKey": "X-CSRF-Token"}""",
                ),
                7,
            )
        }
    }
}
