package me.csepregi.pkmrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
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
                Text(
                    stringResource(R.string.settings_pokedex_list_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
