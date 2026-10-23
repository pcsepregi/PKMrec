package me.csepregi.pkmrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgSession

/** One-line TCG Collector login status with the matching action (log in / retry). */
@Composable
fun SessionStatus(state: SessionState, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val status: Pair<String, Pair<String, () -> Unit>?> = when (state) {
                SessionState.Unknown, SessionState.Checking ->
                    stringResource(R.string.session_checking) to null
                is SessionState.LoggedIn ->
                    stringResource(R.string.session_logged_in, state.user.displayName) to null
                SessionState.LoggedOut ->
                    stringResource(R.string.session_logged_out) to (stringResource(R.string.login) to onLogin)
                is SessionState.Error -> {
                    val msg = state.lastUser?.let {
                        stringResource(R.string.session_error_last_user, it.displayName, state.message)
                    } ?: stringResource(R.string.session_error, state.message)
                    msg to (stringResource(R.string.retry) to { TcgSession.refresh(context) })
                }
            }
            val (text, action) = status
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (state is SessionState.LoggedIn && state.user.isPremium) {
                AssistChip(onClick = {}, label = { Text(stringResource(R.string.session_premium)) })
            }
            if (action != null) {
                TextButton(onClick = action.second) { Text(action.first) }
            }
        }
    }
}
