package me.csepregi.pkmrec.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.cardmarket.Cardmarket
import me.csepregi.pkmrec.cardmarket.CardmarketPrice
import me.csepregi.pkmrec.cardmarket.PriceGuide
import me.csepregi.pkmrec.cardmarket.ProbePage
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads what a Cardmarket page shows: Cloudflare challenge, product id, offer rows (price,
 * condition, language) and product links. Selectors are guesses, so the first row's HTML is
 * returned too, to fix them from the report.
 */
private const val EXTRACT_JS = """
(function () {
  function txt(e) { return e ? (e.innerText || e.textContent || '').trim() : ''; }
  var langs = ['English','German','French','Italian','Spanish','Japanese','Portuguese','Korean','Chinese','S-Chinese','T-Chinese','Russian','Dutch','Polish'];
  var conds = ['MT','NM','EX','GD','LP','PL','PO'];
  var title = document.title || '';
  var challenge = /just a moment|attention required|verify you are human/i.test(title) || !!document.querySelector('#challenge-form, #challenge-running, .cf-turnstile, #cf-challenge-running');
  var html = document.documentElement.innerHTML;
  var idm = html.match(/idProduct["'\s]*[:=]\s*["']?(\d+)/i);
  var h1 = document.querySelector('h1');
  var rows = document.querySelectorAll('.article-row, [id^="articleRow"]');
  var offers = [];
  for (var i = 0; i < rows.length && i < 60; i++) {
    var r = rows[i];
    var priceEl = r.querySelector('.col-offer .price-container, .price-container, .col-price');
    var priceText = txt(priceEl);
    if (!/€/.test(priceText)) { var m = txt(r).match(/\d[\d.]*,\d{2}\s*€/); priceText = m ? m[0] : ''; }
    var cond = '';
    var badges = r.querySelectorAll('.article-condition, .badge, [class*="condition"]');
    for (var b = 0; b < badges.length && !cond; b++) { var t = txt(badges[b]).toUpperCase(); if (conds.indexOf(t) >= 0) cond = t; }
    var lang = '';
    var attrs = r.querySelectorAll('[aria-label], [title], [data-original-title], [data-bs-original-title]');
    for (var a = 0; a < attrs.length && !lang; a++) {
      var el = attrs[a];
      var vals = [el.getAttribute('aria-label'), el.getAttribute('title'), el.getAttribute('data-original-title'), el.getAttribute('data-bs-original-title')];
      for (var v = 0; v < vals.length && !lang; v++) { if (vals[v] && langs.indexOf(vals[v].trim()) >= 0) lang = vals[v].trim(); }
    }
    offers.push({ price: priceText, condition: cond, language: lang });
  }
  var links = [];
  var as = document.querySelectorAll('a[href*="/Products/Singles/"]');
  for (var k = 0; k < as.length && links.length < 25; k++) {
    var label = txt(as[k]);
    if (label) links.push(label + ' -> ' + as[k].getAttribute('href'));
  }
  var sample = rows.length ? rows[0].outerHTML : (document.body ? document.body.innerHTML : '');
  return JSON.stringify({
    url: location.href, title: title, challenge: challenge ? 'true' : 'false',
    idProduct: idm ? parseInt(idm[1], 10) : null, productName: txt(h1),
    rowCount: rows.length, offers: offers, links: links, sample: sample.substring(0, 2500)
  });
})();
"""

/**
 * Test screen for the Cardmarket price feature: opens Cardmarket in a WebView on the phone,
 * applies the English / Good-or-better filter, computes the price with Pali's rule and builds
 * a report to copy. Nothing is sent anywhere by the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardmarketProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("Charizard ex") }
    var lastPage by remember { mutableStateOf<ProbePage?>(null) }
    var lastProductPage by remember { mutableStateOf<ProbePage?>(null) }
    var guideRunning by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val start = remember { mutableStateOf(0L) }

    fun note(line: String) {
        log += SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + line
    }

    fun load(url: String) {
        start.value = System.currentTimeMillis()
        note("Megnyitás: $url")
        webView?.loadUrl(url)
    }

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
                TextButton(onClick = onBack) { Text("‹ Vissza") }
                Text("Cardmarket próba", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        label = { Text("Kártya neve") },
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { load(Cardmarket.searchUrl(query)) }, enabled = query.isNotBlank()) { Text("Keresés") }
                }
                Text(
                    "Keress rá, nyiss meg egy kártyát, majd nyomd meg a „Szűrt ajánlatok” gombot. Végül másold ki a jelentést.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { lastPage?.let { load(Cardmarket.filteredOffersUrl(it.url)) } },
                        enabled = lastPage?.url?.let(Cardmarket::isProductPage) == true,
                    ) { Text("Szűrt ajánlatok") }
                    OutlinedButton(
                        onClick = {
                            val id = lastProductPage?.idProduct
                            guideRunning = true
                            scope.launch {
                                note(withContext(Dispatchers.IO) { priceGuideCheck(id) })
                                guideRunning = false
                            }
                        },
                        enabled = !guideRunning,
                    ) { Text(if (guideRunning) "Árlista…" else "Árlista teszt") }
                }
                Button(
                    onClick = {
                        val report = buildReport(log, lastProductPage)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PKMrec Cardmarket report", report))
                        Toast.makeText(context, "Jelentés vágólapra másolva", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Jelentés másolása") }
                lastProductPage?.let { page ->
                    val estimate = CardmarketPrice.estimate(page.offers)
                    Text(
                        (page.productName ?: "Termék") + ": " +
                            (estimate.average?.let { CardmarketPrice.format(it) } ?: "nincs ár") +
                            " (sorok: ${page.rowCount}, számolható: ${estimate.eligible.size})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Column(modifier = Modifier.heightIn(max = 90.dp).verticalScroll(rememberScrollState())) {
                    log.takeLast(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
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
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                view.evaluateJavascript(EXTRACT_JS) { result ->
                                    val page = ProbePage.fromScriptResult(result)
                                    if (page == null) {
                                        note("Oldal betöltve, de nem olvasható: $url")
                                        return@evaluateJavascript
                                    }
                                    lastPage = page
                                    val ms = System.currentTimeMillis() - start.value
                                    note(
                                        "Betöltve ${ms} ms: \"${page.title}\" challenge=${page.challenge} " +
                                            "idProduct=${page.idProduct} sorok=${page.rowCount}",
                                    )
                                    if (Cardmarket.isProductPage(page.url) && !page.challenge) {
                                        lastProductPage = page
                                    } else {
                                        page.links.take(10).forEach { note("  találat: $it") }
                                    }
                                }
                            }
                        }
                        loadUrl(Cardmarket.BASE_URL + "/en/Pokemon")
                        start.value = System.currentTimeMillis()
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/** Downloads the public price guide and looks up the product, to test the fallback price. */
private fun priceGuideCheck(idProduct: Int?): String = runCatching {
    val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    val started = System.currentTimeMillis()
    http.newCall(Request.Builder().url(Cardmarket.PRICE_GUIDE_URL).build()).execute().use { response ->
        val body = response.body?.string().orEmpty()
        val ms = System.currentTimeMillis() - started
        val head = "Árlista: HTTP ${response.code}, ${body.length / 1024} kB, $ms ms, termékek: ${PriceGuide.count(body)}"
        if (idProduct == null) return@use "$head (nincs megnyitott termék)"
        val entry = PriceGuide.find(body, idProduct)
        "$head; idProduct=$idProduct: " + (entry?.fields?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: "nincs benne")
    }
}.getOrElse { "Árlista hiba: ${it.javaClass.simpleName}: ${it.message}" }

private fun buildReport(log: List<String>, page: ProbePage?): String = buildString {
    appendLine("PKMrec ${BuildConfig.VERSION_NAME} Cardmarket próba, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    appendLine()
    appendLine("== Napló")
    log.forEach { appendLine(it) }
    appendLine()
    if (page == null) {
        appendLine("Nem volt megnyitott termékoldal.")
        return@buildString
    }
    val estimate = CardmarketPrice.estimate(page.offers)
    appendLine("== Utolsó termékoldal")
    appendLine("URL: ${page.url}")
    appendLine("Cím: ${page.title}")
    appendLine("Termék: ${page.productName}  idProduct=${page.idProduct}")
    appendLine("Ajánlat sorok: ${page.rowCount}")
    appendLine("Számolható árak: ${estimate.eligible.joinToString { CardmarketPrice.format(it) }}")
    appendLine("Átlagolt árak: ${estimate.counted.joinToString { CardmarketPrice.format(it) }}")
    appendLine("Ár: ${estimate.average?.let { CardmarketPrice.format(it) } ?: "nincs"}")
    appendLine()
    appendLine("== Sorok (ár | állapot | nyelv)")
    page.rawOffers.forEach { appendLine(it) }
    appendLine()
    appendLine("== Termék linkek")
    page.links.forEach { appendLine(it) }
    appendLine()
    appendLine("== Első sor HTML")
    appendLine(page.sample)
}
