package me.csepregi.pkmrec.sealed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.diagnostics.ErrorLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

/** A sealed product on TCGplayer, e.g. 668496 · group 24541 · Ascended Heroes · "Ascended Heroes Elite Trainer Box". */
data class TcgplayerProduct(val id: Int, val groupId: Int, val set: String, val name: String)

/**
 * The sealed Pokémon products on TCGplayer, which the Sealed catalog prices come from. Bundled
 * with the app (`assets/tcgplayer_sealed.tsv`, refreshed weekly in the repository by the
 * "Sealed products" workflow from TCGCSV); "Sealed lista frissítése" downloads the current copy.
 */
object TcgplayerCatalog {

    private const val FILE_NAME = "tcgplayer_sealed.tsv"
    private const val DOWNLOAD_URL = "https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO}/main/app/src/main/assets/$FILE_NAME"

    /** A downloaded list shorter than this is not used. */
    internal const val MIN_COUNT = 2000

    @Volatile private var cached: List<TcgplayerProduct>? = null

    /** All products, sorted by name. Reads a file: call off the main thread. */
    fun all(context: Context): List<TcgplayerProduct> = cached ?: load(context).also { cached = it }

    private fun load(context: Context): List<TcgplayerProduct> {
        val bundled = runCatching { context.assets.open(FILE_NAME).bufferedReader().use { parse(it.readText()) } }.getOrDefault(emptyList())
        val file = File(context.filesDir, FILE_NAME)
        val downloaded = if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(emptyList()) else emptyList()
        return if (downloaded.size >= MIN_COUNT || downloaded.size > bundled.size) downloaded else bundled
    }

    /** Downloads the repository's current list; returns the number of products. */
    suspend fun refresh(context: Context): SealedListRefreshResult = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val text = http.newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val products = parse(text)
            if (products.size < MIN_COUNT) throw IOException("only ${products.size} TCGplayer products in the downloaded list")
            File(context.filesDir, FILE_NAME).writeText(text)
            cached = null
            SealedListRefreshResult.Done(all(context).size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("tcgplayer products", e, DOWNLOAD_URL)
            SealedListRefreshResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lines of `productId<TAB>groupId<TAB>set<TAB>name`; empty lines and `#` comments are skipped. */
    fun parse(text: String): List<TcgplayerProduct> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val group = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
            val name = parts[3].trim().ifEmpty { return@mapNotNull null }
            TcgplayerProduct(id, group, parts[2].trim(), name)
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .toList()

    /** The product page on TCGplayer. */
    fun productUrl(id: Int): String = "https://www.tcgplayer.com/product/$id"

    /** Product pictures on TCGplayer's image server, largest first (the large size is not there for every product). */
    fun imageUrls(id: Int): List<String> = listOf(
        "https://tcgplayer-cdn.tcgplayer.com/product/${id}_in_1000x1000.jpg",
        "https://tcgplayer-cdn.tcgplayer.com/product/${id}_200w.jpg",
    )
}
