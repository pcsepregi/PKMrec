package me.csepregi.pkmrec.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.cardmarket.CARDMARKET_EXTRACT_JS
import me.csepregi.pkmrec.cardmarket.Cardmarket
import me.csepregi.pkmrec.cardmarket.CardmarketPage
import me.csepregi.pkmrec.cardmarket.CardmarketPrice
import me.csepregi.pkmrec.cardmarket.CardmarketPrices
import me.csepregi.pkmrec.cardmarket.CardmarketResult
import me.csepregi.pkmrec.cardmarket.EurHuf
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.update.UpdateManager

/** Reverse holo offers are counted only for a reverse holo variant. */
fun isReverseHolo(variant: CardVariant?): Boolean = variant?.name?.contains("reverse", ignoreCase = true) == true

/**
 * "Cardmarket ár": the card's price by Pali's rule, loaded in the background after the rest of
 * the screen. Hidden when switched off in settings.
 */
@Composable
fun CardmarketPriceSection(card: TcgCard, reverseHolo: Boolean) {
    val context = LocalContext.current
    if (!remember { CardmarketPrices.isEnabled(context) }) return

    var refresh by remember { mutableIntStateOf(0) }
    var result by remember(card.id, reverseHolo) { mutableStateOf(CardmarketPrices.cached(context, card, reverseHolo)) }
    var checkUrl by remember { mutableStateOf<String?>(null) }
    val hufRate = remember { EurHuf.rate(context) }

    LaunchedEffect(card.id, reverseHolo, refresh) {
        if (refresh == 0 && result != null) return@LaunchedEffect
        result = null
        result = CardmarketPrices.price(context, card, reverseHolo, force = refresh > 0)
    }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = stringResource(R.string.cardmarket_title)
            when (val r = result) {
                null -> Text(stringResource(R.string.cardmarket_loading, title), style = MaterialTheme.typography.bodyLarge)
                is CardmarketResult.Price -> {
                    PriceLine(title, r.average, hufRate)
                    Text(
                        stringResource(R.string.cardmarket_rule, r.counted) + if (reverseHolo) stringResource(R.string.cardmarket_reverse_suffix) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (r.eligible < CardmarketPrice.SKIP_CHEAPEST + CardmarketPrice.AVERAGE_OF) {
                        Text(stringResource(R.string.cardmarket_only_offers, r.eligible), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is CardmarketResult.FewOffers -> {
                    PriceLine(title, r.lowest, hufRate)
                    Text(stringResource(R.string.cardmarket_few_offers, r.eligible), style = MaterialTheme.typography.bodySmall)
                }
                is CardmarketResult.NoOffers -> Text(stringResource(R.string.cardmarket_no_offers, title), style = MaterialTheme.typography.bodyLarge)
                is CardmarketResult.NotFound -> Text(stringResource(R.string.cardmarket_not_found, title), style = MaterialTheme.typography.bodyLarge)
                is CardmarketResult.Challenge -> {
                    Text(stringResource(R.string.cardmarket_challenge, title), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { checkUrl = r.url }) { Text(stringResource(R.string.cardmarket_challenge_open)) }
                }
                is CardmarketResult.Failed -> {
                    Text(
                        when (r.kind) {
                            CardmarketResult.Kind.NO_INTERNET -> stringResource(R.string.cardmarket_failed_no_internet)
                            CardmarketResult.Kind.TIMEOUT -> stringResource(R.string.cardmarket_failed_timeout)
                            CardmarketResult.Kind.SITE_CHANGED -> stringResource(R.string.cardmarket_failed_site_changed)
                            CardmarketResult.Kind.OTHER -> stringResource(R.string.cardmarket_failed_other, r.detail)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (r.kind == CardmarketResult.Kind.SITE_CHANGED) {
                        TextButton(onClick = { UpdateManager.checkNow(context) }) { Text(stringResource(R.string.settings_check_update)) }
                    }
                }
            }
            val r = result
            if (r != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val link = when (r) {
                        is CardmarketResult.Price -> Cardmarket.filteredOffersUrl(r.productUrl)
                        is CardmarketResult.FewOffers -> Cardmarket.filteredOffersUrl(r.productUrl)
                        is CardmarketResult.NoOffers -> Cardmarket.filteredOffersUrl(r.productUrl)
                        is CardmarketResult.NotFound -> r.searchUrl
                        else -> null
                    }
                    if (link != null) TextButton(onClick = { open(link) }) { Text(stringResource(R.string.cardmarket_open)) }
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.cardmarket_refresh)) }
                }
            }
        }
    }

    checkUrl?.let { url ->
        CardmarketCheckDialog(url = url, onDone = {
            checkUrl = null
            refresh++
        })
    }
}

/** "Cardmarket ár: 8,04 € (≈ 2 949 Ft)"; the forint part only once the EUR–HUF rate is known. */
@Composable
private fun PriceLine(title: String, euros: Double, hufRate: Double?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$title: ", style = MaterialTheme.typography.titleMedium)
        Text(CardmarketPrice.format(euros), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (hufRate != null) {
            Text(" (≈\u00A0" + EurHuf.formatHuf(euros, hufRate) + ")", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shows Cardmarket visibly so its Cloudflare check can pass; closes by itself once a normal page loads. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CardmarketCheckDialog(url: String, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cardmarket_check_done)) } },
        title = { Text(stringResource(R.string.cardmarket_challenge_open)) },
        text = {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(420.dp),
                factory = { ctx ->
                    var closed = false
                    WebView(ctx).apply {
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, pageUrl: String?) {
                                view.evaluateJavascript(CARDMARKET_EXTRACT_JS) { result ->
                                    val page = CardmarketPage.fromScriptResult(result)
                                    if (page != null && !page.challenge && !closed) {
                                        closed = true
                                        onDone()
                                    }
                                }
                            }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
        },
    )
}
