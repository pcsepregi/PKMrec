package me.csepregi.pkmrec.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.csepregi.pkmrec.BuildConfig
import me.csepregi.pkmrec.R
import me.csepregi.pkmrec.tcg.SessionState
import me.csepregi.pkmrec.tcg.TcgSession

/**
 * All feature buttons have the same height, whatever the length of their text: they share the
 * screen below the header, so all of them fit without scrolling, but grow no taller than this.
 */
private val FEATURE_CARD_MAX_HEIGHT = 150.dp

/**
 * One entry on the home screen; [accent] is the colour the button's gradient fades into.
 * [needsLogin]: the feature reads TCG Collector, so a logged-out user is sent to the login first.
 */
private data class Feature(
    val title: Int,
    val description: Int,
    val icon: Int,
    val accent: Color,
    val needsLogin: Boolean = true,
    val open: () -> Unit,
)

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onLogin: () -> Unit, onRecognize: () -> Unit, onPokedex: () -> Unit, onSealed: () -> Unit, onBulkSell: () -> Unit) {
    val features = listOf(
        Feature(R.string.feature_recognition, R.string.feature_recognition_desc, R.drawable.feature_scan, Neon.GlowSoft, open = onRecognize),
        Feature(R.string.feature_pokedex, R.string.feature_pokedex_desc, R.drawable.feature_pokedex, Neon.Purple, open = onPokedex),
        Feature(R.string.feature_sealed, R.string.feature_sealed_desc, R.drawable.feature_sealed, Neon.GlowSoft, needsLogin = false, open = onSealed),
        Feature(R.string.feature_bulk_sell, R.string.feature_bulk_sell_desc, R.drawable.feature_bulk_sell, Neon.Purple, open = onBulkSell),
    )
    val session by TcgSession.state.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(onOpenSettings)
            SessionStatus(state = session, onLogin = onLogin)
            features.forEach { feature ->
                FeatureButton(
                    feature,
                    onClick = { if (feature.needsLogin && session is SessionState.LoggedOut) onLogin() else feature.open() },
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

@Composable
private fun Header(onOpenSettings: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.pkmrec_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.size(88.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.home_title_line1), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_title_line2), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Neon.Glow)
            Text(
                stringResource(R.string.home_byline, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = Neon.TextDim,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Neon.SurfaceHigh)
                .border(1.dp, Neon.Border, CircleShape)
                .clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Neon.Text, modifier = Modifier.size(26.dp))
        }
    }
}

@Composable
private fun FeatureButton(feature: Feature, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = FEATURE_CARD_MAX_HEIGHT)
            .fillMaxHeight()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0B5FD6), Color(0xFF0A2A6B), feature.accent.copy(alpha = 0.85f))))
            .border(1.5.dp, Brush.linearGradient(listOf(Neon.Glow, Neon.GlowSoft.copy(alpha = 0.4f), feature.accent)), shape)
            .clickable(onClick = onClick),
    ) {
        val compact = maxHeight < 130.dp
        Canvas(modifier = Modifier.fillMaxSize()) { drawFaintCard(Color.White.copy(alpha = 0.10f)) }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(feature.icon),
                contentDescription = null,
                modifier = Modifier
                    .size(minOf(92.dp, maxHeight - 20.dp).coerceAtLeast(40.dp))
                    .clip(RoundedCornerShape(18.dp))
                    .border(1.5.dp, Neon.Glow.copy(alpha = 0.7f), RoundedCornerShape(18.dp)),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(feature.title),
                    style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    stringResource(feature.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neon.Text.copy(alpha = 0.85f),
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Neon.Background.copy(alpha = 0.7f))
                    .border(1.5.dp, Neon.Glow, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White)
            }
        }
    }
}
