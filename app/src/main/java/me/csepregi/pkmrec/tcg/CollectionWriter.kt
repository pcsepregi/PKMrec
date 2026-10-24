package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.csepregi.pkmrec.collection.CollectionLog
import me.csepregi.pkmrec.diagnostics.ErrorLog
import kotlin.coroutines.cancellation.CancellationException

/** One entry of a card in the TCG Collector collection, exactly as the site stores it. */
data class CollectionEntry(
    val variantId: Int,
    val languageId: Int,
    val conditionId: Int,
    val note: String?,
    val count: Int,
)

/** What a card page tells before a write: the card's entries, the default condition and the CSRF token. */
data class CardCollectionState(
    val cardId: Int,
    val entries: List<CollectionEntry>,
    val languageId: Int,
    val conditionId: Int,
    val csrfHeader: String,
    val csrfToken: String,
) {
    /** English copies (or copies in the card region's default language) of one variant. */
    fun count(variantId: Int): Int = entries.filter { it.variantId == variantId && it.languageId == languageId }.sumOf { it.count }
}

sealed interface WriteOutcome {
    /** Done and checked by reloading the card: [before] are the entries to restore for undo, [newCount] the variant's count now. */
    data class Done(val before: List<CollectionEntry>, val oldCount: Int, val newCount: Int) : WriteOutcome
    data object NotLoggedIn : WriteOutcome

    /** TCG Collector answered, but the reloaded card does not show the change. */
    data object NotApplied : WriteOutcome

    /** Removing: the card page shows no copy of the variant (any more). */
    data object NothingToRemove : WriteOutcome
    data class Error(val error: TcgError) : WriteOutcome
}

/**
 * Changes the user's TCG Collector collection the way the website does: a JSON request to
 * `/card-collection/bulk-merge` (adds to the existing entries) or `/card-collection/bulk-replace`
 * (sets all entries of a card), with the page's CSRF token. Every write reloads the card page
 * first and afterwards, and is recorded in [CollectionLog].
 */
object CollectionWriter {

    private const val MERGE_URL = "${Tcg.BASE_URL}/card-collection/bulk-merge"
    private const val REPLACE_URL = "${Tcg.BASE_URL}/card-collection/bulk-replace"

    /** A write request and the variant's count expected after it. */
    private class Request(val method: String, val url: String, val body: String, val expectedCount: Int)

    /** Adds one copy of [variant] in the default language and condition. */
    suspend fun addOne(context: Context, card: TcgCard, variant: CardVariant): WriteOutcome = write(context, card, variant, "add") { state ->
        val entry = CollectionEntry(variant.id, state.languageId, state.conditionId, note = null, count = 1)
        Request("POST", MERGE_URL, body(card.id, listOf(entry)), state.count(variant.id) + 1)
    }

    /** Removes one English copy of [variant]; see [withOneLess] for which entry. */
    suspend fun removeOne(context: Context, card: TcgCard, variant: CardVariant): WriteOutcome = write(context, card, variant, "remove") { state ->
        withOneLess(state, variant.id)?.let { Request("PUT", REPLACE_URL, body(card.id, it), state.count(variant.id) - 1) }
    }

    /**
     * The card's entries with one copy of the variant less: from the entry in the default condition
     * without a note if there is one, otherwise from the entry with the most copies. An entry
     * that reaches 0 is dropped. Null when there is no copy to remove.
     */
    fun withOneLess(state: CardCollectionState, variantId: Int): List<CollectionEntry>? {
        val own = state.entries.filter { it.variantId == variantId && it.languageId == state.languageId && it.count > 0 }
        val from = own.firstOrNull { it.conditionId == state.conditionId && it.note == null } ?: own.maxByOrNull { it.count } ?: return null
        return state.entries.mapNotNull { e ->
            if (e !== from) e else e.copy(count = e.count - 1).takeIf { it.count > 0 }
        }
    }

    /** Puts back the entries a previous write started from (undo). */
    suspend fun restore(context: Context, card: TcgCard, variant: CardVariant, before: List<CollectionEntry>): WriteOutcome =
        write(context, card, variant, "undo") { state ->
            Request("PUT", REPLACE_URL, body(card.id, before), state.copy(entries = before).count(variant.id))
        }

    private suspend fun write(
        context: Context,
        card: TcgCard,
        variant: CardVariant,
        action: String,
        request: (CardCollectionState) -> Request?,
    ): WriteOutcome = withContext(Dispatchers.IO) {
        try {
            val client = TcgSession.client(context)
            val state = load(client, card) ?: return@withContext WriteOutcome.NotLoggedIn
            val call = request(state) ?: return@withContext WriteOutcome.NothingToRemove
            client.sendJson(call.method, call.url, call.body, mapOf(state.csrfHeader to state.csrfToken), referer = card.url)
            val after = load(client, card) ?: return@withContext WriteOutcome.NotLoggedIn
            val newCount = after.count(variant.id)
            val oldCount = state.count(variant.id)
            if (newCount != call.expectedCount) {
                val problem = IllegalStateException("count $oldCount -> $newCount, expected ${call.expectedCount}")
                ErrorLog.log("collection $action", problem, card.url)
                return@withContext WriteOutcome.NotApplied
            }
            CollectionLog.log(context, CollectionLog.Entry.now(action, card, variant.name, oldCount, newCount))
            WriteOutcome.Done(state.entries, oldCount, newCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("collection $action", e, card.url)
            WriteOutcome.Error(TcgError.of(e))
        }
    }

    /** The card page's collection state; null when the session has expired. */
    private fun load(client: TcgClient, card: TcgCard): CardCollectionState? {
        val appState = client.fetchPage(card.url).requireAppState()
        if (TcgUser.fromAppState(appState) == null) return null
        return parseState(appState, card.id)
    }

    /**
     * Reads the card's entries and write parameters from a card page's appState. Throws
     * [PageFormatException] if anything is missing: writing with a guessed value could damage
     * the collection, so the app rather does nothing.
     */
    fun parseState(appState: JsonObject, cardId: Int): CardCollectionState {
        val key = cardId.toString()
        val dto = (appState["cardIdToCardCollectionCardDtoMap"] as? JsonObject)?.get(key) as? JsonObject
            ?: throw PageFormatException("no collection data for card $cardId")
        val entries = (dto["entries"] as? JsonArray ?: throw PageFormatException("no collection entries for card $cardId"))
            .map { parseEntry(it) }
        val defaults = appState["defaultCardCollectionParametersDto"] as? JsonObject
            ?: throw PageFormatException("no default collection parameters")
        val conditionId = defaults.int("cardConditionId") ?: throw PageFormatException("no default card condition")
        val region = ((appState["cardIdToTcgRegionIdMap"] as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
            ?: TcgIds.REGION_INTERNATIONAL
        val languageId = if (region == TcgIds.REGION_INTERNATIONAL) TcgIds.LANGUAGE_ENGLISH else
            ((defaults["tcgRegionIdToCardLanguageIdMap"] as? JsonObject)?.get(region.toString()) as? JsonPrimitive)?.intOrNull
                ?: throw PageFormatException("no default language for region $region")
        val header = (appState["csrfTokenHttpHeaderKey"] as? JsonPrimitive)?.contentOrNull
            ?: throw PageFormatException("no CSRF header name")
        val token = (appState["csrfToken"] as? JsonPrimitive)?.contentOrNull
            ?: throw PageFormatException("no CSRF token")
        return CardCollectionState(cardId, entries, languageId, conditionId, header, token)
    }

    private fun parseEntry(element: JsonElement): CollectionEntry {
        val o = element as? JsonObject ?: throw PageFormatException("collection entry is not an object")
        fun need(name: String) = o.int(name) ?: throw PageFormatException("collection entry without $name")
        val note = o["note"]
        return CollectionEntry(
            variantId = need("cardVariantTypeId"),
            languageId = need("cardLanguageId"),
            conditionId = need("cardConditionId"),
            note = if (note == null || note is JsonNull) null else (note as? JsonPrimitive)?.contentOrNull,
            count = need("cardCount"),
        )
    }

    /** The request body: a list of `{cardId, entries}` as the website sends it. */
    fun body(cardId: Int, entries: List<CollectionEntry>): String = buildJsonArray {
        add(buildJsonObject {
            put("cardId", cardId)
            put("entries", buildJsonArray {
                entries.forEach { e ->
                    add(buildJsonObject {
                        put("cardVariantTypeId", e.variantId)
                        put("cardLanguageId", e.languageId)
                        put("cardConditionId", e.conditionId)
                        put("note", e.note)
                        put("cardCount", e.count)
                    })
                }
            })
        })
    }.toString()

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
