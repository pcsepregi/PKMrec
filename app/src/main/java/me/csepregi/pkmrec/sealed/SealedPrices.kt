package me.csepregi.pkmrec.sealed

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.cardmarket.Cardmarket
import me.csepregi.pkmrec.cardmarket.CardmarketChallengeException
import me.csepregi.pkmrec.cardmarket.CardmarketFetcher
import me.csepregi.pkmrec.cardmarket.CardmarketPage
import me.csepregi.pkmrec.cardmarket.CardmarketPrices
import me.csepregi.pkmrec.cardmarket.CardmarketResult
import me.csepregi.pkmrec.cardmarket.ProductLink
import me.csepregi.pkmrec.diagnostics.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

/** Progress of "Árak frissítése": [done] of [total] products, [failed] of them without a new price. */
data class SealedRefreshProgress(val done: Int, val total: Int, val failed: Int, val running: Boolean)

/**
 * Cardmarket prices of sealed products by Pali's rule: English offers, skip the 2 cheapest,
 * average the next 5 (no condition filter: the products are sealed). The product page is found
 * once through the name search and kept with the product.
 */
object SealedPrices {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    private val _progress = MutableStateFlow<SealedRefreshProgress?>(null)
    val progress: StateFlow<SealedRefreshProgress?> = _progress.asStateFlow()

    /** Loads the price of one product and stores it; returns the result to show. */
    suspend fun refresh(context: Context, item: SealedItem): CardmarketResult {
        val result = load(context, item)
        val productUrl = productUrlOf(result) ?: item.productUrl
        val price = toPrice(result)
        if (price != null || productUrl != item.productUrl) {
            SealedInventory.setPrice(context, item.id, price ?: item.price, productUrl)
        }
        return result
    }

    /** Refreshes every owned product one after the other; runs on even if the screen is left. */
    fun refreshAll(context: Context) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            val items = SealedInventory.items.value.filter { it.count > 0 }
            var failed = 0
            _progress.value = SealedRefreshProgress(0, items.size, 0, running = true)
            items.forEachIndexed { index, item ->
                val result = refresh(appContext, item)
                if (toPrice(result) == null) failed++
                _progress.value = SealedRefreshProgress(index + 1, items.size, failed, running = true)
                // A Cloudflare check stops the round: every next page would ask for it too.
                if (result is CardmarketResult.Challenge) return@launch finish(index + 1, items.size, failed)
            }
            finish(items.size, items.size, failed)
        }
    }

    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value?.copy(running = false)
    }

    private fun finish(done: Int, total: Int, failed: Int) {
        _progress.value = SealedRefreshProgress(done, total, failed, running = false)
    }

    private suspend fun load(context: Context, item: SealedItem): CardmarketResult = try {
        val productUrl = item.productUrl ?: findProduct(context, item)
        if (productUrl == null) {
            CardmarketResult.NotFound(Cardmarket.searchUrl(item.name))
        } else {
            val page = CardmarketFetcher.load(context, Cardmarket.englishOffersUrl(productUrl))
            CardmarketPrices.fromOffers(page, reverseHolo = false, productUrl = productUrl).also {
                if (it is CardmarketResult.Failed) ErrorLog.log("sealed price", null, "${it.detail} for ${item.name}")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: CardmarketChallengeException) {
        CardmarketResult.Challenge(e.url)
    } catch (e: Exception) {
        ErrorLog.log("sealed price", e, item.name)
        CardmarketResult.Failed(CardmarketResult.Kind.OTHER, e.message ?: e.javaClass.simpleName)
    }

    /**
     * Opens the product by its catalogue id (Cardmarket redirects to the product page); if that
     * does not land on the product, searches by name and picks the result with the same name.
     */
    private suspend fun findProduct(context: Context, item: SealedItem): String? {
        val direct = CardmarketFetcher.load(context, Cardmarket.productByIdUrl(item.id))
        if (Cardmarket.isSealedProductPage(direct.url) && isSameProduct(direct, item)) return clean(direct.url)
        val page = CardmarketFetcher.load(context, Cardmarket.searchUrl(item.name))
        if (Cardmarket.isSealedProductPage(page.url)) return clean(page.url).takeIf { isSameProduct(page, item) }
        return pick(page.sealedLinks, item.name)?.let { clean(Cardmarket.absolute(it.href)) }
    }

    private fun clean(url: String) = url.substringBefore('#').substringBefore('?')

    /** The page's product id when it shows one, else its title (the h1 also holds the category on a second line). */
    private fun isSameProduct(page: CardmarketPage, item: SealedItem): Boolean =
        page.idProduct?.let { it == item.id } ?: (page.productName?.lineSequence()?.firstOrNull()?.let { sameName(it, item.name) } ?: false)

    /** The search result whose label ("Surging Sparks Elite Trainer Box From 95,00 €") or link is the product's name. */
    internal fun pick(links: List<ProductLink>, name: String): ProductLink? =
        links.firstOrNull { sameName(it.label.substringBefore(" From "), name) } ?: links.firstOrNull { slugOf(it.href) == slug(name) }

    internal fun sameName(a: String, b: String): Boolean = SealedMatcher.words(a) == SealedMatcher.words(b)

    /** Cardmarket's link slug: "Pitch-Black-Elite-Trainer-Box" for "Pitch Black Elite Trainer Box". */
    internal fun slug(name: String): String = SealedMatcher.words(name).joinToString("-")

    private fun slugOf(href: String): String =
        SealedMatcher.words(href.substringBefore('?').substringAfterLast('/').replace('-', ' ')).joinToString("-")

    private fun productUrlOf(result: CardmarketResult): String? = when (result) {
        is CardmarketResult.Price -> result.productUrl
        is CardmarketResult.FewOffers -> result.productUrl
        is CardmarketResult.NoOffers -> result.productUrl
        else -> null
    }

    internal fun toPrice(result: CardmarketResult, now: Long = System.currentTimeMillis()): SealedPrice? = when (result) {
        is CardmarketResult.Price -> SealedPrice(SealedPrice.KIND_PRICE, result.average, result.eligible, now)
        is CardmarketResult.FewOffers -> SealedPrice(SealedPrice.KIND_FEW, result.lowest, result.eligible, now)
        is CardmarketResult.NoOffers -> SealedPrice(SealedPrice.KIND_NONE, time = now)
        else -> null
    }
}
