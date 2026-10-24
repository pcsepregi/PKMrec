package me.csepregi.pkmrec.tcg

import android.content.Context
import me.csepregi.pkmrec.recognition.GrayImage
import me.csepregi.pkmrec.recognition.SetSymbols
import me.csepregi.pkmrec.recognition.SymbolMatcher

/**
 * The candidates of an ambiguous search compared with the set symbol seen next to the card's number.
 *
 * @property candidates best symbol match first
 * @property chosen the clear winner, or null when the symbols do not decide
 * @property symbolIds card ID → bundled set symbol ID, for showing the symbols in the picker
 * @property scores card ID → how well its set's symbol matched (0..1)
 */
data class SymbolRanking(
    val candidates: List<TcgCard>,
    val chosen: TcgCard?,
    val symbolIds: Map<Int, String>,
    val scores: Map<Int, Float>,
)

object SetSymbolChoice {

    /** Reads bundled images and compares them: call off the main thread. */
    fun rank(context: Context, crop: GrayImage?, candidates: List<TcgCard>): SymbolRanking {
        val index = SetSymbols.index(context)
        val symbolIds = candidates.mapNotNull { card ->
            index.find(card.expansionName, card.expansionCode)?.let { card.id to it.id }
        }.toMap()
        if (crop == null) return SymbolRanking(candidates, null, symbolIds, emptyMap())
        val images = symbolIds.values.distinct().mapNotNull { id -> SetSymbols.grayImage(context, id)?.let { id to it } }.toMap()
        return choose(candidates, symbolIds, SymbolMatcher.rank(crop, images).toMap())
    }

    /**
     * Orders [candidates] by their set's symbol score. A card is chosen only when every candidate's
     * symbol was compared, one set clearly won, and that set has a single candidate.
     */
    internal fun choose(candidates: List<TcgCard>, symbolIds: Map<Int, String>, setScores: Map<String, Float>): SymbolRanking {
        val scores = candidates.mapNotNull { card -> symbolIds[card.id]?.let { setScores[it] }?.let { card.id to it } }.toMap()
        val sorted = candidates.sortedByDescending { scores[it.id] ?: -1f }
        val allCompared = candidates.all { it.id in scores }
        val winner = if (allCompared) SymbolMatcher.decide(setScores.toList().sortedByDescending { it.second }) else null
        val chosen = winner?.let { set -> candidates.singleOrNull { symbolIds[it.id] == set } }
        return SymbolRanking(sorted, chosen, symbolIds, scores)
    }
}
