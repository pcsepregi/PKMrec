package me.csepregi.pkmrec.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The dark neon blue look of the whole app (same in the phone's light and dark mode). */
object Neon {
    val Background = Color(0xFF040A18)
    val BackgroundTop = Color(0xFF071530)
    val Surface = Color(0xFF0A1630)
    val SurfaceHigh = Color(0xFF0F2142)
    val Glow = Color(0xFF38B6FF)
    val GlowSoft = Color(0xFF1E6FD9)
    val Purple = Color(0xFF5B4BE0)
    val Text = Color(0xFFEAF2FF)
    val TextDim = Color(0xFF9FB3D1)
    val Border = Color(0xFF1E4A8C)
}

private val Yellow = Color(0xFFFACC15)

private val NeonColors = darkColorScheme(
    primary = Neon.Glow,
    onPrimary = Color(0xFF03122B),
    primaryContainer = Color(0xFF0E4FB8),
    onPrimaryContainer = Color.White,
    secondary = Yellow,
    onSecondary = Color(0xFF1F2937),
    // Transparent, so the neon background drawn behind all screens shows through.
    background = Color.Transparent,
    onBackground = Neon.Text,
    surface = Neon.Surface,
    onSurface = Neon.Text,
    surfaceVariant = Neon.SurfaceHigh,
    onSurfaceVariant = Neon.TextDim,
    surfaceContainerLowest = Neon.Background,
    surfaceContainerLow = Neon.Surface,
    surfaceContainer = Neon.Surface,
    surfaceContainerHigh = Neon.SurfaceHigh,
    surfaceContainerHighest = Neon.SurfaceHigh,
    outline = Neon.Border,
    outlineVariant = Color(0xFF16305A),
)

/** Colours for the big owned / not-owned verdict. */
object VerdictColors {
    val Owned = Color(0xFF16A34A)
    val NotOwned = Color(0xFFDC2626)
}

@Composable
fun PkmrecTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeonColors, content = content)
}
