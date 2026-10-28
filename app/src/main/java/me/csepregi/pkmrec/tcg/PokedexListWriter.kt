package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.csepregi.pkmrec.collection.CollectionLog
import me.csepregi.pkmrec.diagnostics.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

sealed interface ListWriteOutcome {
    /** Done and checked: the list's cards for the Pokémon now. */
    data class Done(val after: PokedexListCards) : ListWriteOutcome
    data object NotLoggedIn : ListWriteOutcome

    /** TCG Collector answered, but the reloaded list does not show the change. */
    data object NotApplied : ListWriteOutcome

    /** The new card is in the list, but the old one(s) could not be taken out. */
    data class OldNotRemoved(val after: PokedexListCards?) : ListWriteOutcome
    data class Error(val error: TcgError) : ListWriteOutcome
}

/**
 * Changes which card of a Pokémon is in the user's Pokedex list, the way the website does:
 * `PUT /user-card-lists/<listId>/entries/bulk-replace` with `{cardId, isCardInUserCardList,
 * exclusiveCardVariantTypeIds: []}` (any variant), with the page's CSRF token. The new card goes
 * in first, then the old ones go out, so a failure never leaves the Pokémon without a card.
 */
object PokedexListWriter {

    /**
     * Puts [putIn] into the list, then takes [takeOut] out of it (the Pokémon's other cards when
     * swapping; the reverse for undo). Each step is checked against the reloaded list.
     */
    suspend fun change(context: Context, entry: PokedexEntry, putIn: List<TcgCard>, takeOut: List<TcgCard>): ListWriteOutcome =
        withContext(Dispatchers.IO) {
            val listId = PokedexList.listId(context)
            val referer = (putIn + takeOut).first().url
            try {
                val client = TcgSession.client(context)
                val appState = client.fetchPage(referer).requireAppState()
                if (TcgUser.fromAppState(appState) == null) return@withContext ListWriteOutcome.NotLoggedIn
                val csrf = csrf(appState)

                var now: PokedexListCards? = null
                if (putIn.isNotEmpty()) {
                    putIn.forEach { send(client, listId, it, inList = true, csrf) }
                    val added = PokedexList.listCards(client, entry, listId) ?: return@withContext ListWriteOutcome.NotLoggedIn
                    val missing = putIn.filter { p -> added.cards.none { it.id == p.id } }
                    putIn.filter { it !in missing }.forEach { log(context, "list-add", it) }
                    if (missing.isNotEmpty()) {
                        ErrorLog.log("pokedex list add", IllegalStateException("not in list $listId: ${missing.map { it.id }}"), referer)
                        return@withContext ListWriteOutcome.NotApplied
                    }
                    now = added
                }

                val toRemove = takeOut.filter { o -> putIn.none { it.id == o.id } }
                if (toRemove.isNotEmpty()) {
                    toRemove.forEach { send(client, listId, it, inList = false, csrf) }
                    val after = PokedexList.listCards(client, entry, listId)
                        ?: return@withContext if (putIn.isEmpty()) ListWriteOutcome.NotLoggedIn else ListWriteOutcome.OldNotRemoved(null)
                    val left = toRemove.filter { o -> after.cards.any { it.id == o.id } }
                    toRemove.filter { it !in left }.forEach { log(context, "list-remove", it) }
                    if (left.isNotEmpty()) {
                        ErrorLog.log("pokedex list remove", IllegalStateException("still in list $listId: ${left.map { it.id }}"), referer)
                        return@withContext if (putIn.isEmpty()) ListWriteOutcome.NotApplied else ListWriteOutcome.OldNotRemoved(after)
                    }
                    now = after
                }
                ListWriteOutcome.Done(now ?: PokedexListCards(entry, emptyList()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.log("pokedex list change", e, referer)
                ListWriteOutcome.Error(TcgError.of(e))
            }
        }

    private fun send(client: TcgClient, listId: Int, card: TcgCard, inList: Boolean, csrf: Pair<String, String>) {
        client.sendJson("PUT", url(listId), body(card.id, inList), mapOf(csrf), referer = card.url)
    }

    fun url(listId: Int) = "${Tcg.BASE_URL}/user-card-lists/$listId/entries/bulk-replace"

    /** The request body as the website sends it; an empty variant list means any variant. */
    fun body(cardId: Int, inList: Boolean): String = buildJsonArray {
        add(buildJsonObject {
            put("cardId", cardId)
            put("isCardInUserCardList", inList)
            putJsonArray("exclusiveCardVariantTypeIds") {}
        })
    }.toString()

    private fun csrf(appState: JsonObject): Pair<String, String> {
        val header = (appState["csrfTokenHttpHeaderKey"] as? JsonPrimitive)?.contentOrNull
            ?: throw PageFormatException("no CSRF header name")
        val token = (appState["csrfToken"] as? JsonPrimitive)?.contentOrNull
            ?: throw PageFormatException("no CSRF token")
        return header to token
    }

    private fun log(context: Context, action: String, card: TcgCard) {
        val inList = if (action == "list-add") 1 else 0
        CollectionLog.log(context, CollectionLog.Entry.now(action, card, "Pokedex lista", 1 - inList, inList))
    }
}
