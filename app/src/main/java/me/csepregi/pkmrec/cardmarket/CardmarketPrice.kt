package me.csepregi.pkmrec.cardmarket

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Locale

/** Cardmarket website constants. */
object Cardmarket {
    const val BASE_URL = "https://www.cardmarket.com"

    /** Language filter value for English offers. */
    const val LANGUAGE_ENGLISH = 1

    /** Condition filter value for "Good" (offers in Good or better condition). */
    const val MIN_CONDITION_GOOD = 4

    /** Search by name; Cardmarket ignores set codes and numbers in the search text. [page] starts at 1. */
    fun searchUrl(query: String, page: Int = 1): String =
        "$BASE_URL/en/Pokemon/Products/Search?category=-1&searchString=" + URLEncoder.encode(query.trim(), "UTF-8") +
            if (page > 1) "&site=$page" else ""

    /** The product page with the English / Good-or-better offer filter applied. */
    fun filteredOffersUrl(productUrl: String): String {
        val base = absolute(productUrl).substringBefore('#').substringBefore('?')
        return "$base?language=$LANGUAGE_ENGLISH&minCondition=$MIN_CONDITION_GOOD"
    }

    fun absolute(url: String): String = if (url.startsWith("/")) BASE_URL + url else url

    fun isProductPage(url: String): Boolean = url.contains("/Products/Singles/")

    /** A sealed product page, e.g. /en/Pokemon/Products/Elite-Trainer-Boxes/Surging-Sparks-Elite-Trainer-Box. */
    fun isSealedProductPage(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("/Products/", "")
        val parts = path.split('/').filter { it.isNotEmpty() }
        return parts.size >= 2 && parts[0] != "Singles" && parts[0] != "Search"
    }

    /** The product page with only the English filter: sealed products have no condition. */
    fun englishOffersUrl(productUrl: String): String {
        val base = absolute(productUrl).substringBefore('#').substringBefore('?')
        return "$base?language=$LANGUAGE_ENGLISH"
    }

    /** Opens a product by its catalogue id: Cardmarket redirects to the product page (checked on 1.0.2). */
    fun productByIdUrl(idProduct: Int): String = "$BASE_URL/en/Pokemon/Products?idProduct=$idProduct"
}

/** One offer row read from a product page. Fields are null when the row could not be read. */
data class CardmarketOffer(
    val price: Double?,
    val condition: String?,
    val language: String?,
    val reverseHolo: Boolean = false,
)

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
    /** Offers left after the language / condition / reverse check, cheapest first. */
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
     * English offers in Good or better condition (reverse holo ones only when [reverseHolo]),
     * cheapest first; skip the [SKIP_CHEAPEST] cheapest and average the next [AVERAGE_OF].
     * With fewer offers, the average uses what is left after skipping; with nothing left, there is no average.
     */
    fun estimate(offers: List<CardmarketOffer>, reverseHolo: Boolean = false): PriceEstimate {
        val eligible = offers
            .filter { it.language == null || it.language.equals("English", ignoreCase = true) }
            .filter { it.condition == null || CardCondition.isGoodOrBetter(it.condition) }
            .filter { it.reverseHolo == reverseHolo }
            .mapNotNull { it.price }
            .sorted()
        val counted = eligible.drop(SKIP_CHEAPEST).take(AVERAGE_OF)
        return PriceEstimate(counted.takeIf { it.isNotEmpty() }?.average(), eligible, counted)
    }

    fun format(value: Double): String = String.format(Locale.ROOT, "%.2f €", value).replace('.', ',')
}

/** A product link on a Cardmarket page: its label (e.g. "Lechonk (OBF 209)") and href. */
data class ProductLink(val label: String, val href: String)

/** Finds the Cardmarket product of a TCG Collector card by set code and number. */
object CardmarketMatcher {

    /** "Lechonk (OBF 209)", "Pikachu (HIF 19)", "Charizard (SV-P 044)": set code and number in brackets. */
    private val CODE_AND_NUMBER = Regex("""\(([A-Za-z0-9-]+)\s+([A-Za-z]*\d+[A-Za-z]*)\)""")

    /**
     * TCG Collector set codes that Cardmarket writes differently (TCG Collector → Cardmarket).
     * Filled in from real reports when a set does not match.
     */
    private val CODE_ALIASES: Map<String, String> = emptyMap()

    fun codeAndNumber(label: String): Pair<String, String>? =
        CODE_AND_NUMBER.find(label)?.let { it.groupValues[1] to it.groupValues[2] }

    /** "071" and "71" are the same number; letters ("TG05", "SV049") are kept. "209/197" → "209". */
    fun normalizeNumber(number: String): String {
        val n = number.substringBefore('/').trim().uppercase(Locale.ROOT)
        val match = Regex("""^([A-Z]*)0*(\d+)([A-Z]*)$""").find(n) ?: return n
        return match.groupValues[1] + match.groupValues[2] + match.groupValues[3]
    }

    private fun normalizeCode(code: String): String {
        val c = code.trim().uppercase(Locale.ROOT)
        return CODE_ALIASES[c] ?: c
    }

    fun matches(label: String, setCode: String, number: String): Boolean {
        val (code, num) = codeAndNumber(label) ?: return false
        return code.uppercase(Locale.ROOT) == normalizeCode(setCode) && normalizeNumber(num) == normalizeNumber(number)
    }

    fun find(links: List<ProductLink>, setCode: String, number: String): ProductLink? =
        links.firstOrNull { Cardmarket.isProductPage(it.href) && matches(it.label, setCode, number) }
}

/** What the page extractor script saw on a Cardmarket page. */
data class CardmarketPage(
    val url: String,
    val title: String,
    val challenge: Boolean,
    val idProduct: Int?,
    val productName: String?,
    val rowCount: Int,
    val offers: List<CardmarketOffer>,
    val rawOffers: List<String>,
    val links: List<ProductLink>,
    val sample: String,
) {
    /** Product links on a search page, without duplicates and language switch links. */
    val productLinks: List<ProductLink>
        get() = links.filter { Cardmarket.isProductPage(it.href) && it.href.startsWith("/en/") }.distinctBy { it.href }

    /** Sealed product links on a search page. */
    val sealedLinks: List<ProductLink>
        get() = links.filter { Cardmarket.isSealedProductPage(it.href) && it.href.startsWith("/en/") }.distinctBy { it.href }

    companion object {
        /** evaluateJavascript returns a JSON-encoded value: a quoted JSON string from the extractor. */
        fun fromScriptResult(result: String?): CardmarketPage? = runCatching {
            val outer = Json.parseToJsonElement(result ?: return null)
            val inner = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val o = Json.parseToJsonElement(inner).jsonObject
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            val rows = (o["offers"] as? JsonArray).orEmpty().map { it.jsonObject }
            val links = (o["links"] as? JsonArray).orEmpty().map { it.jsonObject }
            CardmarketPage(
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
                        reverseHolo = row.text("reverse") == "true",
                    )
                },
                rawOffers = rows.map { row ->
                    listOf("price", "condition", "language", "reverse").joinToString(" | ") { row.text(it) ?: "?" }
                },
                links = links.mapNotNull { link ->
                    val href = link.text("href") ?: return@mapNotNull null
                    ProductLink(link.text("label").orEmpty().replace(Regex("""\s+"""), " ").trim(), href)
                },
                sample = str("sample").orEmpty(),
            )
        }.getOrNull()

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }
}

/**
 * Reads what a Cardmarket page shows: Cloudflare challenge, product id and name, offer rows
 * (price, condition, language, reverse holo) and product links. The first row's HTML is
 * returned too, so selectors can be fixed from a debug report.
 */
const val CARDMARKET_EXTRACT_JS = """
(function () {
  function txt(e) { return e ? (e.innerText || e.textContent || '').trim() : ''; }
  var langs = ['English','German','French','Italian','Spanish','Japanese','Portuguese','Korean','Chinese','S-Chinese','T-Chinese','Russian','Dutch','Polish'];
  var conds = ['MT','NM','EX','GD','LP','PL','PO'];
  var title = document.title || '';
  var challenge = /just a moment|attention required|verify you are human/i.test(title) || !!document.querySelector('#challenge-form, #challenge-running, .cf-turnstile, #cf-challenge-running');
  var html = document.documentElement.innerHTML;
  var idm = html.match(/idProduct["'\s]*[:=]\s*["']?(\d+)/i);
  var h1 = document.querySelector('h1');
  var rows = document.querySelectorAll('.article-row, [id^="articleRow"]');
  var offers = [];
  for (var i = 0; i < rows.length && i < 60; i++) {
    var r = rows[i];
    var priceEl = r.querySelector('.col-offer .price-container, .price-container, .col-price');
    var priceText = txt(priceEl);
    if (!/€/.test(priceText)) { var m = txt(r).match(/\d[\d.]*,\d{2}\s*€/); priceText = m ? m[0] : ''; }
    var cond = '';
    var badges = r.querySelectorAll('.article-condition, .badge, [class*="condition"]');
    for (var b = 0; b < badges.length && !cond; b++) { var t = txt(badges[b]).toUpperCase(); if (conds.indexOf(t) >= 0) cond = t; }
    var lang = '';
    var reverse = false;
    var attrs = r.querySelectorAll('[aria-label], [title], [data-original-title], [data-bs-original-title]');
    for (var a = 0; a < attrs.length; a++) {
      var el = attrs[a];
      var vals = [el.getAttribute('aria-label'), el.getAttribute('title'), el.getAttribute('data-original-title'), el.getAttribute('data-bs-original-title')];
      for (var v = 0; v < vals.length; v++) {
        var val = (vals[v] || '').trim();
        if (!lang && langs.indexOf(val) >= 0) lang = val;
        if (/^reverse( holo)?$/i.test(val)) reverse = true;
      }
    }
    offers.push({ price: priceText, condition: cond, language: lang, reverse: reverse ? 'true' : 'false' });
  }
  var links = [];
  var as = document.querySelectorAll('a[href*="/Products/"]');
  for (var k = 0; k < as.length && links.length < 300; k++) {
    var label = txt(as[k]);
    if (label) links.push({ label: label, href: as[k].getAttribute('href') });
  }
  var sample = rows.length ? rows[0].outerHTML : (document.body ? document.body.innerHTML : '');
  return JSON.stringify({
    url: location.href, title: title, challenge: challenge ? 'true' : 'false',
    idProduct: idm ? parseInt(idm[1], 10) : null, productName: txt(h1),
    rowCount: rows.length, offers: offers, links: links, sample: sample.substring(0, 2500)
  });
})();
"""

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
