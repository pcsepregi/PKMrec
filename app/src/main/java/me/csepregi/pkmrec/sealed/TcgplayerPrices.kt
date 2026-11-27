package me.csepregi.pkmrec.sealed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * TCGplayer market prices from TCGCSV (tcgcsv.com), which republishes TCGplayer's prices once a
 * day as one JSON file per set ("group"). This is the price Collectr shows for sealed products.
 */
object TcgplayerPrices {

    private const val BASE = "https://tcgcsv.com/tcgplayer/3"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    fun pricesUrl(groupId: Int) = "$BASE/$groupId/prices"

    /** Market price in dollars per product of the group; products without a market price are missing. */
    suspend fun group(groupId: Int): Map<Int, Double> = withContext(Dispatchers.IO) {
        val text = http.newCall(Request.Builder().url(pricesUrl(groupId)).header("User-Agent", "PKMrec").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("TCGCSV prices: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        parse(text)
    }

    /** Reads a TCGCSV prices file. A product with several kinds ("Normal", "Holofoil") takes the Normal one. */
    fun parse(text: String): Map<Int, Double> {
        val results = (Json.parseToJsonElement(text) as? JsonObject)?.get("results") as? JsonArray
            ?: throw IOException("TCGCSV prices: unreadable file")
        val byProduct = HashMap<Int, MutableList<Pair<String, Double>>>()
        for (row in results) {
            val o = row as? JsonObject ?: continue
            val id = (o["productId"] as? JsonPrimitive)?.intOrNull ?: continue
            val market = (o["marketPrice"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: continue
            val kind = (o["subTypeName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            byProduct.getOrPut(id) { mutableListOf() } += kind to market
        }
        return byProduct.mapValues { (_, prices) -> (prices.firstOrNull { it.first == "Normal" } ?: prices.first()).second }
    }

    /** Downloads a product picture from TCGplayer's image server: the largest one there is. */
    suspend fun image(id: Int): ByteArray = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (url in TcgplayerCatalog.imageUrls(id)) {
            try {
                val bytes = http.newCall(Request.Builder().url(url).header("User-Agent", "PKMrec").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} ($url)")
                    response.body?.bytes() ?: ByteArray(0)
                }
                if (bytes.size > 100) return@withContext bytes
                last = IOException("empty picture ($url)")
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no picture")
    }
}

/** "126,59 $": dollars with a decimal comma, like the euro prices. */
fun formatUsd(value: Double): String = String.format(java.util.Locale.ROOT, "%.2f $", value).replace('.', ',')
