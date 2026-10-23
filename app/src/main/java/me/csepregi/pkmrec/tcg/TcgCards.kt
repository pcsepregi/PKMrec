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
) {
    val url: String get() = Tcg.BASE_URL + path
}

/**
 * Parses TCG Collector card search pages:
 * the list view (name, expansion, set code, number), the image view (card images)
 * and the embedded appState (region, variants, collection entries).
 */
object TcgSearchParser {

    private val CARD_ID_IN_PATH = Regex("""/cards/(\d+)/""")

    /** Cards of a list-view page (`displayAs=list`) combined with its appState. */
    fun parseListPage(html: String, appState: JsonObject?): List<TcgCard> {
        val doc = Jsoup.parse(html)
        val regions = appState.objectAt("cardIdToTcgRegionIdMap")
        val variantIds = appState.objectAt("cardIdToCardVariantTypeIdsMap")
        val variantTypes = appState.objectAt("idToCardVariantTypeDtoMap")
        val collection = appState.objectAt("cardIdToCardCollectionCardDtoMap")

        return doc.select("div.card-list-item[data-card-id]").mapNotNull { row ->
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
