package me.csepregi.pkmrec.cardmarket

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgErrorKind
import kotlin.coroutines.cancellation.CancellationException

/** The Cardmarket price of one card, or why there is none. */
sealed interface CardmarketResult {
    /** Average by Pali's rule; [eligible] = English, Good-or-better offers seen (≥ 3). */
    data class Price(val average: Double, val counted: Int, val eligible: Int, val productUrl: String) : CardmarketResult

    /** Only 1–2 offers: the cheapest is shown as a guide only. */
    data class FewOffers(val lowest: Double, val eligible: Int, val productUrl: String) : CardmarketResult

    data class NoOffers(val productUrl: String) : CardmarketResult

    /** No product with the card's set code and number among the search results. */
    data class NotFound(val searchUrl: String) : CardmarketResult

    /** Cardmarket asks for a Cloudflare check; it has to be opened visibly once. */
    data class Challenge(val url: String) : CardmarketResult

    data class Failed(val kind: Kind, val detail: String) : CardmarketResult

    /** Loading failed, but an older price ([Price], [FewOffers] or [NoOffers]) from [savedAt] is known. */
    data class Stale(val previous: CardmarketResult, val savedAt: Long, val failure: Failed) : CardmarketResult

    enum class Kind { NO_INTERNET, TIMEOUT, SITE_CHANGED, OTHER }
}

@Serializable
private data class CachedPrice(
    val kind: String,
    val value: Double = 0.0,
    val counted: Int = 0,
    val eligible: Int = 0,
    val productUrl: String,
    val time: Long,
)

/**
 * Finds a TCG Collector card on Cardmarket and computes its price: search by name, pick the
 * product whose label has the card's set code and number, read the English / Good-or-better
 * offers and apply Pali's rule. Product links are kept for good, prices for [PRICE_TTL_MS].
 */
object CardmarketPrices {

    private const val PREFS = "cardmarket"
    private const val KEY_ENABLED = "enabled"
    private const val PRICE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_SEARCH_PAGES = 5

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether card screens show the Cardmarket price (default: on). */
    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** A cached price that is still fresh, without loading anything. */
    fun cached(context: Context, card: TcgCard, reverseHolo: Boolean): CardmarketResult? {
        val (result, time) = decodeCached(prefs(context).getString(priceKey(card, reverseHolo), null)) ?: return null
        return result.takeIf { System.currentTimeMillis() - time <= PRICE_TTL_MS }
    }

    /** A stored price and the time it was saved, however old; null if [raw] is missing or unreadable. */
    internal fun decodeCached(raw: String?): Pair<CardmarketResult, Long>? {
        val cached = runCatching { json.decodeFromString(CachedPrice.serializer(), raw ?: return null) }.getOrNull() ?: return null
        val result = when (cached.kind) {
            "price" -> CardmarketResult.Price(cached.value, cached.counted, cached.eligible, cached.productUrl)
            "few" -> CardmarketResult.FewOffers(cached.value, cached.eligible, cached.productUrl)
            "none" -> CardmarketResult.NoOffers(cached.productUrl)
            else -> return null
        }
        return result to cached.time
    }

    /**
     * Forgets the card's remembered Cardmarket product and prices, so the next [price] call
     * searches for the product again (e.g. after a wrong match).
     */
    fun forgetProduct(context: Context, card: TcgCard) {
        prefs(context).edit()
            .remove(productKey(card))
            .remove(priceKey(card, false))
            .remove(priceKey(card, true))
            .apply()
    }

    suspend fun price(context: Context, card: TcgCard, reverseHolo: Boolean, force: Boolean = false): CardmarketResult {
        if (!force) cached(context, card, reverseHolo)?.let { return it }
        val result = load(context, card, reverseHolo)
        if (result !is CardmarketResult.Failed) return result
        // Rather an older price with its date than only an error.
        val (previous, savedAt) = decodeCached(prefs(context).getString(priceKey(card, reverseHolo), null)) ?: return result
        return CardmarketResult.Stale(previous, savedAt, result)
    }

    private suspend fun load(context: Context, card: TcgCard, reverseHolo: Boolean): CardmarketResult {
        return try {
            val productUrl = productUrl(context, card) ?: return CardmarketResult.NotFound(Cardmarket.searchUrl(card.name))
            val page = CardmarketFetcher.load(context, Cardmarket.filteredOffersUrl(productUrl))
            val result = fromOffers(page, reverseHolo, productUrl)
            store(context, card, reverseHolo, result)
            if (result is CardmarketResult.Failed) ErrorLog.log("cardmarket", null, "${result.detail} for ${describe(card)}")
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: CardmarketChallengeException) {
            CardmarketResult.Challenge(e.url)
        } catch (e: Exception) {
            ErrorLog.log("cardmarket", e, describe(card))
            failure(e)
        }
    }

    /** Pali's rule on a product page: ≥ 3 offers → average, 1–2 → cheapest as a guide, none → no offers. */
    fun fromOffers(page: CardmarketPage, reverseHolo: Boolean, productUrl: String): CardmarketResult {
        if (page.rowCount > 0 && page.offers.all { it.price == null }) {
            return CardmarketResult.Failed(CardmarketResult.Kind.SITE_CHANGED, "offer rows without price on ${page.url}")
        }
        val estimate = CardmarketPrice.estimate(page.offers, reverseHolo)
        val average = estimate.average
        return when {
            average != null -> CardmarketResult.Price(average, estimate.counted.size, estimate.eligible.size, productUrl)
            estimate.eligible.isNotEmpty() -> CardmarketResult.FewOffers(estimate.eligible.first(), estimate.eligible.size, productUrl)
            else -> CardmarketResult.NoOffers(productUrl)
        }
    }

    /** The card's Cardmarket product page: remembered, or found through the name search. */
    private suspend fun productUrl(context: Context, card: TcgCard): String? {
        val key = productKey(card)
        prefs(context).getString(key, null)?.let { return it }
        val code = card.expansionCode ?: return null
        for (n in 1..MAX_SEARCH_PAGES) {
            val page = CardmarketFetcher.load(context, Cardmarket.searchUrl(card.name, n))
            // A search with a single result can land straight on the product page.
            if (Cardmarket.isProductPage(page.url)) {
                val found = page.productName?.let { CardmarketMatcher.matches(it, code, card.number) } == true
                return if (found) remember(context, key, page.url) else null
            }
            val links = page.productLinks
            CardmarketMatcher.find(links, code, card.number)?.let { return remember(context, key, Cardmarket.absolute(it.href)) }
            if (links.none { CardmarketMatcher.codeAndNumber(it.label) != null }) break
        }
        return null
    }

    private fun remember(context: Context, key: String, url: String): String {
        val clean = url.substringBefore('#').substringBefore('?')
        prefs(context).edit().putString(key, clean).apply()
        return clean
    }

    private fun store(context: Context, card: TcgCard, reverseHolo: Boolean, result: CardmarketResult) {
        val now = System.currentTimeMillis()
        val cached = when (result) {
            is CardmarketResult.Price -> CachedPrice("price", result.average, result.counted, result.eligible, result.productUrl, now)
            is CardmarketResult.FewOffers -> CachedPrice("few", result.lowest, 0, result.eligible, result.productUrl, now)
            is CardmarketResult.NoOffers -> CachedPrice("none", productUrl = result.productUrl, time = now)
            else -> return
        }
        prefs(context).edit().putString(priceKey(card, reverseHolo), json.encodeToString(CachedPrice.serializer(), cached)).apply()
    }

    private fun failure(e: Exception): CardmarketResult.Failed {
        val kind = when {
            e is CardmarketPageException -> CardmarketResult.Kind.SITE_CHANGED
            else -> when (TcgError.kindOf(e)) {
                TcgErrorKind.NO_INTERNET -> CardmarketResult.Kind.NO_INTERNET
                TcgErrorKind.TIMEOUT -> CardmarketResult.Kind.TIMEOUT
                else -> CardmarketResult.Kind.OTHER
            }
        }
        return CardmarketResult.Failed(kind, e.message ?: e.javaClass.simpleName)
    }

    private fun describe(card: TcgCard) = "${card.name} ${card.expansionCode} ${card.number}"

    private fun productKey(card: TcgCard) = "product:${card.id}"

    private fun priceKey(card: TcgCard, reverseHolo: Boolean) = "price:${card.id}:" + if (reverseHolo) "r" else "n"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
