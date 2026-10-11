package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val MAX_TRAIL = 400

private class TrailPalette(
    val bandColors: List<Color>,
    val bandWidths: FloatArray,
    val headColor: Color,
    val glowColor: Color,
)

private val CoolTrailPalette = TrailPalette(
    bandColors = listOf(
        Color(0xFF3A2E8C).copy(alpha = 0.10f),
        Color(0xFF4A5FD0).copy(alpha = 0.18f),
        Color(0xFF57A8FF).copy(alpha = 0.35f),
        Color(0xFFBFE6FF).copy(alpha = 0.8f),
    ),
    bandWidths = floatArrayOf(6f, 4.5f, 3f, 1.8f),
    headColor = Color(0xFFBFE6FF),
    glowColor = Color(0xFFDCEBFF),
)

private val WarmTrailPalette = TrailPalette(
    bandColors = listOf(
        Color(0xFF8C3A2E).copy(alpha = 0.10f),
        Color(0xFFD0792B).copy(alpha = 0.18f),
        Color(0xFFFFC44D).copy(alpha = 0.35f),
        Color(0xFFFFF0D2).copy(alpha = 0.8f),
    ),
    bandWidths = floatArrayOf(5f, 3.8f, 2.5f, 1.5f),
    headColor = Color(0xFFFFF0D2),
    glowColor = Color(0xFFFFF0D2),
)

private class DoublePendulum {
    var theta1 = PI / 2.0
    var theta2 = PI / 2.0
    var omega1 = 0.0
    var omega2 = 0.0

    var anchorX = 0f
    var anchorY = 0f
    var bob1X = 0f
    var bob1Y = 0f
    var bob2X = 0f
    var bob2Y = 0f
}

private class PendulumViewport {
    var w = 0f
    var h = 0f
}

/**
 * A chaotic double pendulum on a black background, with fading glow trails tracing both bobs.
 */
@Composable
fun DoublePendulumWallpaper(modifier: Modifier = Modifier) {
    val pendulum = remember { DoublePendulum() }
    val viewport = remember { PendulumViewport() }
    val trail = remember { mutableListOf<Offset>() }
    val trail1 = remember { mutableListOf<Offset>() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1_000_000_000.0
                last = now
                if (viewport.w > 0f && dt > 0.0) {
                    val minDim = minOf(viewport.w, viewport.h)
                    val l1 = minDim * 0.24
                    val l2 = minDim * 0.24
                    val g = l1 * 20.0
                    step(pendulum, l1, l2, g, dt.coerceAtMost(0.05))

                    pendulum.anchorX = viewport.w / 2f
                    pendulum.anchorY = viewport.h / 2f
                    pendulum.bob1X = (pendulum.anchorX + l1 * sin(pendulum.theta1)).toFloat()
                    pendulum.bob1Y = (pendulum.anchorY + l1 * cos(pendulum.theta1)).toFloat()
                    pendulum.bob2X = (pendulum.bob1X + l2 * sin(pendulum.theta2)).toFloat()
                    pendulum.bob2Y = (pendulum.bob1Y + l2 * cos(pendulum.theta2)).toFloat()

                    trail1.add(Offset(pendulum.bob1X, pendulum.bob1Y))
                    while (trail1.size > MAX_TRAIL) trail1.removeAt(0)
                    trail.add(Offset(pendulum.bob2X, pendulum.bob2Y))
                    while (trail.size > MAX_TRAIL) trail.removeAt(0)
                }
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        viewport.w = size.width
        viewport.h = size.height

        drawRect(Color.Black)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0A1030).copy(alpha = 0.5f), Color.Transparent),
                center = Offset(pendulum.anchorX, pendulum.anchorY),
                radius = size.maxDimension * 0.55f,
            ),
        )

        // Light-painting trails: warm upper bob path first, cool lower bob path on top.
        drawLightTrail(trail1, WarmTrailPalette)
        drawLightTrail(trail, CoolTrailPalette)

        val anchor = Offset(pendulum.anchorX, pendulum.anchorY)
        val bob1 = Offset(pendulum.bob1X, pendulum.bob1Y)
        val bob2 = Offset(pendulum.bob2X, pendulum.bob2Y)

        for ((a, b) in listOf(anchor to bob1, bob1 to bob2)) {
            drawLine(
                color = Color(0xFF6E9BFF).copy(alpha = 0.12f),
                start = a,
                end = b,
                strokeWidth = 5f,
                blendMode = BlendMode.Plus,
            )
            drawLine(
                color = Color(0xFFD6E4F8).copy(alpha = 0.7f),
                start = a,
                end = b,
                strokeWidth = 2f,
            )
        }

        // Pivot mount.
        drawCircle(Color(0xFF2A3348), radius = 3f, center = anchor)
        drawCircle(
            color = Color(0xFF6E9BFF).copy(alpha = 0.25f),
            radius = 6f,
            center = anchor,
            style = Stroke(width = 1f),
        )

        // Glass bobs.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF9CC0FF).copy(alpha = 0.4f), Color.Transparent),
                center = bob1,
                radius = 14f,
            ),
            radius = 14f,
            center = bob1,
            blendMode = BlendMode.Plus,
        )
        drawCircle(Color(0xFFD6E4F8), radius = 2.5f, center = bob1, blendMode = BlendMode.Plus)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.6f), Color.Transparent),
                center = bob2,
                radius = 22f,
            ),
            radius = 22f,
            center = bob2,
            blendMode = BlendMode.Plus,
        )
        drawCircle(Color.White, radius = 3.5f, center = bob2, blendMode = BlendMode.Plus)
        drawCircle(
            Color.White.copy(alpha = 0.9f),
            radius = 1.2f,
            center = Offset(bob2.x - 1.2f, bob2.y - 1.2f),
            blendMode = BlendMode.Plus,
        )

        // Vignette.
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                center = anchor,
                radius = size.maxDimension * 0.8f,
            ),
        )
    }
}

private fun DrawScope.drawLightTrail(trail: List<Offset>, palette: TrailPalette) {
    // Light-painting path: a wide dim tail melting into a hot thin head.
    val n = trail.size
    if (n >= 2) {
        for (b in 0 until 4) {
            val end = n - (3 - b) * 100
            val start = (end - 100).coerceAtLeast(0)
            if (end - start < 2) continue
            val path = Path().apply {
                moveTo(trail[start].x, trail[start].y)
                for (i in start + 1 until end) lineTo(trail[i].x, trail[i].y)
            }
            drawPath(
                path = path,
                color = palette.bandColors[b],
                style = Stroke(width = palette.bandWidths[b]),
                blendMode = BlendMode.Plus,
            )
        }
    }

    // Hot head.
    if (n > 0) {
        val head = trail[n - 1]
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(palette.headColor.copy(alpha = 0.35f), Color.Transparent),
                center = head,
                radius = 26f,
            ),
            radius = 26f,
            center = head,
            blendMode = BlendMode.Plus,
        )
        val glowStart = (n - 40).coerceAtLeast(0)
        for (i in glowStart until n) {
            val t = (i - glowStart) / 40f
            drawCircle(
                color = palette.glowColor.copy(alpha = 0.5f * t),
                radius = 1f + 2.5f * t,
                center = trail[i],
                blendMode = BlendMode.Plus,
            )
        }
    }
}

private fun step(
    pendulum: DoublePendulum,
    l1: Double,
    l2: Double,
    g: Double,
    dt: Double,
) {
    val substeps = 8
    val h = dt / substeps
    repeat(substeps) {
        val d = pendulum.theta2 - pendulum.theta1
        val sinD = sin(d)
        val cosD = cos(d)
        val denom = 3.0 - cos(2.0 * d)
        val w1 = pendulum.omega1
        val w2 = pendulum.omega2

        val num1 = -g * 3.0 * sin(pendulum.theta1) - g * sin(pendulum.theta1 - 2.0 * pendulum.theta2) -
                2.0 * sinD * (w2 * w2 * l2 + w1 * w1 * l1 * cosD)
        val alpha1 = num1 / (l1 * denom)

        val num2 = 2.0 * sinD * (w1 * w1 * l1 * 2.0 + g * 2.0 * cos(pendulum.theta1) + w2 * w2 * l2 * cosD)
        val alpha2 = num2 / (l2 * denom)

        pendulum.omega1 += alpha1 * h
        pendulum.omega2 += alpha2 * h
        pendulum.theta1 += pendulum.omega1 * h
        pendulum.theta2 += pendulum.omega2 * h
    }
}
