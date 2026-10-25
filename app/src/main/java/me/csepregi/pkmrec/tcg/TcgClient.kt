package me.csepregi.pkmrec.tcg

import android.os.SystemClock
import android.webkit.CookieManager
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** TCG Collector website constants. */
object Tcg {
    const val BASE_URL = "https://www.tcgcollector.com"
    const val SIGN_IN_URL = "$BASE_URL/sign-in"

    /** A light page that is only fully available to a logged-in user; used to check the session. */
    const val SESSION_CHECK_URL = "$BASE_URL/account/settings"
}

/**
 * Shares cookies between OkHttp and Android's WebView, so the session created by logging in
 * on the WebView login page is used by the app's own HTTP requests.
 */
class WebViewCookieJar : CookieJar {
    private val manager: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

/** A downloaded page with its embedded appState. */
data class TcgPage(val finalUrl: String, val html: String, val appState: JsonObject?) {

    /** Every regular TCG Collector page has an appState; without one it is a maintenance page or the site changed. */
    fun requireAppState(): JsonObject = appState ?: throw if (html.contains("maintenance", ignoreCase = true)) {
        MaintenanceException()
    } else {
        PageFormatException("no appState on $finalUrl")
    }
}

/**
 * Low-level HTTP access to TCG Collector pages, using the WebView's session cookies and
 * the WebView's user agent so requests look like the in-app browser.
 */
class TcgClient(private val userAgent: String) {

    private val http = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetchPage(url: String): TcgPage {
        try {
            return fetchOnce(url)
        } catch (e: HttpStatusException) {
            // TCG Collector refuses requests for a while after a burst; one slow retry often gets through.
            if (e.code != 403) throw e
            Thread.sleep(BLOCKED_RETRY_MS)
            return fetchOnce(url)
        }
    }

    private fun fetchOnce(url: String): TcgPage {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        waitTurn()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            val html = response.body!!.string()
            return TcgPage(response.request.url.toString(), html, AppStateExtractor.extract(html))
        }
    }

    /**
     * Sends a JSON request like the website's own scripts do (`X-Requested-With`, CSRF header)
     * and returns the response text. Throws [HttpStatusException] when TCG Collector refuses it.
     */
    fun sendJson(method: String, url: String, json: String, headers: Map<String, String>, referer: String): String {
        val builder = Request.Builder()
            .url(url)
            .method(method, json.toRequestBody(JSON))
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", Tcg.BASE_URL)
            .header("Referer", referer)
        headers.forEach { (name, value) -> builder.header(name, value) }
        waitTurn()
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatusException(response.code)
            return response.body?.string().orEmpty()
        }
    }

    private val turnLock = Any()
    private var lastRequestAt = 0L

    /** Keeps [MIN_REQUEST_GAP_MS] between requests from the whole app, so bulk lookups do not look like a burst. */
    private fun waitTurn() = synchronized(turnLock) {
        val wait = lastRequestAt + MIN_REQUEST_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = SystemClock.elapsedRealtime()
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MIN_REQUEST_GAP_MS = 1_000L
        const val BLOCKED_RETRY_MS = 10_000L
    }
}
