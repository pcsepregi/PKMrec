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

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var webView: WebView? = null

    private val _challenge = MutableStateFlow(false)

    /** A Cloudflare check is showing in the WebView right now: a screen may show it to be ticked. */
    val challenge: StateFlow<Boolean> = _challenge.asStateFlow()

    suspend fun load(context: Context, url: String): CardmarketPage = mutex.withLock {
        withContext(Dispatchers.Main) {
            navigate(context.applicationContext, url, CARDMARKET_EXTRACT_JS) { result ->
                val page = CardmarketPage.fromScriptResult(result)
                when {
                    page == null -> Step.Fail(CardmarketPageException("unreadable page $url"))
                    // A Cloudflare check usually passes by itself and reloads the page: keep waiting.
                    page.challenge -> Step.Challenge
                    else -> Step.Done(page)
                }
            }
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

    private sealed interface Step<out T> {
        data class Done<T>(val value: T) : Step<T>
        data class Fail(val error: Exception) : Step<Nothing>
        /** A Cloudflare check is showing. */
        data object Challenge : Step<Nothing>
        /** Not ready yet: run the script again shortly. */
        data object Retry : Step<Nothing>
    }

    private suspend fun <T> navigate(context: Context, url: String, script: String, read: (String?) -> Step<T>): T =
        suspendCancellableCoroutine { cont ->
            val view = view(context)
            val started = SystemClock.uptimeMillis()
            var challenged = false
            var retries = 0
            var done = false

            fun finish(result: Result<T>) {
                if (done) return
                done = true
                _challenge.value = false
                main.removeCallbacksAndMessages(view)
                view.webViewClient = WebViewClient()
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
                    finish(Result.failure(if (challenged) CardmarketChallengeException(url) else SocketTimeoutException("Cardmarket: $url")))
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
                    }
                }
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) = evaluate()

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val e = when (error.errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT -> UnknownHostException("Cardmarket: ${error.description}")
                        WebViewClient.ERROR_TIMEOUT -> SocketTimeoutException("Cardmarket: ${error.description}")
                        else -> IOException("Cardmarket: ${error.description}")
                    }
                    finish(Result.failure(e))
                }
            }
            main.postAtTime(timeout, view, SystemClock.uptimeMillis() + TIMEOUT_MS)
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
