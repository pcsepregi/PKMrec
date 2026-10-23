package me.csepregi.pkmrec.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.CardVariant
import me.csepregi.pkmrec.tcg.TcgCard

/** Card proportions for images. */
private const val CARD_IMAGE_ASPECT = 63f / 88f

@Composable
fun LookupScreen(onRescan: () -> Unit, onHome: () -> Unit, onLogin: () -> Unit) {
    val vm: LookupViewModel = viewModel()
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        val reading = CurrentScan.reading
        if (reading == null) onRescan() else vm.start(reading)
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
                    Text(stringResource(R.string.lookup_error, s.message), textAlign = TextAlign.Center)
                    Button(onClick = { vm.retry() }) { Text(stringResource(R.string.retry)) }
                }

                LookupUiState.NotFound -> Centered {
                    Text(stringResource(R.string.lookup_not_found), textAlign = TextAlign.Center)
                    Button(onClick = onRescan) { Text(stringResource(R.string.camera_rescan)) }
                }

                is LookupUiState.PickCandidate -> {
                    Text(stringResource(R.string.lookup_pick_candidate), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    s.candidates.forEach { card ->
                        CardRow(card = card, onClick = { vm.chooseCard(card) })
                    }
                }

                is LookupUiState.PickVariant -> {
                    CardHeader(s.card)
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
                    onRescan = onRescan,
                    onOtherVariant = { vm.backToVariants(s.card) },
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
private fun VerdictContent(state: LookupUiState.Verdict, onRescan: () -> Unit, onOtherVariant: () -> Unit) {
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.lookup_pokedex_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.lookup_pokedex_next_version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.camera_rescan)) }
    if (state.canPickOtherVariant) {
        OutlinedButton(onClick = onOtherVariant, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.lookup_other_variant))
        }
    }
    TextButton(
        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.card.url))) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.lookup_open_tcg)) }
}

@Composable
private fun CardHeader(card: TcgCard, large: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CardImage(card, width = if (large) 140 else 90)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            Text(card.number, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CardImage(card: TcgCard, width: Int) {
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
private fun CardRow(card: TcgCard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardImage(card, width = 70)
            Column {
                Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(card.expansionName + (card.expansionCode?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                Text(card.number, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
