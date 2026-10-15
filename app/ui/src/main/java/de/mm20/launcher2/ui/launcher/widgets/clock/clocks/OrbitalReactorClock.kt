package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun OrbitalReactorClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val accent = if (useThemeColor) {
        if (!darkColors) {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.primary
        }
    } else LocalContentColor.current
    val energy = Color(0xFF61E7FF)
    val violet = Color(0xFFA881FF)
    val clockSize = if (compact) 58.dp else 216.dp
    val transition = rememberInfiniteTransition(label = "orbital-reactor")
    val orbitA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(22_000, easing = LinearEasing)),
        label = "reactor-orbit-a",
    )
    val orbitB by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(15_000, easing = LinearEasing)),
        label = "reactor-orbit-b",
    )
    val orbitC by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(31_000, easing = LinearEasing)),
        label = "reactor-orbit-c",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = androidx.compose.animation.core.RepeatMode.Reverse),
        label = "reactor-core-pulse",
    )
    val verticalLayout = !compact
    val pattern = when {
        showSeconds && verticalLayout -> if (twentyFourHours) "HH:mm:ss" else "hh:mm:ss"
        twentyFourHours -> "HH:mm"
        else -> "hh:mm"
    }
    val clockText = SimpleDateFormat(pattern, Locale.getDefault()).format(time)

    Column(
        modifier = Modifier.padding(vertical = if (compact) 0.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.size(clockSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(clockSize)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val rx = size.width * 0.415f
                val ry = size.height * 0.285f
                val stroke = (if (compact) 1.dp else 1.35.dp).toPx()

                // Soft outer halo and three different orbital planes.
                drawCircle(accent.copy(alpha = if (compact) 0.08f else 0.055f), radius = size.minDimension * 0.47f)
                rotate(orbitA, pivot = c) {
                    drawOval(
                        color = accent.copy(alpha = 0.36f),
                        topLeft = Offset(c.x - rx, c.y - ry),
                        size = Size(rx * 2f, ry * 2f),
                        style = Stroke(stroke),
                    )
                }
                rotate(orbitB + 62f, pivot = c) {
                    drawOval(
                        color = energy.copy(alpha = 0.34f),
                        topLeft = Offset(c.x - rx * 0.84f, c.y - ry * 1.3f),
                        size = Size(rx * 1.68f, ry * 2.6f),
                        style = Stroke(stroke),
                    )
                }
                rotate(orbitC + 118f, pivot = c) {
                    drawOval(
                        color = violet.copy(alpha = 0.32f),
                        topLeft = Offset(c.x - rx * 0.98f, c.y - ry * 0.9f),
                        size = Size(rx * 1.96f, ry * 1.8f),
                        style = Stroke(stroke),
                    )
                }

                // Layered core glow makes the reactor feel lit without a bitmap asset.
                val coreRadius = size.minDimension * (if (compact) 0.145f else 0.19f) * pulse
                drawCircle(accent.copy(alpha = 0.06f), coreRadius * 2.15f, c)
                drawCircle(energy.copy(alpha = 0.11f), coreRadius * 1.55f, c)
                drawCircle(accent.copy(alpha = 0.10f), coreRadius, c)
                drawCircle(
                    color = accent.copy(alpha = 0.62f),
                    radius = coreRadius,
                    center = c,
                    style = Stroke(stroke * (if (compact) 1.5f else 2f)),
                )

                fun orbitalPoint(angleDegrees: Float, xRadius: Float, yRadius: Float): Offset {
                    val angle = Math.toRadians(angleDegrees.toDouble())
                    return Offset(
                        x = c.x + cos(angle).toFloat() * xRadius,
                        y = c.y + sin(angle).toFloat() * yRadius,
                    )
                }

                val particleA = orbitalPoint(orbitA, rx, ry)
                val particleB = orbitalPoint(orbitB + 62f, rx * 0.84f, ry * 1.3f)
                val particleC = orbitalPoint(orbitC + 118f, rx * 0.98f, ry * 0.9f)
                val particleRadius = (if (compact) 2.4.dp else 4.dp).toPx()
                listOf(particleA to accent, particleB to energy, particleC to violet).forEach { (point, color) ->
                    drawCircle(color.copy(alpha = 0.22f), particleRadius * 2.8f, point)
                    drawCircle(color, particleRadius, point)
                }
            }

            Text(
                text = clockText,
                color = accent,
                textAlign = TextAlign.Center,
                maxLines = 1,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = if (compact) 8.sp else 26.sp,
                    lineHeight = if (compact) 10.sp else 30.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = if (compact) (-0.5).sp else (-1).sp,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = accent.copy(alpha = 0.55f),
                        blurRadius = if (compact) 4f else 14f,
                    ),
                ),
            )
        }
    }
}
