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
 * One page at a time.
 */
object CardmarketFetcher {

    private const val TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private var webView: WebView? = null

    suspend fun load(context: Context, url: String): CardmarketPage = mutex.withLock {
        withContext(Dispatchers.Main) { loadOnMain(context.applicationContext, url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(context: Context): WebView = webView ?: WebView(context).apply {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkImage = true
        webView = this
    }

    private suspend fun loadOnMain(context: Context, url: String): CardmarketPage =
        suspendCancellableCoroutine { cont ->
            val view = view(context)
            var lastPage: CardmarketPage? = null
            var done = false

            fun finish(result: Result<CardmarketPage>) {
                if (done) return
                done = true
                main.removeCallbacksAndMessages(view)
                view.webViewClient = WebViewClient()
                if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
            }

            val timeout = Runnable {
                view.stopLoading()
                val page = lastPage
                finish(Result.failure(if (page?.challenge == true) CardmarketChallengeException(url) else SocketTimeoutException("Cardmarket: $url")))
            }

            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    view.evaluateJavascript(CARDMARKET_EXTRACT_JS) { result ->
                        val page = CardmarketPage.fromScriptResult(result)
                        when {
                            page == null -> finish(Result.failure(CardmarketPageException("unreadable page $pageUrl")))
                            // A Cloudflare check usually passes by itself and reloads the page: keep waiting.
                            page.challenge -> lastPage = page
                            else -> finish(Result.success(page))
                        }
                    }
                }

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
                    main.removeCallbacksAndMessages(view)
                    view.stopLoading()
                }
            }
        }
}
