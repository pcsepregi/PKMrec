package me.csepregi.pkmrec.sealed

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.roundToLong

/** The last Cardmarket price of a product: [kind] is "price" (average), "few" (cheapest of 1–2 offers) or "none". */
@Serializable
data class SealedPrice(
    val kind: String,
    val eur: Double = 0.0,
    val eligible: Int = 0,
    val time: Long,
) {
    /** The euro value used for the totals, or null when there were no offers. */
    val value: Double? get() = eur.takeIf { kind != KIND_NONE }

    companion object {
        const val KIND_PRICE = "price"
        const val KIND_FEW = "few"
        const val KIND_NONE = "none"
    }
}

/** A product in the Sealed catalog: how many Pali has, the average purchase price per piece and the last price. */
@Serializable
data class SealedItem(
    val id: Int,
    val name: String,
    val type: String = "",
    val set: String = "",
    val count: Int,
    /** Average purchase price of one piece in forints, if known. */
    val purchaseHuf: Long? = null,
    /** The Cardmarket product page, once found. */
    val productUrl: String? = null,
    val price: SealedPrice? = null,
)

@Serializable
private data class InventoryFile(val version: Int = 1, val items: List<SealedItem>)

/** Totals for the top of the Sealed catalog. Forint values are null until the EUR–HUF rate is known. */
data class SealedSummary(
    val pieces: Int,
    val products: Int,
    /** Purchase price of the pieces that have one. */
    val purchaseHuf: Long,
    /** Products without a purchase price. */
    val withoutPurchase: Int,
    /** Current value of the pieces that have a price, in euros. */
    val valueEur: Double,
    /** Products without a price (never loaded, or no offers). */
    val withoutPrice: Int,
    /** Value minus purchase, counting only products that have both. */
    val differenceHuf: Long?,
)

/**
 * Pali's sealed products, kept in a JSON file on the phone. On first use the list in
 * `assets/sealed_seed.tsv` is loaded; export and import move the file between phones.
 */
object SealedInventory {

    private const val FILE_NAME = "sealed_inventory.json"
    private const val SEED_FILE = "sealed_seed.tsv"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<SealedItem>>(emptyList())
    val items: StateFlow<List<SealedItem>> = _items.asStateFlow()

    @Volatile private var loaded = false

    /** Reads the file, or loads the seed list the first time. Safe to call more than once. */
    suspend fun load(context: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                val file = file(context)
                _items.value = if (file.exists()) {
                    decode(file.readText())
                } else {
                    seed(context).also { write(context, it) }
                }
            }
            loaded = true
        }
    }

    /** Adds [count] pieces bought at [unitHuf] each (null = unknown) and updates the average purchase price. */
    suspend fun addPurchase(context: Context, product: SealedProduct, count: Int, unitHuf: Long?) = change(context) { items ->
        val old = items.firstOrNull { it.id == product.id }
        if (old == null) {
            items + SealedItem(product.id, product.name, product.type, product.set, count, unitHuf)
        } else {
            items.map {
                if (it.id != product.id) it
                else it.copy(count = it.count + count, purchaseHuf = weightedAverage(it.count, it.purchaseHuf, count, unitHuf))
            }
        }
    }

    suspend fun setCount(context: Context, id: Int, count: Int) = update(context, id) { it.copy(count = count.coerceAtLeast(0)) }

    suspend fun setPurchase(context: Context, id: Int, unitHuf: Long?) = update(context, id) { it.copy(purchaseHuf = unitHuf) }

    suspend fun setPrice(context: Context, id: Int, price: SealedPrice?, productUrl: String?) =
        update(context, id) { it.copy(price = price, productUrl = productUrl) }

    suspend fun remove(context: Context, id: Int) = change(context) { items -> items.filter { it.id != id } }

    fun exportJson(): String = json.encodeToString(InventoryFile.serializer(), InventoryFile(items = _items.value))

    /** Reads an exported file; [replace] = drop the current list, otherwise counts are added up. Returns the number of products read. */
    suspend fun importJson(context: Context, text: String, replace: Boolean): Int {
        val imported = decodeStrict(text)
        change(context) { items -> if (replace) imported else merge(items, imported) }
        return imported.size
    }

    private suspend fun update(context: Context, id: Int, transform: (SealedItem) -> SealedItem) =
        change(context) { items -> items.map { if (it.id == id) transform(it) else it } }

    private suspend fun change(context: Context, transform: (List<SealedItem>) -> List<SealedItem>) {
        load(context)
        mutex.withLock {
            val next = transform(_items.value)
            withContext(Dispatchers.IO) { write(context, next) }
            _items.value = next
        }
    }

    private fun seed(context: Context): List<SealedItem> {
        val text = context.assets.open(SEED_FILE).bufferedReader().use { it.readText() }
        val catalog = SealedCatalog.all(context).associateBy { it.id }
        return parseSeed(text).mapNotNull { (id, count) ->
            val product = catalog[id] ?: return@mapNotNull null
            SealedItem(product.id, product.name, product.type, product.set, count)
        }
    }

    private fun write(context: Context, items: List<SealedItem>) {
        val file = file(context)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(InventoryFile.serializer(), InventoryFile(items = items)))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun decode(text: String): List<SealedItem> = runCatching { decodeStrict(text) }.getOrDefault(emptyList())

    private fun decodeStrict(text: String): List<SealedItem> =
        json.decodeFromString(InventoryFile.serializer(), text).items.filter { it.count >= 0 }.distinctBy { it.id }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Lines of `idProduct<TAB>count[<TAB>anything]`; `#` comments are skipped. */
    internal fun parseSeed(text: String): List<Pair<Int, Int>> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            val id = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
            val count = parts.getOrNull(1)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
            id to count
        }
        .toList()

    /** Imported items added to the current ones: counts add up, purchase prices are averaged by count. */
    internal fun merge(current: List<SealedItem>, imported: List<SealedItem>): List<SealedItem> {
        val byId = current.associateBy { it.id }.toMutableMap()
        for (item in imported) {
            val old = byId[item.id]
            byId[item.id] = if (old == null) item else old.copy(
                count = old.count + item.count,
                purchaseHuf = weightedAverage(old.count, old.purchaseHuf, item.count, item.purchaseHuf),
                productUrl = old.productUrl ?: item.productUrl,
                price = listOfNotNull(old.price, item.price).maxByOrNull { it.time },
            )
        }
        return byId.values.toList()
    }

    /**
     * Average price per piece after buying [addCount] more at [addHuf]. An unknown price on
     * either side keeps the known one: a guess would be worse than the average of what is known.
     */
    internal fun weightedAverage(oldCount: Int, oldHuf: Long?, addCount: Int, addHuf: Long?): Long? = when {
        oldHuf == null || oldCount <= 0 -> addHuf ?: oldHuf
        addHuf == null || addCount <= 0 -> oldHuf
        else -> ((oldHuf * oldCount + addHuf * addCount).toDouble() / (oldCount + addCount)).roundToLong()
    }

    internal fun summary(items: List<SealedItem>, rate: Double?): SealedSummary {
        val owned = items.filter { it.count > 0 }
        val purchased = owned.filter { it.purchaseHuf != null }
        val priced = owned.filter { it.price?.value != null }
        val both = owned.filter { it.purchaseHuf != null && it.price?.value != null }
        val difference = rate?.let { r ->
            both.sumOf { (it.price!!.value!! * r - it.purchaseHuf!!) * it.count }.roundToLong()
        }
        return SealedSummary(
            pieces = owned.sumOf { it.count },
            products = owned.size,
            purchaseHuf = purchased.sumOf { it.purchaseHuf!! * it.count },
            withoutPurchase = owned.size - purchased.size,
            valueEur = priced.sumOf { it.price!!.value!! * it.count },
            withoutPrice = owned.size - priced.size,
            differenceHuf = difference.takeIf { both.isNotEmpty() },
        )
    }
}
