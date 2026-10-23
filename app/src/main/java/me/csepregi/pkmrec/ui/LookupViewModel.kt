package me.csepregi.pkmrec.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.recognition.CardReading
import me.csepregi.pkmrec.tcg.CardLookup
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.LookupOutcome
import me.csepregi.pkmrec.tcg.MatchResult
import me.csepregi.pkmrec.tcg.TcgCard

/** The reading handed from the camera screen to the lookup screen. */
object CurrentScan {
    @Volatile var reading: CardReading? = null
}

sealed interface LookupUiState {
    data object Searching : LookupUiState
    data object NotLoggedIn : LookupUiState
    data class Error(val message: String) : LookupUiState
    data object NotFound : LookupUiState
    data class PickCandidate(val candidates: List<TcgCard>) : LookupUiState
    data class PickVariant(val card: TcgCard, val canGoBackToCandidates: Boolean) : LookupUiState

    /** [variant] is null only when TCG Collector lists no variants for the card. */
    data class Verdict(val card: TcgCard, val variant: CardVariant?, val canPickOtherVariant: Boolean) : LookupUiState {
        val owned: Boolean get() = variant?.isOwned == true
    }
}

class LookupViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<LookupUiState>(LookupUiState.Searching)
    val state: StateFlow<LookupUiState> = _state.asStateFlow()

    var reading: CardReading? = null
        private set

    private var candidates: List<TcgCard> = emptyList()

    fun start(reading: CardReading) {
        if (this.reading == reading && _state.value != LookupUiState.Searching) return
        this.reading = reading
        search()
    }

    fun retry() = search()

    private fun search() {
        val r = reading ?: return
        _state.value = LookupUiState.Searching
        viewModelScope.launch {
            _state.value = when (val outcome = CardLookup.lookup(getApplication(), r)) {
                LookupOutcome.NotLoggedIn -> LookupUiState.NotLoggedIn
                is LookupOutcome.Error -> LookupUiState.Error(outcome.message)
                is LookupOutcome.Found -> when (val match = outcome.match) {
                    MatchResult.NotFound -> LookupUiState.NotFound
                    is MatchResult.Single -> {
                        candidates = emptyList()
                        stateForCard(match.card)
                    }
                    is MatchResult.Ambiguous -> {
                        candidates = match.candidates
                        LookupUiState.PickCandidate(match.candidates)
                    }
                }
            }
        }
    }

    fun chooseCard(card: TcgCard) {
        _state.value = stateForCard(card)
    }

    fun chooseVariant(card: TcgCard, variant: CardVariant) {
        _state.value = LookupUiState.Verdict(card, variant, canPickOtherVariant = card.variants.size > 1)
    }

    fun backToVariants(card: TcgCard) {
        _state.value = LookupUiState.PickVariant(card, canGoBackToCandidates = candidates.isNotEmpty())
    }

    fun backToCandidates() {
        if (candidates.isNotEmpty()) _state.value = LookupUiState.PickCandidate(candidates)
    }

    /** One variant: nothing to choose, go straight to the verdict. */
    private fun stateForCard(card: TcgCard): LookupUiState = when (card.variants.size) {
        0 -> LookupUiState.Verdict(card, null, canPickOtherVariant = false)
        1 -> LookupUiState.Verdict(card, card.variants.first(), canPickOtherVariant = false)
        else -> LookupUiState.PickVariant(card, canGoBackToCandidates = candidates.isNotEmpty())
    }
}
