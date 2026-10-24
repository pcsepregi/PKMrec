package me.csepregi.pkmrec.tcg

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup

/** TCG Collector region and language IDs used by the app. */
object TcgIds {
    /** International (English) TCG region. */
    const val REGION_INTERNATIONAL = 1

    /** English card language. */
    const val LANGUAGE_ENGLISH = 1
}

/** One variant of a card (e.g. "Normal Holo", "Reverse Holo") and how many the user owns of it. */
data class CardVariant(
    val id: Int,
    val name: String,
    val ownedEnglish: Int,
    val ownedOtherLanguages: Int,
) {
    val isOwned: Boolean get() = ownedEnglish > 0
}

/** A card as found on TCG Collector, with the user's collection data. */
data class TcgCard(
    val id: Int,
    val name: String,
    val expansionName: String,
    val expansionCode: String?,
    val number: String,
    /** e.g. "Double Rare", "Illustration Rare"; null if not shown. */
    val rarity: String? = null,
    val path: String,
    val imageUrl: String?,
    val regionId: Int?,
    val variants: List<CardVariant>,
    /** Energy type(s) of a Pokémon card, e.g. "Fire"; read from the card page, empty if not loaded. */
    val energyTypes: List<String> = emptyList(),
    val hp: Int? = null,
) {
    val url: String get() = Tcg.BASE_URL + path
}

/** What the card page adds to a search result row. */
data class CardDetails(val energyTypes: List<String>, val hp: Int?)

/** Parses a card page (`/cards/<id>/<slug>`). */
object CardPageParser {

    /** Header of the card page: `#card-hit-points-value` and the `#card-energy-types` symbols (alt = "Fire"). */
    fun parseDetails(html: String): CardDetails {
        val doc = Jsoup.parse(html)
        val types = doc.select("#card-energy-types img").mapNotNull { img ->
            img.attr("alt").ifBlank { img.attr("title") }.trim().ifEmpty { null }
        }.distinct()
        val hp = doc.selectFirst("#card-hit-points-value")?.text()?.trim()?.toIntOrNull()
        return CardDetails(types, hp)
    }
}

/**
 * Parses TCG Collector card search pages:
 * the list view (name, expansion, set code, number), the image view (card images)
 * and the embedded appState (region, variants, collection entries).
 */
object TcgSearchParser {

    private val CARD_ID_IN_PATH = Regex("""/cards/(\d+)/""")

    /**
     * Cards of a list-view page (`displayAs=list`) combined with its appState.
     * Throws [PageFormatException] when the page has results that cannot be read (the site changed),
     * so that this is not mistaken for "card not found".
     */
    fun parseListPage(html: String, appState: JsonObject?): List<TcgCard> {
        val doc = Jsoup.parse(html)
        val regions = appState.objectAt("cardIdToTcgRegionIdMap")
        val variantIds = appState.objectAt("cardIdToCardVariantTypeIdsMap")
        val variantTypes = appState.objectAt("idToCardVariantTypeDtoMap")
        val collection = appState.objectAt("cardIdToCardCollectionCardDtoMap")

        val cards = doc.select("div.card-list-item[data-card-id]").mapNotNull { row ->
            val id = row.attr("data-card-id").toIntOrNull() ?: return@mapNotNull null
            val link = row.selectFirst("a.card-list-item-name-entry") ?: return@mapNotNull null
            val key = id.toString()
            TcgCard(
                id = id,
                name = link.text().trim(),
                expansionName = row.selectFirst(".card-list-item-expansion-name")?.text()?.trim().orEmpty(),
                expansionCode = row.selectFirst(".card-list-item-expansion-code")?.text()?.trim()?.ifEmpty { null },
                number = row.selectFirst(".card-list-item-number-entry")?.text()?.trim().orEmpty(),
                rarity = row.selectFirst(".card-list-item-rarity-entry img")
                    ?.let { it.attr("alt").ifBlank { it.attr("title") } }?.trim()?.ifEmpty { null },
                path = link.attr("href"),
                imageUrl = null,
                regionId = (regions?.get(key) as? JsonPrimitive)?.intOrNull,
                variants = variantsOf(key, variantIds, variantTypes, collection),
            )
        }
        if (cards.isEmpty() && doc.selectFirst("[data-card-id]") != null) {
            throw PageFormatException("search result rows could not be read")
        }
        if (cards.isNotEmpty() && appState != null && variantIds == null) {
            throw PageFormatException("no card variants in appState")
        }
        return cards
    }

    /** Card ID → image URL from an image-view page (`displayAs=images`). */
    fun parseImagePage(html: String): Map<Int, String> {
        val doc = Jsoup.parse(html)
        return doc.select("a.card-image-grid-item-link").mapNotNull { link ->
            val id = CARD_ID_IN_PATH.find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val src = link.selectFirst("img.card-image-grid-item-image")?.attr("src")?.ifEmpty { null } ?: return@mapNotNull null
            id to src
        }.toMap()
    }

    private fun variantsOf(
        cardKey: String,
        variantIds: JsonObject?,
        variantTypes: JsonObject?,
        collection: JsonObject?,
    ): List<CardVariant> {
        val ids = (variantIds?.get(cardKey) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.orEmpty()
        val entries = ((collection?.get(cardKey) as? JsonObject)?.get("entries") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }.orEmpty()
        return ids.map { variantId ->
            val type = variantTypes?.get(variantId.toString()) as? JsonObject
            val name = (type?.get("name") as? JsonPrimitive)?.content ?: "#$variantId"
            var english = 0
            var other = 0
            entries.filter { it.int("cardVariantTypeId") == variantId }.forEach { entry ->
                val count = entry.int("cardCount") ?: 0
                if (entry.int("cardLanguageId") == TcgIds.LANGUAGE_ENGLISH) english += count else other += count
            }
            CardVariant(variantId, name, english, other)
        }
    }

    private fun JsonObject?.objectAt(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
