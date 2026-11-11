package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * A glass triangular prism in a black void. A white beam enters from the pointer's side and the
 * prism throws a wide, flowing rainbow fan out of its far side. The fan colour is a direct port
 * of the v-prism reference scene: Alan Zucconi's spectral fit divided by JuliaPoo's iridescence
 * blob field, with the reference's base tuning baked in (rainbow glow 2.5, fan radii 0..0.5,
 * beam ray (-10, -.05) -> (0, 0)).
 *
 * Per-frame cost is fixed: the fan mesh, its index and colour buffers are allocated once and
 * drawn straight through the framework canvas, every gradient shader is cached (unit-sized glows
 * are positioned by the canvas transform, the rest are rebuilt only when their geometry moves),
 * and animation time is measured from the wallpaper's start and wrapped to the animation's exact
 * periods so float precision never degrades however long the launcher has been running.
 */
@Composable
fun PrismWallpaper(modifier: Modifier = Modifier) {
    val aim = remember { PrismAim() }
    val elapsedNanos = remember { mutableLongStateOf(0L) }
    val fan = remember { FanMesh() }
    val cache = remember { PrismCache() }

    LaunchedEffect(Unit) {
        var start = 0L
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                aim.update(dt)
                elapsedNanos.longValue = now - start
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val elapsed = elapsedNanos.longValue
        // Fan flow repeats exactly every FAN_PERIOD; the bokeh drift every DOT_PERIOD.
        val tFan = ((elapsed % FAN_PERIOD_NANOS) / 1e9).toFloat()
        val tDots = (DOT_TIME_BASE + (elapsed / 1e9) % DOT_PERIOD).toFloat()
        drawRect(Color.Black)

        val minDim = minOf(w, h)
        val unit = minDim / 7.5f
        val center = Offset(w * 0.5f, h * 0.6f)
        val diag = hypot(w, h)
        aim.center = center

        // Keep the prism in a fixed pose; the ray and fan still respond to touch.
        val tilt = -6f * DEG
        val top = rot(Offset(-0.02f, -1.0f) * unit, tilt)
        val bottomLeft = rot(Offset(-0.95f, 0.62f) * unit, tilt)
        val bottomRight = rot(Offset(0.95f, 0.58f) * unit, tilt)
        val vTop = center + top
        val vBl = center + bottomLeft
        val vBr = center + bottomRight

        // The beam always aims through the prism, from the pointer side or the default pose.
        val source = center + aim.dir * (diag * 1.5f)
        val through = normalize(center - source)
        val hit = triangleIntersection(through, source, vTop, vBl, vBr)
        val entry = if (hit.isSpecified) hit else center

        // Fan re-aims like the reference: straight through, tilted by the entry face.
        val j0 = angleUp(through)
        val faceIn = faceNormal(entry - center, top, bottomLeft, bottomRight)
        val bend = BEND_DEG * DEG * sin(2f * (j0 - angleUp(faceIn)))
        aim.smoothFanAngle(j0 + bend)

        // Faint ambient glow lifting the prism off the black (reference point lights).
        drawGlow(cache.ambient, center, minDim * 0.5f)

        // The rainbow fan and its hall of mirrors at the screen edges; the apex hides behind
        // the prism drawn on top of it. Colours are evaluated once here and every copy below
        // reuses them.
        fan.ensure(w, h)
        fan.updateColors(tFan)
        drawFanReflections(fan, center, aim.fanAngle, w, h)

        // Wide bloom wash along the fan axis.
        val axis = Offset(cos(aim.fanAngle), -sin(aim.fanAngle))
        drawGlow(cache.wash, center + axis * (diag * 0.26f), diag * 0.34f, blendMode = BlendMode.Plus)

        // The beam.
        drawBeam(cache, source, entry, unit, aim.strength)

        // The prism.
        drawPrism(cache, center, vTop, vBl, vBr, unit, entry, aim.fanAngle)

        // Contact point: glows, vertical flare spike and drifting bokeh dots.
        drawContact(cache, entry, unit, minDim, tDots, aim.strength)
    }
}

private val DEG = (Math.PI / 180.0).toFloat()
private const val BEND_DEG = 14.5f

/** Longest frame step fed to the smoothing, so one hitch cannot jolt the aim. */
private const val MAX_DT = 0.05f

/**
 * The fan colour depends on time only through t / 10 inside [Spectrum]'s hue phases, whose
 * coefficients (2.4, 3.2, 1 / 2.8) all complete whole turns when t / 10 advances by 70: the flow is
 * exactly periodic in 700 s, so wrapping there is seamless and keeps t small.
 */
private const val FAN_PERIOD_NANOS = 700_000_000_000L

/** Every bokeh dot's drift frequency is a multiple of 1/8 rad/s, so the chain repeats in 16π s. */
private const val DOT_PERIOD = 16.0 * Math.PI

/** Keeps the dots' atan2(t·s) term settled at its plateau, as it was under absolute frame time. */
private const val DOT_TIME_BASE = 1000.0

/**
 * Pointer state from the launcher's hit chain. Wallpaper layers sit behind the home component,
 * which fills the screen, so they never join a hit path themselves; the scaffold's home root
 * feeds touches here and the active wallpaper reads them each frame.
 */
object WallpaperPointer {
    @Volatile
    var x = 0f
    @Volatile
    var y = 0f
    @Volatile
    var pressed = false
    @Volatile
    var consumed = false

    private var activeGestureStart: Offset? = null
    private var activeGestureConsumed = false
    private val gestureFlow = MutableSharedFlow<WallpaperGesture>(extraBufferCapacity = 16)
    internal val gestures = gestureFlow.asSharedFlow()

    fun set(pos: Offset, isPressed: Boolean, isConsumed: Boolean = false) {
        if (isPressed) {
            if (!pressed) {
                activeGestureStart = pos
                activeGestureConsumed = isConsumed
            } else if (isConsumed) {
                activeGestureConsumed = true
            }
        } else if (pressed) {
            if (isConsumed) activeGestureConsumed = true
            val start = activeGestureStart
            if (start != null) gestureFlow.tryEmit(WallpaperGesture(start, pos, activeGestureConsumed))
            activeGestureStart = null
            activeGestureConsumed = false
        }
        x = pos.x
        y = pos.y
        pressed = isPressed
        consumed = isConsumed
    }
}

internal data class WallpaperGesture(val start: Offset, val end: Offset, val consumed: Boolean)

/** Tracks where the beam comes from and holds the last aimed direction on release. */
private class PrismAim {
    var center = Offset.Zero
    var dir = DEFAULT_DIR
        private set
    var fanAngle = 0.18f
        private set
    var strength = 1f
        private set

    private var target = DEFAULT_DIR

    /** The light aims from outside the screen toward the prism, tracking the finger live. */
    fun update(dt: Float) {
        if (WallpaperPointer.pressed && center != Offset.Zero) {
            target = normalize(Offset(WallpaperPointer.x, WallpaperPointer.y) - center)
        }
        val s = 1f - exp(-dt * 24f) // ~40 ms: real-time follow with a touch of smoothing
        dir = normalize(dir + (target - dir) * s)
        strength += ((if (WallpaperPointer.pressed) 1.25f else 1f) - strength) * (1f - exp(-dt * 5f))
    }

    fun smoothFanAngle(target: Float) {
        fanAngle += (target - fanAngle) * 0.35f
    }

    companion object {
        val DEFAULT_DIR = Offset(-1f, 0.005f)
    }
}

private fun rot(p: Offset, a: Float): Offset {
    val c = cos(a)
    val s = sin(a)
    return Offset(p.x * c - p.y * s, p.x * s + p.y * c)
}

private fun normalize(o: Offset): Offset {
    val len = o.getDistance()
    return if (len < 1e-4f) Offset(-1f, 0f) else Offset(o.x / len, o.y / len)
}

/** Angle of [o] in a y-up frame, radians. */
private fun angleUp(o: Offset): Float = atan2(-o.y, o.x)

/** [a] in radians, wrapped to (-π, π]. */
private fun wrapPi(a: Float): Float {
    val turn = 360f * DEG
    var x = a
    while (x > 180f * DEG) x -= turn
    while (x <= -180f * DEG) x += turn
    return x
}

/* -------------------------------- fan colouring -------------------------------- */

private object Spectrum {
    private val c1 = floatArrayOf(3.54585104f, 2.93225262f, 2.41593945f)
    private val x1 = floatArrayOf(0.69549072f, 0.49228336f, 0.27699880f)
    private val y1 = floatArrayOf(0.02312639f, 0.15225084f, 0.52607955f)
    private val c2 = floatArrayOf(3.90307140f, 3.21182957f, 3.96587128f)
    private val x2 = floatArrayOf(0.11748627f, 0.86755042f, 0.66077860f)
    private val y2 = floatArrayOf(0.84897130f, 0.88445281f, 0.73949448f)

    const val LUM = 0.05064f
    const val LUMA = 0.01070f

    /** Alan Zucconi's six-Gaussian visible spectrum fit. */
    fun spectral6(w: Float, out: FloatArray) {
        val x = ((w - 400f) / 300f).coerceIn(0f, 1f)
        for (i in 0..2) {
            val d1 = c1[i] * (x - x1[i])
            val d2 = c2[i] * (x - x2[i])
            out[i] = (1f - d1 * d1 - y1[i]).coerceIn(0f, 1f) + (1f - d2 * d2 - y2[i]).coerceIn(0f, 1f)
        }
    }

    /** One channel of JuliaPoo's physhue: a smooth triangle wave of [v]. */
    fun hue(v: Float): Float {
        val w = (v % 1f + 1f) % 1f
        val s = abs(w * 2f - 1f).coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    fun smoothstep(a: Float, b: Float, x: Float): Float {
        val tt = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return tt * tt * (3f - 2f * tt)
    }

    private const val SRGB_N = 4096
    private const val SRGB_EXACT_BELOW = 1f / 256f
    private val srgbLut = FloatArray(SRGB_N + 1) { (it / SRGB_N.toFloat()).pow(1f / 2.2f) }

    /**
     * Linear [v] to an 8-bit sRGB-ish channel, as `Color(red = v^(1/2.2))` packs it. A table
     * replaces the pow call (interpolation error < 1e-5); the steep toe just above 0 is exact.
     */
    fun srgbByte(v: Float): Int {
        val x = v.coerceIn(0f, 1f)
        val g = if (x < SRGB_EXACT_BELOW) {
            x.pow(1f / 2.2f)
        } else {
            val p = x * SRGB_N
            val i = p.toInt().coerceAtMost(SRGB_N - 1)
            val lo = srgbLut[i]
            lo + (srgbLut[i + 1] - lo) * (p - i)
        }
        return (g * 255f + 0.5f).toInt()
    }
}

private const val RAINBOW_GLOW = 2.5f // reference: rainbowIntensity
private const val K_MAX = 1.35f // spectral band edges clamp at |k| = 1
private const val UV_Y0 = 0.03f
private const val UV_Y1 = 1.12f
private const val NK = 97
private const val NR = 26
// Give the existing fan enough spatial reach to cross several mirrored screen cells. This scales
// only the mesh positions; the diffraction and spectrum calculations remain unchanged.
private const val FAN_PATH_REACH = 2.5f

/**
 * The fan as a single triangle mesh with per-vertex colours straight from the reference shader
 * formula, so the blob field interpolates smoothly (as in the reference) instead of through
 * gradient stops. Everything in that formula that does not depend on time (the spectral base
 * colour, the radial falloff, the Fresnel terms) is evaluated once per vertex up front; per frame
 * only the flowing hue phases are recomputed into a preallocated colour array, and every mirror
 * copy draws the same position, colour and index buffers through the framework canvas with no
 * per-frame allocation.
 */
private class FanMesh {
    private val count = NK * NR
    private val positions = FloatArray(count * 2)
    // Sized to the float count, like Compose's padding for pre-Q software canvases.
    private val argb = IntArray(count * 2)
    private val indices = ShortArray((NK - 1) * (NR - 1) * 6)

    // Time-independent per-vertex terms of Spectrum's fan colour.
    private val lit = BooleanArray(count)
    private val baseR = FloatArray(count)
    private val baseG = FloatArray(count)
    private val baseB = FloatArray(count)
    private val fresnel = FloatArray(count)
    private val fresnel12 = FloatArray(count)
    private val hueShift = FloatArray(count)
    private val thickness0 = FloatArray(count)

    private val cornerAngles = FloatArray(4)
    private val paint = Paint().asFrameworkPaint()
    private var cachedW = -1f
    private var cachedH = -1f

    /** Reach of the fan wedge from its apex along the fan axis, and the wedge half-angle. */
    var reach = 0f
        private set
    var halfSpread = 0f
        private set

    init {
        var n = 0
        for (i in 0 until NK - 1) {
            for (j in 0 until NR - 1) {
                val a = (i * NR + j).toShort()
                val b = ((i + 1) * NR + j).toShort()
                val c = ((i + 1) * NR + j + 1).toShort()
                val d = (i * NR + j + 1).toShort()
                indices[n++] = a; indices[n++] = b; indices[n++] = c
                indices[n++] = a; indices[n++] = c; indices[n++] = d
            }
        }
        val c = FloatArray(3)
        for (i in 0 until NK) {
            val k = -K_MAX + 2f * K_MAX * i / (NK - 1)
            val a = atan2(k * 0.5f, 1f) * 10f
            Spectrum.spectral6((k + 0.5f) * 300f + 400f + a, c)
            val bright = Spectrum.smoothstep(0f, 0.5f, c[0] + c[1] + c[2])
            for (j in 0 until NR) {
                val uvY = UV_Y0 + (UV_Y1 - UV_Y0) * j / (NR - 1)
                val v = i * NR + j
                val l = 1f - Spectrum.smoothstep(0f, 1f, uvY)
                val scale = l * bright * RAINBOW_GLOW
                lit[v] = scale > 0f
                baseR[v] = c[0] / 20f * scale
                baseG[v] = c[1] / 20f * scale
                baseB[v] = c[2] / 20f * scale
                val nxv = cos(k * uvY * 0.7854f)
                val f = (1f - nxv) * (1f - nxv)
                fresnel[v] = f
                fresnel12[v] = f * f * f * f * f * f * f * f * f * f * f * f
                hueShift[v] = (nxv - 1f) * 0.5f
                thickness0[v] = 1f - uvY
            }
        }
    }

    fun ensure(w: Float, h: Float) {
        if (w == cachedW && h == cachedH) return
        cachedW = w
        cachedH = h
        val q = (hypot(w, h) + 1.5f * minOf(w, h) / 7.5f) * FAN_PATH_REACH
        reach = UV_Y1 * q / 2f
        halfSpread = atan(K_MAX * UV_Y1 / 2f)
        for (i in 0 until NK) {
            val k = -K_MAX + 2f * K_MAX * i / (NK - 1)
            for (j in 0 until NR) {
                val uvY = UV_Y0 + (UV_Y1 - UV_Y0) * j / (NR - 1)
                val p = (i * NR + j) * 2
                positions[p] = uvY * q / 2f
                positions[p + 1] = -k * uvY * q / 4f
            }
        }
    }

    /**
     * Rebuild the flowing vertex colours for flow time [t]; once per frame, shared by copies.
     * This is the reference formula (spectral base / iridescence blob field, soft-edged and
     * gamma-encoded) with its time-independent parts read from the per-vertex tables.
     */
    fun updateColors(t: Float) {
        val tt = t / 10f
        for (v in 0 until count) {
            if (!lit[v]) {
                argb[v] = 0
                continue
            }
            val thickness = thickness0[v] + tt
            val ratio = thickness / 2.8f
            val dp = hueShift[v]
            val ha = thickness * 2.4f + dp
            val hb = thickness * 3.2f + 0.1f + dp
            val f = fresnel[v]
            val f12 = fresnel12[v]

            var a = Spectrum.hue(ha)
            var b = Spectrum.hue(hb)
            val h0 = a + (b - a) * f
            a = Spectrum.hue(ha + ratio)
            b = Spectrum.hue(hb + ratio)
            val h1 = a + (b - a) * f
            a = Spectrum.hue(ha + 2f * ratio)
            b = Spectrum.hue(hb + 2f * ratio)
            val h2 = a + (b - a) * f

            val ir0 = ((h0 * Spectrum.LUM + 0.9639f * Spectrum.LUMA) * 3f + f12) * 0.49639f
            val ir1 = ((h1 * Spectrum.LUM + 0.78252f * Spectrum.LUMA) * 3f + f12) * 0.78252f
            val ir2 = ((h2 * Spectrum.LUM + 0.18723f * Spectrum.LUMA) * 3f + f12) * 0.8723f

            val r = (baseR[v] / ir0).coerceAtLeast(0f)
            val g = (baseG[v] / ir1).coerceAtLeast(0f)
            val bl = (baseB[v] / ir2).coerceAtLeast(0f)
            val edge = Spectrum.smoothstep(0.05f, 1f, r + g + bl)
            if (edge <= 0f) {
                argb[v] = 0
                continue
            }
            argb[v] = (0xFF shl 24) or
                (Spectrum.srgbByte(r * edge) shl 16) or
                (Spectrum.srgbByte(g * edge) shl 8) or
                Spectrum.srgbByte(bl * edge)
        }
    }

    /** Draw the fan with its apex at [center]: one drawVertices over the shared buffers. */
    fun draw(scope: DrawScope, center: Offset) {
        scope.translate(center.x, center.y) {
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawVertices(
                    android.graphics.Canvas.VertexMode.TRIANGLES,
                    positions.size, positions, 0,
                    null, 0,
                    argb, 0,
                    indices, 0, indices.size,
                    paint,
                )
            }
        }
    }

    /**
     * Whether the fan wedge from [apex] along [axis] - [reach] far, [halfSpread] wide - can reach
     * the screen. Conservative: anything that grazes the screen passes, so a mirror copy is only
     * dropped when it provably shows nothing there.
     */
    fun wedgeReachesScreen(apex: Offset, axis: Offset): Boolean {
        val dx = maxOf(maxOf(-apex.x, 0f), apex.x - cachedW)
        val dy = maxOf(maxOf(-apex.y, 0f), apex.y - cachedH)
        if (dx == 0f && dy == 0f) return true // apex on the screen: the wedge always covers some
        if (hypot(dx, dy) > reach) return false
        // The screen is convex and the apex outside it, so as seen from the apex it subtends one
        // arc below half a turn, delimited by its corners. Drop the copy when that arc misses the
        // wedge's [-halfSpread, halfSpread] about the axis.
        val theta = atan2(axis.y, axis.x)
        val a = cornerAngles
        a[0] = wrapPi(atan2(-apex.y, -apex.x) - theta)
        a[1] = wrapPi(atan2(-apex.y, cachedW - apex.x) - theta)
        a[2] = wrapPi(atan2(cachedH - apex.y, cachedW - apex.x) - theta)
        a[3] = wrapPi(atan2(cachedH - apex.y, -apex.x) - theta)
        a.sort()
        // The widest circular gap between corners is the side the screen leaves empty; the arc
        // it subtends is the rest of the turn, starting just past that gap.
        val turn = 360f * DEG
        var arcStart = a[0]
        var gap = a[0] + turn - a[3]
        for (i in 0..2) {
            val g = a[i + 1] - a[i]
            if (g > gap) {
                gap = g
                arcStart = a[i + 1]
            }
        }
        val arcHalf = (turn - gap) / 2f
        return abs(wrapPi(arcStart + arcHalf)) <= halfSpread + arcHalf
    }
}

/* -------------------------------- fan mirrors -------------------------------- */

/**
 * Draw the finite set of mirrored images whose fan wedges can reach the screen. Image tiles are
 * enumerated directly so multiple bounces cannot create recursive loops or duplicate paths; the
 * tile count is bounded by the fan's fixed reach.
 */
private fun DrawScope.drawFanReflections(
    fan: FanMesh,
    center: Offset,
    fanAngle: Float,
    w: Float,
    h: Float,
) {
    val maxTileX = ceil(fan.reach / w).toInt() + 1
    val maxTileY = ceil(fan.reach / h).toInt() + 1
    val ca = cos(fanAngle)
    val sa = sin(fanAngle)
    val degrees = -fanAngle / DEG
    clipRect(0f, 0f, w, h) {
        for (tileY in -maxTileY..maxTileY) {
            val sy = if (tileY % 2 == 0) 1 else -1
            val by = if (sy == 1) tileY * h else (tileY + 1) * h
            for (tileX in -maxTileX..maxTileX) {
                val sx = if (tileX % 2 == 0) 1 else -1
                val bx = if (sx == 1) tileX * w else (tileX + 1) * w
                val imageCenter = Offset(sx * center.x + bx, sy * center.y + by)
                val imageAxis = Offset(sx * ca, -sy * sa)
                if (!fan.wedgeReachesScreen(imageCenter, imageAxis)) continue

                translate(bx, by) {
                    scale(sx.toFloat(), sy.toFloat(), Offset.Zero) {
                        rotate(degrees, center) {
                            fan.draw(this, center)
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------------- cached paints -------------------------------- */

/** Strength never exceeds this (the pressed target), so glows bake it in and scale down by alpha. */
private const val MAX_STRENGTH = 1.25f

/** A radial gradient of unit radius at the origin; [drawGlow] places and sizes it. */
private fun unitGlow(colors: List<Color>): Brush =
    Brush.radialGradient(colors = colors, center = Offset.Zero, radius = 1f)

/**
 * Draw the unit [brush] as a glow of [radius] at [center]. The canvas transform does the
 * positioning, so the brush's shader is built once and reused every frame.
 */
private fun DrawScope.drawGlow(
    brush: Brush,
    center: Offset,
    radius: Float,
    alpha: Float = 1f,
    blendMode: BlendMode = DrawScope.DefaultBlendMode,
) {
    if (radius <= 0f || alpha <= 0f) return
    withTransform({
        translate(center.x, center.y)
        scale(radius, radius, Offset.Zero)
    }) {
        drawCircle(brush = brush, radius = 1f, center = Offset.Zero, alpha = alpha, blendMode = blendMode)
    }
}

/** Writes a..f into [key] and reports whether anything differed from what it held. */
private fun keyChanged(
    key: FloatArray,
    a: Float, b: Float, c: Float, d: Float, e: Float = 0f, f: Float = 0f,
): Boolean {
    if (key[0] == a && key[1] == b && key[2] == c && key[3] == d && key[4] == e && key[5] == f) {
        return false
    }
    key[0] = a; key[1] = b; key[2] = c; key[3] = d; key[4] = e; key[5] = f
    return true
}

/**
 * Every path and gradient brush the scene draws, built once (unit glows) or rebuilt only when
 * the geometry they depend on moves, so a resting frame allocates nothing.
 */
private class PrismCache {
    val ambient = unitGlow(listOf(Color(0xFF141D33).copy(alpha = 0.3f), Color.Transparent))
    val wash = unitGlow(listOf(Color(0xFF3A5A6E).copy(alpha = 0.10f), Color.Transparent))
    val reflection = unitGlow(listOf(Color(0xFF3A6E8E).copy(alpha = 0.18f), Color.Transparent))
    val contactHalo = unitGlow(
        listOf(
            Color.White.copy(alpha = 0.5f * MAX_STRENGTH),
            Color.White.copy(alpha = 0.1f * MAX_STRENGTH),
            Color.Transparent,
        )
    )
    val contactCore = unitGlow(listOf(Color.White.copy(alpha = 0.75f * MAX_STRENGTH), Color.Transparent))
    val dots = Array(dotColors.size) {
        unitGlow(
            listOf(
                dotColors[it].copy(alpha = 0.12f * MAX_STRENGTH),
                dotColors[it].copy(alpha = 0.05f * MAX_STRENGTH),
                Color.Transparent,
            )
        )
    }

    // Beam: haze quad and two gradient strokes, keyed by its end points and strength.
    val haze = Path()
    var hazeBrush: Brush = SolidColor(Color.Transparent)
        private set
    var wideBrush: Brush = SolidColor(Color.Transparent)
        private set
    var coreBrush: Brush = SolidColor(Color.Transparent)
        private set
    private val beamKey = FloatArray(6) { Float.NaN }

    fun updateBeam(source: Offset, entry: Offset, unit: Float, strength: Float, perp: Offset) {
        if (!keyChanged(beamKey, source.x, source.y, entry.x, entry.y, unit, strength)) return
        haze.rewind()
        haze.moveTo(source.x + perp.x * unit * 1.1f, source.y + perp.y * unit * 1.1f)
        haze.lineTo(entry.x + perp.x * unit * 0.45f, entry.y + perp.y * unit * 0.45f)
        haze.lineTo(entry.x - perp.x * unit * 0.45f, entry.y - perp.y * unit * 0.45f)
        haze.lineTo(source.x - perp.x * unit * 1.1f, source.y - perp.y * unit * 1.1f)
        haze.close()
        hazeBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = 0.05f * strength),
                Color.White.copy(alpha = 0.05f * strength),
            ),
            start = source,
            end = entry,
        )
        wideBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.3f * strength),
                Color.White.copy(alpha = 0.55f * strength),
            ),
            start = source,
            end = entry,
        )
        coreBrush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.35f * strength),
                Color.White.copy(alpha = 0.9f * strength),
            ),
            start = source,
            end = entry,
        )
    }

    // Prism body: fixed pose, so keyed only by its centre and size.
    val body = Path()
    val rightFace = Path()
    val sliver = Path()
    var bodyBrush: Brush = SolidColor(Color.Transparent)
        private set
    var rightBrush: Brush = SolidColor(Color.Transparent)
        private set
    var sliverBrush: Brush = SolidColor(Color.Transparent)
        private set
    private val prismKey = FloatArray(6) { Float.NaN }

    fun updatePrism(t: Offset, bl: Offset, br: Offset, baseMid: Offset, blF: Offset, brF: Offset) {
        if (!keyChanged(prismKey, t.x, t.y, bl.x, bl.y, br.x, br.y)) return
        body.rewind()
        body.moveTo(t.x, t.y)
        body.lineTo(bl.x, bl.y)
        body.lineTo(br.x, br.y)
        body.close()
        rightFace.rewind()
        rightFace.moveTo(t.x, t.y)
        rightFace.lineTo(baseMid.x, baseMid.y)
        rightFace.lineTo(br.x, br.y)
        rightFace.close()
        sliver.rewind()
        sliver.moveTo(bl.x, bl.y)
        sliver.lineTo(br.x, br.y)
        sliver.lineTo(brF.x, brF.y)
        sliver.lineTo(blF.x, blF.y)
        sliver.close()
        bodyBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF0D1119), Color(0xFF05070C)),
            start = t,
            end = bl,
        )
        rightBrush = Brush.linearGradient(
            colors = listOf(Color(0xFF131A26), Color(0xFF080B12)),
            start = t,
            end = br,
        )
        sliverBrush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0A0E15), Color(0xFF1A2230)),
            startY = bl.y,
            endY = blF.y,
        )
    }

    // Vertical flare spike through the contact point.
    val spike = Path()
    var spikeBrush: Brush = SolidColor(Color.Transparent)
        private set
    private val spikeKey = FloatArray(6) { Float.NaN }

    fun updateSpike(entry: Offset, unit: Float, spikeH: Float, strength: Float) {
        if (!keyChanged(spikeKey, entry.x, entry.y, unit, spikeH, strength)) return
        spike.rewind()
        spike.moveTo(entry.x - unit * 0.008f, entry.y - spikeH * 0.45f)
        spike.lineTo(entry.x + unit * 0.008f, entry.y - spikeH * 0.45f)
        spike.lineTo(entry.x + unit * 0.02f, entry.y + spikeH * 0.55f)
        spike.lineTo(entry.x - unit * 0.02f, entry.y + spikeH * 0.55f)
        spike.close()
        spikeBrush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = 0.5f * strength),
                Color.White.copy(alpha = 0.12f * strength),
                Color.White.copy(alpha = 0f),
            ),
            startY = entry.y - spikeH * 0.45f,
            endY = entry.y + spikeH * 0.55f,
        )
    }
}

/* -------------------------------- scene drawing -------------------------------- */

private fun DrawScope.drawBeam(cache: PrismCache, source: Offset, entry: Offset, unit: Float, strength: Float) {
    if ((entry - source).getDistance() < 1f) return
    val dir = normalize(entry - source)
    val perp = Offset(-dir.y, dir.x)
    cache.updateBeam(source, entry, unit, strength, perp)

    // Wide anamorphic haze along the beam.
    drawPath(path = cache.haze, brush = cache.hazeBrush, blendMode = BlendMode.Plus)
    drawLine(
        brush = cache.wideBrush,
        start = source,
        end = entry,
        strokeWidth = unit * 0.06f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = cache.coreBrush,
        start = source,
        end = entry,
        strokeWidth = unit * 0.012f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus,
    )
}

private fun DrawScope.drawPrism(
    cache: PrismCache,
    center: Offset,
    t: Offset,
    bl: Offset,
    br: Offset,
    unit: Float,
    e: Offset,
    fanAngle: Float,
) {
    val blF = Offset(bl.x + unit * 0.02f, bl.y + unit * 0.12f)
    val brF = Offset(br.x + unit * 0.01f, br.y + unit * 0.13f)
    val baseMid = Offset((bl.x + br.x) / 2f + unit * 0.1f, (bl.y + br.y) / 2f)
    cache.updatePrism(t, bl, br, baseMid, blF, brF)

    // Dark glass body, split by the ridge into a shaded left face and a lit right face.
    drawPath(path = cache.body, brush = cache.bodyBrush)
    drawPath(path = cache.rightFace, brush = cache.rightBrush)
    drawLine(Color(0xFFBFD8EE).copy(alpha = 0.2f), t, baseMid, unit * 0.01f)

    // Bottom facet sliver.
    drawPath(path = cache.sliver, brush = cache.sliverBrush)

    clipPath(cache.body) {
        // Env softbox reflections.
        drawLine(
            color = Color.White.copy(alpha = 0.14f),
            start = Offset(t.x - unit * 0.35f, t.y + unit * 0.55f),
            end = Offset(t.x + unit * 0.25f, t.y + unit * 0.1f),
            strokeWidth = unit * 0.02f,
            blendMode = BlendMode.Plus,
        )
        drawLine(
            color = Color.White.copy(alpha = 0.09f),
            start = Offset(bl.x + unit * 0.15f, bl.y - unit * 0.15f),
            end = Offset(br.x - unit * 0.05f, br.y - unit * 0.5f),
            strokeWidth = unit * 0.025f,
            blendMode = BlendMode.Plus,
        )
        // Rainbow refracted at the entry face.
        for (i in spectrum.indices) {
            val f = i / (spectrum.size - 1).toFloat()
            val a = fanAngle + (f - 0.5f) * 0.35f
            drawLine(
                color = spectrum[i].copy(alpha = 0.35f),
                start = e,
                end = e + Offset(cos(a), -sin(a)) * unit * 0.55f,
                strokeWidth = unit * 0.035f,
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus,
            )
        }
        // Fan reflecting along the exit side.
        val refl = Offset(br.x - unit * 0.15f, br.y - unit * 0.3f)
        drawGlow(cache.reflection, refl, unit * 0.7f, blendMode = BlendMode.Plus)
    }

    // Edges: ridge, silhouettes and the bright bottom rim.
    drawLine(Color(0xFFBFD8EE).copy(alpha = 0.35f), t, bl, unit * 0.012f)
    drawLine(Color(0xFFBFD8EE).copy(alpha = 0.25f), t, br, unit * 0.012f)
    drawLine(Color(0xFFDCEEFF).copy(alpha = 0.55f), bl, br, unit * 0.014f)
    drawLine(
        Color(0xFFDCEEFF).copy(alpha = 0.8f), blF, brF, unit * 0.016f,
        blendMode = BlendMode.Plus,
    )
    drawLine(Color(0xFFDCEEFF).copy(alpha = 0.2f), bl, blF, unit * 0.01f)
    drawLine(Color(0xFFDCEEFF).copy(alpha = 0.2f), br, brF, unit * 0.01f)
}

private fun DrawScope.drawContact(
    cache: PrismCache,
    entry: Offset,
    unit: Float,
    minDim: Float,
    t: Float,
    strength: Float,
) {
    val glowAlpha = (strength / MAX_STRENGTH).coerceIn(0f, 1f)

    // Core and glows where the beam lands.
    drawGlow(cache.contactHalo, entry, unit * 0.625f, glowAlpha, BlendMode.Plus)
    drawGlow(cache.contactCore, entry, unit * 0.075f, glowAlpha, BlendMode.Plus)
    drawCircle(
        color = Color.White.copy(alpha = (0.9f * strength).coerceAtMost(1f)),
        radius = unit * 0.02f,
        center = entry,
        blendMode = BlendMode.Plus,
    )

    // Vertical flare spike crossing the contact point.
    cache.updateSpike(entry, unit, minDim * 1.05f, strength)
    drawPath(path = cache.spike, brush = cache.spikeBrush, blendMode = BlendMode.Plus)

    // Drifting bokeh dots (reference flare chain).
    for (i in flareDots.indices) {
        val s = flareDots[i]
        val dx = if (s > 1f) sin(t * s / 2f) else cos(t * s / 2f)
        val dy = if (s > 1f) cos(t * s) else atan2(t * s, 1f)
        val k = unit * 1.25f
        val p = Offset(entry.x + dx * s / 8f * k, entry.y + dy * s / 5f * k)
        drawGlow(cache.dots[i], p, s * 0.45f * unit, glowAlpha, BlendMode.Plus)
    }
}

/* -------------------------------- geometry -------------------------------- */

/** Ray parameter of the hit of [o] + t·[d] on segment [a]..[b], or -1 when it misses. */
private fun raySegT(o: Offset, d: Offset, a: Offset, b: Offset): Float {
    val sx = b.x - a.x
    val sy = b.y - a.y
    val denom = d.x * sy - d.y * sx
    if (abs(denom) < 1e-6f) return -1f
    val qx = a.x - o.x
    val qy = a.y - o.y
    val tt = (qx * sy - qy * sx) / denom
    val u = (qx * d.y - qy * d.x) / denom
    return if (tt >= 0f && u >= 0f && u <= 1f) tt else -1f
}

/** Nearest hit of the ray [origin] + t·[dir] on the triangle outline, or Unspecified. */
private fun triangleIntersection(dir: Offset, origin: Offset, a: Offset, b: Offset, c: Offset): Offset {
    var bestT = Float.MAX_VALUE
    val t0 = raySegT(origin, dir, a, b)
    if (t0 >= 0f && t0 < bestT) bestT = t0
    val t1 = raySegT(origin, dir, b, c)
    if (t1 >= 0f && t1 < bestT) bestT = t1
    val t2 = raySegT(origin, dir, c, a)
    if (t2 >= 0f && t2 < bestT) bestT = t2
    return if (bestT == Float.MAX_VALUE) Offset.Unspecified else origin + dir * bestT
}

/** Outward normal of the triangle edge nearest to [p] (triangle centred on the origin). */
private fun faceNormal(p: Offset, top: Offset, bl: Offset, br: Offset): Offset {
    val d0 = (p - (top + bl) / 2f).getDistance()
    val d1 = (p - (bl + br) / 2f).getDistance()
    val d2 = (p - (br + top) / 2f).getDistance()
    val e = when {
        d0 <= d1 && d0 <= d2 -> normalize(bl - top)
        d1 <= d2 -> normalize(br - bl)
        else -> normalize(top - br)
    }
    return Offset(-e.y, e.x) // outward for this screen-space winding
}

private val spectrum = listOf(
    Color(0xFFFF3B30),
    Color(0xFFFF9500),
    Color(0xFFFFCC00),
    Color(0xFF34C759),
    Color(0xFF00C7BE),
    Color(0xFF007AFF),
    Color(0xFFAF52DE),
)

private val flareDots = floatArrayOf(0.5f, 1.25f, 0.75f, 1.5f, 2f)

private val dotColors = listOf(
    Color(0xFFFF9500),
    Color(0xFFFFCC00),
    Color(0xFF34C759),
    Color(0xFF00C7BE),
    Color(0xFFAF52DE),
)
