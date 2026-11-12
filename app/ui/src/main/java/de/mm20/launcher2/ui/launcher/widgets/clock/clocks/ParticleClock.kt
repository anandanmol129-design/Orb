package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A clock whose digits are formed by a few hundred particles. Every particle is pulled
 * towards a cell of a 5x7 dot-matrix glyph by a damped spring and shimmers slightly.
 * When a glyph changes, its particles are kicked outwards and swirl into the new shape.
 */
@Composable
fun ParticleClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val parsed = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val hour = if (twentyFourHours) parsed.hour else (((parsed.hour + 11) % 12) + 1)
    val minute = parsed.minute
    val second = parsed.second

    // Same convention as SegmentClock / BinaryClock: seconds only in the large layout.
    val withSeconds = showSeconds && !compact

    val color = if (useThemeColor) {
        if (!darkColors) {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.primary
        }
    } else {
        LocalContentColor.current
    }

    val codes = remember(hour, minute, if (withSeconds) second else -1, twentyFourHours) {
        val list = IntArray(if (withSeconds) 8 else 5)
        // 12h mode: hide the leading zero, like a regular digital clock.
        list[0] = if (!twentyFourHours && hour < 10) PARTICLE_GLYPH_BLANK else hour / 10
        list[1] = hour % 10
        list[2] = PARTICLE_GLYPH_COLON
        list[3] = minute / 10
        list[4] = minute % 10
        if (withSeconds) {
            list[5] = PARTICLE_GLYPH_COLON
            list[6] = second / 10
            list[7] = second % 10
        }
        list
    }

    val sim = remember(withSeconds) { ParticleSimulation(codes) }

    SideEffect {
        sim.setDesired(codes)
    }

    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(sim) {
        var lastNanos = -1L
        while (true) {
            // Non-capturing lambda -> no per-frame allocation for it.
            val now = withFrameNanos { it }
            if (lastNanos >= 0L) {
                var dt = (now - lastNanos) / 1_000_000_000f
                // Clamp so that resuming after a pause (or a janky frame) doesn't explode the sim.
                if (dt > PARTICLE_MAX_DT) dt = PARTICLE_MAX_DT
                if (dt < 0f) dt = 0f
                sim.step(dt)
            }
            lastNanos = now
            frameTick++
        }
    }

    val pitch = if (compact) 4.3.dp else 6.5.dp

    // In 12h mode the leading hour digit is hidden, leaving an empty slot on the left. Drop that
    // slot from the canvas and shift the drawing left by it, so the visible digits are centered
    // and nothing pads the clock (animated for 9 → 10 o'clock).
    val leadingBlank = codes[0] == PARTICLE_GLYPH_BLANK
    val hiddenColumns by animateFloatAsState(
        targetValue = if (leadingBlank) (PARTICLE_GLYPH_COLS + 1).toFloat() else 0f,
        animationSpec = tween(800),
        label = "particleHiddenColumns",
    )

    Canvas(
        modifier = Modifier
            .padding(
                top = if (!compact) 16.dp else 0.dp,
                bottom = if (!compact) 16.dp else 0.dp,
            )
            .size(width = pitch * (sim.columns - hiddenColumns), height = pitch * PARTICLE_GLYPH_ROWS.toFloat())
    ) {
        // Reading the tick here invalidates only the draw phase each frame.
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        val p = pitch.toPx()
        val shiftX = -hiddenColumns * p
        val radius = p * 0.27f
        val xs = sim.x
        val ys = sim.y
        val alphas = sim.alpha
        for (i in 0 until sim.count) {
            val a = alphas[i]
            if (a < 0.01f) continue
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(xs[i] * p + shiftX, ys[i] * p),
                alpha = if (a > 1f) 1f else a,
            )
        }
    }
}

private const val PARTICLE_GLYPH_COLS = 5
private const val PARTICLE_GLYPH_ROWS = 7
private const val PARTICLE_GLYPH_COLON = 10
private const val PARTICLE_GLYPH_BLANK = 11
private const val PARTICLE_PER_CELL = 2

private const val PARTICLE_MAX_DT = 1f / 20f
// Spring: omega ~9 rad/s, damping ratio ~0.55 -> settles in roughly 0.8-1.0 s.
private const val PARTICLE_STIFFNESS = 81f
private const val PARTICLE_DAMPING = 10f
private const val PARTICLE_DRIFT_AMPLITUDE = 0.09f
private const val PARTICLE_JITTER_ACCEL = 3f
private const val PARTICLE_FADE_RATE = 5f
private const val PARTICLE_TWO_PI = (2.0 * PI).toFloat()

private val ParticleDigitBitmaps = arrayOf(
    arrayOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"), // 0
    arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"), // 1
    arrayOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"), // 2
    arrayOf("11111", "00010", "00100", "00010", "00001", "10001", "01110"), // 3
    arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"), // 4
    arrayOf("11111", "10000", "11110", "00001", "00001", "10001", "01110"), // 5
    arrayOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"), // 6
    arrayOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"), // 7
    arrayOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"), // 8
    arrayOf("01110", "10001", "10001", "01111", "00001", "00010", "01100"), // 9
)

/**
 * Lit cells per glyph code, encoded as row * PARTICLE_GLYPH_COLS + col.
 * Codes 0-9 are digits, 10 is the colon (single column wide), 11 is blank.
 */
private val ParticleGlyphCells: Array<IntArray> = Array(12) { code ->
    when {
        code < 10 -> {
            val rows = ParticleDigitBitmaps[code]
            val out = ArrayList<Int>()
            for (r in 0 until PARTICLE_GLYPH_ROWS) {
                for (c in 0 until PARTICLE_GLYPH_COLS) {
                    if (rows[r][c] == '1') out.add(r * PARTICLE_GLYPH_COLS + c)
                }
            }
            out.toIntArray()
        }

        code == PARTICLE_GLYPH_COLON -> intArrayOf(
            2 * PARTICLE_GLYPH_COLS,
            4 * PARTICLE_GLYPH_COLS,
        )

        else -> IntArray(0)
    }
}

private val ParticleMaxDigitCells: Int = (0 until 10).maxOf { ParticleGlyphCells[it].size }

/**
 * All per-particle state lives in plain FloatArrays; positions are in cell units
 * (one cell = one dot-matrix pitch), cell centres at (col + 0.5, row + 0.5).
 * The glyph layout (digit / colon slots) is fixed for the lifetime of the simulation.
 */
private class ParticleSimulation(initialCodes: IntArray) {
    val glyphCount = initialCodes.size
    private val glyphIsColon = BooleanArray(glyphCount) { initialCodes[it] == PARTICLE_GLYPH_COLON }
    private val glyphX = IntArray(glyphCount)
    private val glyphWidth = IntArray(glyphCount)
    private val poolStart = IntArray(glyphCount)
    private val poolSize = IntArray(glyphCount)
    private val current = IntArray(glyphCount) { -1 }
    private val desired = initialCodes.copyOf()

    val columns: Int
    val count: Int

    init {
        var x = 0
        var p = 0
        for (g in 0 until glyphCount) {
            val w = if (glyphIsColon[g]) 1 else PARTICLE_GLYPH_COLS
            glyphX[g] = x
            glyphWidth[g] = w
            poolStart[g] = p
            val cells = if (glyphIsColon[g]) ParticleGlyphCells[PARTICLE_GLYPH_COLON].size
            else ParticleMaxDigitCells
            poolSize[g] = cells * PARTICLE_PER_CELL
            p += poolSize[g]
            x += w + 1
        }
        columns = x - 1
        count = p
    }

    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    val alpha = FloatArray(count)
    private val alphaTarget = FloatArray(count)
    private val targetX = FloatArray(count)
    private val targetY = FloatArray(count)
    private val homeOffX = FloatArray(count)
    private val homeOffY = FloatArray(count)
    private val driftFreqX = FloatArray(count)
    private val driftFreqY = FloatArray(count)
    private val driftPhaseX = FloatArray(count)
    private val driftPhaseY = FloatArray(count)

    private var clock = 0f

    init {
        for (g in 0 until glyphCount) {
            val cx = glyphX[g] + glyphWidth[g] * 0.5f
            for (k in 0 until poolSize[g]) {
                val i = poolStart[g] + k
                // Start loosely spread around the glyph: the first frames gather them in.
                x[i] = cx + (Random.nextFloat() - 0.5f) * (glyphWidth[g] + 4f)
                y[i] = PARTICLE_GLYPH_ROWS * 0.5f + (Random.nextFloat() - 0.5f) * (PARTICLE_GLYPH_ROWS + 4f)
                alpha[i] = 0f
                // Particles sharing a cell sit slightly apart so cells look like soft dots.
                val slot = k % PARTICLE_PER_CELL
                val sign = if (slot == 0) -1f else 1f
                homeOffX[i] = sign * 0.13f + (Random.nextFloat() - 0.5f) * 0.08f
                homeOffY[i] = sign * 0.10f + (Random.nextFloat() - 0.5f) * 0.08f
                driftFreqX[i] = 1.2f + Random.nextFloat() * 1.6f
                driftFreqY[i] = 1.2f + Random.nextFloat() * 1.6f
                driftPhaseX[i] = Random.nextFloat() * PARTICLE_TWO_PI
                driftPhaseY[i] = Random.nextFloat() * PARTICLE_TWO_PI
            }
        }
    }

    fun setDesired(codes: IntArray) {
        val n = if (codes.size < glyphCount) codes.size else glyphCount
        for (g in 0 until n) desired[g] = codes[g]
    }

    fun step(dt: Float) {
        clock += dt
        // Wrap to keep float precision for the drift sines; drift is periodic enough.
        if (clock > 10_000f) clock -= 10_000f

        for (g in 0 until glyphCount) {
            if (desired[g] != current[g]) {
                val firstAssignment = current[g] == -1
                current[g] = desired[g]
                assignGlyph(g, scatter = !firstAssignment)
            }
        }

        val t = clock
        val fade = if (dt * PARTICLE_FADE_RATE > 1f) 1f else dt * PARTICLE_FADE_RATE
        for (i in 0 until count) {
            val tx = targetX[i] + homeOffX[i] +
                    PARTICLE_DRIFT_AMPLITUDE * sin(t * driftFreqX[i] + driftPhaseX[i])
            val ty = targetY[i] + homeOffY[i] +
                    PARTICLE_DRIFT_AMPLITUDE * cos(t * driftFreqY[i] + driftPhaseY[i])

            val ax = PARTICLE_STIFFNESS * (tx - x[i]) - PARTICLE_DAMPING * vx[i] +
                    (Random.nextFloat() - 0.5f) * PARTICLE_JITTER_ACCEL
            val ay = PARTICLE_STIFFNESS * (ty - y[i]) - PARTICLE_DAMPING * vy[i] +
                    (Random.nextFloat() - 0.5f) * PARTICLE_JITTER_ACCEL

            // Semi-implicit Euler.
            vx[i] += ax * dt
            vy[i] += ay * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt

            alpha[i] += (alphaTarget[i] - alpha[i]) * fade
        }
    }

    private fun assignGlyph(g: Int, scatter: Boolean) {
        val code = current[g]
        val cells = ParticleGlyphCells[code]
        val lit = cells.size
        val gx = glyphX[g]
        val cx = gx + glyphWidth[g] * 0.5f
        val cy = PARTICLE_GLYPH_ROWS * 0.5f
        val start = poolStart[g]
        val active = lit * PARTICLE_PER_CELL
        // Alternate the swirl direction per change for a bit of variety.
        val swirl = if (Random.nextBoolean()) 2.6f else -2.6f

        for (k in 0 until poolSize[g]) {
            val i = start + k
            if (k < active) {
                val cell = cells[k / PARTICLE_PER_CELL]
                targetX[i] = gx + (cell % PARTICLE_GLYPH_COLS) + 0.5f
                targetY[i] = (cell / PARTICLE_GLYPH_COLS) + 0.5f
                alphaTarget[i] = 1f
            } else if (lit > 0) {
                // Spare: park on the nearest lit cell and fade out.
                var best = cells[0]
                var bestD = Float.MAX_VALUE
                for (c in 0 until lit) {
                    val cell = cells[c]
                    val dx = gx + (cell % PARTICLE_GLYPH_COLS) + 0.5f - x[i]
                    val dy = (cell / PARTICLE_GLYPH_COLS) + 0.5f - y[i]
                    val d = dx * dx + dy * dy
                    if (d < bestD) {
                        bestD = d
                        best = cell
                    }
                }
                targetX[i] = gx + (best % PARTICLE_GLYPH_COLS) + 0.5f
                targetY[i] = (best / PARTICLE_GLYPH_COLS) + 0.5f
                alphaTarget[i] = 0f
            } else {
                // Blank glyph: gather at its centre and fade out.
                targetX[i] = cx
                targetY[i] = cy
                alphaTarget[i] = 0f
            }

            if (scatter) {
                val angle = Random.nextFloat() * PARTICLE_TWO_PI
                val speed = 4f + Random.nextFloat() * 7f
                vx[i] += cos(angle) * speed
                vy[i] += sin(angle) * speed
                val rx = x[i] - cx
                val ry = y[i] - cy
                vx[i] += -ry * swirl
                vy[i] += rx * swirl
            }
        }
    }
}
