package me.csepregi.pkmrec.cardmarket

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class CardmarketImageTest {

    /** evaluateJavascript hands back the script's string JSON-encoded once more. */
    private fun script(json: String) = JsonPrimitive(json).toString()

    @Test
    fun `a data URL becomes the picture bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val read = CardmarketImage.fromScriptResult(script("""{"data":"data:image/webp;base64,${Base64.getEncoder().encodeToString(bytes)}"}"""))
        read as CardmarketImage.Read.Loaded
        assertArrayEquals(bytes, read.image.bytes)
        assertEquals("webp", read.image.extension)
    }

    @Test
    fun `a browser without WebP gives PNG`() {
        val read = CardmarketImage.fromScriptResult(script("""{"data":"data:image/png;base64,AQI="}"""))
        assertEquals("png", (read as CardmarketImage.Read.Loaded).image.extension)
    }

    @Test
    fun `challenge, loading and errors are told apart`() {
        assertEquals(CardmarketImage.Read.Challenge, CardmarketImage.fromScriptResult(script("""{"challenge":"true"}""")))
        assertEquals(CardmarketImage.Read.Loading, CardmarketImage.fromScriptResult(script("""{"loading":"true"}""")))
        assertEquals(CardmarketImage.Read.Failed("the picture did not load"), CardmarketImage.fromScriptResult(script("""{"error":"the picture did not load"}""")))
        assertTrue(CardmarketImage.fromScriptResult(script("""{"data":"data:text/html;base64,AQI="}""")) is CardmarketImage.Read.Failed)
        assertTrue(CardmarketImage.fromScriptResult("null") is CardmarketImage.Read.Failed)
        assertTrue(CardmarketImage.fromScriptResult(null) is CardmarketImage.Read.Failed)
    }
}
