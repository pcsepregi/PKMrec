package me.csepregi.pkmrec.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.Tcg
import me.csepregi.pkmrec.tcg.TcgSession
import me.csepregi.pkmrec.tcg.TcgUser

/** Reads the logged-in user from the page; returns null for logged-out pages. */
private const val USER_PROBE_JS = """
(function () {
  try {
    var s = window.tcgcollector && window.tcgcollector.appState;
    var u = s && s.userDto;
    return u ? JSON.stringify({ userDto: { id: u.id, displayName: u.displayName, isPremiumEnabled: u.isPremiumEnabled } }) : null;
  } catch (e) { return null; }
})();
"""

/**
 * Shows TCG Collector's own sign-in page. The user types e-mail and password there; the app
 * never reads them. As soon as a page reports a logged-in user, the screen closes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finished by remember { mutableStateOf(false) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onDone()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone) { Text("‹ " + stringResource(R.string.back)) }
                Text(
                    text = stringResource(R.string.login_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.login_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val host = request.url.host.orEmpty()
                                val internal = host == "tcgcollector.com" || host.endsWith(".tcgcollector.com")
                                if (!internal && request.isForMainFrame) {
                                    // Links leaving TCG Collector (e.g. Discord) open in the normal browser.
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(USER_PROBE_JS) { result ->
                                    val user = parseProbeResult(result)
                                    if (user != null && !finished) {
                                        finished = true
                                        TcgSession.onLoggedIn(context, user)
                                        onDone()
                                    }
                                }
                            }
                        }
                        loadUrl(Tcg.SIGN_IN_URL)
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/** evaluateJavascript returns a JSON-encoded value: either `null` or a quoted JSON string. */
private fun parseProbeResult(result: String?): TcgUser? = runCatching {
    val outer = Json.parseToJsonElement(result ?: return null)
    val inner = (outer as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    TcgUser.fromAppState(Json.parseToJsonElement(inner).jsonObject as JsonObject)
}.getOrNull()
