package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Fluid ink: a real-time "stable fluids" simulation (semi-Lagrangian advection, pressure
 * projection and vorticity confinement) on a coarse grid, rendered as glowing coloured ink
 * drifting through dark water. Dragging a finger pushes the fluid along the drag and trails ink
 * whose amount grows with speed; holding still blooms a soft, slowly curling puff. Each stroke
 * takes the next colour of a slowly cycling palette. Untouched, a few wandering emitters breathe
 * gentle jets of smoke so the water is never still.
 *
 * While music plays, the emitters give a small extra push and puff of ink on each beat, so the smoke breathes in time.
 */
@Composable
fun FluidInkWallpaper(modifier: Modifier = Modifier) {
    val host = remember { InkHost() }
    val frame = remember { mutableLongStateOf(0L) }
    val generation = rememberResetGeneration()
    val pulse = remember { MusicPulse() }

    LaunchedEffect(generation) {
        host.pendingSeed = true
    }

    LaunchedEffect(Unit) {
        var start = 0L
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (start == 0L) {
                    start = now
                    last = now
                }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                pulse.update(now)
                host.step((now - start) / 1_000_000_000f, dt, pulse.beat)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        host.viewWidth = size.width
        host.viewHeight = size.height
        val sim = host.sim
        if (sim == null) {
            drawRect(color = BACKDROP)
        } else {
            drawImage(
                image = sim.image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(sim.nx, sim.ny),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

private val BACKDROP = Color(0xFF05050C)

private const val MAX_DT = 1f / 30f
private const val GRID_SHORT = 64
private const val GRID_LONG_MAX = 160
private const val PRESSURE_ITERATIONS = 14
private const val PRESSURE_WARM_START = 0.8f
private const val VORTICITY = 2.2f
private const val VELOCITY_DECAY = 0.15f // per second
private const val DYE_DECAY = 0.3f // per second
private const val EXPOSURE = 0.9f
private const val HIGHLIGHT = 0.35f

private const val FINGER_RADIUS = 2.6f // cells
private const val FORCE_GAIN = 0.25f
private const val MAX_FINGER_SPEED = 150f // cells per second
private const val PUFF_SWIRL = 2.5f

private const val EMITTERS = 3
private const val AMBIENT_RADIUS = 3f
private const val AMBIENT_ACCEL = 14f // cells per second²
private const val AMBIENT_DYE = 1.1f // per second
private const val BEAT_PUSH = 1.2f // extra emitter force at the peak of a music beat
private const val BEAT_DYE = 0.6f // extra emitter ink at the peak of a music beat

private const val HUE_DRIFT = 0.012f // palette cycles per second
private const val STROKE_HUE_STEP = 0.19f

/** Cyclic palette of emissive ink colours, RGB triples. */
private val PALETTE = floatArrayOf(
    1.00f, 0.16f, 0.52f, // magenta
    0.62f, 0.20f, 1.00f, // violet
    0.16f, 0.36f, 1.00f, // cobalt
    0.04f, 0.78f, 0.95f, // cyan
    0.10f, 0.95f, 0.62f, // jade
    1.00f, 0.62f, 0.12f, // amber
    1.00f, 0.30f, 0.22f, // coral
)

/** Writes the palette colour at cyclic position [phase] into [out]. */
private fun palette(phase: Float, out: FloatArray) {
    val count = PALETTE.size / 3
    val x = (phase - floor(phase)) * count
    val i = x.toInt() % count
    var f = x - floor(x)
    f = f * f * (3f - 2f * f)
    val j = (i + 1) % count
    for (c in 0 until 3) {
        out[c] = PALETTE[i * 3 + c] + (PALETTE[j * 3 + c] - PALETTE[i * 3 + c]) * f
    }
}

/** Owns the simulation, maps the pointer into grid space and drives the ambient emitters. */
private class InkHost {
    var viewWidth = 0f
    var viewHeight = 0f
    var sim: InkSim? = null
        private set
    var pendingSeed = false

    private val rgb = FloatArray(3)
    private var wasPressed = false
    private var lastGx = 0f
    private var lastGy = 0f
    private var pressTime = 0f
    private var strokeCount = 0
    private var strokePhase = 0f
    private var strokeSpin = 1f

    // Per-emitter path, heading and envelope frequencies, randomised once.
    private val ax = FloatArray(EMITTERS) { 0.05f + Random.nextFloat() * 0.06f }
    private val ay = FloatArray(EMITTERS) { 0.04f + Random.nextFloat() * 0.06f }
    private val phx = FloatArray(EMITTERS) { Random.nextFloat() * 6.28f }
    private val phy = FloatArray(EMITTERS) { Random.nextFloat() * 6.28f }
    private val spin = FloatArray(EMITTERS) { (0.08f + Random.nextFloat() * 0.1f) * if (it % 2 == 0) 1f else -1f }
    private val envFreq = floatArrayOf(0.35f, 0.27f, 0.22f)
    private val envPhase = FloatArray(EMITTERS) { it * 2.1f }

    fun step(t: Float, dt: Float, beat: Float) {
        val w = viewWidth
        val h = viewHeight
        if (w < 1f || h < 1f || dt <= 0f) return
        ensureSim(w, h)
        val s = sim ?: return
        if (pendingSeed) {
            pendingSeed = false
            seed(s, t)
        }
        val pressed = WallpaperPointer.pressed
        if (pressed) touch(s, t, dt, w / s.nx, h / s.ny)
        wasPressed = pressed
        ambient(s, t, dt, if (pressed) 0.5f else 1f, beat)
        s.step(dt)
        s.render()
    }

    private fun ensureSim(w: Float, h: Float) {
        val nx: Int
        val ny: Int
        if (w <= h) {
            nx = GRID_SHORT
            ny = (GRID_SHORT * h / w).roundToInt().coerceIn(GRID_SHORT, GRID_LONG_MAX)
        } else {
            ny = GRID_SHORT
            nx = (GRID_SHORT * w / h).roundToInt().coerceIn(GRID_SHORT, GRID_LONG_MAX)
        }
        val current = sim
        if (current != null && current.nx == nx && current.ny == ny) return
        sim = InkSim(nx, ny)
        pendingSeed = true
    }

    /** A few swirling blobs so a freshly built or resumed wallpaper starts alive. */
    private fun seed(s: InkSim, t: Float) {
        repeat(4) { k ->
            palette(t * HUE_DRIFT + k * 0.27f + Random.nextFloat() * 0.1f, rgb)
            s.splat(
                cx = s.nx * (0.2f + 0.6f * Random.nextFloat()),
                cy = s.ny * (0.2f + 0.6f * Random.nextFloat()),
                radius = 4f + 2f * Random.nextFloat(),
                dvx = (Random.nextFloat() - 0.5f) * 6f,
                dvy = (Random.nextFloat() - 0.5f) * 6f,
                swirl = if (k % 2 == 0) 1.2f else -1.2f,
                rgb = rgb,
                dye = 0.9f,
            )
        }
    }

    private fun touch(s: InkSim, t: Float, dt: Float, cellW: Float, cellH: Float) {
        // Cell i is centred at grid coordinate i (cell 0 is the ghost border).
        val gx = WallpaperPointer.x / cellW + 0.5f
        val gy = WallpaperPointer.y / cellH + 0.5f
        if (!wasPressed) {
            strokeCount++
            pressTime = 0f
            lastGx = gx
            lastGy = gy
            strokeSpin = if (strokeCount % 2 == 0) 1f else -1f
            strokePhase = t * HUE_DRIFT + strokeCount * STROKE_HUE_STEP
        }
        pressTime += dt
        palette(strokePhase + pressTime * 0.04f, rgb)

        val dx = gx - lastGx
        val dy = gy - lastGy
        val dist = sqrt(dx * dx + dy * dy)
        val speed = dist / dt
        var fvx = dx / dt
        var fvy = dy / dt
        if (speed > MAX_FINGER_SPEED) {
            val k = MAX_FINGER_SPEED / speed
            fvx *= k
            fvy *= k
        }
        val sf = (speed / 40f).coerceIn(0f, 1.5f)
        val radius = FINGER_RADIUS * (1f + 0.35f * sf)
        // Fixed splat spacing along the segment keeps the stroke continuous and evenly inked.
        val spacing = radius * 0.6f
        val steps = ceil(dist / spacing).toInt().coerceIn(1, 10)
        val dye = 0.32f * sf
        if (dist > 0f) {
            for (k in 1..steps) {
                val f = k.toFloat() / steps
                s.splat(
                    lastGx + dx * f, lastGy + dy * f, radius,
                    fvx * FORCE_GAIN, fvy * FORCE_GAIN, 0f, rgb, dye,
                )
            }
        }

        // Holding still blooms a growing, gently rotating puff.
        val bloom = 1f - ((speed - 3f) / 10f).coerceIn(0f, 1f)
        if (bloom > 0f) {
            val puffRadius = radius * (1f + min(pressTime, 2f) * 0.6f)
            val rate = (2.2f * exp(-pressTime * 0.7f) + 0.5f) * dt * bloom
            s.splat(gx, gy, puffRadius, 0f, 0f, strokeSpin * PUFF_SWIRL * dt * bloom, rgb, rate)
        }
        lastGx = gx
        lastGy = gy
    }

    private fun ambient(s: InkSim, t: Float, dt: Float, gain: Float, beat: Float) {
        // Music: both factors are exactly 1 when nothing plays.
        val push = 1f + BEAT_PUSH * beat
        val ink = 1f + BEAT_DYE * beat
        for (e in 0 until EMITTERS) {
            val pulse = max(0f, sin(t * envFreq[e] + envPhase[e]))
            val env = (0.22f + 0.78f * pulse * pulse) * gain
            val ex = 0.5f + 0.32f * sin(t * ax[e] + phx[e]) + 0.06f * sin(t * ax[e] * 2.7f + phy[e])
            val ey = 0.5f + 0.36f * sin(t * ay[e] + phy[e]) + 0.05f * sin(t * ay[e] * 3.1f + phx[e])
            val angle = t * spin[e] + phx[e] + 1.4f * sin(t * 0.07f + e)
            val accel = AMBIENT_ACCEL * env * dt * push
            palette(t * HUE_DRIFT + e / EMITTERS.toFloat() + 0.08f * sin(t * 0.05f + e), rgb)
            s.splat(
                cx = 1f + ex * (s.nx - 1),
                cy = 1f + ey * (s.ny - 1),
                radius = AMBIENT_RADIUS,
                dvx = cos(angle) * accel,
                dvy = sin(angle) * accel,
                swirl = 0f,
                rgb = rgb,
                dye = AMBIENT_DYE * env * dt * ink,
            )
        }
    }
}

/**
 * Jos Stam's stable fluids on an [nx] x [ny] grid with a one-cell ghost border. Velocities are
 * in cells per second. Ink is three emissive channels advected by the flow.
 */
private class InkSim(val nx: Int, val ny: Int) {
    private val sx = nx + 2
    private val n = sx * (ny + 2)

    private var u = FloatArray(n)
    private var v = FloatArray(n)
    private var u0 = FloatArray(n)
    private var v0 = FloatArray(n)
    private var r = FloatArray(n)
    private var g = FloatArray(n)
    private var b = FloatArray(n)
    private var r0 = FloatArray(n)
    private var g0 = FloatArray(n)
    private var b0 = FloatArray(n)
    private val p = FloatArray(n)
    private val div = FloatArray(n)
    private val curl = FloatArray(n)

    private val pixels = IntArray(nx * ny)
    private val bgR = FloatArray(nx * ny)
    private val bgG = FloatArray(nx * ny)
    private val bgB = FloatArray(nx * ny)

    val bitmap: Bitmap = Bitmap.createBitmap(nx, ny, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()

    init {
        // Deep indigo-to-black water with a soft vignette, baked once.
        for (j in 0 until ny) {
            val fy = j / (ny - 1f)
            for (i in 0 until nx) {
                val fx = i / (nx - 1f)
                val cx = fx - 0.5f
                val cy = fy - 0.45f
                val vig = (1f - 1.5f * (cx * cx + cy * cy)).coerceIn(0.35f, 1f)
                val o = j * nx + i
                bgR[o] = (0.030f - 0.016f * fy) * vig
                bgG[o] = (0.026f - 0.012f * fy) * vig
                bgB[o] = (0.070f - 0.035f * fy) * vig
            }
        }
    }

    /**
     * Adds a gaussian blob centred on grid coordinate ([cx], [cy]): a velocity impulse
     * ([dvx], [dvy]), a rotational impulse [swirl] (radians per second) and [dye] of colour [rgb].
     */
    fun splat(
        cx: Float,
        cy: Float,
        radius: Float,
        dvx: Float,
        dvy: Float,
        swirl: Float,
        rgb: FloatArray,
        dye: Float,
    ) {
        val reach = radius * 2.2f
        val reach2 = reach * reach
        val inv = 1f / (radius * radius)
        val i0 = max(1, floor(cx - reach).toInt())
        val i1 = min(nx, ceil(cx + reach).toInt())
        val j0 = max(1, floor(cy - reach).toInt())
        val j1 = min(ny, ceil(cy + reach).toInt())
        val dr = rgb[0] * dye
        val dg = rgb[1] * dye
        val db = rgb[2] * dye
        for (j in j0..j1) {
            val ry = j - cy
            for (i in i0..i1) {
                val rx = i - cx
                val d2 = rx * rx + ry * ry
                if (d2 > reach2) continue
                val w = exp(-d2 * inv)
                val k = i + sx * j
                u[k] += (dvx - swirl * ry) * w
                v[k] += (dvy + swirl * rx) * w
                r[k] += dr * w
                g[k] += dg * w
                b[k] += db * w
            }
        }
    }

    fun step(dt: Float) {
        setBoundary(u, 1)
        setBoundary(v, 2)
        confineVorticity(dt)

        var tmp = u0
        u0 = u
        u = tmp
        tmp = v0
        v0 = v
        v = tmp
        advectVelocity(dt, 1f / (1f + dt * VELOCITY_DECAY))
        setBoundary(u, 1)
        setBoundary(v, 2)

        project()

        tmp = r0
        r0 = r
        r = tmp
        tmp = g0
        g0 = g
        g = tmp
        tmp = b0
        b0 = b
        b = tmp
        advectDye(dt, exp(-dt * DYE_DECAY))
        setBoundary(r, 0)
        setBoundary(g, 0)
        setBoundary(b, 0)
    }

    private fun confineVorticity(dt: Float) {
        for (j in 1..ny) {
            for (i in 1..nx) {
                val k = i + sx * j
                curl[k] = 0.5f * ((v[k + 1] - v[k - 1]) - (u[k + sx] - u[k - sx]))
            }
        }
        val scale = VORTICITY * dt
        for (j in 2 until ny) {
            for (i in 2 until nx) {
                val k = i + sx * j
                val gx = 0.5f * (abs(curl[k + 1]) - abs(curl[k - 1]))
                val gy = 0.5f * (abs(curl[k + sx]) - abs(curl[k - sx]))
                val len = sqrt(gx * gx + gy * gy) + 1e-5f
                val c = curl[k] * scale / len
                u[k] += gy * c
                v[k] -= gx * c
            }
        }
    }

    private fun advectVelocity(dt: Float, decay: Float) {
        val maxX = nx + 0.5f
        val maxY = ny + 0.5f
        for (j in 1..ny) {
            for (i in 1..nx) {
                val k = i + sx * j
                val x = (i - dt * u0[k]).coerceIn(0.5f, maxX)
                val y = (j - dt * v0[k]).coerceIn(0.5f, maxY)
                val xi = x.toInt()
                val yi = y.toInt()
                val s1 = x - xi
                val s0 = 1f - s1
                val t1 = y - yi
                val t0 = 1f - t1
                val a = xi + sx * yi
                u[k] = decay * (s0 * (t0 * u0[a] + t1 * u0[a + sx]) + s1 * (t0 * u0[a + 1] + t1 * u0[a + sx + 1]))
                v[k] = decay * (s0 * (t0 * v0[a] + t1 * v0[a + sx]) + s1 * (t0 * v0[a + 1] + t1 * v0[a + sx + 1]))
            }
        }
    }

    private fun advectDye(dt: Float, decay: Float) {
        val maxX = nx + 0.5f
        val maxY = ny + 0.5f
        for (j in 1..ny) {
            for (i in 1..nx) {
                val k = i + sx * j
                val x = (i - dt * u[k]).coerceIn(0.5f, maxX)
                val y = (j - dt * v[k]).coerceIn(0.5f, maxY)
                val xi = x.toInt()
                val yi = y.toInt()
                val s1 = x - xi
                val s0 = 1f - s1
                val t1 = y - yi
                val t0 = 1f - t1
                val a = xi + sx * yi
                val w00 = decay * s0 * t0
                val w01 = decay * s0 * t1
                val w10 = decay * s1 * t0
                val w11 = decay * s1 * t1
                r[k] = w00 * r0[a] + w01 * r0[a + sx] + w10 * r0[a + 1] + w11 * r0[a + sx + 1]
                g[k] = w00 * g0[a] + w01 * g0[a + sx] + w10 * g0[a + 1] + w11 * g0[a + sx + 1]
                b[k] = w00 * b0[a] + w01 * b0[a + sx] + w10 * b0[a + 1] + w11 * b0[a + sx + 1]
            }
        }
    }

    /** Makes the velocity field divergence free with Gauss-Seidel, warm-started from last frame. */
    private fun project() {
        for (j in 1..ny) {
            for (i in 1..nx) {
                val k = i + sx * j
                div[k] = -0.5f * (u[k + 1] - u[k - 1] + v[k + sx] - v[k - sx])
                p[k] *= PRESSURE_WARM_START
            }
        }
        setBoundary(div, 0)
        setBoundary(p, 0)
        repeat(PRESSURE_ITERATIONS) {
            for (j in 1..ny) {
                for (i in 1..nx) {
                    val k = i + sx * j
                    p[k] = (div[k] + p[k - 1] + p[k + 1] + p[k - sx] + p[k + sx]) * 0.25f
                }
            }
            setBoundary(p, 0)
        }
        for (j in 1..ny) {
            for (i in 1..nx) {
                val k = i + sx * j
                u[k] -= 0.5f * (p[k + 1] - p[k - 1])
                v[k] -= 0.5f * (p[k + sx] - p[k - sx])
            }
        }
        setBoundary(u, 1)
        setBoundary(v, 2)
    }

    /** Ghost cells: mode 1 mirrors u off the side walls, mode 2 mirrors v off top and bottom. */
    private fun setBoundary(f: FloatArray, mode: Int) {
        val bottom = sx * (ny + 1)
        for (i in 1..nx) {
            val top = f[i + sx]
            val low = f[i + sx * ny]
            f[i] = if (mode == 2) -top else top
            f[i + bottom] = if (mode == 2) -low else low
        }
        for (j in 1..ny) {
            val row = sx * j
            val left = f[row + 1]
            val right = f[row + nx]
            f[row] = if (mode == 1) -left else left
            f[row + nx + 1] = if (mode == 1) -right else right
        }
        f[0] = 0.5f * (f[1] + f[sx])
        f[nx + 1] = 0.5f * (f[nx] + f[sx + nx + 1])
        f[bottom] = 0.5f * (f[bottom + 1] + f[bottom - sx])
        f[bottom + nx + 1] = 0.5f * (f[bottom + nx] + f[bottom - sx + nx + 1])
    }

    /** Tone maps the ink onto the backdrop and uploads it to [bitmap]. */
    fun render() {
        for (j in 0 until ny) {
            val row = (j + 1) * sx + 1
            val o = j * nx
            for (i in 0 until nx) {
                val k = row + i
                // Reinhard per channel: dense ink saturates toward a soft glow instead of clipping.
                var cr = max(0f, r[k]) * EXPOSURE
                var cg = max(0f, g[k]) * EXPOSURE
                var cb = max(0f, b[k]) * EXPOSURE
                cr /= 1f + cr
                cg /= 1f + cg
                cb /= 1f + cb
                val l = 0.3f * cr + 0.55f * cg + 0.15f * cb
                val hi = l * l * HIGHLIGHT
                val ri = ((bgR[o + i] + cr + hi) * 255f).toInt().coerceIn(0, 255)
                val gi = ((bgG[o + i] + cg + hi) * 255f).toInt().coerceIn(0, 255)
                val bi = ((bgB[o + i] + cb + hi) * 255f).toInt().coerceIn(0, 255)
                pixels[o + i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        bitmap.setPixels(pixels, 0, nx, 0, 0, nx, ny)
    }
}
