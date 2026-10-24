package me.csepregi.pkmrec.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.csepregi.pkmrec.cardmarket.CardmarketPrices
import me.csepregi.pkmrec.cardmarket.EurHuf
import me.csepregi.pkmrec.diagnostics.ErrorLog
import me.csepregi.pkmrec.diagnostics.SlowScanLog
import me.csepregi.pkmrec.pokedex.NameListRefreshResult
import me.csepregi.pkmrec.pokedex.PokemonNames
import me.csepregi.pkmrec.sealed.SealedCatalog
import me.csepregi.pkmrec.sealed.SealedInventory
import me.csepregi.pkmrec.sealed.SealedListRefreshResult
import me.csepregi.pkmrec.sealed.SealedXlsx
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgError
import me.csepregi.pkmrec.tcg.TcgSession
import me.csepregi.pkmrec.update.UpdateManager
import me.csepregi.pkmrec.update.UpdateState

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onCardmarketDebug: () -> Unit,
    onPokemonNames: () -> Unit,
) {
    val context = LocalContext.current
    val updateState by UpdateManager.state.collectAsState()
    val session by TcgSession.state.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.back)) }
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Section(title = stringResource(R.string.settings_account)) {
                SessionStatus(state = session, onLogin = onLogin)
                if (session is SessionState.LoggedIn) {
                    OutlinedButton(
                        onClick = { TcgSession.logout(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.logout)) }
                }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_version)) {
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = { UpdateManager.checkNow(context) },
                    enabled = updateState !is UpdateState.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.settings_check_update))
                }
                when (val s = updateState) {
                    UpdateState.Checking -> Text(stringResource(R.string.update_checking))
                    UpdateState.UpToDate -> Text(stringResource(R.string.update_up_to_date))
                    is UpdateState.CheckFailed -> Text(
                        stringResource(R.string.update_check_failed, s.message),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_error_log)) {
                var count by remember { mutableIntStateOf(ErrorLog.entryCount()) }
                Text(
                    stringResource(R.string.settings_error_log_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.settings_error_log_count, count), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PKMrec error log", ErrorLog.report()))
                        Toast.makeText(context, R.string.settings_error_log_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_error_log_copy)) }
                if (count > 0) {
                    TextButton(onClick = {
                        ErrorLog.clear()
                        count = ErrorLog.entryCount()
                    }) { Text(stringResource(R.string.settings_error_log_clear)) }
                }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_debug)) {
                var enabled by remember { mutableStateOf(SlowScanLog.isEnabled(context)) }
                var count by remember { mutableIntStateOf(SlowScanLog.entryCount(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_debug_enabled), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_debug_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            SlowScanLog.setEnabled(context, it)
                        },
                    )
                }
                Text(
                    stringResource(R.string.settings_debug_count, count, SlowScanLog.MAX_ENTRIES),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PKMrec slow recognitions", SlowScanLog.report(context)))
                        Toast.makeText(context, R.string.settings_debug_copied, Toast.LENGTH_SHORT).show()
                    },
                    enabled = count > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_debug_copy)) }
                if (count > 0) {
                    TextButton(onClick = {
                        SlowScanLog.clear(context)
                        count = SlowScanLog.entryCount(context)
                    }) { Text(stringResource(R.string.settings_debug_clear)) }
                }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_pokedex_list)) {
                var enabled by remember { mutableStateOf(PokedexList.isEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_pokedex_enabled), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_pokedex_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            PokedexList.setEnabled(context, it)
                        },
                    )
                }
                var listId by remember { mutableIntStateOf(PokedexList.listId(context)) }
                var input by remember { mutableStateOf("") }
                Text(stringResource(R.string.settings_pokedex_list_current, listId), style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.settings_pokedex_list_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val saved = stringResource(R.string.settings_pokedex_list_saved)
                val invalid = stringResource(R.string.settings_pokedex_list_invalid)
                Button(
                    onClick = {
                        val ok = PokedexList.setList(context, input)
                        if (ok) {
                            listId = PokedexList.listId(context)
                            input = ""
                        }
                        Toast.makeText(context, if (ok) saved else invalid, Toast.LENGTH_SHORT).show()
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_pokedex_list_save)) }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PokedexList.listUrl(context))))
                }) { Text(stringResource(R.string.settings_pokedex_list_open)) }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_names)) {
                var count by remember { mutableIntStateOf(0) }
                var refreshing by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<TcgError?>(null) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) { count = withContext(Dispatchers.IO) { PokemonNames.all(context).size } }
                Text(
                    stringResource(R.string.settings_names_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.settings_names_count, count), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = {
                        refreshing = true
                        error = null
                        scope.launch {
                            when (val result = PokemonNames.refresh(context)) {
                                is NameListRefreshResult.Done -> {
                                    count = result.count
                                    Toast.makeText(context, context.getString(R.string.settings_names_refreshed, result.count), Toast.LENGTH_SHORT).show()
                                }
                                is NameListRefreshResult.Error -> error = result.error
                            }
                            refreshing = false
                        }
                    },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (refreshing) R.string.settings_names_refreshing else R.string.settings_names_refresh)) }
                error?.let {
                    Text(stringResource(R.string.settings_names_failed, tcgErrorText(it)), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onPokemonNames) { Text(stringResource(R.string.settings_names_view)) }
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_sealed)) {
                SealedSettings()
            }

            HorizontalDivider()

            Section(title = stringResource(R.string.settings_cardmarket)) {
                var enabled by remember { mutableStateOf(CardmarketPrices.isEnabled(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_cardmarket_enabled), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_cardmarket_enabled_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            CardmarketPrices.setEnabled(context, it)
                        },
                    )
                }
                TextButton(onClick = onCardmarketDebug) { Text(stringResource(R.string.settings_cardmarket_debug)) }
            }
        }
    }
}

/** Sealed catalog: product list refresh, export and import of Pali's own list. */
@Composable
private fun SealedSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var importText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { count = withContext(Dispatchers.IO) { SealedCatalog.all(context).size } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            SealedInventory.load(context)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(SealedInventory.exportJson().toByteArray()) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.settings_sealed_exported else R.string.settings_sealed_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val excelExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SealedXlsx.MIME)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            SealedInventory.load(context)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = SealedXlsx.build(SealedInventory.items.value, EurHuf.rate(context), EurHuf.rateDate(context))
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) R.string.settings_sealed_exported else R.string.settings_sealed_export_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importText = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }.getOrNull()
            }
        }
    }

    Text(
        stringResource(R.string.settings_sealed_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.settings_sealed_count, count), style = MaterialTheme.typography.bodyLarge)
    Button(
        onClick = {
            refreshing = true
            error = null
            scope.launch {
                when (val result = SealedCatalog.refresh(context)) {
                    is SealedListRefreshResult.Done -> {
                        count = result.count
                        Toast.makeText(context, context.getString(R.string.settings_sealed_refreshed, result.count), Toast.LENGTH_SHORT).show()
                    }
                    is SealedListRefreshResult.Error -> error = result.message
                }
                refreshing = false
            }
        },
        enabled = !refreshing,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(if (refreshing) R.string.settings_names_refreshing else R.string.settings_sealed_refresh)) }
    error?.let { Text(stringResource(R.string.settings_sealed_failed, it), color = MaterialTheme.colorScheme.error) }
    OutlinedButton(
        onClick = { exporter.launch("pkmrec-sealed-${java.time.LocalDate.now()}.json") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_sealed_export)) }
    OutlinedButton(
        onClick = { excelExporter.launch("pkmrec-sealed-${java.time.LocalDate.now()}.xlsx") },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_sealed_export_excel)) }
    OutlinedButton(
        onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.settings_sealed_import)) }

    importText?.let { text ->
        fun import(replace: Boolean) {
            importText = null
            scope.launch {
                val message = runCatching { SealedInventory.importJson(context, text, replace) }.fold(
                    { context.getString(R.string.settings_sealed_imported, it) },
                    { context.getString(R.string.settings_sealed_import_failed, it.message ?: it.javaClass.simpleName) },
                )
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        AlertDialog(
            onDismissRequest = { importText = null },
            title = { Text(stringResource(R.string.settings_sealed_import_title)) },
            text = { Text(stringResource(R.string.settings_sealed_import_text)) },
            confirmButton = { TextButton(onClick = { import(replace = true) }) { Text(stringResource(R.string.settings_sealed_import_replace)) } },
            dismissButton = { TextButton(onClick = { import(replace = false) }) { Text(stringResource(R.string.settings_sealed_import_merge)) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
