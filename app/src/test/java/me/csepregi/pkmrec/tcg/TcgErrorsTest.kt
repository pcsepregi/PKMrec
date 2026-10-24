package me.csepregi.pkmrec.tcg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TcgErrorsTest {

    @Test
    fun `network failures are told apart`() {
        assertEquals(TcgErrorKind.NO_INTERNET, TcgError.kindOf(UnknownHostException("www.tcgcollector.com")))
        assertEquals(TcgErrorKind.NO_INTERNET, TcgError.kindOf(ConnectException("failed to connect")))
        assertEquals(TcgErrorKind.TIMEOUT, TcgError.kindOf(SocketTimeoutException("timeout")))
        assertEquals(TcgErrorKind.TIMEOUT, TcgError.kindOf(InterruptedIOException("timeout")))
        assertEquals(TcgErrorKind.OTHER, TcgError.kindOf(IllegalStateException("boom")))
    }

    @Test
    fun `http statuses map to rate limit and unavailable`() {
        assertEquals(TcgErrorKind.TOO_MANY_REQUESTS, TcgError.kindOf(HttpStatusException(429)))
        assertEquals(TcgErrorKind.UNAVAILABLE, TcgError.kindOf(HttpStatusException(503)))
        assertEquals(TcgErrorKind.UNAVAILABLE, TcgError.kindOf(HttpStatusException(502)))
        assertEquals(TcgErrorKind.OTHER, TcgError.kindOf(HttpStatusException(404)))
    }

    @Test
    fun `wrapped causes are found`() {
        assertEquals(TcgErrorKind.NO_INTERNET, TcgError.kindOf(IOException("wrapped", UnknownHostException())))
        assertEquals(TcgErrorKind.SITE_CHANGED, TcgError.kindOf(RuntimeException(PageFormatException("x"))))
    }

    @Test
    fun `detail keeps the technical text for the error log`() {
        assertEquals(TcgError(TcgErrorKind.TOO_MANY_REQUESTS, "HTTP 429"), TcgError.of(HttpStatusException(429)))
        assertEquals("IllegalStateException", TcgError.of(IllegalStateException()).detail)
    }

    @Test
    fun `a page without appState is maintenance or a changed site`() {
        val maintenance = TcgPage("u", "<html><body><h1>Down for maintenance</h1></body></html>", null)
        assertEquals(TcgErrorKind.UNAVAILABLE, kindOfFailure { maintenance.requireAppState() })

        val changed = TcgPage("u", "<html><script>window.tcgcollector = { state: {} };</script></html>", null)
        assertEquals(TcgErrorKind.SITE_CHANGED, kindOfFailure { changed.requireAppState() })

        val html = """<script>window.tcgcollector = { appState: {"userDto": null}, };</script>"""
        val normal = TcgPage("u", html, AppStateExtractor.extract(html))
        assertTrue(normal.requireAppState().containsKey("userDto"))
    }

    private fun kindOfFailure(block: () -> Unit): TcgErrorKind {
        try {
            block()
        } catch (e: Exception) {
            return TcgError.kindOf(e)
        }
        fail("expected an exception")
        throw AssertionError()
    }
}
