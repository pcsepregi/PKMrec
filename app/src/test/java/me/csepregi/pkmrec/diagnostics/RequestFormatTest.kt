package me.csepregi.pkmrec.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestFormatTest {

    @Test
    fun masksTokensInJsonAndHeaders() {
        val body = """{"cardId":123,"csrfToken":"abc123","userCardListId":65678}"""
        assertEquals("""{"cardId":123,"csrfToken":***,"userCardListId":65678}""", RequestFormat.mask(body))
        assertEquals("Content-Type: application/json; X-CSRF-TOKEN: ***", RequestFormat.mask("Content-Type: application/json; X-CSRF-TOKEN: abc123"))
    }

    @Test
    fun masksPasswordsAndEmailsInForms() {
        val masked = RequestFormat.mask("email=pali@example.com&password=secret&remember=1")
        assertFalse(masked.contains("secret"))
        assertFalse(masked.contains("pali@"))
        assertTrue(masked.endsWith("&remember=1"))
    }

    @Test
    fun cutsLongText() {
        assertEquals("abc", RequestFormat.cut("abc", 5))
        assertEquals("abcde … (7 chars)", RequestFormat.cut("abcdefg", 5))
    }

    @Test
    fun entryShowsOnlyFilledParts() {
        val e = RequestRecorder.Entry("10:00:00", "fetch", "POST", "https://www.tcgcollector.com/x", "", "{\"a\":1}", "200", "")
        assertEquals("10:00:00 [fetch] POST https://www.tcgcollector.com/x -> 200\n  body: {\"a\":1}\n", RequestFormat.entry(e))
    }
}
