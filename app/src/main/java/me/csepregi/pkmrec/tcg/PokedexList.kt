package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import org.jsoup.Jsoup
import kotlin.coroutines.cancellation.CancellationException

/** A Pokédex entry linked from a card page, e.g. #0909 Fuecoco → `/pokedex/909/fuecoco/intl`. */
data class PokedexEntry(val number: Int, val slug: String, val path: String)

/** The cards of the user's Pokedex list for one Pokémon. */
data class PokedexListCards(val entry: PokedexEntry, val cards: List<TcgCard>)

sealed interface PokedexLookupResult {
    /** The scanned card has no Pokédex entry (Trainer, Energy). */
    data object NotAPokemon : PokedexLookupResult
    data class Found(val perPokemon: List<PokedexListCards>) : PokedexLookupResult

    /** The session expired: a logged-out page would show no list cards, which must not read as "none in your list". */
    data object NotLoggedIn : PokedexLookupResult
    data class Error(val error: TcgError) : PokedexLookupResult
}

/**
 * Finds which card of the same Pokémon is in the user's TCG Collector list (read-only):
 * the card page links its Pokédex entries; each Pokédex page, filtered to the list
 * (`inUserCardList=<listId>`), shows the list's card(s) for that Pokémon.
 */
object PokedexList {

    private const val PREFS = "pokedex_list"
    private const val KEY_LIST_ID = "list_id"
    private const val KEY_ENABLED = "enabled"

    /** The owner's "Pokedex" list; can be changed in settings. */
    const val DEFAULT_LIST_ID = 65678

    private val POKEDEX_PATH = Regex("""^/pokedex/(\d+)/([^/?#]+)""")
    private val LIST_ID_IN_URL = Regex("""/lists/(\d+)""")

    /** Whether the result screen looks up the Pokedex list at all (default: on). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun listId(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIST_ID, DEFAULT_LIST_ID)

    /** Accepts a list URL (".../lists/65678/pokedex?...") or a bare ID; returns false if neither. */
    fun setList(context: Context, input: String): Boolean {
        val text = input.trim()
        val id = LIST_ID_IN_URL.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: text.toIntOrNull()
        if (id == null || id <= 0) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LIST_ID, id).apply()
        return true
    }

    fun listUrl(context: Context): String = "${Tcg.BASE_URL}/lists/${listId(context)}"

    suspend fun lookup(context: Context, card: TcgCard): PokedexLookupResult = withContext(Dispatchers.IO) {
        try {
            val client = TcgSession.client(context)
            val cardPage = client.fetchPage(card.url)
            cardPage.requireAppState()
            val entries = parsePokedexEntries(cardPage.html)
            if (entries.isEmpty()) return@withContext PokedexLookupResult.NotAPokemon
            val listId = listId(context)
            val perPokemon = entries.map { entry ->
                listCards(client, entry, listId) ?: return@withContext PokedexLookupResult.NotLoggedIn
            }
            PokedexLookupResult.Found(perPokemon)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("pokedex list", e, card.url)
            PokedexLookupResult.Error(TcgError.of(e))
        }
    }

    /**
     * The list's card(s) for one Pokémon chosen by name (Pokédex search), each with its energy type and HP
     * from the card page. Always [PokedexLookupResult.Found] with one entry on success.
     */
    suspend fun lookupPokemon(context: Context, number: Int, slug: String): PokedexLookupResult = withContext(Dispatchers.IO) {
        val entry = PokedexEntry(number, slug, "/pokedex/$number/$slug/intl")
        try {
            val client = TcgSession.client(context)
            val found = listCards(client, entry, listId(context)) ?: return@withContext PokedexLookupResult.NotLoggedIn
            val cards = found.cards.map { card ->
                runCatching { CardPageParser.parseDetails(client.fetchPage(card.url).html) }
                    .onFailure { ErrorLog.log("card details", it, card.url) }
                    .getOrNull()
                    ?.let { card.copy(energyTypes = it.energyTypes, hp = it.hp) } ?: card
            }
            PokedexLookupResult.Found(listOf(found.copy(cards = cards)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("pokedex search", e, entry.path)
            PokedexLookupResult.Error(TcgError.of(e))
        }
    }

    /** The Pokédex page filtered to the list; null when the session has expired. */
    internal fun listCards(client: TcgClient, entry: PokedexEntry, listId: Int): PokedexListCards? {
        val base = "${Tcg.BASE_URL}/pokedex/${entry.number}/${entry.slug}/intl?inUserCardList=$listId"
        val listPage = client.fetchPage("$base&displayAs=list")
        val listState = listPage.requireAppState()
        if (TcgUser.fromAppState(listState) == null) return null
        val cards = TcgSearchParser.parseListPage(listPage.html, listState)
        val images = if (cards.isEmpty()) emptyMap() else
            runCatching { TcgSearchParser.parseImagePage(client.fetchPage("$base&displayAs=images").html) }.getOrDefault(emptyMap())
        return PokedexListCards(entry, cards.map { it.copy(imageUrl = images[it.id]) })
    }

    /** Pokédex links in the "Pokédex" footer item of a card page (several for multi-Pokémon cards). */
    fun parsePokedexEntries(cardPageHtml: String): List<PokedexEntry> {
        val doc = Jsoup.parse(cardPageHtml)
        val item = doc.select(".card-info-footer-item").firstOrNull { item ->
            item.selectFirst(".card-info-footer-item-title")?.text()?.trim()?.lowercase()?.startsWith("pokédex") == true
        } ?: return emptyList()
        return item.select("a[href^=/pokedex/]").mapNotNull { link ->
            val href = link.attr("href")
            val match = POKEDEX_PATH.find(href) ?: return@mapNotNull null
            PokedexEntry(match.groupValues[1].toInt(), match.groupValues[2], href)
        }.distinctBy { it.number }
    }
}
