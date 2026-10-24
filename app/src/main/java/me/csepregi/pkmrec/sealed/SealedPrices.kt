package me.csepregi.pkmrec.sealed

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

/**
 * Progress of "Árak frissítése": [done] of [total] products, [failed] of them without a new price.
 * [challengeUrl]: the round stopped at a Cloudflare check that has to be opened visibly.
 */
data class SealedRefreshProgress(
    val done: Int,
    val total: Int,
    val failed: Int,
    val running: Boolean,
    val challengeUrl: String? = null,
)

/**
 * Cardmarket prices of sealed products by Pali's rule: English offers, skip the 2 cheapest,
 * average the next 5 (no condition filter: the products are sealed). The product page is found
 * once through the name search and kept with the product.
 */
object SealedPrices {

    /**
     * Pause between two Cardmarket pages. Loaded back to back, Cloudflare took the refresh for a
     * robot and asked for a check every two or three products.
     */
    private const val PAGE_GAP_MS = 4_000L

    @Volatile private var lastPage = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    /** Start of the last round: a round resumed after a Cloudflare check skips what it already priced. */
    private var roundStart = 0L

    private val _progress = MutableStateFlow<SealedRefreshProgress?>(null)
    val progress: StateFlow<SealedRefreshProgress?> = _progress.asStateFlow()

    /** Loads the price of one product and stores it; returns the result to show. */
    suspend fun refresh(context: Context, item: SealedItem): CardmarketResult {
        val (result, imageUrl) = load(context, item)
        val productUrl = productUrlOf(result) ?: item.productUrl
        val price = toPrice(result)
        if (price != null || productUrl != item.productUrl || (imageUrl != null && imageUrl != item.imageUrl)) {
            SealedInventory.setPrice(context, item.id, price ?: item.price, productUrl, imageUrl)
        }
        val pictureUrl = imageUrl ?: item.imageUrl
        if (pictureUrl != null && result !is CardmarketResult.Challenge && SealedImages.file(context, item) == null) {
            downloadImage(context, item, pictureUrl)
        }
        return result
    }

    /** Downloads the picture through the Cardmarket WebView; a failure is kept to show on the product screen. */
    private suspend fun downloadImage(context: Context, item: SealedItem, url: String) {
        try {
            val image = paced { CardmarketFetcher.loadImage(context, url) }
            SealedInventory.setImage(context, item.id, SealedImages.save(context, item.id, image), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: CardmarketChallengeException) {
            // Tried again with the next refresh.
        } catch (e: Exception) {
            ErrorLog.log("sealed image", e, url)
            SealedInventory.setImage(context, item.id, null, "${e.message ?: e.javaClass.simpleName} ($url)")
        }
    }

    /** Loads a Cardmarket page, at least [PAGE_GAP_MS] after the previous one. */
    private suspend fun fetchPage(context: Context, url: String): CardmarketPage = paced { CardmarketFetcher.load(context, url) }

    private suspend fun <T> paced(block: suspend () -> T): T {
        val wait = lastPage + PAGE_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
        return try {
            block()
        } finally {
            lastPage = SystemClock.elapsedRealtime()
        }
    }

    /**
     * Refreshes every owned product one after the other; runs on even if the screen is left.
     * [resume]: continue a round stopped by a Cloudflare check, skipping products it already priced.
     */
    fun refreshAll(context: Context, resume: Boolean = false) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        if (!resume) roundStart = System.currentTimeMillis()
        val since = roundStart
        job = scope.launch {
            val items = SealedInventory.items.value.filter { it.count > 0 && (it.price?.time ?: 0L) < since }
            var failed = 0
            _progress.value = SealedRefreshProgress(0, items.size, 0, running = true)
            items.forEachIndexed { index, item ->
                val result = refresh(appContext, item)
                if (toPrice(result) == null) failed++
                _progress.value = SealedRefreshProgress(index + 1, items.size, failed, running = true)
                // A Cloudflare check stops the round: every next page would ask for it too.
                if (result is CardmarketResult.Challenge) {
                    _progress.value = SealedRefreshProgress(index + 1, items.size, failed, running = false, challengeUrl = result.url)
                    return@launch
                }
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

    /** The price result and the product picture seen on the offers page. */
    private suspend fun load(context: Context, item: SealedItem): Pair<CardmarketResult, String?> = try {
        val productUrl = item.productUrl ?: findProduct(context, item)
        if (productUrl == null) {
            CardmarketResult.NotFound(Cardmarket.searchUrl(item.name)) to null
        } else {
            val page = fetchPage(context, Cardmarket.englishOffersUrl(productUrl))
            val result = CardmarketPrices.fromOffers(page, reverseHolo = false, productUrl = productUrl)
            if (result is CardmarketResult.Failed) ErrorLog.log("sealed price", null, "${result.detail} for ${item.name}")
            result to page.imageUrl
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: CardmarketChallengeException) {
        CardmarketResult.Challenge(e.url) to null
    } catch (e: Exception) {
        ErrorLog.log("sealed price", e, item.name)
        CardmarketResult.Failed(CardmarketResult.Kind.OTHER, e.message ?: e.javaClass.simpleName) to null
    }

    /**
     * Opens the product by its catalogue id (Cardmarket redirects to the product page); if that
     * does not land on the product, searches by name and picks the result with the same name.
     */
    private suspend fun findProduct(context: Context, item: SealedItem): String? {
        val direct = fetchPage(context, Cardmarket.productByIdUrl(item.id))
        if (Cardmarket.isSealedProductPage(direct.url) && isSameProduct(direct, item)) return clean(direct.url)
        val page = fetchPage(context, Cardmarket.searchUrl(item.name))
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
