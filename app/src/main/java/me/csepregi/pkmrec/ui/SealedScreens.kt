package me.csepregi.pkmrec.ui

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.cardmarket.Cardmarket
import me.csepregi.pkmrec.cardmarket.CardmarketFetcher
import me.csepregi.pkmrec.cardmarket.CardmarketPrice
import me.csepregi.pkmrec.cardmarket.CardmarketResult
import me.csepregi.pkmrec.cardmarket.EurHuf
import me.csepregi.pkmrec.sealed.SealedCatalog
import me.csepregi.pkmrec.sealed.SealedImages
import me.csepregi.pkmrec.sealed.SealedInventory
import me.csepregi.pkmrec.sealed.SealedItem
import me.csepregi.pkmrec.sealed.SealedMatcher
import me.csepregi.pkmrec.sealed.PriceTrend
import me.csepregi.pkmrec.sealed.SealedPrice
import me.csepregi.pkmrec.sealed.SealedPrices
import me.csepregi.pkmrec.sealed.SealedProduct
import me.csepregi.pkmrec.sealed.SealedRefreshResult
import me.csepregi.pkmrec.sealed.TcgplayerCatalog
import me.csepregi.pkmrec.sealed.TcgplayerMatcher
import me.csepregi.pkmrec.sealed.TcgplayerProduct
import me.csepregi.pkmrec.sealed.formatUsd
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Sealed catalog: totals, "Árak frissítése", adding products by name and the list of owned products. */
@Composable
fun SealedCatalogScreen(onBack: () -> Unit, onOpenItem: (Int) -> Unit) {
    val context = LocalContext.current
    val items by SealedInventory.items.collectAsState()
    val progress by SealedPrices.progress.collectAsState()
    var products by remember { mutableStateOf<List<SealedProduct>>(emptyList()) }
    LaunchedEffect(Unit) {
        products = withContext(Dispatchers.IO) { SealedCatalog.all(context) }
        SealedInventory.load(context)
    }
    val rate = remember { EurHuf.rate(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var byValue by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf<SealedProduct?>(null) }
    var checkUrl by remember { mutableStateOf<String?>(null) }
    val suggestions = remember(query, products) { SealedMatcher.suggest(query, products) }
    val summary = remember(items, rate) { SealedInventory.summary(items, rate) }
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(stringResource(R.string.sealed_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SummaryRow(stringResource(R.string.sealed_pieces), stringResource(R.string.sealed_pieces_value, summary.pieces, summary.products))
                    SummaryRow(stringResource(R.string.sealed_purchase_total), EurHuf.formatForints(summary.purchaseHuf))
                    if (summary.withoutPurchase > 0) Hint(stringResource(R.string.sealed_without_purchase, summary.withoutPurchase))
                    SummaryRow(
                        stringResource(R.string.sealed_value_total),
                        CardmarketPrice.format(summary.valueEur) + (rate?.let { " (≈ " + EurHuf.formatHuf(summary.valueEur, it) + ")" } ?: ""),
                    )
                    if (summary.withoutPrice > 0) Hint(stringResource(R.string.sealed_without_price, summary.withoutPrice))
                    summary.differenceHuf?.let { diff ->
                        SummaryRow(
                            stringResource(R.string.sealed_difference),
                            (if (diff > 0) "+" else "") + EurHuf.formatForints(diff),
                            color = if (diff >= 0) VerdictColors.Owned else VerdictColors.NotOwned,
                        )
                        Hint(stringResource(R.string.sealed_difference_hint))
                    }
                }
            }

            val p = progress
            if (p?.running == true) {
                Text(stringResource(R.string.sealed_refreshing, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { SealedPrices.cancel() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.sealed_refresh_cancel)) }
            } else {
                Button(
                    // After a stop for lack of internet, the round continues where it stopped.
                    onClick = { SealedPrices.refreshAll(context, resume = p?.offline == true) },
                    enabled = items.any { it.count > 0 },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.sealed_refresh_all)) }
                if (p?.challengeUrl != null) {
                    Text(stringResource(R.string.sealed_refresh_challenge, p.done, p.total), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { checkUrl = p.challengeUrl }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cardmarket_challenge_open))
                    }
                } else if (p?.offline == true) {
                    Hint(stringResource(R.string.sealed_refresh_offline, p.done, p.total))
                } else if (p != null && p.failed > 0) {
                    Hint(stringResource(R.string.sealed_refresh_failed, p.failed))
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.sealed_add_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            if (query.isNotBlank()) {
                if (suggestions.isEmpty()) Text(stringResource(R.string.sealed_no_match), style = MaterialTheme.typography.bodyMedium)
                suggestions.forEach { product ->
                    val owned = items.firstOrNull { it.id == product.id }?.count
                    Card(modifier = Modifier.fillMaxWidth().clickable { adding = product }) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(product.type, product.set.ifEmpty { null }, owned?.let { stringResource(R.string.sealed_owned, it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !byValue, onClick = { byValue = false }, label = { Text(stringResource(R.string.sealed_sort_name)) })
                    FilterChip(selected = byValue, onClick = { byValue = true }, label = { Text(stringResource(R.string.sealed_sort_value)) })
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        placeholder = { Text(stringResource(R.string.sealed_filter)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // By value: the price of one piece, so a single expensive box comes before many cheap packs.
                val sorted = if (byValue) {
                    items.sortedByDescending { it.price?.value ?: -1.0 }
                } else {
                    items.sortedBy { it.name.lowercase() }
                }.filter { SealedMatcher.matches(filter, "${it.name} ${it.set} ${it.type}") }
                if (sorted.isEmpty() && items.isNotEmpty()) Text(stringResource(R.string.sealed_filter_none), style = MaterialTheme.typography.bodyMedium)
                if (items.isEmpty()) Text(stringResource(R.string.sealed_empty), style = MaterialTheme.typography.bodyMedium)
                sorted.forEach { item -> OwnedRow(item, onClick = { onOpenItem(item.id) }) }
            }
        }
    }

    CardmarketChallengeDialog()
    checkUrl?.let { url ->
        CardmarketCheckDialog(url = url, onDone = {
            checkUrl = null
            SealedPrices.refreshAll(context, resume = true)
        })
    }

    adding?.let { product ->
        PurchaseDialog(
            title = product.name,
            owned = items.firstOrNull { it.id == product.id }?.count,
            onDismiss = { adding = null },
            onConfirm = { count, unitHuf ->
                adding = null
                query = ""
                scope.launch {
                    SealedInventory.addPurchase(context, product, count, unitHuf)
                    // A new product: pair it with TCGplayer and load its price and picture from there.
                    // Cardmarket (20 s between pages) is left to "Árak frissítése".
                    val added = SealedInventory.items.value.firstOrNull { it.id == product.id }
                    if (added != null && added.price == null) {
                        val paired = SealedPrices.pair(context, added)
                        if (paired.tcgplayerPaired) SealedPrices.refresh(context, paired)
                    }
                }
            },
        )
    }
}

/**
 * Shows the WebView the prices are loaded in, full screen, while Cardmarket asks for its Cloudflare
 * check: the waiting page load goes on by itself once it is ticked, and the dialog closes.
 */
@Composable
private fun CardmarketChallengeDialog() {
    val challenge by CardmarketFetcher.challenge.collectAsState()
    var closed by remember { mutableStateOf(false) }
    LaunchedEffect(challenge) { if (!challenge) closed = false }
    if (!challenge || closed) return
    Dialog(onDismissRequest = { closed = true }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.sealed_challenge_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint(stringResource(R.string.sealed_challenge_hint))
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { ctx ->
                        CardmarketFetcher.view(ctx).also { view ->
                            (view.parent as? ViewGroup)?.removeView(view)
                            view.settings.blockNetworkImage = false
                        }
                    },
                    onRelease = { view ->
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.settings.blockNetworkImage = true
                    },
                )
                TextButton(onClick = { closed = true }, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.sealed_challenge_close)) }
            }
        }
    }
}

@Composable
private fun OwnedRow(item: SealedItem, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val image = SealedImages.file(LocalContext.current, item)
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOf(item.type, item.set).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.sealed_count, item.count), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.price?.let { priceText(it) } ?: stringResource(R.string.sealed_no_price_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.trend?.let {
                        Spacer(Modifier.width(4.dp))
                        TrendMark(it, MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** One product: count, purchase price and its Cardmarket price. */
@Composable
fun SealedItemScreen(id: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by SealedInventory.items.collectAsState()
    LaunchedEffect(Unit) { SealedInventory.load(context) }
    val item = items.firstOrNull { it.id == id }
    val rate = remember { EurHuf.rate(context) }
    var buying by remember { mutableStateOf(false) }
    var editingPurchase by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    var loadingPrice by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<SealedRefreshResult?>(null) }
    var checkUrl by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var loadingImage by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun loadPrice(current: SealedItem) {
        loadingPrice = true
        scope.launch {
            lastResult = SealedPrices.refresh(context, current)
            loadingPrice = false
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            if (item == null) {
                Text(stringResource(R.string.sealed_item_missing), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            Text(item.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val image = SealedImages.file(context, item)
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = item.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else if (item.imageError != null) {
                Hint(stringResource(R.string.sealed_image_failed, item.imageError))
            } else {
                Hint(stringResource(R.string.sealed_image_later))
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.type.isNotEmpty()) SummaryRow(stringResource(R.string.sealed_type), item.type)
                    if (item.set.isNotEmpty()) SummaryRow(stringResource(R.string.pokedex_set), item.set)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.sealed_pieces), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = {
                            val next = item.count - 1
                            scope.launch { SealedInventory.setCount(context, item.id, next) }
                            if (next <= 0) askRemove = true
                        }, enabled = item.count > 0) { Text("−") }
                        Text(
                            item.count.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        OutlinedButton(onClick = { buying = true }) { Text("+") }
                    }
                    Hint(stringResource(R.string.sealed_count_hint))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.sealed_purchase_unit), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                item.purchaseHuf?.let { EurHuf.formatForints(it) } ?: stringResource(R.string.sealed_purchase_unknown),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        TextButton(onClick = { editingPurchase = true }) { Text(stringResource(R.string.sealed_edit)) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val title = stringResource(if (item.tcgplayerPaired) R.string.tcgplayer_title else R.string.cardmarket_title)
                    if (loadingPrice) Text(stringResource(R.string.cardmarket_loading, title), style = MaterialTheme.typography.bodyLarge)
                    else SealedPriceLines(item, rate)
                    when (val r = lastResult) {
                        is SealedRefreshResult.Tcgplayer -> if (r.usd == null) Hint(stringResource(R.string.tcgplayer_no_market_price))
                        is SealedRefreshResult.TcgplayerFailed -> Text(
                            if (r.offline) stringResource(R.string.cardmarket_failed_no_internet) else stringResource(R.string.tcgplayer_failed, r.detail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        is SealedRefreshResult.Cardmarket -> when (val c = r.result) {
                            is CardmarketResult.NotFound -> Text(stringResource(R.string.sealed_not_found), style = MaterialTheme.typography.bodyMedium)
                            is CardmarketResult.Challenge -> {
                                Text(stringResource(R.string.cardmarket_challenge, title), style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { checkUrl = c.url }) { Text(stringResource(R.string.cardmarket_challenge_open)) }
                            }
                            is CardmarketResult.Failed -> Text(
                                when (c.kind) {
                                    CardmarketResult.Kind.NO_INTERNET -> stringResource(R.string.cardmarket_failed_no_internet)
                                    CardmarketResult.Kind.TIMEOUT -> stringResource(R.string.cardmarket_failed_timeout)
                                    CardmarketResult.Kind.SITE_CHANGED -> stringResource(R.string.cardmarket_failed_site_changed)
                                    CardmarketResult.Kind.OTHER -> stringResource(R.string.cardmarket_failed_other, c.detail)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> {}
                        }
                        null -> {}
                    }
                    Row {
                        TextButton(onClick = { loadPrice(item) }, enabled = !loadingPrice) { Text(stringResource(R.string.cardmarket_refresh)) }
                        if (item.tcgplayerPaired) {
                            TextButton(onClick = { open(TcgplayerCatalog.productUrl(item.tcgplayerId!!)) }) { Text(stringResource(R.string.tcgplayer_open)) }
                        } else {
                            TextButton(onClick = {
                                open(item.productUrl?.let { Cardmarket.englishOffersUrl(it) } ?: Cardmarket.productByIdUrl(item.id))
                            }) { Text(stringResource(R.string.cardmarket_open)) }
                        }
                    }
                    if (!item.tcgplayerPaired && item.productUrl != null) {
                        TextButton(onClick = {
                            scope.launch {
                                SealedInventory.setPrice(context, item.id, item.price, null)
                                SealedInventory.items.value.firstOrNull { it.id == item.id }?.let { loadPrice(it) }
                            }
                        }, enabled = !loadingPrice) { Text(stringResource(R.string.cardmarket_research)) }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.tcgplayer_pairing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    when {
                        item.tcgplayerPaired -> Text(item.tcgplayerName ?: item.tcgplayerId.toString(), style = MaterialTheme.typography.bodyLarge)
                        item.noTcgplayer -> Hint(stringResource(R.string.tcgplayer_not_there))
                        else -> Hint(stringResource(R.string.tcgplayer_unpaired))
                    }
                    Row {
                        TextButton(onClick = { pairing = true }) {
                            Text(stringResource(if (item.tcgplayerPaired || item.noTcgplayer) R.string.sealed_edit else R.string.tcgplayer_pair))
                        }
                        if (item.tcgplayerPaired) {
                            TextButton(onClick = {
                                loadingImage = true
                                scope.launch {
                                    SealedPrices.replaceImageFromTcgplayer(context, item)
                                    loadingImage = false
                                }
                            }, enabled = !loadingImage) { Text(stringResource(R.string.tcgplayer_image)) }
                        }
                    }
                }
            }
        }
    }

    if (item != null && pairing) {
        TcgplayerPickDialog(
            item = item,
            onDismiss = { pairing = false },
            onPick = { product ->
                pairing = false
                scope.launch {
                    SealedInventory.setTcgplayer(context, item.id, product, notThere = product == null)
                    SealedInventory.items.value.firstOrNull { it.id == item.id }?.let { loadPrice(it) }
                }
            },
        )
    }
    if (item != null && buying) {
        PurchaseDialog(
            title = item.name,
            owned = item.count,
            onDismiss = { buying = false },
            onConfirm = { count, unitHuf ->
                buying = false
                scope.launch { SealedInventory.addPurchase(context, SealedProduct(item.id, item.type, item.set, item.name), count, unitHuf) }
            },
        )
    }
    if (item != null && editingPurchase) {
        PurchasePriceDialog(
            current = item.purchaseHuf,
            onDismiss = { editingPurchase = false },
            onConfirm = { unitHuf ->
                editingPurchase = false
                scope.launch { SealedInventory.setPurchase(context, item.id, unitHuf) }
            },
        )
    }
    if (item != null && askRemove) {
        AlertDialog(
            onDismissRequest = { askRemove = false },
            title = { Text(stringResource(R.string.sealed_remove_title)) },
            text = { Text(stringResource(R.string.sealed_remove_text, item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    askRemove = false
                    scope.launch { SealedInventory.remove(context, item.id) }
                    onBack()
                }) { Text(stringResource(R.string.sealed_remove)) }
            },
            dismissButton = { TextButton(onClick = { askRemove = false }) { Text(stringResource(R.string.sealed_keep)) } },
        )
    }
    CardmarketChallengeDialog()
    checkUrl?.let { url ->
        CardmarketCheckDialog(url = url, onDone = {
            checkUrl = null
            item?.let { loadPrice(it) }
        })
    }
}

private val PRICE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** A price as shown: TCGplayer prices in dollars, Cardmarket prices in euros. */
private fun priceText(price: SealedPrice): String =
    if (price.isTcgplayer && price.usd != null) formatUsd(price.usd) else CardmarketPrice.format(price.eur)

@Composable
private fun SealedPriceLines(item: SealedItem, rate: Double?) {
    val price = item.price
    if (price == null) {
        val title = stringResource(if (item.tcgplayerPaired) R.string.tcgplayer_title else R.string.cardmarket_title)
        Text(stringResource(R.string.sealed_price_never, title), style = MaterialTheme.typography.bodyLarge)
        return
    }
    val title = stringResource(if (price.isTcgplayer) R.string.tcgplayer_title else R.string.cardmarket_title)
    val date = Instant.ofEpochMilli(price.time).atZone(ZoneId.systemDefault()).format(PRICE_DATE)
    val value = price.value
    if (value == null) {
        Text(
            if (price.isTcgplayer) stringResource(R.string.tcgplayer_no_price, title) else stringResource(R.string.sealed_no_offers, title),
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$title: ", style = MaterialTheme.typography.titleMedium)
            Text(priceText(price), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (rate != null) Text(" (≈ " + EurHuf.formatHuf(value, rate) + ")", style = MaterialTheme.typography.titleMedium)
            item.trend?.let {
                Spacer(Modifier.width(8.dp))
                TrendMark(it, MaterialTheme.typography.titleLarge)
            }
        }
        when {
            price.isTcgplayer -> Hint(stringResource(R.string.tcgplayer_rule))
            price.kind == SealedPrice.KIND_FEW -> Hint(stringResource(R.string.cardmarket_few_offers, price.eligible))
            else -> Hint(stringResource(R.string.sealed_rule))
        }
    }
    Hint(stringResource(R.string.sealed_price_date, date))
    if (item.previousPrices.isNotEmpty()) {
        Text(stringResource(R.string.sealed_previous_prices), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        item.previousPrices.forEach { old ->
            val oldDate = Instant.ofEpochMilli(old.time).atZone(ZoneId.systemDefault()).format(PRICE_DATE)
            val oldValue = old.value
            val shop = stringResource(if (old.isTcgplayer) R.string.tcgplayer_short else R.string.cardmarket_short)
            Text(
                "$oldDate: " + (oldValue?.let { priceText(old) + (rate?.let { r -> " (≈\u00A0" + EurHuf.formatHuf(it, r) + ")" } ?: "") }
                    ?: stringResource(if (old.isTcgplayer) R.string.tcgplayer_previous_no_price else R.string.sealed_previous_no_offers)) + " · $shop",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Picks the TCGplayer product a Sealed catalog product is priced by: the best matches by name
 * first, or a search; "Nincs a TCGplayeren" leaves it on Cardmarket. [onPick] gets null for that.
 */
@Composable
private fun TcgplayerPickDialog(item: SealedItem, onDismiss: () -> Unit, onPick: (TcgplayerProduct?) -> Unit) {
    val context = LocalContext.current
    var products by remember { mutableStateOf<List<TcgplayerProduct>>(emptyList()) }
    var best by remember { mutableStateOf<List<TcgplayerProduct>>(emptyList()) }
    LaunchedEffect(item.id) {
        products = withContext(Dispatchers.IO) { TcgplayerCatalog.all(context) }
        best = withContext(Dispatchers.Default) { TcgplayerMatcher.rank(item.name, item.set, products) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query, products, best) {
        if (query.isBlank()) best
        else products.filter { SealedMatcher.matches(query, "${it.name} ${it.set}") }.sortedBy { it.name.length }.take(30)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.tcgplayer_pick_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(item.name, style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.tcgplayer_pick_search)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (products.isEmpty()) Hint(stringResource(R.string.tcgplayer_pick_empty_list))
                else if (shown.isEmpty()) Hint(stringResource(R.string.tcgplayer_pick_none))
                shown.forEach { product ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onPick(product) }) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                product.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (product.id == item.tcgplayerId) FontWeight.Bold else FontWeight.SemiBold,
                            )
                            Text(
                                listOfNotNull(product.set.ifEmpty { null }, if (product.id == item.tcgplayerId) stringResource(R.string.tcgplayer_pick_current) else null).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tcgplayer_not_there_button)) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** = within 1 € of the previous price, green ↑ above it, red ↓ below it. */
@Composable
private fun TrendMark(trend: PriceTrend, style: TextStyle) {
    val (symbol, color, description) = when (trend) {
        PriceTrend.UP -> Triple("↑", VerdictColors.Owned, R.string.sealed_trend_up)
        PriceTrend.DOWN -> Triple("↓", VerdictColors.NotOwned, R.string.sealed_trend_down)
        PriceTrend.SAME -> Triple("=", MaterialTheme.colorScheme.onSurfaceVariant, R.string.sealed_trend_same)
    }
    val label = stringResource(description)
    Text(symbol, style = style, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = label })
}

/** Count and price per piece of a purchase; the price may stay empty. */
@Composable
private fun PurchaseDialog(title: String, owned: Int?, onDismiss: () -> Unit, onConfirm: (Int, Long?) -> Unit) {
    var count by remember { mutableStateOf("1") }
    var price by remember { mutableStateOf("") }
    val countValue = count.trim().toIntOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (owned != null) Text(stringResource(R.string.sealed_owned_already, owned))
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.sealed_dialog_count)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.sealed_dialog_price)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Hint(stringResource(R.string.sealed_dialog_price_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = { countValue?.let { onConfirm(it, price.toLongOrNull()) } }, enabled = countValue != null) {
                Text(stringResource(R.string.sealed_dialog_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PurchasePriceDialog(current: Long?, onDismiss: () -> Unit, onConfirm: (Long?) -> Unit) {
    var price by remember { mutableStateOf(current?.toString().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sealed_purchase_unit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter(Char::isDigit).take(9) },
                    label = { Text(stringResource(R.string.sealed_dialog_price)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Hint(stringResource(R.string.sealed_purchase_edit_hint))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(price.toLongOrNull()) }) { Text(stringResource(R.string.sealed_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
