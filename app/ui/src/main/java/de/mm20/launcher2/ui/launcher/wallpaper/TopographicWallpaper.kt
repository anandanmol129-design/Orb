package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Topographic lines: a living contour map. A slowly morphing, gently drifting heightfield is
 * traced with fine iso-lines coloured by elevation, from deep teal valleys through sea-green to
 * sand and pale cream peaks, with every fourth line drawn as a heavier index contour. Pressing
 * raises a hill under the finger that keeps growing while held, so rings of contours bloom
 * outward and glow; dragging pulls it along and leaves a ridge that relaxes, and on release the
 * hill subsides over a couple of seconds. The terrain is reseeded whenever the launcher resumes.
 *
 * While music plays, the terrain gently swells on each beat so the contours ripple outward and brighten a little.
 */
@Composable
fun TopographicWallpaper(modifier: Modifier = Modifier) {
    val topo = remember { TopoMap() }
    val frame = remember { mutableLongStateOf(0L) }
    val generation = rememberResetGeneration()
    val pulse = remember { MusicPulse() }

    LaunchedEffect(generation) { topo.reseed() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                pulse.update(now)
                if (topo.ready) {
                    topo.step(dt, WallpaperPointer.x, WallpaperPointer.y, WallpaperPointer.pressed, pulse.beat)
                }
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        topo.ensure(size.width, size.height, density)
        topo.draw(this)
    }
}

private const val MAX_DT = 1f / 20f
private const val CELL_DP = 16f

private const val LEVELS = 16
private const val LEVEL_STEP = 1f / (LEVELS + 1)
private const val INDEX_EVERY = 4
private const val TIERS = 3 // calm, warm (near a bump), hot (on a bump)
private const val WARM_THRESHOLD = 0.06f
private const val HOT_THRESHOLD = 0.3f

private const val OCTAVES = 4
private const val NOISE_SPAN = 1.7f // noise units across the shorter screen side
private const val MORPH_SPEED = 0.04f
private const val SCROLL_X = 0.011f
private const val SCROLL_Y = 0.005f
private const val WARP = 0.38f
private const val PALETTE_PERIOD = 420f

private const val BUMPS = 8
private const val BUMP_PEAK = 0.92f
private const val BUMP_GROW_TAU = 1.3f
private const val BUMP_DECAY_TAU = 0.75f
private const val SIGMA_START_DP = 34f
private const val SIGMA_MAX_DP = 120f
private const val SIGMA_GROW_TAU = 2.4f
private const val TRAIL_SPACING_DP = 30f
private const val FOLLOW_RATE = 16f

private const val LINE_DP = 1.1f
private const val INDEX_LINE_DP = 1.8f
private const val HALO_DP = 5f

// Music: terrain lift (in contour intervals) and line alpha gain at the peak of a beat.
private const val BEAT_LIFT = 0.35f * LEVEL_STEP
private const val BEAT_GLOW = 0.15f

private val BACKDROP = Color(0xFF030608)

// Elevation gradient stops (r, g, b) for two palettes the map slowly drifts between.
private val STOPS = floatArrayOf(0f, 0.28f, 0.52f, 0.76f, 1f)
private val PALETTE_A = floatArrayOf(
    0.055f, 0.310f, 0.360f, // deep teal
    0.122f, 0.541f, 0.478f, // sea-green
    0.498f, 0.722f, 0.580f, // sage
    0.847f, 0.753f, 0.541f, // warm sand
    0.957f, 0.922f, 0.839f, // pale cream
)
private val PALETTE_B = floatArrayOf(
    0.094f, 0.227f, 0.420f, // deep ocean
    0.173f, 0.498f, 0.627f, // lagoon
    0.435f, 0.722f, 0.690f, // aqua
    0.878f, 0.706f, 0.541f, // apricot sand
    0.973f, 0.902f, 0.878f, // pale rose
)

private val PERM: IntArray = IntArray(512).also { p ->
    val src = IntArray(256) { it }
    val rnd = Random(0x70B0)
    for (i in 255 downTo 1) {
        val j = rnd.nextInt(i + 1)
        val tmp = src[i]
        src[i] = src[j]
        src[j] = tmp
    }
    for (i in 0 until 512) p[i] = src[i and 255]
}

private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

private fun mixF(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun grad(hash: Int, x: Float, y: Float, z: Float): Float {
    val h = hash and 15
    val u = if (h < 8) x else y
    val v = if (h < 4) y else if (h == 12 || h == 14) x else z
    return (if ((h and 1) == 0) u else -u) + (if ((h and 2) == 0) v else -v)
}

/** Ken Perlin's improved 3D gradient noise, roughly in -1..1. */
private fun perlin(x: Float, y: Float, z: Float): Float {
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
    val p = PERM
    val a = p[xi] + yi
    val aa = p[a] + zi
    val ab = p[a + 1] + zi
    val b = p[xi + 1] + yi
    val ba = p[b] + zi
    val bb = p[b + 1] + zi
    return mixF(
        mixF(
            mixF(grad(p[aa], xf, yf, zf), grad(p[ba], xf - 1f, yf, zf), u),
            mixF(grad(p[ab], xf, yf - 1f, zf), grad(p[bb], xf - 1f, yf - 1f, zf), u),
            v,
        ),
        mixF(
            mixF(grad(p[aa + 1], xf, yf, zf - 1f), grad(p[ba + 1], xf - 1f, yf, zf - 1f), u),
            mixF(grad(p[ab + 1], xf, yf - 1f, zf - 1f), grad(p[bb + 1], xf - 1f, yf - 1f, zf - 1f), u),
            v,
        ),
        w,
    )
}

/** Domain-warped fBm terrain, mapped to roughly 0..1. */
private fun terrain(nx: Float, ny: Float, z: Float): Float {
    val x = nx + WARP * perlin(nx * 0.5f + 5.2f, ny * 0.5f + 1.3f, z * 0.6f)
    val y = ny + WARP * perlin(nx * 0.5f + 8.3f, ny * 0.5f + 2.8f, z * 0.6f + 3.1f)
    var sum = 0f
    var amp = 1f
    var freq = 1f
    for (o in 0 until OCTAVES) {
        val off = o * 31.7f
        sum += amp * perlin(x * freq + off, y * freq - off, z * (1f + 0.5f * o) + off)
        amp *= 0.5f
        freq *= 2.03f
    }
    return 0.5f + sum * (1.15f / 1.875f)
}

private fun argb(a: Float, r: Float, g: Float, b: Float): Int {
    val ai = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
}

private class TopoMap {
    var ready = false
        private set

    private var width = 0f
    private var height = 0f
    private var density = 0f
    private var cell = 1f
    private var cols = 0
    private var rows = 0
    private var noiseScale = 1f

    private var heights = FloatArray(0)
    private var bumpField = FloatArray(0)
    private var pixels = IntArray(0)
    private var tint: Bitmap? = null
    private val tintDst = RectF()
    private val tintPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var vignette: Brush? = null

    private var time = 0f
    private var seedX = 0f
    private var seedY = 0f

    // Bump pool: one follows the finger while pressed, the rest are a fading trail.
    private val bx = FloatArray(BUMPS)
    private val by = FloatArray(BUMPS)
    private val amp = FloatArray(BUMPS)
    private val sigma = FloatArray(BUMPS)
    private val inv2s2 = FloatArray(BUMPS)
    private val cut2 = FloatArray(BUMPS)
    private var active = -1
    private var wasPressed = false
    private var trailX = 0f
    private var trailY = 0f

    // Segment buckets, indexed tier * LEVELS + level; each holds x0,y0,x1,y1 quads.
    private val lines = Array(LEVELS * TIERS) { FloatArray(1024) }
    private val counts = IntArray(LEVELS * TIERS)
    private val linePaints = Array(LEVELS * TIERS) { strokePaint(false) }
    private val haloPaints = Array(LEVELS * TIERS) { strokePaint(true) }
    private val rgb = FloatArray(3)

    // Current cell being marched.
    private var cx = 0f
    private var cy = 0f
    private var v0 = 0f
    private var v1 = 0f
    private var v2 = 0f
    private var v3 = 0f
    private var bucket = 0

    init {
        reseed()
    }

    fun reseed() {
        seedX = Random.nextFloat() * 200f
        seedY = Random.nextFloat() * 200f
        for (q in 0 until BUMPS) amp[q] = 0f
        active = -1
    }

    fun ensure(w: Float, h: Float, d: Float) {
        if (w == width && h == height && d == density) return
        width = w
        height = h
        density = d
        if (w <= 0f || h <= 0f) {
            ready = false
            return
        }
        cell = CELL_DP * d
        cols = ceil(w / cell).toInt() + 1
        rows = ceil(h / cell).toInt() + 1
        noiseScale = NOISE_SPAN / min(w, h)
        val n = cols * rows
        heights = FloatArray(n)
        bumpField = FloatArray(n)
        pixels = IntArray(n)
        tint = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        // Pixel centres line up with grid nodes so bilinear filtering interpolates between them.
        val half = cell * 0.5f
        tintDst.set(-half, -half, cols * cell - half, rows * cell - half)
        vignette = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0x33000000), Color(0xB3020406)),
            center = Offset(w * 0.5f, h * 0.45f),
            radius = max(w, h) * 0.75f,
        )
        for (k in 0 until LEVELS * TIERS) {
            val index = (k % LEVELS) % INDEX_EVERY == INDEX_EVERY - 1
            linePaints[k].strokeWidth = (if (index) INDEX_LINE_DP else LINE_DP) * d
            haloPaints[k].strokeWidth = (if (index) HALO_DP * 1.3f else HALO_DP) * d
        }
        counts.fill(0)
        ready = true
    }

    fun step(dt: Float, px: Float, py: Float, pressed: Boolean, beat: Float) {
        time += dt
        stepBumps(dt, px, py, pressed)
        computeField(BEAT_LIFT * beat)
        extractContours()
        updateTint()
        updatePaints(1f + BEAT_GLOW * beat)
    }

    private fun stepBumps(dt: Float, px: Float, py: Float, pressed: Boolean) {
        if (pressed) {
            if (!wasPressed || active < 0) {
                active = spawn(px, py, 0f, SIGMA_START_DP * density)
                trailX = px
                trailY = py
            } else {
                val follow = 1f - exp(-dt * FOLLOW_RATE)
                bx[active] += (px - bx[active]) * follow
                by[active] += (py - by[active]) * follow
                val dx = bx[active] - trailX
                val dy = by[active] - trailY
                val spacing = TRAIL_SPACING_DP * density
                if (dx * dx + dy * dy > spacing * spacing) {
                    spawn(bx[active], by[active], amp[active] * 0.85f, sigma[active])
                    trailX = bx[active]
                    trailY = by[active]
                }
            }
        } else {
            active = -1
        }
        wasPressed = pressed

        val grow = 1f - exp(-dt / BUMP_GROW_TAU)
        val spread = 1f - exp(-dt / SIGMA_GROW_TAU)
        val decay = exp(-dt / BUMP_DECAY_TAU)
        val sigmaMax = SIGMA_MAX_DP * density
        for (q in 0 until BUMPS) {
            if (q == active) {
                amp[q] += (BUMP_PEAK - amp[q]) * grow
                sigma[q] += (sigmaMax - sigma[q]) * spread
            } else if (amp[q] > 0f) {
                amp[q] *= decay
                sigma[q] += dt * 18f * density // relaxing hills slump outward a little
                if (amp[q] < 0.002f) amp[q] = 0f
            }
            val s = sigma[q]
            inv2s2[q] = if (s > 0f) 1f / (2f * s * s) else 0f
            cut2[q] = 9f * s * s
        }
    }

    /** Places a bump in a free slot, or recycles the weakest one that isn't under the finger. */
    private fun spawn(x: Float, y: Float, a: Float, s: Float): Int {
        var slot = -1
        var weakest = Float.MAX_VALUE
        for (q in 0 until BUMPS) {
            if (q == active) continue
            if (amp[q] < weakest) {
                weakest = amp[q]
                slot = q
            }
        }
        bx[slot] = x
        by[slot] = y
        amp[slot] = a
        sigma[slot] = s
        return slot
    }

    /** [lift] raises the whole terrain slightly (0 without music), so every contour ring widens. */
    private fun computeField(lift: Float) {
        val z = time * MORPH_SPEED
        val sx = seedX + time * SCROLL_X
        val sy = seedY + time * SCROLL_Y
        val h = heights
        val bf = bumpField
        var anyBump = false
        for (q in 0 until BUMPS) if (amp[q] > 0f) anyBump = true
        for (j in 0 until rows) {
            val py = j * cell
            val ny = py * noiseScale + sy
            val row = j * cols
            for (i in 0 until cols) {
                val px = i * cell
                val base = terrain(px * noiseScale + sx, ny, z)
                var b = 0f
                if (anyBump) {
                    // Combine bumps as 1 - prod(1 - g) so overlapping hills never exceed the peak.
                    var keep = 1f
                    for (q in 0 until BUMPS) {
                        val a = amp[q]
                        if (a <= 0f) continue
                        val dx = px - bx[q]
                        val dy = py - by[q]
                        val d2 = dx * dx + dy * dy
                        if (d2 > cut2[q]) continue
                        keep *= 1f - a * exp(-d2 * inv2s2[q])
                    }
                    b = 1f - keep
                }
                bf[row + i] = b
                h[row + i] = base + (1f - base) * b + lift
            }
        }
    }

    private fun extractContours() {
        counts.fill(0)
        val h = heights
        val bf = bumpField
        for (j in 0 until rows - 1) {
            val row = j * cols
            cy = j * cell
            for (i in 0 until cols - 1) {
                val i0 = row + i
                v0 = h[i0]
                v1 = h[i0 + 1]
                v2 = h[i0 + cols + 1]
                v3 = h[i0 + cols]
                val lo = min(min(v0, v1), min(v2, v3))
                val hi = max(max(v0, v1), max(v2, v3))
                val kStart = max(0, ceil(lo / LEVEL_STEP - 1f).toInt())
                val kEnd = min(LEVELS - 1, floor(hi / LEVEL_STEP - 1f).toInt())
                if (kStart > kEnd) continue
                cx = i * cell
                val bc = (bf[i0] + bf[i0 + 1] + bf[i0 + cols + 1] + bf[i0 + cols]) * 0.25f
                val tier = if (bc > HOT_THRESHOLD) 2 else if (bc > WARM_THRESHOLD) 1 else 0
                for (k in kStart..kEnd) {
                    bucket = tier * LEVELS + k
                    march((k + 1) * LEVEL_STEP)
                }
            }
        }
    }

    /** Marching squares for the current cell; corners 0..3 are TL, TR, BR, BL. */
    private fun march(level: Float) {
        val b0 = v0 >= level
        val b1 = v1 >= level
        val b2 = v2 >= level
        val b3 = v3 >= level
        val mask = (if (b0) 1 else 0) or (if (b1) 2 else 0) or (if (b2) 4 else 0) or (if (b3) 8 else 0)
        if (mask == 0 || mask == 15) return
        if (mask == 5 || mask == 10) {
            // Saddle: the cell-centre average decides which diagonal pair is connected.
            val centre = (v0 + v1 + v2 + v3) * 0.25f >= level
            if (centre == b0) {
                segment(0, 1, level)
                segment(2, 3, level)
            } else {
                segment(3, 0, level)
                segment(1, 2, level)
            }
            return
        }
        var e0 = -1
        var e1 = -1
        if (b0 != b1) e0 = 0
        if (b1 != b2) { if (e0 < 0) e0 = 1 else e1 = 1 }
        if (b3 != b2) { if (e0 < 0) e0 = 2 else e1 = 2 }
        if (b0 != b3) { if (e0 < 0) e0 = 3 else e1 = 3 }
        if (e0 >= 0 && e1 >= 0) segment(e0, e1, level)
    }

    // Edges: 0 top (0-1), 1 right (1-2), 2 bottom (3-2), 3 left (0-3).
    private fun edgeX(e: Int, level: Float): Float = when (e) {
        0 -> cx + cell * (level - v0) / (v1 - v0)
        1 -> cx + cell
        2 -> cx + cell * (level - v3) / (v2 - v3)
        else -> cx
    }

    private fun edgeY(e: Int, level: Float): Float = when (e) {
        0 -> cy
        1 -> cy + cell * (level - v1) / (v2 - v1)
        2 -> cy + cell
        else -> cy + cell * (level - v0) / (v3 - v0)
    }

    private fun segment(ea: Int, eb: Int, level: Float) {
        var buf = lines[bucket]
        val n = counts[bucket]
        if (n + 4 > buf.size) {
            buf = buf.copyOf(buf.size * 2)
            lines[bucket] = buf
        }
        buf[n] = edgeX(ea, level)
        buf[n + 1] = edgeY(ea, level)
        buf[n + 2] = edgeX(eb, level)
        buf[n + 3] = edgeY(eb, level)
        counts[bucket] = n + 4
    }

    private fun paletteMix(): Float =
        0.5f - 0.5f * cos(time * (2f * PI.toFloat() / PALETTE_PERIOD))

    /** Writes the elevation colour at [t] (0..1) into [rgb]. */
    private fun palette(t: Float, m: Float) {
        val x = t.coerceIn(0f, 1f)
        var s = 0
        while (s < STOPS.size - 2 && x > STOPS[s + 1]) s++
        val f = ((x - STOPS[s]) / (STOPS[s + 1] - STOPS[s])).coerceIn(0f, 1f)
        val o0 = s * 3
        val o1 = o0 + 3
        for (c in 0 until 3) {
            val a = mixF(PALETTE_A[o0 + c], PALETTE_A[o1 + c], f)
            val b = mixF(PALETTE_B[o0 + c], PALETTE_B[o1 + c], f)
            rgb[c] = mixF(a, b, m)
        }
    }

    private fun updateTint() {
        val bmp = tint ?: return
        val m = paletteMix()
        val h = heights
        val bf = bumpField
        for (idx in 0 until cols * rows) {
            val e = h[idx].coerceIn(0f, 1f)
            palette(e, m)
            val f = 0.035f + 0.075f * e * e + 0.11f * bf[idx]
            pixels[idx] = argb(
                1f,
                0.012f + rgb[0] * f,
                0.020f + rgb[1] * f,
                0.027f + rgb[2] * f,
            )
        }
        bmp.setPixels(pixels, 0, cols, 0, 0, cols, rows)
    }

    /** [glow] scales the line alpha (exactly 1 without music). */
    private fun updatePaints(glow: Float) {
        val m = paletteMix()
        for (k in 0 until LEVELS) {
            val t = (k + 1f) / (LEVELS + 1f)
            val index = k % INDEX_EVERY == INDEX_EVERY - 1
            palette(t, m)
            val r = rgb[0]
            val g = rgb[1]
            val b = rgb[2]
            val calm = (if (index) 0.6f else 0.26f + 0.16f * t) * glow
            linePaints[k].color = argb(calm, r, g, b)
            // Warm and hot lines lift toward cream and gain alpha.
            val w1 = 0.25f
            linePaints[LEVELS + k].color = argb(
                min(1f, calm + 0.22f),
                mixF(r, 1f, w1), mixF(g, 0.97f, w1), mixF(b, 0.9f, w1),
            )
            haloPaints[LEVELS + k].color = argb(
                if (index) 0.09f else 0.06f,
                mixF(r, 1f, w1), mixF(g, 0.97f, w1), mixF(b, 0.9f, w1),
            )
            val w2 = 0.5f
            linePaints[2 * LEVELS + k].color = argb(
                if (index) 1f else min(1f, 0.88f * glow),
                mixF(r, 1f, w2), mixF(g, 0.97f, w2), mixF(b, 0.9f, w2),
            )
            haloPaints[2 * LEVELS + k].color = argb(
                if (index) 0.2f else 0.14f,
                mixF(r, 1f, w2), mixF(g, 0.97f, w2), mixF(b, 0.9f, w2),
            )
        }
    }

    fun draw(scope: DrawScope) {
        scope.drawRect(BACKDROP)
        if (!ready) return
        val bmp = tint
        val vig = vignette
        scope.drawIntoCanvas { canvas ->
            if (bmp != null) canvas.nativeCanvas.drawBitmap(bmp, null, tintDst, tintPaint)
        }
        if (vig != null) scope.drawRect(vig)
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (k in 0 until LEVELS) {
                val n = counts[k]
                if (n > 0) nc.drawLines(lines[k], 0, n, linePaints[k])
            }
            for (k in LEVELS until LEVELS * TIERS) {
                val n = counts[k]
                if (n > 0) nc.drawLines(lines[k], 0, n, haloPaints[k])
            }
            for (k in LEVELS until LEVELS * TIERS) {
                val n = counts[k]
                if (n > 0) nc.drawLines(lines[k], 0, n, linePaints[k])
            }
        }
    }
}

private fun strokePaint(additive: Boolean): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    if (additive) xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
}
