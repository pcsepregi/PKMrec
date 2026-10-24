package me.csepregi.pkmrec.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgSession

/** Both feature buttons have the same height, whatever the length of their text. */
private val FEATURE_CARD_HEIGHT = 136.dp

/** One entry on the home screen. More features will be added to this list later. */
private data class Feature(val title: Int, val description: Int, val icon: Int, val open: () -> Unit)

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onLogin: () -> Unit, onRecognize: () -> Unit, onPokedex: () -> Unit) {
    val features = listOf(
        Feature(R.string.feature_recognition, R.string.feature_recognition_desc, R.drawable.feature_scan, onRecognize),
        Feature(R.string.feature_pokedex, R.string.feature_pokedex_desc, R.drawable.feature_pokedex, onPokedex),
    )
    val session by TcgSession.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val comingSoon = stringResource(R.string.coming_soon)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.pkmrec_logo),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.home_byline, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.settings))
                }
            }

            SessionStatus(state = session, onLogin = onLogin)

            Spacer(Modifier.height(8.dp))

            features.forEach { feature ->
                Card(
                    onClick = {
                        if (session is SessionState.LoggedOut) {
                            onLogin()
                        } else {
                            feature.open()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(FEATURE_CARD_HEIGHT),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Image(
                            painter = painterResource(feature.icon),
                            contentDescription = null,
                            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)),
                        )
                        Column {
                            Text(
                                text = stringResource(feature.title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = stringResource(feature.description),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
