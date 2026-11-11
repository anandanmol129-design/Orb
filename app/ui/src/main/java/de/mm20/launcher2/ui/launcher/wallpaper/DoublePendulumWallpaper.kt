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

private const val TRAIL_BANDS = 4
private const val BAND_LEN = 120
private const val MAX_TRAIL = TRAIL_BANDS * BAND_LEN

/** Music: every fourth beat speeds the bobs up this much (times groove) if energy has drifted low. */
private const val BAR_NUDGE = 0.03

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
 * A chaotic double pendulum on a black background, with a fading light-painting trail tracing
 * the outer bob. The inner bob only ever sweeps a circle, so it gets no trail.
 * While music plays the trail and bobs brighten on each beat, with a gentle push on every fourth.
 */
@Composable
fun DoublePendulumWallpaper(modifier: Modifier = Modifier) {
    val pendulum = remember { DoublePendulum() }
    val viewport = remember { PendulumViewport() }
    val trail = remember { mutableListOf<Offset>() }
    val frame = remember { mutableLongStateOf(0L) }
    val pulse = remember { MusicPulse() }

    LaunchedEffect(Unit) {
        var last = 0L
        var lastBeat = 0f
        var beatCount = 0
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1_000_000_000.0
                last = now
                pulse.update(now)
                val onset = pulse.beat > 0f && lastBeat == 0f
                lastBeat = pulse.beat
                if (pulse.groove == 0f) beatCount = 0
                if (viewport.w > 0f && dt > 0.0) {
                    val minDim = minOf(viewport.w, viewport.h)
                    val l1 = minDim * 0.24
                    val l2 = minDim * 0.24
                    val g = l1 * 20.0
                    if (onset && beatCount++ % 4 == 0) barNudge(pendulum, l1, l2, g, pulse.groove)
                    step(pendulum, l1, l2, g, dt.coerceAtMost(0.05))

                    pendulum.anchorX = viewport.w / 2f
                    pendulum.anchorY = viewport.h / 2f
                    pendulum.bob1X = (pendulum.anchorX + l1 * sin(pendulum.theta1)).toFloat()
                    pendulum.bob1Y = (pendulum.anchorY + l1 * cos(pendulum.theta1)).toFloat()
                    pendulum.bob2X = (pendulum.bob1X + l2 * sin(pendulum.theta2)).toFloat()
                    pendulum.bob2Y = (pendulum.bob1Y + l2 * cos(pendulum.theta2)).toFloat()

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

        val beat = pulse.beat

        // Light-painting trail of the outer bob.
        drawLightTrail(trail, CoolTrailPalette, beat)

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

        // Glass bobs; their halos swell and brighten slightly on the music beat.
        val halo1 = 14f * (1f + 0.1f * beat)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF9CC0FF).copy(alpha = 0.4f + 0.06f * beat), Color.Transparent),
                center = bob1,
                radius = halo1,
            ),
            radius = halo1,
            center = bob1,
            blendMode = BlendMode.Plus,
        )
        drawCircle(Color(0xFFD6E4F8), radius = 2.5f, center = bob1, blendMode = BlendMode.Plus)

        val halo2 = 22f * (1f + 0.1f * beat)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.6f + 0.08f * beat), Color.Transparent),
                center = bob2,
                radius = halo2,
            ),
            radius = halo2,
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

/** [beat] (0..1, 0 without music) lifts the trail's brightness ~12% and its head glow a little. */
private fun DrawScope.drawLightTrail(trail: List<Offset>, palette: TrailPalette, beat: Float) {
    val lift = 1f + 0.12f * beat
    // Light-painting path: a wide dim tail melting into a hot thin head.
    val n = trail.size
    if (n >= 2) {
        for (b in 0 until TRAIL_BANDS) {
            val end = n - (TRAIL_BANDS - 1 - b) * BAND_LEN
            val start = (end - BAND_LEN).coerceAtLeast(0)
            if (end - start < 2) continue
            val path = Path().apply {
                moveTo(trail[start].x, trail[start].y)
                for (i in start + 1 until end) lineTo(trail[i].x, trail[i].y)
            }
            val color = palette.bandColors[b]
            drawPath(
                path = path,
                color = if (beat > 0f) color.copy(alpha = (color.alpha * lift).coerceAtMost(1f)) else color,
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
                colors = listOf(palette.headColor.copy(alpha = 0.35f * lift), Color.Transparent),
                center = head,
                radius = 26f * lift,
            ),
            radius = 26f * lift,
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

/**
 * A downbeat push for the music: speeds both arms up by [BAR_NUDGE] * [groove], but only while the
 * total energy sits below the release energy (both arms horizontal, at rest, which is 0 here), so
 * it makes up integrator drift and can never pump the swing beyond where it started.
 */
private fun barNudge(pendulum: DoublePendulum, l1: Double, l2: Double, g: Double, groove: Float) {
    val w1 = pendulum.omega1
    val w2 = pendulum.omega2
    // Unit masses, matching the equations of motion in step.
    val kinetic = l1 * l1 * w1 * w1 + 0.5 * l2 * l2 * w2 * w2 +
            l1 * l2 * w1 * w2 * cos(pendulum.theta1 - pendulum.theta2)
    val potential = -2.0 * g * l1 * cos(pendulum.theta1) - g * l2 * cos(pendulum.theta2)
    if (kinetic + potential >= 0.0) return
    val k = 1.0 + BAR_NUDGE * groove
    pendulum.omega1 = w1 * k
    pendulum.omega2 = w2 * k
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
