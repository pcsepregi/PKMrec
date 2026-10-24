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

/** A sealed product from Cardmarket's catalogue, e.g. 885542 · Elite Trainer Box · Pitch Black · "Pitch Black Elite Trainer Box". */
data class SealedProduct(val id: Int, val type: String, val set: String, val name: String)

sealed interface SealedListRefreshResult {
    data class Done(val count: Int) : SealedListRefreshResult
    data class Error(val message: String) : SealedListRefreshResult
}

/**
 * The English sealed Pokémon products on Cardmarket, for the Sealed catalog search. A list is
 * bundled with the app (`assets/sealed_products.tsv`, refreshed weekly in the repository by the
 * "Sealed products" workflow); settings can download the repository's current copy.
 */
object SealedCatalog {

    private const val FILE_NAME = "sealed_products.tsv"
    private const val DOWNLOAD_URL = "https://raw.githubusercontent.com/${BuildConfig.GITHUB_REPO}/main/app/src/main/assets/$FILE_NAME"

    /** A downloaded list shorter than this is not used. */
    internal const val MIN_COUNT = 2000

    @Volatile private var cached: List<SealedProduct>? = null

    /** All products, sorted by name. Reads a file: call off the main thread. */
    fun all(context: Context): List<SealedProduct> = cached ?: load(context).also { cached = it }

    fun byId(context: Context, id: Int): SealedProduct? = all(context).firstOrNull { it.id == id }

    private fun load(context: Context): List<SealedProduct> {
        val bundled = context.assets.open(FILE_NAME).bufferedReader().use { parse(it.readText()) }
        val file = File(context.filesDir, FILE_NAME)
        val downloaded = if (file.exists()) runCatching { parse(file.readText()) }.getOrDefault(emptyList()) else emptyList()
        return if (downloaded.size >= MIN_COUNT) downloaded else bundled
    }

    suspend fun refresh(context: Context): SealedListRefreshResult = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            val text = http.newCall(Request.Builder().url(DOWNLOAD_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            val products = parse(text)
            if (products.size < MIN_COUNT) throw IOException("only ${products.size} products in the downloaded list")
            File(context.filesDir, FILE_NAME).writeText(text)
            cached = null
            SealedListRefreshResult.Done(all(context).size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("sealed products", e, DOWNLOAD_URL)
            SealedListRefreshResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Lines of `idProduct<TAB>type<TAB>set<TAB>name`; empty lines and `#` comments are skipped. */
    fun parse(text: String): List<SealedProduct> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 4) return@mapNotNull null
            val id = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val name = parts[3].trim().ifEmpty { return@mapNotNull null }
            SealedProduct(id, parts[1].trim(), parts[2].trim(), name)
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
        .toList()
}
