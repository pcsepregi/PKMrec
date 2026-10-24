package me.csepregi.pkmrec.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.recognition.GrayImage
import me.csepregi.pkmrec.tcg.CardLookup
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.LookupOutcome
import me.csepregi.pkmrec.tcg.MatchResult
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.PokedexListCards
import me.csepregi.pkmrec.tcg.PokedexLookupResult
import me.csepregi.pkmrec.tcg.SetSymbolChoice
import me.csepregi.pkmrec.tcg.SymbolRanking
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgError
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

class LookupViewModel(app: Application) : AndroidViewModel(app) {

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
            _state.value = when (val outcome = CardLookup.lookup(getApplication(), r)) {
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

    private fun verdict(card: TcgCard, variant: CardVariant?, canPickOtherVariant: Boolean) = LookupUiState.Verdict(
        card,
        variant,
        canPickOtherVariant = canPickOtherVariant,
        canGoBackToCandidates = candidates.isNotEmpty(),
        bySymbol = chosenBySymbol,
    )

    /** One variant: nothing to choose, go straight to the verdict. */
    private fun stateForCard(card: TcgCard): LookupUiState = when (card.variants.size) {
        0 -> verdict(card, null, canPickOtherVariant = false)
        1 -> verdict(card, card.variants.first(), canPickOtherVariant = false)
        else -> LookupUiState.PickVariant(card, canGoBackToCandidates = candidates.isNotEmpty(), bySymbol = chosenBySymbol)
    }
}
