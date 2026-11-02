package me.csepregi.pkmrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.collection.BulkAdd
import me.csepregi.pkmrec.collection.RecentAdd
import me.csepregi.pkmrec.collection.TempList
import me.csepregi.pkmrec.collection.TempListAddState
import me.csepregi.pkmrec.collection.TempListAdder
import me.csepregi.pkmrec.collection.TempListItem
import me.csepregi.pkmrec.tcg.WriteOutcome

/**
 * The temporary list: "Mindet törli" and "Mindet a gyűjteménybe" on top, then every card with its
 * own "Hozzáad" and "Töröl". Adding puts one copy of the shown variant into the TCG Collector
 * collection and takes the card off the list.
 */
@Composable
fun TempListScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by TempList.items.collectAsState()
    val adder by TempListAdder.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmAddAll by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { TempList.load(context) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(
                stringResource(R.string.temp_list_title) + if (items.isEmpty()) "" else " (${items.size})",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (items.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { confirmClear = true }, enabled = !adder.busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.temp_list_clear_all))
                    }
                    Button(onClick = { confirmAddAll = true }, enabled = !adder.busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.temp_list_add_all))
                    }
                }
            }
            adder.bulk?.let { BulkStatus(it, onLogin = onLogin) }
            adder.recent.forEach { RecentRow(it) }
            if (items.isEmpty() && adder.bulk == null && adder.recent.isEmpty()) {
                Text(stringResource(R.string.temp_list_empty), modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items, key = { it.id }) { item ->
                    ItemRow(
                        item,
                        adder,
                        onAdd = { TempListAdder.addOne(context, item) },
                        onDelete = {
                            TempListAdder.forget(item.id)
                            scope.launch { TempList.remove(context, item.id) }
                        },
                        onLogin = onLogin,
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.temp_list_clear_confirm_title)) },
            text = { Text(stringResource(R.string.temp_list_clear_confirm_text, items.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    TempListAdder.forgetAll()
                    scope.launch { TempList.clear(context) }
                }) { Text(stringResource(R.string.temp_list_clear_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (confirmAddAll) {
        AlertDialog(
            onDismissRequest = { confirmAddAll = false },
            title = { Text(stringResource(R.string.temp_list_add_all_confirm_title)) },
            text = { Text(stringResource(R.string.temp_list_add_all_confirm_text, items.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAddAll = false
                    TempListAdder.addAll(context)
                }) { Text(stringResource(R.string.temp_list_add_all_yes)) }
            },
            dismissButton = { TextButton(onClick = { confirmAddAll = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun BulkStatus(bulk: BulkAdd, onLogin: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (bulk.running) {
                Text(stringResource(R.string.temp_list_bulk_progress, bulk.done, bulk.total), style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { bulk.done.toFloat() / bulk.total }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { TempListAdder.cancelBulk() }) { Text(stringResource(R.string.temp_list_bulk_cancel)) }
            } else {
                val summary = if (bulk.cancelled) R.string.temp_list_bulk_cancelled else R.string.temp_list_bulk_done
                Text(stringResource(summary, bulk.added, bulk.failed), style = MaterialTheme.typography.titleMedium)
                bulk.stoppedBy?.let {
                    Text(
                        stringResource(R.string.temp_list_bulk_stopped, writeFailureText(it)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row {
                    if (bulk.stoppedBy == WriteOutcome.NotLoggedIn) {
                        TextButton(onClick = onLogin) { Text(stringResource(R.string.login)) }
                    }
                    TextButton(onClick = { TempListAdder.dismissBulk() }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
    }
}

/** A card just added with its own button: the result and a short undo. */
@Composable
private fun RecentRow(recent: RecentAdd) {
    val context = LocalContext.current
    val item = recent.item
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.temp_list_recent_added, item.name, item.variantName, recent.count),
                style = MaterialTheme.typography.bodyMedium,
                color = VerdictColors.Owned,
            )
            recent.undoFailure?.let {
                Text(
                    stringResource(R.string.temp_list_recent_undo_failed, writeFailureText(it)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        when {
            recent.undoing -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            recent.canUndo -> TextButton(onClick = { TempListAdder.undo(context, item.id) }) { Text(stringResource(R.string.add_undo)) }
            else -> TextButton(onClick = { TempListAdder.dismissRecent(item.id) }) { Text(stringResource(R.string.ok)) }
        }
    }
}

@Composable
private fun ItemRow(item: TempListItem, adder: TempListAddState, onAdd: () -> Unit, onDelete: () -> Unit, onLogin: () -> Unit) {
    val card = remember(item) { item.toCard() }
    val working = item.id in adder.working
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CardImage(card, width = 72)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(item.expansionName + (item.expansionCode?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Text(numberAndRarity(card), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.variantName + if (item.energyTypes.isEmpty()) "" else " · " + item.energyTypes.joinToString(", "),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onDelete, enabled = !working, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.temp_list_row_delete))
                }
                Button(onClick = onAdd, enabled = !adder.busy, modifier = Modifier.weight(1f)) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(stringResource(R.string.temp_list_row_add))
                }
            }
            adder.failures[item.id]?.let { failure ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.temp_list_row_failed, writeFailureText(failure)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (failure == WriteOutcome.NotLoggedIn) {
                        TextButton(onClick = onLogin) { Text(stringResource(R.string.login)) }
                    }
                }
            }
        }
    }
}

/** Why a collection write did not happen, for the user. */
@Composable
internal fun writeFailureText(outcome: WriteOutcome): String = when (outcome) {
    WriteOutcome.NotLoggedIn -> stringResource(R.string.add_not_logged_in)
    WriteOutcome.NotApplied, WriteOutcome.NothingToRemove -> stringResource(R.string.add_not_applied)
    is WriteOutcome.Error -> tcgErrorText(outcome.error)
    is WriteOutcome.Done -> ""
}
