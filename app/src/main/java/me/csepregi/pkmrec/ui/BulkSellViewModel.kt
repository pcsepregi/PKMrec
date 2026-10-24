package me.csepregi.pkmrec.ui

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.pokedex.PokemonNames
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.recognition.MultiCardReader
import me.csepregi.pkmrec.recognition.PokemonNameFinder
import me.csepregi.pkmrec.tcg.CardLookup
import me.csepregi.pkmrec.tcg.CollectionEntry
import me.csepregi.pkmrec.tcg.CollectionWriter
import me.csepregi.pkmrec.tcg.LookupOutcome
import me.csepregi.pkmrec.tcg.MatchResult
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.PokedexLookupResult
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.WriteOutcome
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** How a card read from a photo was found on TCG Collector. */
sealed interface SellLookup {
    data object Searching : SellLookup
    data class Found(val card: TcgCard) : SellLookup

    /** Several cards fit the reading: the user picks one. */
    data class Pick(val candidates: List<TcgCard>) : SellLookup
    data object NotFound : SellLookup
    data class Error(val error: TcgError) : SellLookup
}

/** The row's "Levesz" button. */
sealed interface RemoveState {
    data object Idle : RemoveState
    data object Working : RemoveState

    /** One copy removed; [canUndo] for [AddUiState.UNDO_SECONDS] seconds. */
    data class Removed(val count: Int, val canUndo: Boolean) : RemoveState
    data class Undone(val count: Int) : RemoveState
    data object NotLoggedIn : RemoveState
    data object NothingToRemove : RemoveState
    data object NotApplied : RemoveState
    data class Error(val error: TcgError) : RemoveState
}

/** One card found on the photos; the same card twice on the photos is two rows. */
data class SellRow(
    val id: Int,
    val reading: CardReading,
    val lookup: SellLookup = SellLookup.Searching,
    val variantId: Int? = null,
    val remove: RemoveState = RemoveState.Idle,
    val inPokedexList: Boolean = false,
) {
    val card: TcgCard? get() = (lookup as? SellLookup.Found)?.card

    /** Found cards first, the ones to check by hand (several candidates, not found) at the end. */
    val needsAttention: Boolean get() = lookup !is SellLookup.Found && lookup != SellLookup.Searching
}

data class BulkSellState(
    val photos: List<Uri> = emptyList(),
    val identifying: Boolean = false,
    /** "2 / 5" while identifying. */
    val progress: Pair<Int, Int>? = null,
    val identified: Boolean = false,
    val notLoggedIn: Boolean = false,
    /** Photos that could not be read at all. */
    val unreadablePhotos: Int = 0,
    val rows: List<SellRow> = emptyList(),
)

class BulkSellViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(BulkSellState())
    val state: StateFlow<BulkSellState> = _state.asStateFlow()

    private val photoDir = File(app.cacheDir, "bulk-sell")
    private var identifyJob: Job? = null
    private var nextRowId = 0
    private val undoEntries = mutableMapOf<Int, List<CollectionEntry>>()
    private val undoTimers = mutableMapOf<Int, Job>()

    /** A new file for the camera app to save a photo into. */
    fun newPhotoUri(): Uri {
        photoDir.mkdirs()
        val file = File(photoDir, "photo-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(getApplication(), "${BuildConfig.APPLICATION_ID}.photos", file)
    }

    fun addPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(photos = (it.photos + uris).distinct()) }
    }

    fun removePhoto(uri: Uri) {
        _state.update { it.copy(photos = it.photos - uri) }
    }

    /** Reads every photo, then looks up every card found, one after the other. */
    fun identify() {
        val photos = _state.value.photos
        if (photos.isEmpty() || _state.value.identifying) return
        undoTimers.values.forEach { it.cancel() }
        undoTimers.clear()
        undoEntries.clear()
        _state.update {
            it.copy(identifying = true, identified = false, notLoggedIn = false, unreadablePhotos = 0, rows = emptyList(), progress = 0 to photos.size)
        }
        identifyJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val names = withContext(Dispatchers.IO) {
                runCatching { PokemonNameFinder(PokemonNames.all(app).map { it.name }) }.getOrNull()
            }
            val readings = mutableListOf<CardReading>()
            var unreadable = 0
            photos.forEachIndexed { index, photo ->
                _state.update { it.copy(progress = index + 1 to photos.size) }
                try {
                    readings += MultiCardReader.read(app, photo, names).map { it.reading }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLog.log("bulk sell photo", e, photo.toString())
                    unreadable++
                }
            }
            val rows = readings.map { SellRow(nextRowId++, it) }
            _state.update { it.copy(rows = rows, unreadablePhotos = unreadable, progress = null) }

            for (row in rows) {
                val lookup = when (val outcome = CardLookup.lookup(app, row.reading)) {
                    LookupOutcome.NotLoggedIn -> {
                        _state.update { it.copy(notLoggedIn = true) }
                        break
                    }
                    is LookupOutcome.Error -> SellLookup.Error(outcome.error)
                    is LookupOutcome.Found -> when (val match = outcome.match) {
                        MatchResult.NotFound -> SellLookup.NotFound
                        is MatchResult.Single -> SellLookup.Found(match.card)
                        is MatchResult.Ambiguous -> SellLookup.Pick(match.candidates)
                    }
                }
                updateRow(row.id) { it.withLookup(lookup) }
            }
            _state.update { it.copy(identifying = false, identified = true) }
            markPokedexList()
        }
    }

    fun pickCandidate(rowId: Int, card: TcgCard) {
        updateRow(rowId) { it.withLookup(SellLookup.Found(card)) }
        viewModelScope.launch { markPokedexList(listOf(rowId)) }
    }

    fun chooseVariant(rowId: Int, variantId: Int) {
        updateRow(rowId) { if (it.remove is RemoveState.Working) it else it.copy(variantId = variantId, remove = RemoveState.Idle) }
    }

    fun deleteRow(rowId: Int) {
        _state.update { s -> s.copy(rows = s.rows.filterNot { it.id == rowId }) }
    }

    fun remove(rowId: Int) {
        val row = row(rowId) ?: return
        val card = row.card ?: return
        val variant = card.variants.firstOrNull { it.id == row.variantId } ?: return
        if (row.remove == RemoveState.Working) return
        updateRow(rowId) { it.copy(remove = RemoveState.Working) }
        viewModelScope.launch {
            val outcome = CollectionWriter.removeOne(getApplication(), card, variant)
            if (outcome is WriteOutcome.Done) {
                undoEntries[rowId] = outcome.before
                updateRow(rowId) { it.withCount(variant.id, outcome.newCount).copy(remove = RemoveState.Removed(outcome.newCount, canUndo = true)) }
                undoTimers[rowId]?.cancel()
                undoTimers[rowId] = launch {
                    delay(AddUiState.UNDO_SECONDS * 1000L)
                    updateRow(rowId) { r -> (r.remove as? RemoveState.Removed)?.let { r.copy(remove = it.copy(canUndo = false)) } ?: r }
                }
            } else {
                updateRow(rowId) { it.copy(remove = failure(outcome)) }
            }
        }
    }

    fun undo(rowId: Int) {
        val row = row(rowId) ?: return
        val card = row.card ?: return
        val variant = card.variants.firstOrNull { it.id == row.variantId } ?: return
        val before = undoEntries[rowId] ?: return
        if ((row.remove as? RemoveState.Removed)?.canUndo != true) return
        undoTimers.remove(rowId)?.cancel()
        updateRow(rowId) { it.copy(remove = RemoveState.Working) }
        viewModelScope.launch {
            val outcome = CollectionWriter.restore(getApplication(), card, variant, before)
            if (outcome is WriteOutcome.Done) {
                undoEntries.remove(rowId)
                updateRow(rowId) { it.withCount(variant.id, outcome.newCount).copy(remove = RemoveState.Undone(outcome.newCount)) }
            } else {
                updateRow(rowId) { it.copy(remove = failure(outcome)) }
            }
        }
    }

    private fun failure(outcome: WriteOutcome): RemoveState = when (outcome) {
        WriteOutcome.NotLoggedIn -> RemoveState.NotLoggedIn
        WriteOutcome.NotApplied -> RemoveState.NotApplied
        WriteOutcome.NothingToRemove -> RemoveState.NothingToRemove
        is WriteOutcome.Error -> RemoveState.Error(outcome.error)
        is WriteOutcome.Done -> RemoveState.Idle
    }

    /** Marks the rows whose card is the one in the Pokedex list (read only; the list is never changed). */
    private suspend fun markPokedexList(rowIds: List<Int>? = null) {
        val app = getApplication<Application>()
        if (!PokedexList.isEnabled(app)) return
        val rows = _state.value.rows.filter { rowIds == null || it.id in rowIds }
        val checked = mutableMapOf<Int, Boolean>()
        for (row in rows) {
            val card = row.card ?: continue
            val inList = checked.getOrPut(card.id) {
                (PokedexList.lookup(app, card) as? PokedexLookupResult.Found)
                    ?.perPokemon?.any { pokemon -> pokemon.cards.any { it.id == card.id } } == true
            }
            if (inList) updateRow(row.id) { it.copy(inPokedexList = true) }
        }
    }

    private fun row(id: Int): SellRow? = _state.value.rows.firstOrNull { it.id == id }

    private fun updateRow(id: Int, change: (SellRow) -> SellRow) {
        _state.update { s -> s.copy(rows = s.rows.map { if (it.id == id) change(it) else it }) }
    }

    override fun onCleared() {
        identifyJob?.cancel()
        runCatching { photoDir.deleteRecursively() }
    }
}

/** A found card gets its only owned variant selected; with several the user picks one. */
private fun SellRow.withLookup(lookup: SellLookup): SellRow {
    val owned = (lookup as? SellLookup.Found)?.card?.variants?.filter { it.isOwned }.orEmpty()
    return copy(lookup = lookup, variantId = owned.singleOrNull()?.id, remove = RemoveState.Idle)
}

private fun SellRow.withCount(variantId: Int, count: Int): SellRow {
    val card = card ?: return this
    val variants = card.variants.map { if (it.id == variantId) it.copy(ownedEnglish = count) else it }
    return copy(lookup = SellLookup.Found(card.copy(variants = variants)))
}
