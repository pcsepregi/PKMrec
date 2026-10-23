package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokedexListTest {

    @Test
    fun `reads the pokedex entry from a card page footer`() {
        val html = """
            <nav><a href="/pokedex/intl">Pokédex</a></nav>
            <div class="card-info-footer-item">
              <div class="card-info-footer-item-title">Illustrators</div>
              <div class="card-info-footer-item-text-container">5ban Graphics</div>
            </div>
            <div class="card-info-footer-item">
              <div class="card-info-footer-item-title">Pokédex</div>
              <div class="card-info-footer-item-text-container">
                <span class="card-info-footer-item-text"><a href="/pokedex/909/fuecoco/intl" class="card-info-footer-item-text"> #0909</a></span>
              </div>
            </div>
        """.trimIndent()
        assertEquals(listOf(PokedexEntry(909, "fuecoco", "/pokedex/909/fuecoco/intl")), PokedexList.parsePokedexEntries(html))
    }

    @Test
    fun `multi-pokemon cards have several entries, trainers none`() {
        val tagTeam = """
            <div class="card-info-footer-item">
              <div class="card-info-footer-item-title">Pokédex</div>
              <div class="card-info-footer-item-text-container">
                <a href="/pokedex/25/pikachu/intl">#0025</a>, <a href="/pokedex/644/zekrom/intl">#0644</a>
              </div>
            </div>
        """.trimIndent()
        assertEquals(listOf(25, 644), PokedexList.parsePokedexEntries(tagTeam).map { it.number })
        assertTrue(PokedexList.parsePokedexEntries("<div class=\"card-info-footer-item\">Rarity</div>").isEmpty())
    }
}
