package me.csepregi.pkmrec.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF0A5BD8) // matches the logo
private val Yellow = Color(0xFFFACC15)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    secondary = Yellow,
    onSecondary = Color(0xFF1F2937),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5CC8FF),
    onPrimary = Color(0xFF0B1B44),
    secondary = Yellow,
    onSecondary = Color(0xFF1F2937),
)

/** Colours for the big owned / not-owned verdict (used from milestone 4 on). */
object VerdictColors {
    val Owned = Color(0xFF16A34A)
    val NotOwned = Color(0xFFDC2626)
}

@Composable
fun PkmrecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
