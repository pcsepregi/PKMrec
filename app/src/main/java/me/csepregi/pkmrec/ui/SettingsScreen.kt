package me.csepregi.pkmrec.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.collectAsState
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
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.PokedexList
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgSession
import me.csepregi.pkmrec.update.UpdateManager
import me.csepregi.pkmrec.update.UpdateState

@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: () -> Unit) {
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
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}
