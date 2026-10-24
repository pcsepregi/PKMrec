package me.csepregi.pkmrec.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Reverse holo offers are counted only for a reverse holo variant. */
fun isReverseHolo(variant: CardVariant?): Boolean = variant?.name?.contains("reverse", ignoreCase = true) == true

/**
 * "Cardmarket ár": the card's price by Pali's rule, loaded in the background after the rest of
 * the screen. Hidden when switched off in settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardmarketPriceSection(card: TcgCard, reverseHolo: Boolean) {
    val context = LocalContext.current
    if (!remember { CardmarketPrices.isEnabled(context) }) return

    var refresh by remember { mutableIntStateOf(0) }
    var result by remember(card.id, reverseHolo) { mutableStateOf(CardmarketPrices.cached(context, card, reverseHolo)) }
    var checkUrl by remember { mutableStateOf<String?>(null) }
    val rate = remember { HufRate(EurHuf.rate(context), EurHuf.rateDate(context)) }

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
                is CardmarketResult.Challenge -> {
                    Text(stringResource(R.string.cardmarket_challenge, title), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { checkUrl = r.url }) { Text(stringResource(R.string.cardmarket_challenge_open)) }
                }
                is CardmarketResult.Failed -> FailureLines(r)
                is CardmarketResult.Stale -> {
                    ResultLines(r.previous, title, reverseHolo, rate)
                    Text(
                        stringResource(R.string.cardmarket_stale, formatSavedAt(r.savedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FailureLines(r.failure)
                }
                else -> ResultLines(r, title, reverseHolo, rate)
            }
            val r = result
            if (r != null) {
                val shown = (r as? CardmarketResult.Stale)?.previous ?: r
                val productUrl = when (shown) {
                    is CardmarketResult.Price -> shown.productUrl
                    is CardmarketResult.FewOffers -> shown.productUrl
                    is CardmarketResult.NoOffers -> shown.productUrl
                    else -> null
                }
                FlowRow {
                    val link = productUrl?.let { Cardmarket.filteredOffersUrl(it) } ?: (shown as? CardmarketResult.NotFound)?.searchUrl
                    if (link != null) TextButton(onClick = { open(link) }) { Text(stringResource(R.string.cardmarket_open)) }
                    TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.cardmarket_refresh)) }
                    if (productUrl != null) {
                        TextButton(onClick = {
                            CardmarketPrices.forgetProduct(context, card)
                            refresh++
                        }) { Text(stringResource(R.string.cardmarket_research)) }
                    }
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

/** The EUR–HUF rate (null before the first download) and its ECB date. */
private data class HufRate(val rate: Double?, val date: String?)

/** A price, few offers, no offers or not found. */
@Composable
private fun ResultLines(r: CardmarketResult, title: String, reverseHolo: Boolean, rate: HufRate) {
    when (r) {
        is CardmarketResult.Price -> {
            PriceLine(title, r.average, rate)
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
            PriceLine(title, r.lowest, rate)
            Text(stringResource(R.string.cardmarket_few_offers, r.eligible), style = MaterialTheme.typography.bodySmall)
        }
        is CardmarketResult.NoOffers -> Text(stringResource(R.string.cardmarket_no_offers, title), style = MaterialTheme.typography.bodyLarge)
        is CardmarketResult.NotFound -> Text(stringResource(R.string.cardmarket_not_found, title), style = MaterialTheme.typography.bodyLarge)
        else -> {}
    }
}

@Composable
private fun FailureLines(r: CardmarketResult.Failed) {
    val context = LocalContext.current
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

private val SAVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatSavedAt(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(SAVED_AT)

/**
 * "Cardmarket ár: 8,04 € (≈ 2 949 Ft)"; the forint part only once the EUR–HUF rate is known,
 * with the rate's date when it is more than a week old.
 */
@Composable
private fun PriceLine(title: String, euros: Double, rate: HufRate) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$title: ", style = MaterialTheme.typography.titleMedium)
        Text(CardmarketPrice.format(euros), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (rate.rate != null) {
            Text(" (≈\u00A0" + EurHuf.formatHuf(euros, rate.rate) + ")", style = MaterialTheme.typography.titleMedium)
        }
    }
    if (rate.rate != null && EurHuf.isOld(rate.date, LocalDate.now())) {
        Text(
            stringResource(R.string.cardmarket_rate_old, rate.date ?: "?"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Shows Cardmarket visibly so its Cloudflare check can pass; closes by itself once a normal page loads. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CardmarketCheckDialog(url: String, onDone: () -> Unit) {
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
