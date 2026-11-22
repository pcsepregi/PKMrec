package me.csepregi.pkmrec.cardmarket

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cardmarket showed a Cloudflare check that did not pass on its own. */
class CardmarketChallengeException(val url: String) : IOException("Cloudflare check on $url")

/** A Cardmarket page that the extractor script could not read. */
class CardmarketPageException(message: String) : IOException(message)

/**
 * Loads Cardmarket pages in an invisible WebView, like the in-app browser does, and reads them
 * with [CARDMARKET_EXTRACT_JS]. A plain HTTP client is stopped by Cloudflare; the WebView is not.
 * One page at a time. While a screen shows the WebView ([view], e.g. when [challenge] is on), a
 * Cloudflare check waits for the user to tick it instead of giving up after [TIMEOUT_MS].
 */
object CardmarketFetcher {

    private const val TIMEOUT_MS = 20_000L
    private const val VISIBLE_CHALLENGE_WAIT_MS = 180_000L
    private const val IMAGE_RETRIES = 10
    private const val IMAGE_RETRY_MS = 500L

    /** While a page loads, it is read this often once its HTML is in, not only when every resource has loaded. */
    private const val POLL_MS = 1_000L

    /** A page that timed out is loaded once more before giving up. */
    private const val PAGE_ATTEMPTS = 2

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var webView: WebView? = null

    private const val BLANK = "about:blank"

    /** Chromium errors that mean there is no connection at all. */
    private val OFFLINE_ERRORS = listOf("ERR_INTERNET_DISCONNECTED", "ERR_NAME_NOT_RESOLVED", "ERR_ADDRESS_UNREACHABLE", "ERR_NETWORK_CHANGED")

    private val _challenge = MutableStateFlow(false)

    /** A Cloudflare check is showing in the WebView right now: a screen may show it to be ticked. */
    val challenge: StateFlow<Boolean> = _challenge.asStateFlow()

    suspend fun load(context: Context, url: String): CardmarketPage = mutex.withLock {
        withContext(Dispatchers.Main) { loadWithRetry(context.applicationContext, url) }
    }

    private suspend fun loadWithRetry(context: Context, url: String): CardmarketPage {
        repeat(PAGE_ATTEMPTS - 1) {
            try {
                return loadOnce(context, url)
            } catch (e: SocketTimeoutException) {
                // Loaded again below.
            }
        }
        return loadOnce(context, url)
    }

    private suspend fun loadOnce(context: Context, url: String): CardmarketPage =
        navigate(context, url, CARDMARKET_EXTRACT_JS, pollMs = POLL_MS) { result ->
            val page = CardmarketPage.fromScriptResult(result)
            when {
                page == null -> Step.Fail(CardmarketPageException("unreadable page $url"))
                // A Cloudflare check usually passes by itself and reloads the page: keep waiting.
                page.challenge -> Step.Challenge
                // Read too early (still parsing, or the empty document before the page): wait for more.
                !page.isReadable -> Step.Wait(page.state)
                else -> Step.Done(page)
            }
        }

    /**
     * Downloads a picture by opening it in the WebView, with the same cookies as the pages, and
     * reading it back through a canvas (at most [CARDMARKET_IMAGE_SIZE] pixels).
     */
    suspend fun loadImage(context: Context, url: String): CardmarketImage = mutex.withLock {
        withContext(Dispatchers.Main) {
            val settings = view(context.applicationContext).settings
            val blocked = settings.blockNetworkImage
            settings.blockNetworkImage = false
            try {
                navigate(context.applicationContext, url, CARDMARKET_IMAGE_JS) { result ->
                    when (val image = CardmarketImage.fromScriptResult(result)) {
                        is CardmarketImage.Read.Loaded -> Step.Done(image.image)
                        CardmarketImage.Read.Loading -> Step.Retry
                        CardmarketImage.Read.Challenge -> Step.Challenge
                        is CardmarketImage.Read.Failed -> Step.Fail(CardmarketPageException(image.reason))
                    }
                }
            } finally {
                settings.blockNetworkImage = blocked
            }
        }
    }

    /** The WebView the pages are loaded in, to show on screen while a long refresh runs. Main thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    fun view(context: Context): WebView = webView ?: WebView(context.applicationContext).apply {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkImage = true
        webView = this
    }

    /**
     * Unloads the last Cardmarket page when the app goes to the background, so its scripts do not
     * keep running there. Not while a page is being read (e.g. a sealed price round), nor while a
     * Cloudflare check waits to be ticked. Main thread only.
     */
    fun release() {
        val view = webView ?: return
        if (mutex.isLocked || _challenge.value) return
        view.loadUrl(BLANK)
    }

    private sealed interface Step<out T> {
        data class Done<T>(val value: T) : Step<T>
        data class Fail(val error: Exception) : Step<Nothing>
        /** A Cloudflare check is showing. */
        data object Challenge : Step<Nothing>
        /** Not ready yet: run the script again shortly. */
        data object Retry : Step<Nothing>
        /** The page is not loaded yet ([state] for the log): read again at the next poll or page finish. */
        data class Wait(val state: String) : Step<Nothing>
    }

    /**
     * Loads [url] and reads it with [script] when the page finishes loading; with [pollMs], also
     * every [pollMs] once the new page has started (a page can show its offers long before a slow
     * ad or tracker lets it "finish").
     */
    private suspend fun <T> navigate(context: Context, url: String, script: String, pollMs: Long = 0, read: (String?) -> Step<T>): T =
        suspendCancellableCoroutine { cont ->
            val view = view(context)
            val started = SystemClock.uptimeMillis()
            var challenged = false
            var retries = 0
            var done = false
            /** The new page has started loading (not the blank page or the previous page). */
            var pageStarted = false
            var lastState = "not read"

            fun finish(result: Result<T>) {
                if (done) return
                done = true
                _challenge.value = false
                main.removeCallbacksAndMessages(view)
                view.webViewClient = WebViewClient()
                // The page stays loaded until the next one: loading a blank page after every read
                // (2.3.0–2.5.0) got in the way of the next load, which was then read empty.
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            val timeout = object : Runnable {
                override fun run() {
                    // Someone can see the check: give them time to tick it.
                    if (challenged && view.isAttachedToWindow && view.isShown && SystemClock.uptimeMillis() - started < VISIBLE_CHALLENGE_WAIT_MS) {
                        main.postAtTime(this, view, SystemClock.uptimeMillis() + TIMEOUT_MS)
                        return
                    }
                    view.stopLoading()
                    finish(Result.failure(if (challenged) CardmarketChallengeException(url) else SocketTimeoutException("Cardmarket: $url (started=$pageStarted, last read: $lastState)")))
                }
            }

            fun evaluate() {
                view.evaluateJavascript(script) { result ->
                    when (val step = read(result)) {
                        is Step.Done -> finish(Result.success(step.value))
                        is Step.Fail -> finish(Result.failure(step.error))
                        Step.Challenge -> {
                            challenged = true
                            _challenge.value = true
                        }
                        Step.Retry -> if (++retries <= IMAGE_RETRIES) {
                            main.postAtTime({ if (!done) evaluate() }, view, SystemClock.uptimeMillis() + IMAGE_RETRY_MS)
                        }
                        is Step.Wait -> lastState = step.state
                    }
                }
            }

            val poll = object : Runnable {
                override fun run() {
                    if (done) return
                    if (pageStarted && !challenged) evaluate()
                    main.postAtTime(this, view, SystemClock.uptimeMillis() + pollMs)
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    if (pageUrl != null && pageUrl != BLANK) pageStarted = true
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    // A blank page loaded when the app went to the background may finish after this load started.
                    if (pageUrl == BLANK) return
                    evaluate()
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val description = error.description?.toString().orEmpty()
                    val e = when {
                        // Only these mean the phone is offline: a refused connection must not stop a price round.
                        error.errorCode == WebViewClient.ERROR_HOST_LOOKUP || OFFLINE_ERRORS.any { it in description } ->
                            UnknownHostException("Cardmarket: $description")
                        error.errorCode == WebViewClient.ERROR_TIMEOUT -> SocketTimeoutException("Cardmarket: $description")
                        else -> IOException("Cardmarket: $description (${error.errorCode})")
                    }
                    finish(Result.failure(e))
                }
            }
            main.postAtTime(timeout, view, SystemClock.uptimeMillis() + TIMEOUT_MS)
            if (pollMs > 0) main.postAtTime(poll, view, SystemClock.uptimeMillis() + pollMs)
            view.loadUrl(url)
            cont.invokeOnCancellation {
                main.post {
                    done = true
                    _challenge.value = false
                    main.removeCallbacksAndMessages(view)
                    view.stopLoading()
                }
            }
        }
}
