package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import me.csepregi.pkmrec.diagnostics.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

sealed interface MyCardsResult {
    data class Found(val cards: List<TcgCard>) : MyCardsResult
    data object NotLoggedIn : MyCardsResult
    data class Error(val error: TcgError) : MyCardsResult
}

/**
 * Every English card of one Pokémon that the user owns (read-only): the Pokédex page lists all the
 * Pokémon's cards with the user's collection counts, newest set first; the owned ones are kept.
 */
object MyCards {

    /** Safety cap; the Pokédex list view normally fits on one page. */
    private const val MAX_PAGES = 10

    /** Owned English copies, in the page's order (newest first). */
    fun pick(cards: List<TcgCard>): List<TcgCard> = cards
        .distinctBy { it.id }
        .filter { card ->
            (card.regionId == null || card.regionId == TcgIds.REGION_INTERNATIONAL) && card.variants.any { it.isOwned }
        }

    suspend fun lookup(context: Context, number: Int, slug: String): MyCardsResult = withContext(Dispatchers.IO) {
        val base = "${Tcg.BASE_URL}/pokedex/$number/$slug/intl?releaseDateOrder=newToOld"
        try {
            val client = TcgSession.client(context)
            val owned = mutableListOf<TcgCard>()
            val pages = mutableSetOf<Int>()
            var page = 1
            while (page <= MAX_PAGES) {
                val listPage = client.fetchPage(pageUrl(base, "list", page))
                val state = listPage.requireAppState()
                if (TcgUser.fromAppState(state) == null) return@withContext MyCardsResult.NotLoggedIn
                val found = TcgSearchParser.parseListPage(listPage.html, state)
                val mine = pick(found).filter { card -> owned.none { it.id == card.id } }
                if (mine.isNotEmpty()) pages += page
                owned += mine
                val hasMore = (state["hasMultiplePages"] as? JsonPrimitive)?.content == "true"
                if (!hasMore || found.isEmpty()) break
                page++
            }
            val images = mutableMapOf<Int, String>()
            pages.sorted().forEach { p ->
                runCatching { images += TcgSearchParser.parseImagePage(client.fetchPage(pageUrl(base, "images", p)).html) }
                    .onFailure { ErrorLog.log("my cards images", it, base) }
            }
            MyCardsResult.Found(owned.map { it.copy(imageUrl = images[it.id]) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("my cards", e, base)
            MyCardsResult.Error(TcgError.of(e))
        }
    }

    private fun pageUrl(base: String, displayAs: String, page: Int): String =
        "$base&displayAs=$displayAs" + if (page > 1) "&page=$page" else ""
}
