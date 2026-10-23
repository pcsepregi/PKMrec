package me.csepregi.pkmrec.tcg

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** A Pokédex entry linked from a card page, e.g. #0909 Fuecoco → `/pokedex/909/fuecoco/intl`. */
data class PokedexEntry(val number: Int, val slug: String, val path: String)

/** The cards of the user's Pokedex list for one Pokémon. */
data class PokedexListCards(val entry: PokedexEntry, val cards: List<TcgCard>)

sealed interface PokedexLookupResult {
    /** The scanned card has no Pokédex entry (Trainer, Energy). */
    data object NotAPokemon : PokedexLookupResult
    data class Found(val perPokemon: List<PokedexListCards>) : PokedexLookupResult
    data class Error(val message: String) : PokedexLookupResult
}

/**
 * Finds which card of the same Pokémon is in the user's TCG Collector list (read-only):
 * the card page links its Pokédex entries; each Pokédex page, filtered to the list
 * (`inUserCardList=<listId>`), shows the list's card(s) for that Pokémon.
 */
object PokedexList {

    private const val PREFS = "pokedex_list"
    private const val KEY_LIST_ID = "list_id"

    /** The owner's "Pokedex" list; can be changed in settings. */
    const val DEFAULT_LIST_ID = 65678

    private val POKEDEX_PATH = Regex("""^/pokedex/(\d+)/([^/?#]+)""")
    private val LIST_ID_IN_URL = Regex("""/lists/(\d+)""")

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
            val entries = parsePokedexEntries(client.fetchPage(card.url).html)
            if (entries.isEmpty()) return@withContext PokedexLookupResult.NotAPokemon
            val listId = listId(context)
            val perPokemon = entries.map { entry ->
                val base = "${Tcg.BASE_URL}/pokedex/${entry.number}/${entry.slug}/intl?inUserCardList=$listId"
                val listPage = client.fetchPage("$base&displayAs=list")
                val cards = TcgSearchParser.parseListPage(listPage.html, listPage.appState)
                val images = if (cards.isEmpty()) emptyMap() else
                    runCatching { TcgSearchParser.parseImagePage(client.fetchPage("$base&displayAs=images").html) }.getOrDefault(emptyMap())
                PokedexListCards(entry, cards.map { it.copy(imageUrl = images[it.id]) })
            }
            PokedexLookupResult.Found(perPokemon)
        } catch (e: Exception) {
            PokedexLookupResult.Error(e.message ?: e.javaClass.simpleName)
        }
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
