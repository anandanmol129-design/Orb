package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import android.graphics.Canvas as BitmapCanvas

/**
 * Flow field: thousands of particles drift through a smooth, slowly evolving curl-noise current,
 * each leaving a long fading trail, so the screen fills with silky strands like a wind or
 * ocean-current map. Strands are coloured by the stream line they follow (deep blue through cyan
 * to magenta, slowly shifting over minutes); the fastest currents flare warm gold. Holding a
 * finger stirs a vortex that wraps the strands around it, dragging pushes the flow along, and on
 * release the vortex dies away over about a second.
 *
 * While music plays, the particles surge a little faster and the strands brighten slightly on each beat.
 */
@Composable
fun FlowFieldWallpaper(modifier: Modifier = Modifier) {
    val sim = remember { FlowFieldSim() }
    val frame = remember { mutableLongStateOf(0L) }
    val pulse = remember { MusicPulse() }

    LaunchedEffect(Unit) {
        var start = 0L
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                val rawDt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000f
                last = now
                pulse.update(now)
                sim.step(rawDt, (now - start) / 1_000_000_000f, pulse.beat)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        sim.ensure(size.width, size.height)
        drawRect(sim.backdrop)
        drawRect(sim.hazeCool)
        drawRect(sim.hazeWarm)
        val trails = sim.front ?: return@Canvas
        drawIntoCanvas { it.nativeCanvas.drawBitmap(trails, null, sim.dst, sim.blitPaint) }
    }
}

private const val PARTICLE_COUNT = 4200

// Trail buffer resolution: at most half the screen, capped by pixel count for big displays.
private const val MAX_BUFFER_SCALE = 0.5f
private const val MAX_BUFFER_PIXELS = 400_000f

private const val GRID_COLS = 18
private const val NOISE_STEP = 0.15f // noise-space distance between grid nodes
private const val FIELD_EVOLVE = 0.035f // noise z per second
private const val FIELD_PAN = 0.012f // noise x per second
private const val FLOW_SPEED = 0.09f // typical speed, fraction of buffer width per second
private const val DRIFT_SPEED = 0.02f
private const val MAX_DT = 1f / 20f

private const val LIFE_MIN = 3.5f
private const val LIFE_MAX = 11f
private const val FADE_IN = 0.8f
private const val FADE_OUT = 1.2f

private const val HUE_SHIFT_RATE = 1f / 150f
private const val GOLD_SPEED = 2.1f // multiples of FLOW_SPEED

private const val VORTEX_RADIUS = 0.17f // fraction of buffer width
private const val VORTEX_SPEED = 0.5f // peak swirl, fraction of buffer width per second
private const val VORTEX_ATTRACT = 0.18f
private const val DRAG_PUSH = 0.9f

private const val STROKE = 1.1f // in buffer pixels

// Music: extra advection speed and strand alpha at the peak of a beat.
private const val BEAT_SURGE = 0.3f
private const val BEAT_GLOW = 0.15f

// Trail decay: multiplicative with time constant FADE_TAU, plus a fixed per-frame subtraction
// (in 0..255 units) so 8-bit rounding can never leave a permanent residue.
private const val FADE_TAU = 0.8f
private const val FADE_FLOOR = 0.75f

private val FLOW_HUES = intArrayOf(
    0xFF2440E0.toInt(), // deep blue
    0xFF3A6CFF.toInt(), // royal
    0xFF2FA6FF.toInt(), // azure
    0xFF33DDFF.toInt(), // cyan
    0xFF8C6BFF.toInt(), // violet
    0xFFC45CF0.toInt(), // orchid
    0xFFF052C4.toInt(), // magenta
)
private val FLOW_LEVEL_ALPHA = intArrayOf(40, 72, 112)
private val FLOW_GOLD = 0xFFFFC266.toInt()
private const val FLOW_GOLD_ALPHA = 150
private const val LEVELS = 3
private val BUCKETS = FLOW_HUES.size * LEVELS + 1
private val GOLD_BUCKET = BUCKETS - 1

private val FADE_PERIODS = floatArrayOf(1 / 144f, 1 / 120f, 1 / 90f, 1 / 72f, 1 / 60f, 1 / 45f, 1 / 30f)

private class FlowFieldSim {
    var width = 0f
        private set
    var height = 0f
        private set

    val backdrop: Brush = Brush.verticalGradient(
        listOf(Color(0xFF03040A), Color(0xFF060815), Color(0xFF040309)),
    )
    var hazeCool: Brush = backdrop
        private set
    var hazeWarm: Brush = backdrop
        private set

    // Ping-pong trail buffers: each frame the previous one is copied, faded, into the other.
    var front: Bitmap? = null
        private set
    private var back: Bitmap? = null
    private var frontCanvas: BitmapCanvas? = null
    private var backCanvas: BitmapCanvas? = null
    val dst = RectF()

    val blitPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val fadePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val fadeFilters = Array(FADE_PERIODS.size) { k ->
        val f = exp(-FADE_PERIODS[k] / FADE_TAU)
        ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    f, 0f, 0f, 0f, -FADE_FLOOR,
                    0f, f, 0f, 0f, -FADE_FLOOR,
                    0f, 0f, f, 0f, -FADE_FLOOR,
                    0f, 0f, 0f, 0f, 255f,
                ),
            ),
        )
    }
    private val strandAlpha = IntArray(BUCKETS) { b ->
        if (b == GOLD_BUCKET) FLOW_GOLD_ALPHA else FLOW_LEVEL_ALPHA[b % LEVELS]
    }
    private var strandBoost = 0f
    private val strandPaints = Array(BUCKETS) { b ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (b == GOLD_BUCKET) {
                color = FLOW_GOLD
                alpha = FLOW_GOLD_ALPHA
            } else {
                color = FLOW_HUES[b / LEVELS]
                alpha = FLOW_LEVEL_ALPHA[b % LEVELS]
            }
            strokeWidth = STROKE
            xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
        }
    }

    // Buffer size and screen -> buffer scale.
    private var bw = 0
    private var bh = 0
    private var sx = 1f
    private var sy = 1f

    // Field grid in buffer space: velocity and stream function at (cols+1) x (rows+1) nodes.
    private val noise = FlowFieldNoise(0x5EED)
    private var cols = GRID_COLS
    private var rows = 1
    private var cell = 1f
    private var psiPad = FloatArray(0) // stream function with a one-node border for derivatives
    private var gridVx = FloatArray(0)
    private var gridVy = FloatArray(0)
    private var gridPsi = FloatArray(0)
    private var flowSpeed = 1f

    private val px = FloatArray(PARTICLE_COUNT)
    private val py = FloatArray(PARTICLE_COUNT)
    private val age = FloatArray(PARTICLE_COUNT)
    private val life = FloatArray(PARTICLE_COUNT)
    private val bucket = IntArray(PARTICLE_COUNT)
    private val segs = FloatArray(PARTICLE_COUNT * 4)
    private val lines = FloatArray(PARTICLE_COUNT * 4)
    private val counts = IntArray(BUCKETS)
    private val cursor = IntArray(BUCKETS)

    // Touch vortex state, in buffer pixels.
    private var wasPressed = false
    private var vortex = 0f
    private var vortexX = 0f
    private var vortexY = 0f
    private var spin = 1f
    private var lastPX = 0f
    private var lastPY = 0f
    private var dragVx = 0f
    private var dragVy = 0f

    fun ensure(w: Float, h: Float) {
        if (w == width && h == height) return
        if (w < 1f || h < 1f) return
        width = w
        height = h
        val s = min(MAX_BUFFER_SCALE, sqrt(MAX_BUFFER_PIXELS / (w * h)))
        bw = max(16, (w * s).roundToInt())
        bh = max(16, (h * s).roundToInt())
        sx = bw / w
        sy = bh / h

        val f = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        f.eraseColor(android.graphics.Color.BLACK)
        b.eraseColor(android.graphics.Color.BLACK)
        front = f
        back = b
        frontCanvas = BitmapCanvas(f)
        backCanvas = BitmapCanvas(b)
        dst.set(0f, 0f, w, h)

        hazeCool = Brush.radialGradient(
            colors = listOf(
                Color(0xFF1B2366).copy(alpha = 0.30f),
                Color(0xFF12103C).copy(alpha = 0.12f),
                Color.Transparent,
            ),
            center = Offset(w * 0.3f, h * 0.28f),
            radius = max(w, h) * 0.7f,
        )
        hazeWarm = Brush.radialGradient(
            colors = listOf(
                Color(0xFF3A0F44).copy(alpha = 0.22f),
                Color(0xFF1A0A2A).copy(alpha = 0.08f),
                Color.Transparent,
            ),
            center = Offset(w * 0.82f, h * 0.86f),
            radius = max(w, h) * 0.55f,
        )

        cols = GRID_COLS
        cell = bw.toFloat() / cols
        rows = max(1, ceil(bh / cell).toInt())
        psiPad = FloatArray((cols + 3) * (rows + 3))
        val nodes = (cols + 1) * (rows + 1)
        gridVx = FloatArray(nodes)
        gridVy = FloatArray(nodes)
        gridPsi = FloatArray(nodes)
        flowSpeed = FLOW_SPEED * bw

        for (p in 0 until PARTICLE_COUNT) spawn(p, scattered = true)
    }

    fun step(rawDt: Float, t: Float, beat: Float) {
        val target = backCanvas ?: return
        val source = front ?: return
        val dt = rawDt.coerceIn(0f, MAX_DT)

        updatePointer(dt)
        computeField(t)
        moveParticles(dt, t, 1f + BEAT_SURGE * beat)
        applyBeatGlow(beat)

        fadePaint.colorFilter = fadeFilters[fadeIndex(rawDt)]
        target.drawBitmap(source, 0f, 0f, fadePaint)
        drawStrands(target)

        val fb = front
        front = back
        back = fb
        val fc = frontCanvas
        frontCanvas = backCanvas
        backCanvas = fc
    }

    /** Brightens the strand paints a touch on the beat; touches nothing while the music is silent. */
    private fun applyBeatGlow(beat: Float) {
        if (beat == 0f && strandBoost == 0f) return
        strandBoost = beat
        val k = 1f + BEAT_GLOW * beat
        for (b in 0 until BUCKETS) strandPaints[b].alpha = min(255, (strandAlpha[b] * k).roundToInt())
    }

    private fun fadeIndex(dt: Float): Int {
        var best = 0
        var bestErr = Float.MAX_VALUE
        for (k in FADE_PERIODS.indices) {
            val err = abs(FADE_PERIODS[k] - dt)
            if (err < bestErr) {
                bestErr = err
                best = k
            }
        }
        return best
    }

    private fun spawn(p: Int, scattered: Boolean) {
        px[p] = Random.nextFloat() * bw
        py[p] = Random.nextFloat() * bh
        life[p] = LIFE_MIN + Random.nextFloat() * (LIFE_MAX - LIFE_MIN)
        age[p] = if (scattered) Random.nextFloat() * life[p] else 0f
    }

    private fun updatePointer(dt: Float) {
        val pressed = WallpaperPointer.pressed
        if (pressed) {
            val x = WallpaperPointer.x * sx
            val y = WallpaperPointer.y * sy
            if (!wasPressed) {
                lastPX = x
                lastPY = y
                dragVx = 0f
                dragVy = 0f
                spin = -spin // alternate the swirl direction with every touch
            }
            if (dt > 0f) {
                val k = 1f - exp(-dt * 12f)
                dragVx += ((x - lastPX) / dt - dragVx) * k
                dragVy += ((y - lastPY) / dt - dragVy) * k
                val cap = bw * 2f
                val mag = sqrt(dragVx * dragVx + dragVy * dragVy)
                if (mag > cap) {
                    dragVx *= cap / mag
                    dragVy *= cap / mag
                }
            }
            vortexX = x
            vortexY = y
            lastPX = x
            lastPY = y
            vortex += (1f - vortex) * (1f - exp(-dt * 6f))
        } else {
            val decay = exp(-dt * 3f)
            vortex *= decay
            dragVx *= decay
            dragVy *= decay
        }
        wasPressed = pressed
    }

    /** Samples a two-octave stream function and takes its curl, giving a divergence-free flow. */
    private fun computeField(t: Float) {
        val padW = cols + 3
        val padH = rows + 3
        val z = t * FIELD_EVOLVE
        val ox = t * FIELD_PAN
        for (b in 0 until padH) {
            val ny = (b - 1) * NOISE_STEP + 40f
            val row = b * padW
            for (a in 0 until padW) {
                val nx = (a - 1) * NOISE_STEP + ox
                psiPad[row + a] = noise.sample(nx, ny, z) +
                    0.35f * noise.sample(nx * 2.07f + 13.1f, ny * 2.07f + 7.7f, z * 1.7f + 3f)
            }
        }
        val gain = flowSpeed / (2f * NOISE_STEP)
        val driftX = DRIFT_SPEED * bw * kotlin.math.cos(t * 0.017f)
        val driftY = DRIFT_SPEED * bw * 0.6f * kotlin.math.sin(t * 0.017f)
        var n = 0
        for (j in 0..rows) {
            for (i in 0..cols) {
                val pa = (j + 1) * padW + (i + 1)
                gridVx[n] = (psiPad[pa + padW] - psiPad[pa - padW]) * gain + driftX
                gridVy[n] = -(psiPad[pa + 1] - psiPad[pa - 1]) * gain + driftY
                gridPsi[n] = psiPad[pa]
                n++
            }
        }
    }

    private fun moveParticles(dt: Float, t: Float, surge: Float) {
        val inv = 1f / cell
        val stride = cols + 1
        val maxGx = cols - 0.001f
        val maxGy = rows - 0.001f
        val touching = vortex > 0.01f
        val r2v = (VORTEX_RADIUS * bw) * (VORTEX_RADIUS * bw)
        val swirl = vortex * 4f * VORTEX_SPEED * bw / (VORTEX_RADIUS * bw)
        val hueShift = t * HUE_SHIFT_RATE
        val goldSpeed = flowSpeed * GOLD_SPEED
        val levelSpeed = flowSpeed * 1.4f

        for (p in 0 until PARTICLE_COUNT) {
            bucket[p] = -1
            val a = age[p] + dt
            age[p] = a
            if (a >= life[p]) {
                spawn(p, scattered = false)
                continue
            }
            val x = px[p]
            val y = py[p]

            val gx = (x * inv).coerceIn(0f, maxGx)
            val gy = (y * inv).coerceIn(0f, maxGy)
            val i = gx.toInt()
            val j = gy.toInt()
            val fx = gx - i
            val fy = gy - j
            val n00 = j * stride + i
            val n01 = n00 + stride
            val w00 = (1f - fx) * (1f - fy)
            val w10 = fx * (1f - fy)
            val w01 = (1f - fx) * fy
            val w11 = fx * fy
            var vx = gridVx[n00] * w00 + gridVx[n00 + 1] * w10 + gridVx[n01] * w01 + gridVx[n01 + 1] * w11
            var vy = gridVy[n00] * w00 + gridVy[n00 + 1] * w10 + gridVy[n01] * w01 + gridVy[n01 + 1] * w11
            val psi = gridPsi[n00] * w00 + gridPsi[n00 + 1] * w10 + gridPsi[n01] * w01 + gridPsi[n01 + 1] * w11

            if (touching) {
                // Swirl peaks around one vortex radius and falls off fast; slight pull inward.
                val dx = x - vortexX
                val dy = y - vortexY
                val g = r2v / (dx * dx + dy * dy + r2v)
                val s = swirl * g * g
                vx += s * (-dy * spin - dx * VORTEX_ATTRACT)
                vy += s * (dx * spin - dy * VORTEX_ATTRACT)
                val push = vortex * g * DRAG_PUSH
                vx += dragVx * push
                vy += dragVy * push
            }

            val nx = x + vx * surge * dt
            val ny = y + vy * surge * dt
            if (nx < -2f || ny < -2f || nx > bw + 2f || ny > bh + 2f) {
                spawn(p, scattered = false)
                continue
            }
            px[p] = nx
            py[p] = ny

            val speed = sqrt(vx * vx + vy * vy)
            val env = min(1f, min(a / FADE_IN, (life[p] - a) / FADE_OUT))
            val intensity = env * (0.3f + 0.7f * min(1f, speed / levelSpeed))
            if (intensity < 0.12f) continue

            bucket[p] = if (speed > goldSpeed && env > 0.5f) {
                GOLD_BUCKET
            } else {
                // Colour follows the stream line (psi), so a strand keeps its hue along its length.
                val heading = if (speed > 1e-3f) vy / speed else 0f
                val h = psi * 0.62f + 0.1f * heading + hueShift
                var q = h - 2f * floor(h * 0.5f)
                if (q > 1f) q = 2f - q
                val hue = (q * (FLOW_HUES.size - 1) + 0.5f).toInt().coerceIn(0, FLOW_HUES.size - 1)
                val level = if (intensity < 0.45f) 0 else if (intensity < 0.75f) 1 else 2
                hue * LEVELS + level
            }
            val o = p * 4
            segs[o] = x
            segs[o + 1] = y
            segs[o + 2] = nx
            segs[o + 3] = ny
        }
    }

    /** Counting-sorts the segments by colour bucket so each Paint needs a single drawLines call. */
    private fun drawStrands(canvas: BitmapCanvas) {
        counts.fill(0)
        for (p in 0 until PARTICLE_COUNT) {
            val b = bucket[p]
            if (b >= 0) counts[b]++
        }
        var acc = 0
        for (b in 0 until BUCKETS) {
            cursor[b] = acc
            acc += counts[b]
        }
        for (p in 0 until PARTICLE_COUNT) {
            val b = bucket[p]
            if (b < 0) continue
            val o = cursor[b] * 4
            cursor[b]++
            val s = p * 4
            lines[o] = segs[s]
            lines[o + 1] = segs[s + 1]
            lines[o + 2] = segs[s + 2]
            lines[o + 3] = segs[s + 3]
        }
        for (b in 0 until BUCKETS) {
            val c = counts[b]
            if (c == 0) continue
            // cursor[b] now points at the end of the bucket.
            canvas.drawLines(lines, (cursor[b] - c) * 4, c * 4, strandPaints[b])
        }
    }
}

/** Improved Perlin gradient noise in 3D; z is used as the time axis. */
private class FlowFieldNoise(seed: Int) {
    private val perm = IntArray(512)

    init {
        val p = IntArray(256) { it }
        val rnd = Random(seed)
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val tmp = p[i]
            p[i] = p[j]
            p[j] = tmp
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    fun sample(x: Float, y: Float, z: Float): Float {
        val fx = floor(x)
        val fy = floor(y)
        val fz = floor(z)
        val xi = fx.toInt() and 255
        val yi = fy.toInt() and 255
        val zi = fz.toInt() and 255
        val xf = x - fx
        val yf = y - fy
        val zf = z - fz
        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)
        val a = perm[xi] + yi
        val aa = perm[a] + zi
        val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi
        val ba = perm[b] + zi
        val bb = perm[b + 1] + zi
        return mix(
            w,
            mix(
                v,
                mix(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1f, yf, zf)),
                mix(u, grad(perm[ab], xf, yf - 1f, zf), grad(perm[bb], xf - 1f, yf - 1f, zf)),
            ),
            mix(
                v,
                mix(u, grad(perm[aa + 1], xf, yf, zf - 1f), grad(perm[ba + 1], xf - 1f, yf, zf - 1f)),
                mix(u, grad(perm[ab + 1], xf, yf - 1f, zf - 1f), grad(perm[bb + 1], xf - 1f, yf - 1f, zf - 1f)),
            ),
        )
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun mix(t: Float, a: Float, b: Float): Float = a + t * (b - a)

    private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if ((h and 1) == 0) u else -u) + (if ((h and 2) == 0) v else -v)
    }
}
