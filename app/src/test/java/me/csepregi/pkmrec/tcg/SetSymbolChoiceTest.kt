package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetSymbolChoiceTest {

    private fun card(id: Int, expansion: String) =
        TcgCard(id, "Card $id", expansion, null, "25/73", path = "/cards/$id/x", imageUrl = null, regionId = 1, variants = emptyList())

    private val slg = card(1, "Shining Legends")
    private val cpa = card(2, "Champion's Path")
    private val ids = mapOf(1 to "sm35", 2 to "swsh45")

    @Test
    fun `a clear symbol winner picks the card and sorts the candidates`() {
        val ranking = SetSymbolChoice.choose(listOf(cpa, slg), ids, mapOf("sm35" to 0.8f, "swsh45" to 0.4f))
        assertEquals(slg, ranking.chosen)
        assertEquals(listOf(slg, cpa), ranking.candidates)
        assertEquals(0.8f, ranking.scores[1]!!, 1e-6f)
    }

    @Test
    fun `no choice when scores are close or a candidate has no symbol`() {
        assertNull(SetSymbolChoice.choose(listOf(slg, cpa), ids, mapOf("sm35" to 0.8f, "swsh45" to 0.75f)).chosen)
        assertNull(SetSymbolChoice.choose(listOf(slg, cpa), mapOf(1 to "sm35"), mapOf("sm35" to 0.9f)).chosen)
    }

    @Test
    fun `no choice when the winning set has two candidates`() {
        val slg2 = card(3, "Shining Legends")
        val ranking = SetSymbolChoice.choose(listOf(slg, slg2, cpa), ids + (3 to "sm35"), mapOf("sm35" to 0.9f, "swsh45" to 0.2f))
        assertNull(ranking.chosen)
    }
}
