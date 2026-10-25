package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.recognition.CardReading
import java.net.URLEncoder
import kotlin.coroutines.cancellation.CancellationException

sealed interface LookupOutcome {
    data class Found(val match: MatchResult) : LookupOutcome
    data object NotLoggedIn : LookupOutcome
    data class Error(val error: TcgError) : LookupOutcome
}

/** Finds a scanned card on TCG Collector (read-only) together with the user's collection data. */
object CardLookup {

    /** Promo sets whose number is printed glued to the code ("SWSH123"); TCG Collector uses the same form. */
    private val GLUED_PROMO_CODES = setOf("SWSH", "SM", "XY", "BW", "DP", "HGSS")

    /** Number-only searches (promos) can return hundreds of cards; this many pages are scanned at most. */
    private const val MAX_PAGES = 4

    suspend fun lookup(context: Context, reading: CardReading): LookupOutcome = withContext(Dispatchers.IO) {
        try {
            val client = TcgSession.client(context)
            val (query, matchReading) = queryFor(reading)
            val paged = matchReading.total == null

            val cards = mutableListOf<TcgCard>()
            val pageOfCard = mutableMapOf<Int, Int>()
            var page = 1
            var result: MatchResult = MatchResult.NotFound
            while (page <= MAX_PAGES) {
                val listPage = client.fetchPage(searchUrl(query, "list", page))
                val state = listPage.requireAppState()
                if (TcgUser.fromAppState(state) == null) return@withContext LookupOutcome.NotLoggedIn
                val found = TcgSearchParser.parseListPage(listPage.html, state)
                found.forEach { pageOfCard[it.id] = page }
                cards += found
                result = CardMatcher.match(matchReading, cards)
                val hasMore = (state["hasMultiplePages"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                if (result != MatchResult.NotFound || !paged || !hasMore || found.isEmpty()) break
                page++
            }

            LookupOutcome.Found(withImages(client, query, result, pageOfCard))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("lookup", e, "reading ${reading.display}")
            LookupOutcome.Error(TcgError.of(e))
        }
    }

    /**
     * Finds a card by its name alone (Tömeges eladás, number unreadable): only cards the user owns
     * count, as only those can be sold, best name match first. Several owned prints → the user picks.
     */
    suspend fun lookupOwnedByName(context: Context, name: String, setCode: String? = null): LookupOutcome =
        lookupByName(context, name) { cards -> ownedByName(name, cards, setCode) }

    /**
     * Finds a card by its name in the set [setCode] (camera recognition, number unreadable, e.g. a
     * promo shown on a screen). Owned or not; several cards of that name in the set → the user picks.
     */
    suspend fun lookupByNameInSet(context: Context, name: String, setCode: String): LookupOutcome =
        lookupByName(context, name) { cards -> byNameInSet(name, setCode, cards) }

    private suspend fun lookupByName(context: Context, name: String, match: (List<TcgCard>) -> MatchResult): LookupOutcome =
        withContext(Dispatchers.IO) {
            try {
                val client = TcgSession.client(context)
                val cards = mutableListOf<TcgCard>()
                val pageOfCard = mutableMapOf<Int, Int>()
                var page = 1
                while (page <= MAX_PAGES) {
                    val listPage = client.fetchPage(searchUrl(name, "list", page))
                    val state = listPage.requireAppState()
                    if (TcgUser.fromAppState(state) == null) return@withContext LookupOutcome.NotLoggedIn
                    val found = TcgSearchParser.parseListPage(listPage.html, state)
                    found.forEach { pageOfCard[it.id] = page }
                    cards += found
                    val hasMore = (state["hasMultiplePages"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                    if (!hasMore || found.isEmpty()) break
                    page++
                }
                LookupOutcome.Found(withImages(client, name, match(cards), pageOfCard))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLog.log("lookup by name", e, name)
                LookupOutcome.Error(TcgError.of(e))
            }
        }

    /** The English cards of [setCode] whose name contains [name] or is close to it, best match first. */
    internal fun byNameInSet(name: String, setCode: String, cards: List<TcgCard>): MatchResult {
        val inSet = cards.distinctBy { it.id }
            .filter { it.regionId == null || it.regionId == TcgIds.REGION_INTERNATIONAL }
            .filter { it.expansionCode.equals(setCode, ignoreCase = true) || CardMatcher.codeMatchesLoosely(setCode, it.expansionCode) }
            .filter { namesMatch(name, it.name) }
            .sortedByDescending { CardMatcher.nameSimilarity(name, it.name) }
        return when (inSet.size) {
            0 -> MatchResult.NotFound
            1 -> MatchResult.Single(inSet.first())
            else -> MatchResult.Ambiguous(inSet)
        }
    }

    private fun namesMatch(read: String, cardName: String): Boolean {
        val key = read.lowercase().filter { it.isLetterOrDigit() }
        return cardName.lowercase().filter { it.isLetterOrDigit() }.contains(key) || CardMatcher.nameSimilarity(read, cardName) >= 0.75
    }

    /**
     * The English cards the user owns whose name contains [name] or is close to it, best match
     * first; only those of [setCode] when the set code was read and some match it.
     */
    internal fun ownedByName(name: String, cards: List<TcgCard>, setCode: String? = null): MatchResult {
        val owned = cards.distinctBy { it.id }
            .filter { it.regionId == null || it.regionId == TcgIds.REGION_INTERNATIONAL }
            .filter { card -> card.variants.any { it.isOwned } }
            .filter { card -> namesMatch(name, card.name) }
            .sortedByDescending { CardMatcher.nameSimilarity(name, it.name) }
            .let { all ->
                if (setCode == null) all
                else all.filter { it.expansionCode.equals(setCode, ignoreCase = true) || CardMatcher.codeMatchesLoosely(setCode, it.expansionCode) }.ifEmpty { all }
            }
        return when (owned.size) {
            0 -> MatchResult.NotFound
            1 -> MatchResult.Single(owned.first())
            else -> MatchResult.Ambiguous(owned)
        }
    }

    /** The search text, and the reading as it should be compared with TCG Collector's numbers. */
    internal fun queryFor(reading: CardReading): Pair<String, CardReading> {
        val code = reading.setCode
        if (code != null && reading.total == null && code in GLUED_PROMO_CODES) {
            val glued = reading.copy(setCode = null, number = code + reading.number)
            return glued.number to glued
        }
        val query = if (reading.total != null) "${reading.number}/${reading.total}" else reading.number
        return query to reading
    }

    private fun withImages(
        client: TcgClient,
        query: String,
        result: MatchResult,
        pageOfCard: Map<Int, Int>,
    ): MatchResult {
        val cards = when (result) {
            is MatchResult.Single -> listOf(result.card)
            is MatchResult.Ambiguous -> result.candidates
            MatchResult.NotFound -> return result
        }
        val images = mutableMapOf<Int, String>()
        cards.mapNotNull { pageOfCard[it.id] }.distinct().forEach { page ->
            runCatching { images += TcgSearchParser.parseImagePage(client.fetchPage(searchUrl(query, "images", page)).html) }
        }
        fun TcgCard.withImage() = copy(imageUrl = images[id])
        return when (result) {
            is MatchResult.Single -> MatchResult.Single(result.card.withImage())
            is MatchResult.Ambiguous -> MatchResult.Ambiguous(result.candidates.map { it.withImage() })
            MatchResult.NotFound -> result
        }
    }

    private fun searchUrl(query: String, displayAs: String, page: Int): String {
        val q = URLEncoder.encode(query, "UTF-8")
        val pagePart = if (page > 1) "&page=$page" else ""
        return "${Tcg.BASE_URL}/cards?cardSearch=$q&displayAs=$displayAs$pagePart"
    }
}
