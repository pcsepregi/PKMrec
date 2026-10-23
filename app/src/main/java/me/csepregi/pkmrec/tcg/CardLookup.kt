package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.recognition.CardReading
import java.net.URLEncoder

sealed interface LookupOutcome {
    data class Found(val match: MatchResult) : LookupOutcome
    data object NotLoggedIn : LookupOutcome
    data class Error(val message: String) : LookupOutcome
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
                val state = listPage.appState
                if (state == null || TcgUser.fromAppState(state) == null) return@withContext LookupOutcome.NotLoggedIn
                val found = TcgSearchParser.parseListPage(listPage.html, state)
                found.forEach { pageOfCard[it.id] = page }
                cards += found
                result = CardMatcher.match(matchReading, cards)
                val hasMore = (state["hasMultiplePages"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
                if (result != MatchResult.NotFound || !paged || !hasMore || found.isEmpty()) break
                page++
            }

            LookupOutcome.Found(withImages(client, query, result, pageOfCard))
        } catch (e: Exception) {
            LookupOutcome.Error(e.message ?: e.javaClass.simpleName)
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
