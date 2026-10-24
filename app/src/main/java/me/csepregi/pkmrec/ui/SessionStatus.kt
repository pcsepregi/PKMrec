package me.csepregi.pkmrec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgSession

/** TCG Collector login status with the matching action (log in / retry). */
@Composable
fun SessionStatus(state: SessionState, onLogin: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Neon.Surface.copy(alpha = 0.85f))
            .border(1.dp, Neon.Border, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Neon.SurfaceHigh)
                .border(1.dp, Neon.Border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Neon.Glow, modifier = Modifier.size(26.dp))
        }
        when (state) {
            is SessionState.LoggedIn -> {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.session_logged_in_label), style = MaterialTheme.typography.bodySmall, color = Neon.TextDim)
                    Text(state.user.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (state.user.isPremium) PremiumBadge()
            }
            else -> {
                val (text, action) = statusText(state, onLogin, retry = { TcgSession.refresh(context) })
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (action != null) {
                    TextButton(onClick = action.second) { Text(action.first) }
                }
            }
        }
    }
}

@Composable
private fun statusText(state: SessionState, onLogin: () -> Unit, retry: () -> Unit): Pair<String, Pair<String, () -> Unit>?> =
    when (state) {
        SessionState.Unknown, SessionState.Checking -> stringResource(R.string.session_checking) to null
        is SessionState.LoggedIn -> stringResource(R.string.session_logged_in, state.user.displayName) to null
        SessionState.LoggedOut -> stringResource(R.string.session_logged_out) to (stringResource(R.string.login) to onLogin)
        is SessionState.Error -> {
            val msg = state.lastUser?.let {
                stringResource(R.string.session_error_last_user, it.displayName, tcgErrorText(state.error))
            } ?: stringResource(R.string.session_error, tcgErrorText(state.error))
            msg to (stringResource(R.string.retry) to retry)
        }
    }

/** Shown when the TCG Collector account is premium; only a badge, not a button. */
@Composable
private fun PremiumBadge() {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(Neon.Glow.copy(alpha = 0.08f))
            .border(1.5.dp, Neon.Glow, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CrownIcon(color = Neon.Glow, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.session_premium), color = Neon.Glow, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}
