package me.csepregi.pkmrec.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.TcgCard

/** Tömeges eladás: photos of the sold cards, every card on them found, each removable from the collection. */
@Composable
fun BulkSellScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val vm: BulkSellViewModel = viewModel()
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var pendingPhoto by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        pendingPhoto?.let { if (saved) vm.addPhotos(listOf(it)) }
        pendingPhoto = null
    }
    fun takePhoto() {
        val uri = vm.newPhotoUri()
        pendingPhoto = uri
        runCatching { camera.launch(uri) }.onFailure {
            pendingPhoto = null
            Toast.makeText(context, R.string.bulk_no_camera, Toast.LENGTH_LONG).show()
        }
    }
    // The app declares the camera permission, so the camera app may only be started once it is granted.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) takePhoto() else Toast.makeText(context, R.string.bulk_camera_denied, Toast.LENGTH_LONG).show()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        vm.addPhotos(uris)
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
            Text(stringResource(R.string.feature_bulk_sell), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.bulk_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            takePhoto()
                        } else {
                            permission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    enabled = !state.identifying,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.bulk_take_photo)) }
                OutlinedButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !state.identifying,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.bulk_from_file)) }
            }

            if (state.photos.isNotEmpty()) {
                Text(stringResource(R.string.bulk_photo_count, state.photos.size), style = MaterialTheme.typography.titleMedium)
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.photos.forEach { uri -> PhotoThumb(uri, enabled = !state.identifying, onRemove = { vm.removePhoto(uri) }) }
                }
                Button(onClick = { vm.identify() }, enabled = !state.identifying, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.bulk_identify))
                }
            }

            if (state.identifying) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    val p = state.progress
                    Text(
                        if (p != null) stringResource(R.string.bulk_reading_photos, p.first, p.second)
                        else stringResource(R.string.bulk_searching_cards),
                    )
                }
            }
            if (state.notLoggedIn) {
                Text(stringResource(R.string.lookup_not_logged_in), color = MaterialTheme.colorScheme.error)
                Button(onClick = onLogin) { Text(stringResource(R.string.login)) }
            }
            if (state.unreadablePhotos > 0) {
                Text(stringResource(R.string.bulk_unreadable, state.unreadablePhotos), color = MaterialTheme.colorScheme.error)
            }
            if (state.identified && state.rows.isEmpty()) {
                Text(stringResource(R.string.bulk_none_found), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (state.rows.isNotEmpty()) {
                val found = state.rows.count { it.card != null }
                Text(stringResource(R.string.bulk_cards_found, state.rows.size, found), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                state.rows.sortedBy { it.needsAttention }.forEach { row ->
                    SellRowCard(
                        row = row,
                        onVariant = { vm.chooseVariant(row.id, it) },
                        onRemove = { vm.remove(row.id) },
                        onUndo = { vm.undo(row.id) },
                        onPick = { vm.pickCandidate(row.id, it) },
                        onDelete = { vm.deleteRow(row.id) },
                        onLogin = onLogin,
                    )
                }
            }
        }
    }
}

private const val MAX_PICK = 30

@Composable
private fun PhotoThumb(uri: Uri, enabled: Boolean, onRemove: () -> Unit) {
    Box {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (enabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White) }
        }
    }
}

@Composable
private fun SellRowCard(
    row: SellRow,
    onVariant: (Int) -> Unit,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
    onPick: (TcgCard) -> Unit,
    onDelete: () -> Unit,
    onLogin: () -> Unit,
) {
    val removed = row.remove is RemoveState.Removed
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (removed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val lookup = row.lookup) {
                SellLookup.Searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.bulk_row_searching, row.reading.display))
                }
                SellLookup.NotFound -> Text(stringResource(R.string.bulk_row_not_found, row.reading.display))
                is SellLookup.Error -> Text(stringResource(R.string.bulk_row_error, row.reading.display, tcgErrorText(lookup.error)))
                is SellLookup.Pick -> {
                    Text(stringResource(R.string.bulk_row_pick, row.reading.display), fontWeight = FontWeight.SemiBold)
                    lookup.candidates.forEach { card ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(card) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(card.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${card.expansionName} · ${numberAndRarity(card)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                is SellLookup.Found -> FoundCard(row, lookup.card, removed, onVariant, onRemove, onUndo, onLogin)
            }
            if (row.remove == RemoveState.Idle || row.lookup !is SellLookup.Found) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.bulk_row_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundCard(
    row: SellRow,
    card: TcgCard,
    removed: Boolean,
    onVariant: (Int) -> Unit,
    onRemove: () -> Unit,
    onUndo: () -> Unit,
    onLogin: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CardImage(card, width = 64)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(card.expansionName, style = MaterialTheme.typography.bodyMedium)
            Text(numberAndRarity(card), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.inPokedexList) {
                Text(stringResource(R.string.bulk_in_pokedex_list), style = MaterialTheme.typography.bodySmall, color = Neon.Glow, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    val owned = card.variants.filter { it.isOwned || it.id == row.variantId }
    if (owned.isEmpty()) {
        Text(stringResource(R.string.bulk_not_in_collection), color = VerdictColors.NotOwned, fontWeight = FontWeight.SemiBold)
        return
    }
    val busy = row.remove == RemoveState.Working
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        owned.forEach { variant ->
            FilterChip(
                selected = variant.id == row.variantId,
                onClick = { onVariant(variant.id) },
                enabled = !busy && !removed,
                label = { Text(stringResource(R.string.bulk_variant_chip, variant.name, variant.ownedEnglish)) },
            )
        }
    }
    val selected = card.variants.firstOrNull { it.id == row.variantId }
    when (val r = row.remove) {
        is RemoveState.Removed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bulk_removed, r.count), color = VerdictColors.Owned, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (r.canUndo) TextButton(onClick = onUndo) { Text(stringResource(R.string.add_undo)) }
        }
        else -> {
            Button(
                onClick = onRemove,
                enabled = !busy && selected != null && selected.ownedEnglish > 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
                Text(if (selected == null) stringResource(R.string.bulk_pick_variant) else stringResource(R.string.bulk_remove, selected.name))
            }
            val message = when (r) {
                is RemoveState.Undone -> stringResource(R.string.add_undone, r.count)
                RemoveState.NotLoggedIn -> stringResource(R.string.add_not_logged_in)
                RemoveState.NothingToRemove -> stringResource(R.string.bulk_nothing_to_remove)
                RemoveState.NotApplied -> stringResource(R.string.add_not_applied)
                is RemoveState.Error -> stringResource(R.string.bulk_remove_error, tcgErrorText(r.error))
                else -> null
            }
            if (message != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r is RemoveState.Undone) VerdictColors.Owned else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (r == RemoveState.NotLoggedIn) TextButton(onClick = onLogin) { Text(stringResource(R.string.login)) }
                }
            }
        }
    }
}
