package me.csepregi.pkmrec.pokedex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NameMatcherTest {

    /** The list bundled with the app (unit tests run in the app module's directory). */
    private val names = PokemonNames.parse(File("src/main/assets/pokemon_names.tsv").readText())

    private fun first(query: String): String? = NameMatcher.suggest(query, names).firstOrNull()?.name

    @Test
    fun `bundled list has every pokemon once`() {
        assertEquals(1025, names.size)
        assertEquals((1..1025).toList(), names.map { it.number })
        assertEquals(PokemonName(909, "Fuecoco", "fuecoco"), names[908])
        assertEquals(PokemonName(29, "Nidoran ♀", "nidoran-female"), names[28])
    }

    @Test
    fun `typos are corrected`() {
        assertEquals("Eevee", first("Eve"))
        assertEquals("Eevee", first("eeve"))
        assertEquals("Charizard", first("Charizrad"))
        assertEquals("Bulbasaur", first("bulbasur"))
        assertEquals("Gengar", first("Gengr"))
    }

    @Test
    fun `hungarian spelling is understood`() {
        assertEquals("Pikachu", first("Pikacsu"))
        assertEquals("Charmander", first("Csarmander"))
        assertEquals("Bulbasaur", first("Bulbaszaur"))
        assertEquals("Jirachi", first("Dzsirácsi"))
    }

    @Test
    fun `exact names, case and punctuation do not matter`() {
        assertEquals("Mr. Mime", first("mr mime"))
        assertEquals("Flabébé", first("Flabebe"))
        assertEquals("Farfetch'd", first("farfetchd"))
        assertEquals("Type: Null", first("type null"))
        assertEquals("Ho-Oh", first("HO-OH"))
    }

    @Test
    fun `beginning of a name lists the names starting with it`() {
        assertEquals("Pikachu", first("pika"))
        val nidoran = NameMatcher.suggest("nidoran", names).map { it.number }
        assertEquals(listOf(29, 32), nidoran.take(2))
        assertTrue(NameMatcher.suggest("pi", names).all { NameMatcher.normalize(it.name).startsWith("pi") })
    }

    @Test
    fun `number finds the pokedex entry`() {
        assertEquals("Eevee", first("133"))
        assertEquals("Eevee", first("#0133"))
        assertEquals("Bulbasaur", first("#1"))
        assertTrue(NameMatcher.suggest("9999", names).isEmpty())
    }

    @Test
    fun `nonsense finds nothing`() {
        assertTrue(NameMatcher.suggest("xqzwvk", names).isEmpty())
        assertTrue(NameMatcher.suggest("", names).isEmpty())
        assertTrue(NameMatcher.suggest("  ", names).isEmpty())
        assertTrue(NameMatcher.suggest("qx", names).isEmpty())
    }

    @Test
    fun `at most five suggestions`() {
        assertEquals(5, NameMatcher.suggest("char", names).size)
        assertEquals(2, NameMatcher.suggest("char", names, limit = 2).size)
    }

    @Test
    fun `distance counts swapped letters as one`() {
        assertEquals(1, NameMatcher.distance("eve", "eev"))
        assertEquals(0, NameMatcher.distance("abc", "abc"))
        assertEquals(3, NameMatcher.distance("", "abc"))
    }
}
