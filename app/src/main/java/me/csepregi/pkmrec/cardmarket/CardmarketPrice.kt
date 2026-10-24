package me.csepregi.pkmrec.cardmarket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/** Cardmarket website constants. */
object Cardmarket {
    const val BASE_URL = "https://www.cardmarket.com"

    /** Language filter value for English offers. */
    const val LANGUAGE_ENGLISH = 1

    /** Condition filter value for "Good" (offers in Good or better condition). */
    const val MIN_CONDITION_GOOD = 4

    /** Cardmarket's public daily price guide for Pokémon (game 6), one entry per product. */
    const val PRICE_GUIDE_URL = "https://downloads.s3.cardmarket.com/productCatalog/priceGuide/price_guide_6.json"

    fun searchUrl(query: String): String =
        "$BASE_URL/en/Pokemon/Products/Search?category=-1&searchString=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")

    /** The product page with the English / Good-or-better offer filter applied. */
    fun filteredOffersUrl(productUrl: String): String {
        val base = productUrl.substringBefore('#').substringBefore('?')
        return "$base?language=$LANGUAGE_ENGLISH&minCondition=$MIN_CONDITION_GOOD"
    }

    fun isProductPage(url: String): Boolean = url.contains("/Products/Singles/")
}

/** One offer row read from a product page. Fields are null when the row could not be read. */
data class CardmarketOffer(val price: Double?, val condition: String?, val language: String?)

/** Cardmarket's condition grades, best first. */
object CardCondition {
    private val ranks = mapOf("MT" to 1, "NM" to 2, "EX" to 3, "GD" to 4, "LP" to 5, "PL" to 6, "PO" to 7)

    fun rank(code: String?): Int? = code?.trim()?.uppercase(Locale.ROOT)?.let { ranks[it] }

    fun isGoodOrBetter(code: String?): Boolean = rank(code)?.let { it <= 4 } ?: false
}

/** Result of Pali's price rule on a list of offers. */
data class PriceEstimate(
    /** Average of the counted offers, or null when there are not enough offers. */
    val average: Double?,
    /** Offers left after the language / condition check, cheapest first. */
    val eligible: List<Double>,
    /** The prices that went into the average. */
    val counted: List<Double>,
)

object CardmarketPrice {
    const val SKIP_CHEAPEST = 2
    const val AVERAGE_OF = 5

    /** Reads "1.234,56 €" or "0,10 €" (Cardmarket's English site uses comma decimals). */
    fun parsePrice(text: String?): Double? {
        val match = Regex("""(\d{1,3}(?:[.\s]\d{3})*|\d+)(?:,(\d{1,2}))?\s*€""").find(text ?: return null) ?: return null
        val whole = match.groupValues[1].replace(".", "").replace(" ", "")
        val cents = match.groupValues[2].ifEmpty { "0" }.padEnd(2, '0')
        return "$whole.$cents".toDoubleOrNull()
    }

    /**
     * English offers in Good or better condition, cheapest first; skip the [SKIP_CHEAPEST] cheapest
     * and average the next [AVERAGE_OF]. With fewer offers, the average uses what is left after
     * skipping; with no offer left, there is no price.
     */
    fun estimate(offers: List<CardmarketOffer>): PriceEstimate {
        val eligible = offers
            .filter { it.language == null || it.language.equals("English", ignoreCase = true) }
            .filter { it.condition == null || CardCondition.isGoodOrBetter(it.condition) }
            .mapNotNull { it.price }
            .sorted()
        val counted = eligible.drop(SKIP_CHEAPEST).take(AVERAGE_OF)
        return PriceEstimate(counted.takeIf { it.isNotEmpty() }?.average(), eligible, counted)
    }

    fun format(value: Double): String = String.format(Locale.ROOT, "%.2f €", value).replace('.', ',')
}

/** What the page extractor script saw on a Cardmarket page. */
data class ProbePage(
    val url: String,
    val title: String,
    val challenge: Boolean,
    val idProduct: Int?,
    val productName: String?,
    val rowCount: Int,
    val offers: List<CardmarketOffer>,
    val rawOffers: List<String>,
    val links: List<String>,
    val sample: String,
) {
    companion object {
        /** evaluateJavascript returns a JSON-encoded value: a quoted JSON string from the extractor. */
        fun fromScriptResult(result: String?): ProbePage? = runCatching {
            val outer = Json.parseToJsonElement(result ?: return null)
            val inner = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val o = Json.parseToJsonElement(inner).jsonObject
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            fun strings(key: String) = (o[key] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            val rows = (o["offers"] as? JsonArray).orEmpty().map { it.jsonObject }
            ProbePage(
                url = str("url").orEmpty(),
                title = str("title").orEmpty(),
                challenge = str("challenge") == "true",
                idProduct = (o["idProduct"] as? JsonPrimitive)?.intOrNull,
                productName = str("productName"),
                rowCount = (o["rowCount"] as? JsonPrimitive)?.intOrNull ?: 0,
                offers = rows.map { row ->
                    CardmarketOffer(
                        price = CardmarketPrice.parsePrice(row.text("price")),
                        condition = row.text("condition"),
                        language = row.text("language"),
                    )
                },
                rawOffers = rows.map { row -> listOf("price", "condition", "language").joinToString(" | ") { row.text(it) ?: "?" } },
                links = strings("links"),
                sample = str("sample").orEmpty(),
            )
        }.getOrNull()

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/** Entry of Cardmarket's public price guide for one product. */
data class PriceGuideEntry(val idProduct: Int, val fields: Map<String, String>)

object PriceGuide {
    /** Finds one product's entry in the price guide JSON without parsing the whole file. */
    fun find(json: String, idProduct: Int): PriceGuideEntry? {
        val start = Regex(""""idProduct"\s*:\s*$idProduct\s*[,}]""").find(json)?.range?.first ?: return null
        val open = json.lastIndexOf('{', start)
        val close = json.indexOf('}', start)
        if (open < 0 || close < 0) return null
        val obj = runCatching { Json.parseToJsonElement(json.substring(open, close + 1)).jsonObject }.getOrNull() ?: return null
        return PriceGuideEntry(idProduct, obj.mapValues { it.value.toString().trim('"') })
    }

    /** Number of products in the guide, for the report. */
    fun count(json: String): Int = Regex(""""idProduct"\s*:""").findAll(json).count()
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
