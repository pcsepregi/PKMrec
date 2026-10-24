package me.csepregi.pkmrec.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/** Dark blue gradient with a large, faint Poké Ball in the bottom right corner; drawn once behind every screen. */
@Composable
fun NeonBackground() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Neon.BackgroundTop, Neon.Background))),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width * 0.62f
            drawPokeBallOutline(
                center = Offset(size.width * 0.92f, size.height * 0.86f),
                radius = radius,
                color = Neon.GlowSoft.copy(alpha = 0.14f),
                strokeWidth = radius * 0.05f,
            )
        }
    }
}

/** Outline of a Poké Ball: ring, middle band and the button in the centre. */
fun DrawScope.drawPokeBallOutline(center: Offset, radius: Float, color: Color, strokeWidth: Float) {
    val stroke = Stroke(width = strokeWidth)
    val buttonRing = radius * 0.3f
    drawCircle(color, radius = radius, center = center, style = stroke)
    drawLine(color, Offset(center.x - radius, center.y), Offset(center.x - buttonRing, center.y), strokeWidth)
    drawLine(color, Offset(center.x + buttonRing, center.y), Offset(center.x + radius, center.y), strokeWidth)
    drawCircle(color, radius = buttonRing, center = center, style = stroke)
    drawCircle(color, radius = radius * 0.16f, center = center)
}

/** A faint, tilted trading card with a Poké Ball on it, used as decoration on the home screen buttons. */
fun DrawScope.drawFaintCard(color: Color) {
    val cardHeight = size.height * 1.05f
    val cardWidth = cardHeight * 0.7f
    val topLeft = Offset(size.width - cardWidth * 0.95f, size.height * 0.05f)
    rotate(degrees = 12f, pivot = Offset(topLeft.x + cardWidth / 2, topLeft.y + cardHeight / 2)) {
        drawRoundRect(
            color = color,
            topLeft = topLeft,
            size = Size(cardWidth, cardHeight),
            cornerRadius = CornerRadius(cardWidth * 0.08f),
            style = Stroke(width = cardWidth * 0.03f),
        )
        drawPokeBallOutline(
            center = Offset(topLeft.x + cardWidth / 2, topLeft.y + cardHeight * 0.42f),
            radius = cardWidth * 0.26f,
            color = color,
            strokeWidth = cardWidth * 0.03f,
        )
    }
}

/** A small crown for the premium badge. */
@Composable
fun CrownIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.25f)
            lineTo(w * 0.32f, h * 0.55f)
            lineTo(w * 0.5f, h * 0.12f)
            lineTo(w * 0.68f, h * 0.55f)
            lineTo(w * 0.92f, h * 0.25f)
            lineTo(w * 0.84f, h * 0.75f)
            lineTo(w * 0.16f, h * 0.75f)
            close()
        }
        drawPath(path, color)
        drawRect(color, topLeft = Offset(w * 0.16f, h * 0.8f), size = Size(w * 0.68f, h * 0.1f))
    }
}
