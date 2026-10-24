package me.csepregi.pkmrec.pokedex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonNamesTest {

    @Test
    fun `reads the pokedex page`() {
        val html = """
            <div class="pokedex-grid-items">
              <div class=" pokedex-grid-item caught " data-pokemon-id="1">
                <a href="/pokedex/1/bulbasaur?pokemonCardCountMode=allCardVariants" title="Bulbasaur" class="pokedex-grid-item-image-container"><img alt="Bulbasaur"></a>
                <div class="pokedex-grid-item-content">
                  <a href="/pokedex/1/bulbasaur?pokemonCardCountMode=allCardVariants" title="Bulbasaur" class="pokedex-grid-item-name"> Bulbasaur </a>
                  <div class="pokedex-grid-item-number"> #0001 </div>
                </div>
              </div>
              <div class=" pokedex-grid-item " data-pokemon-id="29">
                <div class="pokedex-grid-item-content">
                  <a href="/pokedex/29/nidoran-female?pokemonCardCountMode=allCardVariants" title="Nidoran ♀" class="pokedex-grid-item-name"> Nidoran ♀ </a>
                </div>
              </div>
            </div>
        """.trimIndent()
        assertEquals(
            listOf(PokemonName(1, "Bulbasaur", "bulbasaur"), PokemonName(29, "Nidoran ♀", "nidoran-female")),
            PokemonNames.parseIndexPage(html),
        )
        assertTrue(PokemonNames.parseIndexPage("<html><body>maintenance</body></html>").isEmpty())
    }

    @Test
    fun `saved list reads back the same`() {
        val names = listOf(PokemonName(83, "Farfetch'd", "farfetchd"), PokemonName(122, "Mr. Mime", "mr-mime"))
        assertEquals(names, PokemonNames.parse(PokemonNames.format(names)))
        assertEquals(names, PokemonNames.parse("# comment\n\n122\tMr. Mime\tmr-mime\nbroken line\n83\tFarfetch'd\tfarfetchd\n"))
    }
}
