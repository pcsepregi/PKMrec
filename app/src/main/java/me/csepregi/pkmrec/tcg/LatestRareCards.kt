package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

sealed interface RareCardsResult {
    data class Found(val cards: List<TcgCard>) : RareCardsResult
    data object NotLoggedIn : RareCardsResult
    data class Error(val error: TcgError) : RareCardsResult
}

/**
 * A Pokémon's newest full art and double rare cards (read-only), shown under a common, uncommon
 * or rare Pokedex list card: the Pokédex page lists all the Pokémon's cards, newest set first.
 */
object LatestRareCards {

    const val MAX_CARDS = 5

    /** Rarities of the list card that get the section. */
    val PLAIN_RARITIES = setOf("Common", "Uncommon", "Rare")

    /** Rarities that count as full art or double rare, as TCG Collector names them (newer and older eras). */
    val RARE_RARITIES = setOf(
        "Double Rare", "Ultra Rare", "Illustration Rare", "Special Illustration Rare", "Hyper Rare",
        "Mega Hyper Rare", "Shiny Rare", "Shiny Ultra Rare",
        "Rare Ultra", "Rare Secret", "Rare Rainbow", "Rare Shiny", "Rare Shiny GX",
        "Holo Rare V", "Holo Rare VMAX", "Holo Rare VSTAR", "Rare Holo GX", "Rare Holo EX",
    )

    fun shouldShow(listCards: List<TcgCard>): Boolean = listCards.any { it.rarity in PLAIN_RARITIES }

    /** The first [MAX_CARDS] English cards of a rare rarity, in the page's order (newest first). */
    fun pick(cards: List<TcgCard>): List<TcgCard> = cards
        .filter { it.rarity in RARE_RARITIES && (it.regionId == null || it.regionId == TcgIds.REGION_INTERNATIONAL) }
        .take(MAX_CARDS)

    suspend fun lookup(context: Context, number: Int, slug: String): RareCardsResult = withContext(Dispatchers.IO) {
        val base = "${Tcg.BASE_URL}/pokedex/$number/$slug/intl?releaseDateOrder=newToOld"
        try {
            val client = TcgSession.client(context)
            val listPage = client.fetchPage("$base&displayAs=list")
            val state = listPage.requireAppState()
            if (TcgUser.fromAppState(state) == null) return@withContext RareCardsResult.NotLoggedIn
            val cards = pick(TcgSearchParser.parseListPage(listPage.html, state))
            val images = if (cards.isEmpty()) emptyMap() else
                runCatching { TcgSearchParser.parseImagePage(client.fetchPage("$base&displayAs=images").html) }
                    .onFailure { ErrorLog.log("rare card images", it, base) }
                    .getOrDefault(emptyMap())
            RareCardsResult.Found(cards.map { it.copy(imageUrl = images[it.id]) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("rare cards", e, base)
            RareCardsResult.Error(TcgError.of(e))
        }
    }
}
