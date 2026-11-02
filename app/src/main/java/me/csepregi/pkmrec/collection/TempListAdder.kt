package me.csepregi.pkmrec.collection

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.tcg.CollectionEntry
import me.csepregi.pkmrec.tcg.CollectionWriter
import me.csepregi.pkmrec.tcg.TcgErrorKind
import me.csepregi.pkmrec.tcg.WriteOutcome

/** A card just added from the temporary list (and taken off it), with a short undo. */
data class RecentAdd(
    val item: TempListItem,
    val count: Int,
    val canUndo: Boolean,
    val undoing: Boolean = false,
    /** The undo failed: why (anything but [WriteOutcome.Done]). */
    val undoFailure: WriteOutcome? = null,
)

/** "Mindet a gyűjteménybe" in progress or finished. */
data class BulkAdd(
    val total: Int,
    val done: Int = 0,
    val added: Int = 0,
    val failed: Int = 0,
    val running: Boolean = true,
    /** Why the run stopped before the end (not logged in, TCG Collector blocking, cancelled); null if it did not. */
    val stoppedBy: WriteOutcome? = null,
    val cancelled: Boolean = false,
)

data class TempListAddState(
    /** Items being added now. */
    val working: Set<Long> = emptySet(),
    /** Items whose last add failed, and why. */
    val failures: Map<Long, WriteOutcome> = emptyMap(),
    val recent: List<RecentAdd> = emptyList(),
    val bulk: BulkAdd? = null,
) {
    val busy: Boolean get() = working.isNotEmpty() || recent.any { it.undoing } || bulk?.running == true
}

/**
 * Adds the temporary list's cards to the TCG Collector collection, one copy each, with
 * [CollectionWriter.addOne] (so every write is in [CollectionLog]). A card added is taken off the
 * list; a card that failed stays on it. Runs outside the screen, so leaving it does not stop a run.
 */
object TempListAdder {

    /** Pause between two cards of a bulk add, so TCG Collector does not start refusing the requests. */
    const val BULK_PAUSE_MS = 2_500L

    const val UNDO_SECONDS = 6

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(TempListAddState())
    val state: StateFlow<TempListAddState> = _state.asStateFlow()

    private var bulkJob: Job? = null
    private val undoEntries = mutableMapOf<Long, List<CollectionEntry>>()
    private val undoTimers = mutableMapOf<Long, Job>()

    /** One item, with a short undo after it. */
    fun addOne(context: Context, item: TempListItem) {
        if (_state.value.busy) return
        val app = context.applicationContext
        _state.update { it.copy(working = it.working + item.id, failures = it.failures - item.id) }
        scope.launch {
            val outcome = add(app, item)
            _state.update { it.copy(working = it.working - item.id) }
            if (outcome is WriteOutcome.Done) {
                undoEntries[item.id] = outcome.before
                _state.update { it.copy(recent = it.recent.filterNot { r -> r.item.id == item.id } + RecentAdd(item, outcome.newCount, canUndo = true)) }
                undoTimers[item.id]?.cancel()
                undoTimers[item.id] = launch {
                    delay(UNDO_SECONDS * 1000L)
                    undoEntries.remove(item.id)
                    _state.update { s -> s.copy(recent = s.recent.filterNot { it.item.id == item.id && it.undoFailure == null }) }
                }
            }
        }
    }

    /** Takes the copy added by [addOne] out again and puts the card back on the list. */
    fun undo(context: Context, itemId: Long) {
        val recent = _state.value.recent.firstOrNull { it.item.id == itemId } ?: return
        val before = undoEntries[itemId] ?: return
        if (!recent.canUndo || recent.undoing) return
        val app = context.applicationContext
        undoTimers.remove(itemId)?.cancel()
        updateRecent(itemId) { it.copy(undoing = true, undoFailure = null) }
        scope.launch {
            val item = recent.item
            val outcome = CollectionWriter.restore(app, item.toCard(), item.variant, before)
            if (outcome is WriteOutcome.Done) {
                undoEntries.remove(itemId)
                TempList.restore(app, item)
                _state.update { s -> s.copy(recent = s.recent.filterNot { it.item.id == itemId }) }
            } else {
                updateRecent(itemId) { it.copy(undoing = false, canUndo = false, undoFailure = outcome) }
            }
        }
    }

    fun dismissRecent(itemId: Long) {
        undoTimers.remove(itemId)?.cancel()
        undoEntries.remove(itemId)
        _state.update { s -> s.copy(recent = s.recent.filterNot { it.item.id == itemId }) }
    }

    /**
     * Every item on the list, one after the other with [BULK_PAUSE_MS] between them. Stops when the
     * session has expired, there is no internet or TCG Collector refuses the requests or has
     * changed; a timeout or an add that did not show skips only that card.
     */
    fun addAll(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        val items = TempList.items.value
        if (items.isEmpty()) return
        clearRecent()
        _state.update { it.copy(failures = emptyMap(), bulk = BulkAdd(total = items.size)) }
        bulkJob = scope.launch {
            for ((index, item) in items.withIndex()) {
                if (index > 0) delay(BULK_PAUSE_MS)
                // Deleted from the list while the run was going: skip it.
                if (TempList.items.value.none { it.id == item.id }) {
                    updateBulk { it.copy(done = it.done + 1) }
                    continue
                }
                _state.update { it.copy(working = it.working + item.id) }
                val outcome = add(app, item)
                _state.update { it.copy(working = it.working - item.id) }
                val ok = outcome is WriteOutcome.Done
                updateBulk { it.copy(done = it.done + 1, added = it.added + if (ok) 1 else 0, failed = it.failed + if (ok) 0 else 1) }
                if (stopsBulkAdd(outcome)) {
                    updateBulk { it.copy(running = false, stoppedBy = outcome) }
                    return@launch
                }
            }
            updateBulk { it.copy(running = false) }
        }
    }

    fun cancelBulk() {
        if (_state.value.bulk?.running != true) return
        bulkJob?.cancel()
        _state.update { it.copy(working = emptySet(), bulk = it.bulk?.copy(running = false, cancelled = true)) }
    }

    fun dismissBulk() {
        if (_state.value.bulk?.running == true) return
        _state.update { it.copy(bulk = null) }
    }

    /** Forgets the failure of a deleted item. */
    fun forget(itemId: Long) {
        _state.update { it.copy(failures = it.failures - itemId) }
    }

    fun forgetAll() {
        _state.update { it.copy(failures = emptyMap()) }
    }

    /** Adds one copy; on success the item leaves the list, on failure it stays with the reason. */
    private suspend fun add(context: Context, item: TempListItem): WriteOutcome {
        val outcome = CollectionWriter.addOne(context, item.toCard(), item.variant)
        if (outcome is WriteOutcome.Done) {
            TempList.remove(context, item.id)
        } else {
            _state.update { it.copy(failures = it.failures + (item.id to outcome)) }
        }
        return outcome
    }

    private fun clearRecent() {
        undoTimers.values.forEach { it.cancel() }
        undoTimers.clear()
        undoEntries.clear()
        _state.update { it.copy(recent = emptyList()) }
    }

    private fun updateRecent(itemId: Long, change: (RecentAdd) -> RecentAdd) {
        _state.update { s -> s.copy(recent = s.recent.map { if (it.item.id == itemId) change(it) else it }) }
    }

    private fun updateBulk(change: (BulkAdd) -> BulkAdd) {
        _state.update { s -> s.copy(bulk = s.bulk?.let(change)) }
    }
}

/** A bulk add stops after this outcome: going on would fail the same way for every card. */
internal fun stopsBulkAdd(outcome: WriteOutcome): Boolean = when (outcome) {
    WriteOutcome.NotLoggedIn -> true
    is WriteOutcome.Error -> outcome.error.kind != TcgErrorKind.TIMEOUT && outcome.error.kind != TcgErrorKind.OTHER
    else -> false
}
