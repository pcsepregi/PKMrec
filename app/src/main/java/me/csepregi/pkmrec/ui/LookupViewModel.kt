package me.csepregi.pkmrec.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.recognition.GrayImage
import me.csepregi.pkmrec.tcg.CardLookup
import me.csepregi.pkmrec.tcg.CardMatcher
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.CollectionEntry
import me.csepregi.pkmrec.tcg.CollectionWriter
import me.csepregi.pkmrec.tcg.LookupOutcome
import me.csepregi.pkmrec.tcg.MatchResult
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.PokedexListCards
import me.csepregi.pkmrec.tcg.PokedexLookupResult
import me.csepregi.pkmrec.tcg.SetSymbolChoice
import me.csepregi.pkmrec.tcg.SymbolRanking
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.WriteOutcome
import kotlin.coroutines.cancellation.CancellationException

/** The reading handed from the camera screen to the lookup screen, with the set symbol crop if one was seen. */
object CurrentScan {
    @Volatile var reading: CardReading? = null
    @Volatile var symbol: GrayImage? = null
}

sealed interface LookupUiState {
    data object Searching : LookupUiState
    data object NotLoggedIn : LookupUiState
    data class Error(val error: TcgError) : LookupUiState
    data object NotFound : LookupUiState
    /** [symbols] (when not null) gives each candidate's set symbol and how well it matched the card. */
    data class PickCandidate(val candidates: List<TcgCard>, val symbols: SymbolRanking? = null) : LookupUiState

    /** [bySymbol]: the card was picked among several by its set symbol. */
    data class PickVariant(val card: TcgCard, val canGoBackToCandidates: Boolean, val bySymbol: Boolean = false) : LookupUiState

    /** [variant] is null only when TCG Collector lists no variants for the card. */
    data class Verdict(
        val card: TcgCard,
        val variant: CardVariant?,
        val canPickOtherVariant: Boolean,
        val canGoBackToCandidates: Boolean = false,
        val bySymbol: Boolean = false,
    ) : LookupUiState {
        val owned: Boolean get() = variant?.isOwned == true
    }
}

/** The "which card of this Pokémon is in my Pokedex list" section. */
sealed interface PokedexUiState {
    data object Idle : PokedexUiState
    data object Loading : PokedexUiState
    data object NotAPokemon : PokedexUiState
    data object NotLoggedIn : PokedexUiState
    data class Error(val error: TcgError) : PokedexUiState
    data class Loaded(val perPokemon: List<PokedexListCards>, val scannedCardId: Int) : PokedexUiState
}

/** The "Hozzáadás a kollekcióhoz" button under the verdict. */
sealed interface AddUiState {
    data object Idle : AddUiState
    data object Adding : AddUiState

    /** One copy added; [canUndo] for [UNDO_SECONDS] seconds after it. */
    data class Added(val count: Int, val canUndo: Boolean) : AddUiState
    data object Undoing : AddUiState
    data class Undone(val count: Int) : AddUiState
    data object NotLoggedIn : AddUiState

    /** TCG Collector answered, but the card does not show the change. */
    data object NotApplied : AddUiState
    data class Error(val error: TcgError) : AddUiState

    companion object {
        const val UNDO_SECONDS = 6
    }
}

class LookupViewModel(app: Application) : AndroidViewModel(app) {

    private val _add = MutableStateFlow<AddUiState>(AddUiState.Idle)
    val add: StateFlow<AddUiState> = _add.asStateFlow()
    private var undoTimer: Job? = null

    /** Changes with every new verdict, so a late write result is not shown on another card. */
    private var addGeneration = 0

    /** The entries before the last add, for undo. */
    private var undoEntries: List<CollectionEntry>? = null

    private val _pokedex = MutableStateFlow<PokedexUiState>(PokedexUiState.Idle)
    val pokedex: StateFlow<PokedexUiState> = _pokedex.asStateFlow()
    private var pokedexJob: Job? = null
    private var pokedexCardId: Int? = null

    private val _state = MutableStateFlow<LookupUiState>(LookupUiState.Searching)
    val state: StateFlow<LookupUiState> = _state.asStateFlow()

    var reading: CardReading? = null
        private set

    private var candidates: List<TcgCard> = emptyList()
    private var symbolRanking: SymbolRanking? = null
    private var symbol: GrayImage? = null

    /** The card was chosen among several candidates by its set symbol (not by the user). */
    private var chosenBySymbol = false

    fun start(reading: CardReading, symbol: GrayImage? = null) {
        if (this.reading == reading && _state.value != LookupUiState.Searching) return
        this.reading = reading
        this.symbol = symbol
        search()
    }

    fun retry() = search()

    private fun search() {
        val r = reading ?: return
        _state.value = LookupUiState.Searching
        viewModelScope.launch {
            val name = r.name
            val code = r.setCode
            val outcome = if (r.isNameOnly && name != null && code != null) {
                CardLookup.lookupByNameInSet(getApplication(), name, code)
            } else {
                checkedByName(CardLookup.lookup(getApplication(), r), name, code)
            }
            _state.value = when (outcome) {
                LookupOutcome.NotLoggedIn -> LookupUiState.NotLoggedIn
                is LookupOutcome.Error -> LookupUiState.Error(outcome.error)
                is LookupOutcome.Found -> when (val match = outcome.match) {
                    MatchResult.NotFound -> LookupUiState.NotFound
                    is MatchResult.Single -> {
                        candidates = emptyList()
                        symbolRanking = null
                        chosenBySymbol = false
                        loadPokedex(match.card)
                        stateForCard(match.card)
                    }
                    is MatchResult.Ambiguous -> {
                        val ranking = rankBySymbol(match.candidates)
                        candidates = ranking?.candidates ?: match.candidates
                        symbolRanking = ranking
                        val chosen = ranking?.chosen
                        chosenBySymbol = chosen != null
                        if (chosen != null) {
                            loadPokedex(chosen)
                            stateForCard(chosen)
                        } else {
                            LookupUiState.PickCandidate(candidates, ranking)
                        }
                    }
                }
            }
        }
    }

    /**
     * The number found a card whose name differs from the name read on the card: one of the two
     * was misread, so the cards of that name in the set are offered next to it.
     */
    private suspend fun checkedByName(outcome: LookupOutcome, name: String?, code: String?): LookupOutcome {
        val single = (outcome as? LookupOutcome.Found)?.match as? MatchResult.Single ?: return outcome
        if (name.isNullOrBlank() || code == null || CardMatcher.namesAgree(name, single.card.name)) return outcome
        val named = (CardLookup.lookupByNameInSet(getApplication(), name, code) as? LookupOutcome.Found)?.match
        val others = when (named) {
            is MatchResult.Single -> listOf(named.card)
            is MatchResult.Ambiguous -> named.candidates
            else -> emptyList()
        }.filter { it.id != single.card.id }
        return if (others.isEmpty()) outcome else LookupOutcome.Found(MatchResult.Ambiguous(listOf(single.card) + others))
    }

    /** Compares the set symbol seen on the card with the candidates' symbols; null if that fails. */
    private suspend fun rankBySymbol(cards: List<TcgCard>): SymbolRanking? = withContext(Dispatchers.Default) {
        try {
            SetSymbolChoice.rank(getApplication(), symbol, cards)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLog.log("set symbol", e, reading?.display.orEmpty())
            null
        }
    }

    fun chooseCard(card: TcgCard) {
        chosenBySymbol = false
        loadPokedex(card)
        _state.value = stateForCard(card)
    }

    fun retryPokedex() {
        val id = pokedexCardId ?: return
        pokedexCardId = null
        currentCard()?.takeIf { it.id == id }?.let { loadPokedex(it) }
    }

    /** Starts loading the Pokedex list card in the background while the user picks the variant. */
    private fun loadPokedex(card: TcgCard) {
        if (!PokedexList.isEnabled(getApplication())) {
            _pokedex.value = PokedexUiState.Idle
            return
        }
        val failed = _pokedex.value.let { it is PokedexUiState.Error || it == PokedexUiState.NotLoggedIn }
        if (pokedexCardId == card.id && !failed) return
        pokedexCardId = card.id
        pokedexJob?.cancel()
        _pokedex.value = PokedexUiState.Loading
        pokedexJob = viewModelScope.launch {
            _pokedex.value = when (val result = PokedexList.lookup(getApplication(), card)) {
                PokedexLookupResult.NotAPokemon -> PokedexUiState.NotAPokemon
                PokedexLookupResult.NotLoggedIn -> PokedexUiState.NotLoggedIn
                is PokedexLookupResult.Error -> PokedexUiState.Error(result.error)
                is PokedexLookupResult.Found -> PokedexUiState.Loaded(result.perPokemon, card.id)
            }
        }
    }

    private fun currentCard(): TcgCard? = when (val s = _state.value) {
        is LookupUiState.PickVariant -> s.card
        is LookupUiState.Verdict -> s.card
        else -> null
    }

    fun chooseVariant(card: TcgCard, variant: CardVariant) {
        _state.value = verdict(card, variant, canPickOtherVariant = card.variants.size > 1)
    }

    fun backToVariants(card: TcgCard) {
        _state.value = LookupUiState.PickVariant(card, canGoBackToCandidates = candidates.isNotEmpty(), bySymbol = chosenBySymbol)
    }

    fun backToCandidates() {
        if (candidates.isNotEmpty()) _state.value = LookupUiState.PickCandidate(candidates, symbolRanking)
    }

    /** Adds one copy of the verdict's card and variant to the collection. */
    fun addToCollection() {
        val v = _state.value as? LookupUiState.Verdict ?: return
        val variant = v.variant ?: return
        if (_add.value == AddUiState.Adding || _add.value == AddUiState.Undoing) return
        _add.value = AddUiState.Adding
        val generation = addGeneration
        viewModelScope.launch {
            val outcome = CollectionWriter.addOne(getApplication(), v.card, variant)
            if (generation != addGeneration) return@launch
            when (outcome) {
                is WriteOutcome.Done -> {
                    undoEntries = outcome.before
                    showCount(v, variant, outcome.newCount)
                    _add.value = AddUiState.Added(outcome.newCount, canUndo = true)
                    undoTimer?.cancel()
                    undoTimer = launch {
                        delay(AddUiState.UNDO_SECONDS * 1000L)
                        (_add.value as? AddUiState.Added)?.let { _add.value = it.copy(canUndo = false) }
                    }
                }
                else -> _add.value = failure(outcome)
            }
        }
    }

    fun undoAdd() {
        val v = _state.value as? LookupUiState.Verdict ?: return
        val variant = v.variant ?: return
        val before = undoEntries ?: return
        if ((_add.value as? AddUiState.Added)?.canUndo != true) return
        undoTimer?.cancel()
        _add.value = AddUiState.Undoing
        val generation = addGeneration
        viewModelScope.launch {
            val outcome = CollectionWriter.restore(getApplication(), v.card, variant, before)
            if (generation != addGeneration) return@launch
            when (outcome) {
                is WriteOutcome.Done -> {
                    undoEntries = null
                    showCount(v, variant, outcome.newCount)
                    _add.value = AddUiState.Undone(outcome.newCount)
                }
                else -> _add.value = failure(outcome)
            }
        }
    }

    private fun failure(outcome: WriteOutcome): AddUiState = when (outcome) {
        WriteOutcome.NotLoggedIn -> AddUiState.NotLoggedIn
        WriteOutcome.NotApplied, WriteOutcome.NothingToRemove -> AddUiState.NotApplied
        is WriteOutcome.Error -> AddUiState.Error(outcome.error)
        is WriteOutcome.Done -> AddUiState.Idle
    }

    /** Shows the variant's new English count on the verdict, if the user is still looking at it. */
    private fun showCount(v: LookupUiState.Verdict, variant: CardVariant, count: Int) {
        val updated = variant.copy(ownedEnglish = count)
        val card = v.card.copy(variants = v.card.variants.map { if (it.id == variant.id) updated else it })
        val current = _state.value as? LookupUiState.Verdict ?: return
        if (current.card.id == v.card.id && current.variant?.id == variant.id) {
            _state.value = current.copy(card = card, variant = updated)
        }
    }

    private fun verdict(card: TcgCard, variant: CardVariant?, canPickOtherVariant: Boolean): LookupUiState.Verdict {
        resetAdd()
        return LookupUiState.Verdict(
            card,
            variant,
            canPickOtherVariant = canPickOtherVariant,
            canGoBackToCandidates = candidates.isNotEmpty(),
            bySymbol = chosenBySymbol,
        )
    }

    /** A new verdict starts without add state. A write already sent is not cancelled, only its result is no longer shown. */
    private fun resetAdd() {
        addGeneration++
        undoTimer?.cancel()
        undoEntries = null
        _add.value = AddUiState.Idle
    }

    /** One variant: nothing to choose, go straight to the verdict. */
    private fun stateForCard(card: TcgCard): LookupUiState = when (card.variants.size) {
        0 -> verdict(card, null, canPickOtherVariant = false)
        1 -> verdict(card, card.variants.first(), canPickOtherVariant = false)
        else -> LookupUiState.PickVariant(card, canGoBackToCandidates = candidates.isNotEmpty(), bySymbol = chosenBySymbol)
    }
}
