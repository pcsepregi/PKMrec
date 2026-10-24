package me.csepregi.pkmrec.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

class PokemonNameFinderFindTest {

    private val finder = PokemonNameFinder(listOf("Zekrom", "Garchomp", "Chandelure", "Cetitan", "Throh", "Mr. Mime"))

    @Test
    fun `finds names glued to stage labels, HP and ex`() {
        assertEquals("Zekrom", finder.find("CASIGNZekrom"))
        assertEquals("Garchomp", finder.find("m Garchomp330"))
        assertEquals("Chandelure", finder.find("CTAGCE Mega ChandelureeX"))
        assertEquals("Cetitan", finder.find("CetitanE"))
        assertEquals("Mr. Mime", finder.find("Mr Mime"))
    }

    @Test
    fun `short names must match exactly`() {
        assertEquals(null, finder.find("Overhead Throw"))
        assertEquals("Throh", finder.find("Throh"))
    }
}
