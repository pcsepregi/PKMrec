package me.csepregi.pkmrec.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.recognition.SetSymbols
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.TcgCard
import me.csepregi.pkmrec.tcg.TcgErrorKind
import me.csepregi.pkmrec.update.UpdateManager
import kotlin.math.roundToInt

/** Card proportions for images. */
internal const val CARD_IMAGE_ASPECT = 63f / 88f

@Composable
fun LookupScreen(onRescan: () -> Unit, onHome: () -> Unit, onLogin: () -> Unit) {
    val vm: LookupViewModel = viewModel()
    val state by vm.state.collectAsState()
    val pokedex by vm.pokedex.collectAsState()

    LaunchedEffect(Unit) {
        val reading = CurrentScan.reading
        if (reading == null) onRescan() else vm.start(reading, CurrentScan.symbol)
    }
    BackHandler { onRescan() }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRescan) { Text("‹ " + stringResource(R.string.camera_rescan)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onHome) { Text(stringResource(R.string.lookup_home)) }
            }
            vm.reading?.let {
                Text(
                    stringResource(R.string.lookup_scanned, it.display),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val s = state) {
                LookupUiState.Searching -> Centered {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.lookup_searching))
                }

                LookupUiState.NotLoggedIn -> Centered {
                    Text(stringResource(R.string.lookup_not_logged_in), textAlign = TextAlign.Center)
                    Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
                    OutlinedButton(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                }

                is LookupUiState.Error -> Centered {
                    Text(stringResource(R.string.lookup_error, tcgErrorText(s.error)), textAlign = TextAlign.Center)
                    Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                    if (s.error.kind == TcgErrorKind.SITE_CHANGED) {
                        val context = LocalContext.current
                        OutlinedButton(onClick = { UpdateManager.checkNow(context) }) {
                            Text(stringResource(R.string.settings_check_update))
                        }
                    }
                }

                LookupUiState.NotFound -> Centered {
                    Text(stringResource(R.string.lookup_not_found), textAlign = TextAlign.Center)
                    Button(onClick = onRescan) { Text(stringResource(R.string.camera_rescan)) }
                }

                is LookupUiState.PickCandidate -> {
                    Text(stringResource(R.string.lookup_pick_candidate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    s.candidates.forEach { card ->
                        CardRow(
                            card = card,
                            symbolId = s.symbols?.symbolIds?.get(card.id),
                            symbolScore = s.symbols?.scores?.get(card.id),
                            onClick = { vm.chooseCard(card) },
                        )
                    }
                    OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.lookup_none_of_these))
                    }
                    Text(
                        stringResource(R.string.lookup_none_of_these_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is LookupUiState.PickVariant -> {
                    CardHeader(s.card)
                    if (s.bySymbol) BySymbolNote()
                    Text(stringResource(R.string.lookup_pick_variant), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    s.card.variants.forEach { variant ->
                        VariantRow(variant = variant, onClick = { vm.chooseVariant(s.card, variant) })
                    }
                    if (s.canGoBackToCandidates) {
                        TextButton(onClick = { vm.backToCandidates() }) { Text(stringResource(R.string.lookup_other_card)) }
                    }
                }

                is LookupUiState.Verdict -> VerdictContent(
                    state = s,
                    pokedex = pokedex,
                    onRetryPokedex = { vm.retryPokedex() },
                    onLogin = onLogin,
                    onRescan = onRescan,
                    onOtherVariant = { vm.backToVariants(s.card) },
                    onOtherCard = { vm.backToCandidates() },
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun VerdictContent(
    state: LookupUiState.Verdict,
    pokedex: PokedexUiState,
    onRetryPokedex: () -> Unit,
    onLogin: () -> Unit,
    onRescan: () -> Unit,
    onOtherVariant: () -> Unit,
    onOtherCard: () -> Unit,
) {
    val context = LocalContext.current
    val color = if (state.owned) VerdictColors.Owned else VerdictColors.NotOwned

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(color)
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(if (state.owned) R.string.verdict_owned else R.string.verdict_not_owned),
            color = Color.White,
            fontSize = 52.sp,
            fontWeight = FontWeight.Black,
        )
    }

    state.variant?.let { variant ->
        Text(
            stringResource(R.string.lookup_variant_line, variant.name, variant.ownedEnglish),
            style = MaterialTheme.typography.titleMedium,
        )
        if (variant.ownedOtherLanguages > 0) {
            Text(
                stringResource(R.string.lookup_other_languages, variant.ownedOtherLanguages),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    CardHeader(state.card, large = true)
    if (state.bySymbol) BySymbolNote()

    CardmarketPriceSection(state.card, reverseHolo = isReverseHolo(state.variant))

    PokedexSection(pokedex, onRetry = onRetryPokedex, onLogin = onLogin)

    Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.camera_rescan)) }
    if (state.canPickOtherVariant) {
        OutlinedButton(onClick = onOtherVariant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lookup_other_variant))
        }
    }
    if (state.canGoBackToCandidates) {
        OutlinedButton(onClick = onOtherCard, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lookup_other_card))
        }
    }
    TextButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.card.url))) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.lookup_open_tcg)) }
}

/** "A Pokedex listádban": the list's card(s) for the same Pokémon. */
@Composable
private fun PokedexSection(state: PokedexUiState, onRetry: () -> Unit, onLogin: () -> Unit) {
    if (state == PokedexUiState.Idle || state == PokedexUiState.NotAPokemon) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.lookup_pokedex_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (state) {
                PokedexUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).aspectRatio(1f), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.lookup_pokedex_loading), style = MaterialTheme.typography.bodyMedium)
                }
                PokedexUiState.NotLoggedIn -> {
                    Text(stringResource(R.string.lookup_pokedex_not_logged_in), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                    }
                }
                is PokedexUiState.Error -> {
                    Text(stringResource(R.string.lookup_pokedex_error, tcgErrorText(state.error)), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
                is PokedexUiState.Loaded -> state.perPokemon.forEach { entry ->
                    if (state.perPokemon.size > 1) {
                        Text("#%04d %s".format(entry.entry.number, entry.entry.slug.replaceFirstChar { it.uppercase() }), style = MaterialTheme.typography.labelLarge)
                    }
                    if (entry.cards.isEmpty()) {
                        Text(stringResource(R.string.lookup_pokedex_none), style = MaterialTheme.typography.bodyMedium)
                    }
                    entry.cards.forEach { card ->
                        CardHeader(card)
                        if (card.id == state.scannedCardId) {
                            Text(
                                stringResource(R.string.lookup_pokedex_same_card),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = VerdictColors.Owned,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun CardHeader(card: TcgCard, large: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(card, width = if (large) 140 else 90)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(numberAndRarity(card), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal fun numberAndRarity(card: TcgCard): String =
    listOfNotNull(card.number.ifBlank { null }, card.rarity).joinToString(" · ")

@Composable
internal fun CardImage(card: TcgCard, width: Int) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .aspectRatio(CARD_IMAGE_ASPECT)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (card.imageUrl != null) {
            AsyncImage(
                model = card.imageUrl,
                contentDescription = card.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CardRow(card: TcgCard, symbolId: String?, symbolScore: Float?, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardImage(card, width = 70)
            Column(modifier = Modifier.weight(1f)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(numberAndRarity(card), style = MaterialTheme.typography.bodySmall)
                if (symbolScore != null) {
                    Text(
                        stringResource(R.string.lookup_symbol_score, (symbolScore * 100).roundToInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (symbolId != null) SetSymbol(symbolId)
        }
    }
}

/** The set symbol on a light badge (many symbols are black). */
@Composable
private fun SetSymbol(id: String) {
    val context = LocalContext.current
    var bitmap by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(id) {
        bitmap = withContext(Dispatchers.IO) { SetSymbols.bitmap(context, id)?.asImageBitmap() }
    }
    val image = bitmap ?: return
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun BySymbolNote() {
    Text(
        stringResource(R.string.lookup_by_symbol),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VariantRow(variant: CardVariant, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(variant.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (variant.ownedOtherLanguages > 0) {
                    Text(
                        stringResource(R.string.lookup_other_languages, variant.ownedOtherLanguages),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = if (variant.isOwned) "✓ ${variant.ownedEnglish}" else "✗",
                color = if (variant.isOwned) VerdictColors.Owned else VerdictColors.NotOwned,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
    Spacer(Modifier.height(2.dp))
}
