package me.csepregi.pkmrec.tcg

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppStateExtractorTest {

    // Shape of the inline script on TCG Collector pages (values anonymised).
    private fun page(appState: String) = """
        <html><head><script>
              window.tcgcollector = {
                appState: $appState,
              };
        </script></head><body>Page</body></html>
    """.trimIndent()

    @Test
    fun `extracts appState with nested objects and braces inside strings`() {
        val html = page("""{"routeName":"cards_card_page","note":"a } tricky { \" string","userDto":{"id":42,"displayName":"Test User","isPremiumEnabled":true}}""")
        val state = AppStateExtractor.extract(html)
        assertNotNull(state)
        assertEquals("cards_card_page", state!!["routeName"]!!.jsonPrimitive.content)
        assertEquals(TcgUser(42, "Test User", true), TcgUser.fromAppState(state))
    }

    @Test
    fun `logged-out page has no user`() {
        val state = AppStateExtractor.extract(page("""{"routeName":"sign_in","userDto":null}"""))
        assertNotNull(state)
        assertNull(TcgUser.fromAppState(state!!))
    }

    @Test
    fun `returns null when the page has no appState`() {
        assertNull(AppStateExtractor.extract("<html><body>Maintenance</body></html>"))
    }
}
