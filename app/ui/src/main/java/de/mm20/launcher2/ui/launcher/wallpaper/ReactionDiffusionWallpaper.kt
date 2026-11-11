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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Reaction-diffusion: a living Gray-Scott chemical field that grows coral, fingerprints, mazes
 * and dividing cells across the screen. Feed and kill rates vary across space, so different
 * regions grow different kinds of pattern, and the whole parameter field drifts slowly through
 * known pattern regimes so the tissue keeps reshaping itself. Touching or dragging paints the
 * catalyst under the finger and new growth blooms from there. The palette (deep indigo through
 * teal to pale mint, with bright rims where the pattern's edges are steep) rotates in hue over
 * minutes. If the field ever dies out, it reseeds itself.
 */
@Composable
fun ReactionDiffusionWallpaper(modifier: Modifier = Modifier) {
    val sim = remember { RdSim() }
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
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                t += dt

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

                val pressed = WallpaperPointer.pressed
                if (pressed) {
                    val x = WallpaperPointer.x
                    val y = WallpaperPointer.y
                    if (wasPressed) sim.paintStroke(lastX, lastY, x, y) else sim.paintStroke(x, y, x, y)
                    lastX = x
                    lastY = y
                }
                wasPressed = pressed

                val start = System.nanoTime()
                sim.step(sim.iterations)
                sim.adaptIterations(System.nanoTime() - start)

                sim.render()
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
        drawImage(
            image = glow,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(glow.width, glow.height),
            dstOffset = IntOffset.Zero,
            dstSize = dst,
            alpha = GLOW_ALPHA,
            blendMode = BlendMode.Plus,
            filterQuality = FilterQuality.Low,
        )
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
private const val GLOW_ALPHA = 0.6f

/** B concentration that maps to the top of the colour table. */
private const val B_SCALE = 255f / 0.38f
private const val EDGE_SCALE = 255f / 0.22f

private val BACKDROP = Color(0xFF030308)

/** Path through (feed, kill) space: coral, worms, mazes, fingerprints, mitosis, then back. */
private val WAYPOINTS_F = floatArrayOf(0.0545f, 0.0420f, 0.0290f, 0.0370f, 0.0367f)
private val WAYPOINTS_K = floatArrayOf(0.0620f, 0.0610f, 0.0570f, 0.0600f, 0.0645f)

/** Colour ramp from empty (near black) to dense ridges (pale mint). */
private val STOP_POS = floatArrayOf(0f, 0.16f, 0.36f, 0.56f, 0.76f, 0.91f, 1f)
private val STOP_RGB = intArrayOf(
    0x030308, 0x0B0A2C, 0x241F72, 0x135F8C, 0x2DB3A6, 0x9EF0CF, 0xEBFFF5,
)
private const val EDGE_BASE_R = 150f
private const val EDGE_BASE_G = 255f
private const val EDGE_BASE_B = 228f
private const val EDGE_STRENGTH = 0.5f

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

    /** Per-cell offsets added to the drifting global feed and kill rates. */
    private var spF = FloatArray(0)
    private var spK = FloatArray(0)
    private var fc = WAYPOINTS_F[0]
    private var kc = WAYPOINTS_K[0]

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

    private val lut = IntArray(256)
    private val glowLut = IntArray(256)
    private var edgeR = 0
    private var edgeG = 0
    private var edgeB = 0

    private var vignetteBrush: Brush? = null
    private var vignetteW = 0f
    private var vignetteH = 0f

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
        spF = FloatArray(n)
        spK = FloatArray(n)
        val twoPi = (2.0 * PI).toFloat()
        for (y in 1..h) {
            val v = (y - 0.5f) / h
            for (x in 1..w) {
                val u = (x - 0.5f) / w
                val i = y * stride + x
                spF[i] = 0.007f * (u - 0.5f) + 0.0025f * sin(twoPi * (1.3f * v + 0.4f * u))
                spK[i] = 0.0014f * (v - 0.5f) + 0.0006f * sin(twoPi * (1.7f * u - 0.6f * v) + 1f)
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
                    val f = f0 + sf[i]
                    val k = k0 + sk[i]
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

    /** Maps B (plus a rim from its gradient) through the colour table into the bitmaps. */
    fun render() {
        val bmp = bitmap ?: return
        val gBmp = glowBitmap ?: return
        val s = stride
        val w = gw
        val h = gh
        val bb = b
        wrap(bb)
        val px = pixels
        val table = lut
        val er = edgeR
        val eg = edgeG
        val eb = edgeB
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
                var r = ((c shr 16) and 0xFF) + ((er * e) shr 8)
                var g = ((c shr 8) and 0xFF) + ((eg * e) shr 8)
                var bl = (c and 0xFF) + ((eb * e) shr 8)
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

        // Low-res bloom: block-averaged B through a table that only lights the bright ridges.
        val gp = glowPixels
        val gt = glowLut
        for (gy in 0 until glowH) {
            val y0 = gy * GLOW_DIV + 1
            val y1 = min(y0 + GLOW_DIV, h + 1)
            for (gx in 0 until glowW) {
                val x0 = gx * GLOW_DIV + 1
                val x1 = min(x0 + GLOW_DIV, w + 1)
                var acc = 0f
                for (y in y0 until y1) {
                    val row = y * s
                    for (x in x0 until x1) acc += bb[row + x]
                }
                var idx = (acc / ((y1 - y0) * (x1 - x0)) * B_SCALE).toInt()
                if (idx < 0) idx = 0 else if (idx > 255) idx = 255
                gp[gy * glowW + gx] = gt[idx]
            }
        }
        gBmp.setPixels(gp, 0, glowW, 0, 0, glowW, glowH)
    }

    /** Reseeds a dead field and thins out one that has grown over everything. */
    fun checkHealth() {
        if (meanB < 0.0015f) {
            repeat(6) { randomBlob() }
        } else if (meanB > 0.26f) {
            clearDisc(
                1f + Random.nextFloat() * gw,
                1f + Random.nextFloat() * gh,
                SHORT_CELLS * (0.08f + 0.06f * Random.nextFloat()),
            )
        }
    }

    fun randomBlob() {
        if (gw == 0) return
        val r = 3f + Random.nextFloat() * 4f
        stamp(1f + Random.nextFloat() * gw, 1f + Random.nextFloat() * gh, r)
    }

    /** Paints catalyst along the finger's path since the last frame (screen pixels). */
    fun paintStroke(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (gw == 0 || viewW <= 0f || viewH <= 0f) return
        val sx = gw / viewW
        val sy = gh / viewH
        val cx0 = x0 * sx + 1f
        val cy0 = y0 * sy + 1f
        val cx1 = x1 * sx + 1f
        val cy1 = y1 * sy + 1f
        val r = max(2.5f, SHORT_CELLS * 0.03f)
        val dx = cx1 - cx0
        val dy = cy1 - cy0
        val len = sqrt(dx * dx + dy * dy)
        val steps = min(64, (len / (r * 0.5f)).toInt() + 1)
        for (j in 1..steps) {
            val f = j.toFloat() / steps
            stamp(cx0 + dx * f, cy0 + dy * f, r)
        }
    }

    private fun wrapX(x: Int): Int = ((x - 1) % gw + gw) % gw + 1
    private fun wrapY(y: Int): Int = ((y - 1) % gh + gh) % gh + 1

    private fun stamp(cx: Float, cy: Float, r: Float) {
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
                bb[i] = max(bb[i], 0.22f + 0.1f * fall * Random.nextFloat())
                aa[i] = min(aa[i], 0.5f)
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

        var seg = 0
        for (i in 0 until 256) {
            val tt = i / 255f
            while (seg < STOP_POS.size - 2 && tt > STOP_POS[seg + 1]) seg++
            val p0 = STOP_POS[seg]
            val p1 = STOP_POS[seg + 1]
            val e = ((tt - p0) / (p1 - p0)).coerceIn(0f, 1f)
            val c0 = STOP_RGB[seg]
            val c1 = STOP_RGB[seg + 1]
            val r = lerpChannel(c0 shr 16, c1 shr 16, e)
            val g = lerpChannel(c0 shr 8, c1 shr 8, e)
            val bl = lerpChannel(c0, c1, e)
            val rr = clampByte(m00 * r + m01 * g + m02 * bl)
            val gg = clampByte(m10 * r + m11 * g + m12 * bl)
            val bb = clampByte(m20 * r + m21 * g + m22 * bl)
            lut[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            // Bloom only from the upper part of the ramp.
            val w = ((tt - 0.35f) / 0.65f).coerceIn(0f, 1f)
            val gw2 = w * w
            glowLut[i] = (0xFF shl 24) or ((rr * gw2).toInt() shl 16) or
                ((gg * gw2).toInt() shl 8) or (bb * gw2).toInt()
        }
        edgeR = clampByte(EDGE_STRENGTH * (m00 * EDGE_BASE_R + m01 * EDGE_BASE_G + m02 * EDGE_BASE_B))
        edgeG = clampByte(EDGE_STRENGTH * (m10 * EDGE_BASE_R + m11 * EDGE_BASE_G + m12 * EDGE_BASE_B))
        edgeB = clampByte(EDGE_STRENGTH * (m20 * EDGE_BASE_R + m21 * EDGE_BASE_G + m22 * EDGE_BASE_B))
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
