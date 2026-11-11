package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Reaction-diffusion: a dark, slowly living Gray-Scott chemical field of glowing spots, thin
 * worms and fine mazes. Feed and kill rates vary across space and drift slowly through sparse
 * pattern regimes, with the top of the screen (where the clock sits) biased toward the sparsest
 * ones and shaded a little darker, so white text always reads over it. The palette (near-black
 * through indigo to a muted teal that never gets brighter than mid-luminance) rotates in hue over
 * minutes, and a coverage check thins the tissue out whenever it starts to crowd the screen.
 *
 * Touch excites the tissue: dragging lays down a stroke of catalyst whose width grows with the
 * drag speed, and the area around the finger glows in a warm, hue-shifted accent while its
 * chemistry switches to a fast-growing regime, so patterns bloom from the stroke and then split
 * and settle back once the finger lifts (the glow fades over about a second). A tap sends out an
 * expanding ring that seeds new growth as it travels; holding still keeps pulsing rings. If the
 * field ever dies out, it reseeds itself.
 *
 * While music plays, the tissue and its rims brighten by a few percent on each beat.
 */
@Composable
fun ReactionDiffusionWallpaper(modifier: Modifier = Modifier) {
    val sim = remember { RdSim() }
    val pulse = remember { MusicPulse() }
    val frame = remember { mutableLongStateOf(0L) }
    val resetGeneration = rememberResetGeneration()
    val generation = rememberUpdatedState(resetGeneration)

    LaunchedEffect(Unit) {
        var last = 0L
        var t = 0f
        var nextLut = 0f
        var nextDrop = 8f
        var seenGeneration = generation.value
        var wasPressed = false
        var lastX = 0f
        var lastY = 0f
        var holdTimer = 0f
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                t += dt
                pulse.update(now)

                if (!sim.ensure()) {
                    frame.longValue = now
                    return@withFrameNanos
                }

                if (t >= nextLut) {
                    sim.buildLut(t)
                    nextLut = t + LUT_INTERVAL
                }
                sim.updateParams(t)

                if (generation.value != seenGeneration) {
                    seenGeneration = generation.value
                    repeat(3) { sim.randomBlob() }
                }
                if (t >= nextDrop) {
                    sim.randomBlob()
                    nextDrop = t + 9f + Random.nextFloat() * 12f
                }

                sim.decayHeat(dt)
                val pressed = WallpaperPointer.pressed
                if (pressed) {
                    val x = WallpaperPointer.x
                    val y = WallpaperPointer.y
                    if (!wasPressed) {
                        sim.ripple(x, y)
                        sim.touchStroke(x, y, x, y, 0f)
                        holdTimer = 0f
                    } else {
                        val dx = x - lastX
                        val dy = y - lastY
                        val speed = if (dt > 0f) sqrt(dx * dx + dy * dy) / dt else 0f
                        sim.touchStroke(lastX, lastY, x, y, speed)
                        holdTimer += dt
                        if (speed > HOLD_SPEED_PX) {
                            holdTimer = 0f
                        } else if (holdTimer >= HOLD_RIPPLE_INTERVAL) {
                            sim.ripple(x, y)
                            holdTimer = 0f
                        }
                    }
                    lastX = x
                    lastY = y
                }
                wasPressed = pressed
                sim.updateRipples(dt)

                val start = System.nanoTime()
                sim.step(sim.iterations)
                sim.adaptIterations(System.nanoTime() - start)

                sim.render(pulse.beat)
                sim.checkHealth()
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        sim.viewW = size.width
        sim.viewH = size.height
        val image = sim.image
        val glow = sim.glowImage
        if (image == null || glow == null) {
            drawRect(BACKDROP)
            return@Canvas
        }
        val dst = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = dst,
            filterQuality = FilterQuality.Low,
        )
        if (sim.glowVisible) {
            drawImage(
                image = glow,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(glow.width, glow.height),
                dstOffset = IntOffset.Zero,
                dstSize = dst,
                alpha = GLOW_ALPHA * (1f + BEAT_BODY_GAIN * sim.beat),
                blendMode = BlendMode.Plus,
                filterQuality = FilterQuality.Low,
            )
        }
        drawRect(sim.topShade(size.height))
        drawRect(sim.vignette(size.width, size.height))
    }
}

/** Cells across the short side of the screen. */
private const val SHORT_CELLS = 112
private const val MAX_ITERATIONS = 10
private const val MIN_ITERATIONS = 3
private const val START_ITERATIONS = 8

/** Main-thread budget for the simulation step; iterations adapt to stay inside it. */
private const val STEP_BUDGET_NANOS = 6_000_000f
private const val MAX_DT = 0.05f
private const val LUT_INTERVAL = 1.5f
private const val SEGMENT_SECONDS = 40f
private const val GLOW_DIV = 4

/** Bloom strength; the bloom only exists where the tissue is excited by touch. */
private const val GLOW_ALPHA = 0.55f

/** B concentration that maps to the top of the colour table. */
private const val B_SCALE = 255f / 0.38f
private const val EDGE_SCALE = 255f / 0.22f

private val BACKDROP = Color(0xFF020207)

/** Music beat: brightness added to the tissue colours and to the rim light at the beat's peak. */
private const val BEAT_BODY_GAIN = 0.05f
private const val BEAT_RIM_GAIN = 0.35f

/** Darkening over the clock area: alpha at the very top, and where it fades out (fraction of h). */
private const val TOP_SHADE_ALPHA = 0.5f
private const val TOP_SHADE_END = 0.45f

/**
 * Path through (feed, kill) space, all sparse regimes: mitosis, spots/short worms, worms,
 * solitons, thin mazes, then back.
 */
private val WAYPOINTS_F = floatArrayOf(0.0367f, 0.0460f, 0.0580f, 0.0300f, 0.0420f)
private val WAYPOINTS_K = floatArrayOf(0.0649f, 0.0645f, 0.0650f, 0.0620f, 0.0630f)

/** Extra kill rate over the top of the screen (fading out by ~40% height), for a sparser clock area. */
private const val TOP_KILL_BIAS = 0.0010f

/** Coverage control: above this mean B the tissue is thinned out (sparse regimes sit ~0.04-0.10). */
private const val HEALTH_MAX_B = 0.12f
private const val HEALTH_HARD_B = 0.18f
private const val HEALTH_MIN_B = 0.0015f

/** Colour ramp from empty (near black) to dense ridges (muted teal, relative luminance ~0.29). */
private val STOP_POS = floatArrayOf(0f, 0.14f, 0.32f, 0.5f, 0.68f, 0.84f, 1f)
private val STOP_RGB = intArrayOf(
    0x020207, 0x06061C, 0x121145, 0x1B2F70, 0x1A5F86, 0x1E8592, 0x2FA39C,
)

/** Excited ramp shown near the finger: dim violet on empty cells through magenta to warm coral. */
private val HOT_POS = floatArrayOf(0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f)
private val HOT_RGB = intArrayOf(
    0x150A2C, 0x2C1060, 0x5A1F8A, 0x9A3A9E, 0xD8609A, 0xF59A84,
)

private const val EDGE_BASE_R = 90f
private const val EDGE_BASE_G = 200f
private const val EDGE_BASE_B = 190f
private const val EDGE_STRENGTH = 0.15f

/** Touch excitation ("heat"): fade time constant after release, and the cut-off below which it is dropped. */
private const val HEAT_TAU = 0.4f
private const val HEAT_EPS = 0.004f

/** Chemistry the excited area is pulled toward: coral, which grows quickly from any catalyst. */
private const val TOUCH_F = 0.0545f
private const val TOUCH_K = 0.0620f

/** Catalyst stroke radius in cells: minimum, growth per cell/s of drag speed, maximum. */
private const val STROKE_R_MIN = 2.5f
private const val STROKE_R_PER_SPEED = 0.025f
private const val STROKE_R_MAX = 7f

/** Heat radius around the stroke = stroke radius * scale + pad (cells). */
private const val HEAT_R_SCALE = 1.6f
private const val HEAT_R_PAD = 7f

/** Tap ripples: lifetime (s), max radius (cells), ring half-width (cells), seeding portion of life. */
private const val MAX_RIPPLES = 6
private const val RIPPLE_LIFE = 1.1f
private const val RIPPLE_MAX_R = SHORT_CELLS * 0.2f
private const val RIPPLE_WIDTH = 2.2f
private const val RIPPLE_SEED_UNTIL = 0.55f

/** While the finger rests (slower than this, px/s), a new ripple pulses out every interval (s). */
private const val HOLD_SPEED_PX = 300f
private const val HOLD_RIPPLE_INTERVAL = 0.9f

/**
 * Gray-Scott field on a padded grid (one ghost cell on each side, refreshed with wrap-around
 * values before every iteration) so the inner loop needs no bounds handling.
 */
private class RdSim {
    var viewW = 0f
    var viewH = 0f

    private var gw = 0
    private var gh = 0
    private var stride = 0

    private var a = FloatArray(0)
    private var b = FloatArray(0)
    private var na = FloatArray(0)
    private var nb = FloatArray(0)

    /** Touch excitation per cell, 0..1; drives the local regime switch, glow and hue shift. */
    private var heat = FloatArray(0)
    private var heatMax = 0f

    /** Per-cell offsets added to the drifting global feed and kill rates. */
    private var spF = FloatArray(0)
    private var spK = FloatArray(0)
    private var fc = WAYPOINTS_F[0]
    private var kc = WAYPOINTS_K[0]

    private val rippleX = FloatArray(MAX_RIPPLES)
    private val rippleY = FloatArray(MAX_RIPPLES)
    private val rippleAge = FloatArray(MAX_RIPPLES)
    private var rippleCount = 0

    private var pixels = IntArray(0)
    private var glowW = 0
    private var glowH = 0
    private var glowPixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var glowBitmap: Bitmap? = null
    var image: ImageBitmap? = null
        private set
    var glowImage: ImageBitmap? = null
        private set
    var glowVisible = false
        private set

    private val lut = IntArray(256)
    private val hotLut = IntArray(256)
    /** [lut] brightened for the current music beat; only filled while a beat is sounding. */
    private val beatLut = IntArray(256)

    /** Music beat used for the last [render], 0..1. */
    var beat = 0f
        private set
    private var edgeR = 0
    private var edgeG = 0
    private var edgeB = 0

    private var vignetteBrush: Brush? = null
    private var vignetteW = 0f
    private var vignetteH = 0f
    private var shadeBrush: Brush? = null
    private var shadeH = 0f

    var iterations = START_ITERATIONS
        private set
    private var avgCost = 0f
    private var meanB = 0f

    /** Allocates the grid for the current view size. Returns false until a size is known. */
    fun ensure(): Boolean {
        val vw = viewW
        val vh = viewH
        if (vw <= 0f || vh <= 0f) return false
        val w: Int
        val h: Int
        if (vw <= vh) {
            w = SHORT_CELLS
            h = (SHORT_CELLS * vh / vw).roundToInt().coerceIn(SHORT_CELLS, SHORT_CELLS * 3)
        } else {
            h = SHORT_CELLS
            w = (SHORT_CELLS * vw / vh).roundToInt().coerceIn(SHORT_CELLS, SHORT_CELLS * 3)
        }
        if (w == gw && h == gh) return true

        gw = w
        gh = h
        stride = w + 2
        val n = stride * (h + 2)
        a = FloatArray(n) { 1f }
        na = FloatArray(n) { 1f }
        b = FloatArray(n)
        nb = FloatArray(n)
        heat = FloatArray(n)
        heatMax = 0f
        rippleCount = 0
        spF = FloatArray(n)
        spK = FloatArray(n)
        val twoPi = (2.0 * PI).toFloat()
        for (y in 1..h) {
            val v = (y - 0.5f) / h
            // 1 at the top, easing to 0 by ~40% down: the clock area gets a higher kill rate.
            val top = ((0.42f - v) / 0.32f).coerceIn(0f, 1f)
            val topW = top * top * (3f - 2f * top)
            for (x in 1..w) {
                val u = (x - 0.5f) / w
                val i = y * stride + x
                spF[i] = 0.005f * (u - 0.5f) + 0.002f * sin(twoPi * (1.3f * v + 0.4f * u))
                spK[i] = 0.0006f * (0.5f - v) + 0.0005f * sin(twoPi * (1.7f * u - 0.6f * v) + 1f) +
                    TOP_KILL_BIAS * topW
            }
        }

        pixels = IntArray(w * h)
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { image = it.asImageBitmap() }
        glowW = (w + GLOW_DIV - 1) / GLOW_DIV
        glowH = (h + GLOW_DIV - 1) / GLOW_DIV
        glowPixels = IntArray(glowW * glowH)
        glowBitmap = Bitmap.createBitmap(glowW, glowH, Bitmap.Config.ARGB_8888)
            .also { glowImage = it.asImageBitmap() }

        repeat(7) { randomBlob() }
        return true
    }

    /** Moves the global feed/kill point smoothly along the waypoint loop. */
    fun updateParams(t: Float) {
        val n = WAYPOINTS_F.size
        val phase = t / SEGMENT_SECONDS
        val seg = floor(phase).toInt()
        val i0 = ((seg % n) + n) % n
        val i1 = (i0 + 1) % n
        val s = phase - seg
        val e = s * s * (3f - 2f * s)
        fc = WAYPOINTS_F[i0] + (WAYPOINTS_F[i1] - WAYPOINTS_F[i0]) * e
        kc = WAYPOINTS_K[i0] + (WAYPOINTS_K[i1] - WAYPOINTS_K[i0]) * e
    }

    private fun wrap(arr: FloatArray) {
        val s = stride
        val w = gw
        val h = gh
        for (y in 1..h) {
            val r = y * s
            arr[r] = arr[r + w]
            arr[r + w + 1] = arr[r + 1]
        }
        arr.copyInto(arr, 0, h * s, h * s + s)
        arr.copyInto(arr, (h + 1) * s, s, 2 * s)
    }

    fun step(count: Int) {
        val s = stride
        val w = gw
        val h = gh
        val sf = spF
        val sk = spK
        val hp = heat
        val excited = heatMax > HEAT_EPS
        val f0 = fc
        val k0 = kc
        repeat(count) {
            val a0 = a
            val b0 = b
            val a1 = na
            val b1 = nb
            wrap(a0)
            wrap(b0)
            for (y in 1..h) {
                var i = y * s + 1
                val end = i + w
                while (i < end) {
                    val ac = a0[i]
                    val bc = b0[i]
                    val up = i - s
                    val dn = i + s
                    val lapA = 0.2f * (a0[i - 1] + a0[i + 1] + a0[up] + a0[dn]) +
                        0.05f * (a0[up - 1] + a0[up + 1] + a0[dn - 1] + a0[dn + 1]) - ac
                    val lapB = 0.2f * (b0[i - 1] + b0[i + 1] + b0[up] + b0[dn]) +
                        0.05f * (b0[up - 1] + b0[up + 1] + b0[dn - 1] + b0[dn + 1]) - bc
                    val abb = ac * bc * bc
                    var f = f0 + sf[i]
                    var k = k0 + sk[i]
                    if (excited) {
                        // Pull the excited area toward the fast-growing touch regime.
                        val hv = hp[i]
                        f += (TOUCH_F - f) * hv
                        k += (TOUCH_K - k) * hv
                    }
                    a1[i] = ac + (lapA - abb + f * (1f - ac))
                    b1[i] = bc + (0.5f * lapB + abb - (k + f) * bc)
                    i++
                }
            }
            a = a1
            b = b1
            na = a0
            nb = b0
        }
    }

    fun adaptIterations(costNanos: Long) {
        val perIteration = costNanos.toFloat() / iterations
        avgCost = if (avgCost == 0f) perIteration else avgCost * 0.9f + perIteration * 0.1f
        val target = (STEP_BUDGET_NANOS / avgCost).toInt()
        iterations = target.coerceIn(MIN_ITERATIONS, MAX_ITERATIONS)
    }

    /**
     * Maps B (plus a faint rim from its gradient) through the colour table into the bitmap,
     * blending toward the excited table where the tissue is warm, and builds the low-res bloom
     * from the warm areas only. A music [beat] (0 when silent) lifts the colours and rims a little.
     */
    fun render(beat: Float) {
        this.beat = beat
        val bmp = bitmap ?: return
        val gBmp = glowBitmap ?: return
        val s = stride
        val w = gw
        val h = gh
        val bb = b
        wrap(bb)
        val hp = heat
        val warm = heatMax > HEAT_EPS
        val px = pixels
        var table = lut
        val hot = hotLut
        var er = edgeR
        var eg = edgeG
        var eb = edgeB
        if (beat > 0f) {
            // 256 table entries and three rim channels, so the per-cell loop is unchanged.
            val q = ((1f + BEAT_BODY_GAIN * beat) * 256f).toInt()
            val src = lut
            val dst = beatLut
            for (k in 0 until 256) {
                val c = src[k]
                val r = min(255, (((c shr 16) and 0xFF) * q) shr 8)
                val g = min(255, (((c shr 8) and 0xFF) * q) shr 8)
                val bl = min(255, ((c and 0xFF) * q) shr 8)
                dst[k] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
            table = dst
            val rim = 1f + BEAT_RIM_GAIN * beat
            er = (er * rim).toInt()
            eg = (eg * rim).toInt()
            eb = (eb * rim).toInt()
        }
        var sum = 0f
        for (y in 1..h) {
            var i = y * s + 1
            var p = (y - 1) * w
            val end = i + w
            while (i < end) {
                val v = bb[i]
                sum += v
                var idx = (v * B_SCALE).toInt()
                if (idx < 0) idx = 0 else if (idx > 255) idx = 255
                val grad = abs(bb[i + 1] - bb[i - 1]) + abs(bb[i + s] - bb[i - s])
                var e = (grad * EDGE_SCALE).toInt()
                if (e > 255) e = 255
                val c = table[idx]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var bl = c and 0xFF
                if (warm) {
                    val hv = hp[i]
                    if (hv > HEAT_EPS) {
                        val q = if (hv >= 1f) 256 else (hv * 256f).toInt()
                        val hc = hot[idx]
                        r += ((((hc shr 16) and 0xFF) - r) * q) shr 8
                        g += ((((hc shr 8) and 0xFF) - g) * q) shr 8
                        bl += (((hc and 0xFF) - bl) * q) shr 8
                    }
                }
                r += (er * e) shr 8
                g += (eg * e) shr 8
                bl += (eb * e) shr 8
                if (r > 255) r = 255
                if (g > 255) g = 255
                if (bl > 255) bl = 255
                px[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                i++
                p++
            }
        }
        meanB = sum / (w * h)
        bmp.setPixels(px, 0, w, 0, 0, w, h)

        glowVisible = warm
        if (!warm) return
        // Low-res bloom, only where touched: block-averaged B through the excited table, scaled by heat.
        val gp = glowPixels
        for (gy in 0 until glowH) {
            val y0 = gy * GLOW_DIV + 1
            val y1 = min(y0 + GLOW_DIV, h + 1)
            for (gx in 0 until glowW) {
                val x0 = gx * GLOW_DIV + 1
                val x1 = min(x0 + GLOW_DIV, w + 1)
                var accB = 0f
                var accH = 0f
                for (y in y0 until y1) {
                    val row = y * s
                    for (x in x0 until x1) {
                        accB += bb[row + x]
                        accH += hp[row + x]
                    }
                }
                val inv = 1f / ((y1 - y0) * (x1 - x0))
                val hv = accH * inv
                if (hv <= HEAT_EPS) {
                    gp[gy * glowW + gx] = 0xFF000000.toInt()
                    continue
                }
                var idx = (accB * inv * B_SCALE).toInt()
                if (idx < 0) idx = 0 else if (idx > 255) idx = 255
                val c = hot[idx]
                val q = if (hv >= 1f) 256 else (hv * 256f).toInt()
                val r = (((c shr 16) and 0xFF) * q) shr 8
                val g = (((c shr 8) and 0xFF) * q) shr 8
                val bl = ((c and 0xFF) * q) shr 8
                gp[gy * glowW + gx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
        gBmp.setPixels(gp, 0, glowW, 0, 0, glowW, glowH)
    }

    /** Reseeds a dead field and thins out one that is starting to crowd the screen. */
    fun checkHealth() {
        if (meanB < HEALTH_MIN_B) {
            repeat(6) { randomBlob() }
        } else if (meanB > HEALTH_MAX_B) {
            repeat(if (meanB > HEALTH_HARD_B) 3 else 1) {
                val cx = 1f + Random.nextFloat() * gw
                val cy = 1f + Random.nextFloat() * gh
                // Leave the tissue the finger is currently working on alone.
                val i = wrapY(cy.toInt()) * stride + wrapX(cx.toInt())
                if (heat[i] < 0.05f) clearDisc(cx, cy, SHORT_CELLS * (0.07f + 0.05f * Random.nextFloat()))
            }
        }
    }

    fun randomBlob() {
        if (gw == 0) return
        val r = 3f + Random.nextFloat() * 4f
        stamp(1f + Random.nextFloat() * gw, 1f + Random.nextFloat() * gh, r, 0.22f, 0.1f, 0.5f)
    }

    /** Fades touch excitation out with a ~[HEAT_TAU] time constant. */
    fun decayHeat(dt: Float) {
        if (heatMax <= 0f || dt <= 0f) return
        val k = exp(-dt / HEAT_TAU)
        heatMax *= k
        val hp = heat
        if (heatMax < HEAT_EPS) {
            hp.fill(0f)
            heatMax = 0f
            return
        }
        for (i in hp.indices) hp[i] *= k
    }

    /**
     * Excites the tissue along the finger's path since the last frame (screen pixels): a strong
     * stroke of catalyst whose width grows with [speedPx] (px/s), and a wider warm halo.
     */
    fun touchStroke(x0: Float, y0: Float, x1: Float, y1: Float, speedPx: Float) {
        if (gw == 0 || viewW <= 0f || viewH <= 0f) return
        val sx = gw / viewW
        val sy = gh / viewH
        val cx0 = x0 * sx + 1f
        val cy0 = y0 * sy + 1f
        val cx1 = x1 * sx + 1f
        val cy1 = y1 * sy + 1f
        val speed = speedPx * sx
        val r = (STROKE_R_MIN + speed * STROKE_R_PER_SPEED).coerceAtMost(STROKE_R_MAX)
        val rh = r * HEAT_R_SCALE + HEAT_R_PAD
        val dx = cx1 - cx0
        val dy = cy1 - cy0
        val len = sqrt(dx * dx + dy * dy)
        val steps = min(48, (len / (r * 0.5f)).toInt() + 1)
        for (j in 1..steps) {
            val f = j.toFloat() / steps
            stamp(cx0 + dx * f, cy0 + dy * f, r, 0.3f, 0.15f, 0.35f)
        }
        val heatSteps = min(16, (len / (rh * 0.5f)).toInt() + 1)
        for (j in 1..heatSteps) {
            val f = j.toFloat() / heatSteps
            warm(cx0 + dx * f, cy0 + dy * f, rh, 1f)
        }
    }

    /** Starts an expanding ring at a screen position. */
    fun ripple(x: Float, y: Float) {
        if (gw == 0 || viewW <= 0f || viewH <= 0f) return
        var slot = rippleCount
        if (slot == MAX_RIPPLES) {
            slot = 0
            for (j in 1 until MAX_RIPPLES) if (rippleAge[j] > rippleAge[slot]) slot = j
        } else {
            rippleCount++
        }
        rippleX[slot] = x * gw / viewW + 1f
        rippleY[slot] = y * gh / viewH + 1f
        rippleAge[slot] = 0f
    }

    /** Advances the rings: each one warms a moving annulus and seeds catalyst on its way out. */
    fun updateRipples(dt: Float) {
        var j = 0
        while (j < rippleCount) {
            val age = rippleAge[j] + dt
            if (age >= RIPPLE_LIFE) {
                val lastIdx = rippleCount - 1
                rippleX[j] = rippleX[lastIdx]
                rippleY[j] = rippleY[lastIdx]
                rippleAge[j] = rippleAge[lastIdx]
                rippleCount = lastIdx
                continue
            }
            rippleAge[j] = age
            val p = age / RIPPLE_LIFE
            val q = 1f - p
            val radius = 1.5f + RIPPLE_MAX_R * (1f - q * q)
            ring(rippleX[j], rippleY[j], radius, q, p < RIPPLE_SEED_UNTIL)
            j++
        }
    }

    private fun ring(cx: Float, cy: Float, radius: Float, strength: Float, seed: Boolean) {
        val outer = radius + RIPPLE_WIDTH
        val inner = max(0f, radius - RIPPLE_WIDTH)
        val outer2 = outer * outer
        val inner2 = inner * inner
        val aa = a
        val bb = b
        val hp = heat
        for (iy in floor(cy - outer).toInt()..ceil(cy + outer).toInt()) {
            val dy = iy - cy
            val row = wrapY(iy) * stride
            for (ix in floor(cx - outer).toInt()..ceil(cx + outer).toInt()) {
                val dx = ix - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= outer2 || d2 < inner2) continue
                val prof = 1f - abs(sqrt(d2) - radius) / RIPPLE_WIDTH
                if (prof <= 0f) continue
                val i = row + wrapX(ix)
                val hv = strength * prof
                if (hv > hp[i]) hp[i] = hv
                if (seed && prof > 0.5f) {
                    if (bb[i] < 0.27f) bb[i] = 0.27f
                    if (aa[i] > 0.55f) aa[i] = 0.55f
                }
            }
        }
        if (strength > heatMax) heatMax = strength
    }

    private fun warm(cx: Float, cy: Float, r: Float, amount: Float) {
        val r2 = r * r
        val hp = heat
        for (iy in floor(cy - r).toInt()..ceil(cy + r).toInt()) {
            val dy = iy - cy
            val row = wrapY(iy) * stride
            for (ix in floor(cx - r).toInt()..ceil(cx + r).toInt()) {
                val dx = ix - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val i = row + wrapX(ix)
                val fall = 1f - d2 / r2
                val hv = amount * fall * fall
                if (hv > hp[i]) hp[i] = hv
            }
        }
        if (amount > heatMax) heatMax = amount
    }

    private fun wrapX(x: Int): Int = ((x - 1) % gw + gw) % gw + 1
    private fun wrapY(y: Int): Int = ((y - 1) % gh + gh) % gh + 1

    private fun stamp(cx: Float, cy: Float, r: Float, bBase: Float, bJitter: Float, aCap: Float) {
        val r2 = r * r
        val aa = a
        val bb = b
        for (iy in floor(cy - r).toInt()..ceil(cy + r).toInt()) {
            val dy = iy - cy
            val row = wrapY(iy) * stride
            for (ix in floor(cx - r).toInt()..ceil(cx + r).toInt()) {
                val dx = ix - cx
                val d2 = dx * dx + dy * dy
                if (d2 >= r2) continue
                val i = row + wrapX(ix)
                val fall = 1f - d2 / r2
                bb[i] = max(bb[i], bBase + bJitter * fall * Random.nextFloat())
                aa[i] = min(aa[i], aCap)
            }
        }
    }

    private fun clearDisc(cx: Float, cy: Float, r: Float) {
        val r2 = r * r
        for (iy in floor(cy - r).toInt()..ceil(cy + r).toInt()) {
            val dy = iy - cy
            val row = wrapY(iy) * stride
            for (ix in floor(cx - r).toInt()..ceil(cx + r).toInt()) {
                val dx = ix - cx
                if (dx * dx + dy * dy >= r2) continue
                val i = row + wrapX(ix)
                a[i] = 1f
                b[i] = 0f
            }
        }
    }

    /** Rebuilds the colour tables with the palette hue rotated by a slow wander over minutes. */
    fun buildLut(t: Float) {
        val twoPi = (2.0 * PI).toFloat()
        val deg = 38f * sin(twoPi * t / 420f) + 18f * sin(twoPi * t / 157f + 1.3f)
        val rad = deg * (PI / 180.0).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        // Standard luminance-preserving hue rotation matrix.
        val m00 = 0.213f + 0.787f * c - 0.213f * s
        val m01 = 0.715f - 0.715f * c - 0.715f * s
        val m02 = 0.072f - 0.072f * c + 0.928f * s
        val m10 = 0.213f - 0.213f * c + 0.143f * s
        val m11 = 0.715f + 0.285f * c + 0.140f * s
        val m12 = 0.072f - 0.072f * c - 0.283f * s
        val m20 = 0.213f - 0.213f * c - 0.787f * s
        val m21 = 0.715f - 0.715f * c + 0.715f * s
        val m22 = 0.072f + 0.928f * c + 0.072f * s

        for (pass in 0..1) {
            val pos = if (pass == 0) STOP_POS else HOT_POS
            val rgb = if (pass == 0) STOP_RGB else HOT_RGB
            val out = if (pass == 0) lut else hotLut
            var seg = 0
            for (i in 0 until 256) {
                val tt = i / 255f
                while (seg < pos.size - 2 && tt > pos[seg + 1]) seg++
                val p0 = pos[seg]
                val p1 = pos[seg + 1]
                val e = ((tt - p0) / (p1 - p0)).coerceIn(0f, 1f)
                val c0 = rgb[seg]
                val c1 = rgb[seg + 1]
                val r = lerpChannel(c0 shr 16, c1 shr 16, e)
                val g = lerpChannel(c0 shr 8, c1 shr 8, e)
                val bl = lerpChannel(c0, c1, e)
                val rr = clampByte(m00 * r + m01 * g + m02 * bl)
                val gg = clampByte(m10 * r + m11 * g + m12 * bl)
                val bb = clampByte(m20 * r + m21 * g + m22 * bl)
                out[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        edgeR = clampByte(EDGE_STRENGTH * (m00 * EDGE_BASE_R + m01 * EDGE_BASE_G + m02 * EDGE_BASE_B))
        edgeG = clampByte(EDGE_STRENGTH * (m10 * EDGE_BASE_R + m11 * EDGE_BASE_G + m12 * EDGE_BASE_B))
        edgeB = clampByte(EDGE_STRENGTH * (m20 * EDGE_BASE_R + m21 * EDGE_BASE_G + m22 * EDGE_BASE_B))
    }

    /** Darkening over the top of the screen behind the clock; rebuilt only when the height changes. */
    fun topShade(h: Float): Brush {
        val cached = shadeBrush
        if (cached != null && h == shadeH) return cached
        shadeH = h
        return Brush.verticalGradient(
            0f to Color.Black.copy(alpha = TOP_SHADE_ALPHA),
            TOP_SHADE_END * 0.5f to Color.Black.copy(alpha = TOP_SHADE_ALPHA * 0.6f),
            TOP_SHADE_END to Color.Transparent,
            startY = 0f,
            endY = h,
        ).also { shadeBrush = it }
    }

    /** Soft darkening toward the corners; rebuilt only when the canvas size changes. */
    fun vignette(w: Float, h: Float): Brush {
        val cached = vignetteBrush
        if (cached != null && w == vignetteW && h == vignetteH) return cached
        vignetteW = w
        vignetteH = h
        return Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.55f)),
            center = Offset(w * 0.5f, h * 0.45f),
            radius = max(w, h) * 0.78f,
        ).also { vignetteBrush = it }
    }
}

private fun lerpChannel(c0: Int, c1: Int, t: Float): Float {
    val a = (c0 and 0xFF).toFloat()
    val b = (c1 and 0xFF).toFloat()
    return a + (b - a) * t
}

private fun clampByte(v: Float): Int = v.toInt().coerceIn(0, 255)
