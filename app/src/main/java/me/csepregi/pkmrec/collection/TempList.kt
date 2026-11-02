package me.csepregi.pkmrec.collection

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
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.TcgCard
import java.io.File

/**
 * One card put on the temporary list from the recognition screen, with the variant chosen there.
 * The same card twice is two items (two copies to add).
 */
@Serializable
data class TempListItem(
    val id: Long,
    val cardId: Int,
    val name: String,
    val expansionName: String,
    val expansionCode: String? = null,
    val number: String,
    val rarity: String? = null,
    val path: String,
    val imageUrl: String? = null,
    val regionId: Int? = null,
    val variantId: Int,
    val variantName: String,
    val energyTypes: List<String> = emptyList(),
) {
    /** The variant to add; the owned counts are not kept (the writer reloads them). */
    val variant: CardVariant get() = CardVariant(variantId, variantName, ownedEnglish = 0, ownedOtherLanguages = 0)

    /** Enough of the card for [me.csepregi.pkmrec.tcg.CollectionWriter] and the card header. */
    fun toCard(): TcgCard = TcgCard(
        id = cardId,
        name = name,
        expansionName = expansionName,
        expansionCode = expansionCode,
        number = number,
        rarity = rarity,
        path = path,
        imageUrl = imageUrl,
        regionId = regionId,
        variants = listOf(variant),
        energyTypes = energyTypes,
    )

    companion object {
        fun of(id: Long, card: TcgCard, variant: CardVariant) = TempListItem(
            id = id,
            cardId = card.id,
            name = card.name,
            expansionName = card.expansionName,
            expansionCode = card.expansionCode,
            number = card.number,
            rarity = card.rarity,
            path = card.path,
            imageUrl = card.imageUrl,
            regionId = card.regionId,
            variantId = variant.id,
            variantName = variant.name,
            energyTypes = card.energyTypes,
        )
    }
}

@Serializable
private data class TempListFile(val version: Int = 1, val items: List<TempListItem>)

/**
 * The temporary list: cards collected on the recognition screen to add to the TCG Collector
 * collection later. Kept only on the phone, in a JSON file; oldest first.
 */
object TempList {

    private const val FILE_NAME = "temp_list.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _items = MutableStateFlow<List<TempListItem>>(emptyList())
    val items: StateFlow<List<TempListItem>> = _items.asStateFlow()

    @Volatile private var loaded = false

    /** Reads the file; without one the list is empty. Safe to call more than once. */
    suspend fun load(context: Context) {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            withContext(Dispatchers.IO) {
                val file = file(context)
                _items.value = if (file.exists()) decode(file.readText()) else emptyList()
            }
            loaded = true
        }
    }

    suspend fun add(context: Context, card: TcgCard, variant: CardVariant) = change(context) { items ->
        items + TempListItem.of(nextId(items), card, variant)
    }

    /** Puts an item back where it was (after an undone add), keeping its id. */
    suspend fun restore(context: Context, item: TempListItem) = change(context) { items ->
        if (items.any { it.id == item.id }) items else (items + item).sortedBy { it.id }
    }

    suspend fun remove(context: Context, id: Long) = change(context) { items -> items.filterNot { it.id == id } }

    suspend fun clear(context: Context) = change(context) { emptyList() }

    /** Ids grow with time, so the list stays in the order the cards were added. */
    internal fun nextId(items: List<TempListItem>): Long = maxOf(System.currentTimeMillis(), (items.maxOfOrNull { it.id } ?: 0L) + 1)

    private suspend fun change(context: Context, transform: (List<TempListItem>) -> List<TempListItem>) {
        load(context)
        mutex.withLock {
            val next = transform(_items.value)
            withContext(Dispatchers.IO) {
                runCatching { write(context, next) }.onFailure { ErrorLog.log("temp list", it) }
            }
            _items.value = next
        }
    }

    private fun write(context: Context, items: List<TempListItem>) {
        val file = file(context)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(encode(items))
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    internal fun encode(items: List<TempListItem>): String = json.encodeToString(TempListFile.serializer(), TempListFile(items = items))

    internal fun decode(text: String): List<TempListItem> =
        runCatching { json.decodeFromString(TempListFile.serializer(), text).items.distinctBy { it.id } }.getOrDefault(emptyList())

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)
}
