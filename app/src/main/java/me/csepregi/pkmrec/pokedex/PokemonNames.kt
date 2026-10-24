package me.csepregi.pkmrec.pokedex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.tcg.PageFormatException
import me.csepregi.pkmrec.tcg.Tcg
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgSession
import org.jsoup.Jsoup
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** A Pokémon as TCG Collector's Pokédex lists it, e.g. 909 · "Fuecoco" · `fuecoco`. */
data class PokemonName(val number: Int, val name: String, val slug: String) {
    val display: String get() = "#%04d %s".format(number, name)
}

sealed interface NameListRefreshResult {
    data class Done(val count: Int) : NameListRefreshResult
    data class Error(val error: TcgError) : NameListRefreshResult
}

/**
 * The names of all Pokémon, for the Pokédex search. A list is bundled with the app
 * (`assets/pokemon_names.tsv`), so name correction works offline; settings can replace it
 * with a fresh copy of TCG Collector's Pokédex page when a new generation comes out.
 */
object PokemonNames {

    private const val FILE_NAME = "pokemon_names.tsv"
    private const val INDEX_URL = "${Tcg.BASE_URL}/pokedex"

    /** A refreshed list shorter than this means the Pokédex page could not be read properly. */
    internal const val MIN_COUNT = 1000

    private val POKEDEX_PATH = Regex("""^/pokedex/(\d+)/([^/?#]+)""")

    @Volatile private var cached: List<PokemonName>? = null

    /** All names, sorted by Pokédex number. Reads a file: call off the main thread. */
    fun all(context: Context): List<PokemonName> = cached ?: load(context).also { cached = it }

    fun byNumber(context: Context, number: Int): PokemonName? = all(context).firstOrNull { it.number == number }

    private fun load(context: Context): List<PokemonName> {
        val bundled = context.assets.open(FILE_NAME).bufferedReader().use { parse(it.readText()) }
        val file = File(context.filesDir, FILE_NAME)
        val downloaded = if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(emptyList()) else emptyList()
        return if (downloaded.size >= bundled.size) downloaded else bundled
    }

    suspend fun refresh(context: Context): NameListRefreshResult = withContext(Dispatchers.IO) {
        try {
            val page = TcgSession.client(context).fetchPage(INDEX_URL)
            val names = parseIndexPage(page.html)
            if (names.size < MIN_COUNT) {
                page.requireAppState()
                throw PageFormatException("only ${names.size} Pokémon on the Pokédex page")
            }
            File(context.filesDir, FILE_NAME).writeText(format(names))
            cached = null
            NameListRefreshResult.Done(all(context).size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("pokemon names", e, INDEX_URL)
            NameListRefreshResult.Error(TcgError.of(e))
        }
    }

    /** Lines of `number<TAB>name<TAB>slug`; empty lines and `#` comments are skipped. */
    fun parse(text: String): List<PokemonName> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val number = parts[0].toIntOrNull() ?: return@mapNotNull null
            PokemonName(number, parts[1].trim(), parts[2].trim())
        }
        .distinctBy { it.number }
        .sortedBy { it.number }
        .toList()

    fun format(names: List<PokemonName>): String =
        names.joinToString(separator = "\n", postfix = "\n") { "${it.number}\t${it.name}\t${it.slug}" }

    /** The Pokédex page (`/pokedex`) has one `a.pokedex-grid-item-name` link per Pokémon. */
    fun parseIndexPage(html: String): List<PokemonName> {
        val doc = Jsoup.parse(html)
        return doc.select("a.pokedex-grid-item-name[href]").mapNotNull { link ->
            val match = POKEDEX_PATH.find(link.attr("href")) ?: return@mapNotNull null
            val name = link.text().trim().ifEmpty { link.attr("title").trim() }
            if (name.isEmpty()) return@mapNotNull null
            PokemonName(match.groupValues[1].toInt(), name, match.groupValues[2])
        }.distinctBy { it.number }.sortedBy { it.number }
    }
}
