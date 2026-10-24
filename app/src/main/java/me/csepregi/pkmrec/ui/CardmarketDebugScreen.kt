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
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.cardmarket.Cardmarket
import me.csepregi.pkmrec.cardmarket.CardmarketPrice
import me.csepregi.pkmrec.cardmarket.CARDMARKET_EXTRACT_JS
import me.csepregi.pkmrec.cardmarket.CardmarketFetcher
import me.csepregi.pkmrec.cardmarket.CardmarketPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug screen for the Cardmarket price: opens Cardmarket in a visible WebView, applies the
 * English / Good-or-better filter, computes the price with Pali's rule and builds a report to
 * copy, to fix the page reading when Cardmarket changes. Nothing is sent anywhere by the app.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CardmarketDebugScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("Charizard ex") }
    var lastPage by remember { mutableStateOf<CardmarketPage?>(null) }
    var lastProductPage by remember { mutableStateOf<CardmarketPage?>(null) }
    val log = remember { mutableStateListOf<String>() }
    val start = remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    var sealedRunning by remember { mutableStateOf(false) }
    var sealedSample by remember { mutableStateOf<String?>(null) }

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
                Text("Cardmarket hibakeresés", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                            sealedRunning = true
                            scope.launch {
                                sealedSample = runSealedProbe(context, ::note)
                                sealedRunning = false
                            }
                        },
                        enabled = !sealedRunning,
                    ) { Text(if (sealedRunning) "Sealed próba fut…" else "Sealed próba") }
                }
                Button(
                    onClick = {
                        val report = buildReport(log, lastProductPage, sealedSample)
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
                                view.evaluateJavascript(CARDMARKET_EXTRACT_JS) { result ->
                                    val page = CardmarketPage.fromScriptResult(result)
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
                                        page.productLinks.take(10).forEach { note("  találat: ${it.label} -> ${it.href}") }
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

/** Sealed products from the Cardmarket catalogue, for the probe: idProduct and name. */
private val SEALED_PROBES = listOf(
    784963 to "Surging Sparks Elite Trainer Box",
    798940 to "Prismatic Evolutions: Umbreon Mini Tin",
)

/**
 * Checks how sealed products can be priced: whether a product opens by its catalogue id,
 * whether the name search finds it, and what its English offers look like. Writes to [note];
 * returns the first offer row's HTML.
 */
private suspend fun runSealedProbe(context: Context, note: (String) -> Unit): String? {
    var sample: String? = null
    suspend fun open(url: String): CardmarketPage? = try {
        val t = System.currentTimeMillis()
        CardmarketFetcher.load(context, url).also {
            note("  ${System.currentTimeMillis() - t} ms -> ${it.url} \"${it.title}\" idProduct=${it.idProduct} termék=\"${it.productName}\" sorok=${it.rowCount}")
        }
    } catch (e: Exception) {
        note("  hiba: ${e.javaClass.simpleName} ${e.message}")
        null
    }
    note("== Sealed próba")
    for ((id, name) in SEALED_PROBES) {
        note("Termék: $name ($id)")
        val url = Cardmarket.productByIdUrl(id)
        note(" azonosítóval: $url")
        open(url)
        note(" keresés: $name")
        val search = open(Cardmarket.searchUrl(name)) ?: continue
        val productUrl = if (Cardmarket.isSealedProductPage(search.url)) {
            search.url
        } else {
            search.sealedLinks.take(10).forEach { note("  találat: ${it.label} -> ${it.href}") }
            val link = search.sealedLinks.firstOrNull { it.label.equals(name, ignoreCase = true) } ?: search.sealedLinks.firstOrNull()
            link?.let { Cardmarket.absolute(it.href) }
        }
        if (productUrl == null) {
            note("  nincs sealed találat")
            continue
        }
        note(" angol ajánlatok")
        val page = open(Cardmarket.englishOffersUrl(productUrl)) ?: continue
        page.rawOffers.take(12).forEach { note("  sor: $it") }
        val estimate = CardmarketPrice.estimate(page.offers)
        note("  ár: ${estimate.average?.let { CardmarketPrice.format(it) } ?: "nincs"} (számolható: ${estimate.eligible.size})")
        if (sample == null) sample = page.sample
    }
    note("== Sealed próba vége")
    return sample
}

private fun buildReport(log: List<String>, page: CardmarketPage?, sealedSample: String?): String = buildString {
    appendLine("PKMrec ${BuildConfig.VERSION_NAME} Cardmarket hibakeresés, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    appendLine()
    appendLine("== Napló")
    log.forEach { appendLine(it) }
    appendLine()
    sealedSample?.let {
        appendLine("== Sealed első sor HTML")
        appendLine(it)
        appendLine()
    }
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
    appendLine("== Sorok (ár | állapot | nyelv | reverse)")
    page.rawOffers.forEach { appendLine(it) }
    appendLine()
    appendLine("== Termék linkek")
    page.productLinks.forEach { appendLine("${it.label} -> ${it.href}") }
    appendLine()
    appendLine("== Első sor HTML")
    appendLine(page.sample)
}
