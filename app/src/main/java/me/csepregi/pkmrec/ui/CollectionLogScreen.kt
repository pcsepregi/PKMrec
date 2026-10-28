package me.csepregi.pkmrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.collection.CollectionLog

/** The collection log, newest first: what the app added to or removed from the TCG Collector collection. */
@Composable
fun CollectionLogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf<List<CollectionLog.Entry>?>(null) }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { CollectionLog.entries(context) } }

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(
                stringResource(R.string.settings_collection_log),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            val shown = entries ?: return@Column
            if (shown.isEmpty()) {
                Text(stringResource(R.string.collection_log_empty), modifier = Modifier.padding(vertical = 12.dp))
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(shown) { e ->
                    Column(modifier = Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${actionLabel(e.action)}: ${e.name} · ${e.variant}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${e.expansion} · ${e.number} · ${e.before} → ${e.after} db",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(e.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun actionLabel(action: String): String = when (action) {
    "add" -> stringResource(R.string.collection_log_add)
    "undo" -> stringResource(R.string.collection_log_undo)
    "remove" -> stringResource(R.string.collection_log_remove)
    "list-add" -> stringResource(R.string.collection_log_list_add)
    "list-remove" -> stringResource(R.string.collection_log_list_remove)
    else -> action
}
