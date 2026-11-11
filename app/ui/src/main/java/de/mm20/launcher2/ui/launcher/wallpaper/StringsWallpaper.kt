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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Light strings: a fan of luminous strings stretched across the screen like an instrument.
 * Dragging across a string catches it, pulls it along with the finger and lets it snap free,
 * so it rings out as a real vibrating string; a quick strum plucks every string it crosses and
 * a tap plucks the nearest one. Ringing strings glow brighter and settle back to rest. While
 * music plays, each [MusicPulse] beat softly plucks a string, rotating through the fan.
 */
@Composable
fun StringsWallpaper(modifier: Modifier = Modifier) {
    val strings = remember { List(STRING_COUNT) { LightString(it) } }
    val layout = remember { StringsLayout() }
    val timeNanos = remember { mutableLongStateOf(0L) }
    val pulse = remember { MusicPulse() }

    LaunchedEffect(Unit) {
        var start = 0L
        var wasPressed = false
        var lastX = 0f
        var lastY = 0f
        var beatHigh = false
        var beatCount = 0
        while (isActive) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                val t = (now - start) / 1_000_000_000f
                layout.time = t

                if (layout.width > 0f) {
                    val x = WallpaperPointer.x
                    val y = WallpaperPointer.y
                    val pressed = WallpaperPointer.pressed
                    if (pressed && !wasPressed) {
                        tap(strings, layout, x, y)
                    } else if (pressed) {
                        strum(strings, layout, lastX, lastY, x, y)
                    }
                    for (s in strings) {
                        if (s.grabbed) s.hold(layout, x, y, pressed)
                    }
                    lastX = x
                    lastY = y
                    wasPressed = pressed
                }

                pulse.update(now)
                val groove = pulse.groove
                if (groove > 0f) {
                    // Pluck once on each beat's rising edge.
                    val high = pulse.beat > BEAT_THRESHOLD * groove
                    if (high && !beatHigh && layout.width > 0f) {
                        musicPluck(strings, layout, beatCount, groove)
                        beatCount++
                    }
                    beatHigh = high
                } else {
                    beatHigh = false
                }

                for (s in strings) s.step()
                timeNanos.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        timeNanos.longValue // redraw is driven by the simulation loop
        layout.ensure(size.width, size.height)
        drawBackdrop(this, strings)
        for (s in strings) drawString(this, s, layout)
    }
}

private const val STRING_COUNT = 16
private const val NODES = 40
private const val SUBSTEPS = 6
private const val DAMPING = 0.0022f
private const val U_START = -0.06f
private const val U_SPAN = 1.12f
private const val MAX_PULL = 0.8f
private const val TAP_RADIUS = 0.6f
private const val TAP_PULL = 0.45f
private const val BEAT_THRESHOLD = 0.3f
private const val MUSIC_PULL = 0.12f

/** Screen geometry of the fan: where each string rests and how far apart neighbours are. */
private class StringsLayout {
    var width = 0f
        private set
    var height = 0f
        private set
    var time = 0f
    var coreBrushes: List<Brush> = emptyList()
        private set
    var glowBrushes: List<Brush> = emptyList()
        private set

    fun ensure(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        coreBrushes = List(STRING_COUNT) { i -> Brush.horizontalGradient(stringColors(i, 0.55f), 0f, w) }
        glowBrushes = List(STRING_COUNT) { i -> Brush.horizontalGradient(stringColors(i, 0.85f), 0f, w) }
    }

    /** Distance between neighbouring strings; the fan opens up toward the right. */
    fun spacing(u: Float): Float = height * (0.024f + 0.026f * u.coerceIn(0f, 1f))

    /** Resting height of string [i] at horizontal position [u] (0..1 across the screen). */
    fun restY(i: Int, u: Float): Float {
        val centre = height * (0.5f + 0.085f * sin(2.3f * u + 0.5f) - 0.07f * u)
        val sway = height * 0.003f * sin(time * 0.35f + i * 0.55f + u * 3f)
        return centre + (i - (STRING_COUNT - 1) / 2f) * spacing(u) + sway
    }

    fun nodeU(j: Int): Float = U_START + U_SPAN * j / (NODES - 1)

    fun nodeAt(x: Float): Float = ((x / width - U_START) / U_SPAN * (NODES - 1))
        .coerceIn(1f, (NODES - 2).toFloat())
}

private fun stringColors(i: Int, saturation: Float): List<Color> {
    val hue = 200f + 140f * i / (STRING_COUNT - 1)
    return listOf(
        Color.hsv((hue - 30f + 360f) % 360f, saturation, 1f),
        Color.hsv(hue % 360f, saturation, 1f),
        Color.hsv((hue + 40f) % 360f, saturation, 1f),
    )
}

/** One string: a damped 1D wave equation with both ends pinned beyond the screen edges. */
private class LightString(val index: Int) {
    var disp = FloatArray(NODES)
        private set
    private var prev = FloatArray(NODES)
    private var next = FloatArray(NODES)

    // Higher strings are tighter and ring at a higher pitch.
    private val c2 = 0.95f - 0.6f * index / (STRING_COUNT - 1)

    var grabbed = false
        private set
    private var grabNode = 0f
    var glow = 0f
        private set

    fun step() {
        if (!grabbed) {
            repeat(SUBSTEPS) {
                for (j in 1 until NODES - 1) {
                    val lap = disp[j - 1] - 2f * disp[j] + disp[j + 1]
                    next[j] = (2f - DAMPING) * disp[j] - (1f - DAMPING) * prev[j] + c2 * lap
                }
                val old = prev
                prev = disp
                disp = next
                next = old
            }
        }
        var peak = 0f
        for (v in disp) peak = max(peak, abs(v))
        glow = max(peak, glow * 0.94f)
    }

    /** Shapes the string into a pull toward [node] by [amount] pixels and lets it go from rest. */
    fun pull(node: Float, amount: Float) {
        val k = node.coerceIn(1f, (NODES - 2).toFloat())
        for (j in 0 until NODES) {
            disp[j] = if (j <= k) amount * j / k else amount * (NODES - 1 - j) / (NODES - 1 - k)
        }
        disp[0] = 0f
        disp[NODES - 1] = 0f
        disp.copyInto(prev)
    }

    /** Adds a soft pluck of [amount] pixels at [node] on top of whatever the string is doing. */
    fun nudge(node: Float, amount: Float) {
        val k = node.coerceIn(1f, (NODES - 2).toFloat())
        for (j in 1 until NODES - 1) {
            val d = if (j <= k) amount * j / k else amount * (NODES - 1 - j) / (NODES - 1 - k)
            disp[j] += d
            prev[j] += d
        }
    }

    fun grab(layout: StringsLayout, x: Float) {
        grabbed = true
        grabNode = layout.nodeAt(x)
    }

    /** Drags a caught string with the finger until it slips off or the finger lifts. */
    fun hold(layout: StringsLayout, x: Float, y: Float, pressed: Boolean) {
        val u = x / layout.width
        val offset = y - layout.restY(index, u)
        if (!pressed || abs(offset) > layout.spacing(u) * MAX_PULL) {
            grabbed = false
            return
        }
        grabNode = layout.nodeAt(x)
        pull(grabNode, offset)
    }

    fun yAt(layout: StringsLayout, j: Int): Float = layout.restY(index, layout.nodeU(j)) + disp[j]
}

private fun tap(strings: List<LightString>, layout: StringsLayout, x: Float, y: Float) {
    val u = x / layout.width
    val spacing = layout.spacing(u)
    val nearest = strings.minByOrNull { abs(y - layout.restY(it.index, u)) } ?: return
    if (abs(y - layout.restY(nearest.index, u)) > spacing * TAP_RADIUS) return
    val dir = if (Random.nextBoolean()) 1f else -1f
    nearest.pull(layout.nodeAt(x), dir * spacing * TAP_PULL)
}

/**
 * Music groove: each beat gently plucks one string, stepping through the fan in a scattered order
 * at a slowly wandering point; every fourth beat a second string joins in.
 */
private fun musicPluck(strings: List<LightString>, layout: StringsLayout, beat: Int, groove: Float) {
    val u = 0.25f + 0.5f * ((beat * 0.382f) % 1f)
    val node = layout.nodeAt(u * layout.width)
    val amount = layout.spacing(u) * MUSIC_PULL * groove * (if (beat % 2 == 0) 1f else -1f)
    val first = strings[(beat * 5) % STRING_COUNT]
    if (!first.grabbed) first.nudge(node, amount)
    if (beat % 4 == 0) {
        val second = strings[(beat * 5 + 8) % STRING_COUNT]
        if (!second.grabbed) second.nudge(node, -0.7f * amount)
    }
}

/** Catches every string the finger crossed since the last frame. */
private fun strum(
    strings: List<LightString>,
    layout: StringsLayout,
    x0: Float,
    y0: Float,
    x1: Float,
    y1: Float,
) {
    if (x0 == x1 && y0 == y1) return
    val u0 = x0 / layout.width
    val u1 = x1 / layout.width
    for (s in strings) {
        if (s.grabbed) continue
        val before = y0 - layout.restY(s.index, u0)
        val after = y1 - layout.restY(s.index, u1)
        if ((before < 0f) != (after < 0f)) s.grab(layout, x1)
    }
}

private fun drawBackdrop(scope: DrawScope, strings: List<LightString>) {
    val w = scope.size.width
    val h = scope.size.height
    var energy = 0f
    for (s in strings) energy += s.glow
    val e = (energy / (h * 0.15f)).coerceIn(0f, 1f)
    scope.drawRect(
        Brush.verticalGradient(
            listOf(Color(0xFF04040A), Color(0xFF0A0818), Color(0xFF040409)),
        ),
    )
    scope.drawRect(
        Brush.radialGradient(
            colors = listOf(
                Color(0xFF4B2A9E).copy(alpha = 0.22f + 0.25f * e),
                Color(0xFF1B3D8F).copy(alpha = 0.10f + 0.12f * e),
                Color.Transparent,
            ),
            center = Offset(w * 0.55f, h * 0.5f),
            radius = max(w, h) * 0.55f,
        ),
    )
}

private val stringPath = Path()

private fun drawString(scope: DrawScope, s: LightString, layout: StringsLayout) {
    val w = layout.width
    stringPath.reset()
    var px = layout.nodeU(0) * w
    var py = s.yAt(layout, 0)
    stringPath.moveTo(px, py)
    for (j in 1 until NODES) {
        val x = layout.nodeU(j) * w
        val y = s.yAt(layout, j)
        stringPath.quadraticTo(px, py, (px + x) / 2f, (py + y) / 2f)
        px = x
        py = y
    }
    stringPath.lineTo(px, py)

    val ring = (s.glow / layout.spacing(0.5f)).coerceIn(0f, 1f)
    scope.drawPath(
        path = stringPath,
        brush = layout.glowBrushes[s.index],
        alpha = 0.05f + 0.30f * ring,
        style = Stroke(width = w * (0.012f + 0.03f * ring), cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
    scope.drawPath(
        path = stringPath,
        brush = layout.glowBrushes[s.index],
        alpha = 0.12f + 0.35f * ring,
        style = Stroke(width = w * (0.005f + 0.008f * ring), cap = StrokeCap.Round),
        blendMode = BlendMode.Plus,
    )
    scope.drawPath(
        path = stringPath,
        brush = layout.coreBrushes[s.index],
        alpha = 0.55f + 0.45f * ring,
        style = Stroke(width = w * (0.0022f + 0.0015f * ring), cap = StrokeCap.Round),
    )
}
