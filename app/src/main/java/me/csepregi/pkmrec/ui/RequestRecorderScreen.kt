package me.csepregi.pkmrec.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.diagnostics.RequestRecorder
import me.csepregi.pkmrec.tcg.PokedexList

/**
 * Wraps the page's fetch, XMLHttpRequest and form submits, and hands every request with its
 * answer to [RecorderBridge]. Safe to run more than once per page.
 */
private const val RECORDER_JS = """
(function () {
  if (window.__pkmrecRecorder || !window.PkmrecRecorder) return;
  window.__pkmrecRecorder = true;
  function send(o) { try { PkmrecRecorder.record(JSON.stringify(o)); } catch (e) {} }
  function bodyText(b) {
    if (b == null) return '';
    if (typeof b === 'string') return b;
    if (b instanceof URLSearchParams) return b.toString();
    if (b instanceof FormData) { var p = []; b.forEach(function (v, k) { p.push(k + '=' + v); }); return p.join('&'); }
    try { return JSON.stringify(b); } catch (e) { return String(b); }
  }
  function headerText(h) {
    if (!h) return '';
    try {
      if (h instanceof Headers) { var p = []; h.forEach(function (v, k) { p.push(k + ': ' + v); }); return p.join('; '); }
      return Object.keys(h).map(function (k) { return k + ': ' + h[k]; }).join('; ');
    } catch (e) { return ''; }
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      init = init || {};
      var isReq = typeof Request !== 'undefined' && input instanceof Request;
      var o = {
        kind: 'fetch',
        method: init.method || (isReq ? input.method : 'GET'),
        url: isReq ? input.url : String(input),
        headers: headerText(init.headers || (isReq ? input.headers : null)),
        body: bodyText(init.body)
      };
      return origFetch.apply(this, arguments).then(function (res) {
        o.status = String(res.status);
        res.clone().text().then(function (t) { o.response = t; send(o); }, function () { send(o); });
        return res;
      }, function (err) { o.status = 'error ' + err; send(o); throw err; });
    };
  }
  var open = XMLHttpRequest.prototype.open;
  var setHeader = XMLHttpRequest.prototype.setRequestHeader;
  var xsend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) {
    this.__pkmrec = { kind: 'xhr', method: method, url: String(url), headers: [] };
    return open.apply(this, arguments);
  };
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
    if (this.__pkmrec) this.__pkmrec.headers.push(k + ': ' + v);
    return setHeader.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function (body) {
    var o = this.__pkmrec, xhr = this;
    if (o) {
      o.body = bodyText(body);
      xhr.addEventListener('loadend', function () {
        o.headers = o.headers.join('; ');
        o.status = String(xhr.status);
        try { o.response = (xhr.responseType === '' || xhr.responseType === 'text') ? xhr.responseText : '(' + xhr.responseType + ')'; } catch (e) {}
        send(o);
      });
    }
    return xsend.apply(this, arguments);
  };
  document.addEventListener('submit', function (ev) {
    var f = ev.target;
    try { send({ kind: 'form', method: f.method || 'GET', url: f.action, headers: '', body: bodyText(new FormData(f)) }); } catch (e) {}
  }, true);
})();
"""

/** Receives the requests recorded by [RECORDER_JS]; called on a WebView background thread. */
private class RecorderBridge {
    @JavascriptInterface
    fun record(json: String) {
        val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
        fun s(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        RequestRecorder.record(s("kind"), s("method").ifEmpty { "GET" }, s("url"), s("headers"), s("body"), s("status"), s("response"))
    }
}

/**
 * Debug mode: TCG Collector in the in-app browser (same login as the app), recording the
 * requests the site sends while the user does something by hand, e.g. adds a card to a list.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun RequestRecorderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by RequestRecorder.entries.collectAsState()
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else onBack()
    }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
                Text(
                    text = stringResource(R.string.recorder_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(R.string.recorder_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.recorder_count, entries.size, RequestRecorder.MAX_ENTRIES),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { RequestRecorder.clear() }) { Text(stringResource(R.string.settings_debug_clear)) }
                }
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PKMrec TCG Collector requests", RequestRecorder.report()))
                        Toast.makeText(context, R.string.recorder_copied, Toast.LENGTH_SHORT).show()
                    },
                    enabled = entries.isNotEmpty(),
                ) { Text(stringResource(R.string.recorder_copy)) }
            }
            if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(RecorderBridge(), "PkmrecRecorder")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val host = request.url.host.orEmpty()
                                val internal = host == "tcgcollector.com" || host.endsWith(".tcgcollector.com")
                                if (!internal && request.isForMainFrame) {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                                    return true
                                }
                                return false
                            }

                            // Requests the page script does not send itself (plain form posts): method and address only.
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                if (request.method != "GET" && request.isForMainFrame) {
                                    RequestRecorder.record("page", request.method, request.url.toString(), "", "", "", "")
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageCommitVisible(view: WebView, url: String?) {
                                view.evaluateJavascript(RECORDER_JS, null)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(RECORDER_JS, null)
                            }
                        }
                        loadUrl(PokedexList.listUrl(context))
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
