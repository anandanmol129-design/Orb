package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
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
 */
@Composable
fun PrismWallpaper(modifier: Modifier = Modifier) {
    val aim = remember { PrismAim() }
    val timeNanos = remember { mutableStateOf(0L) }
    val fan = remember { FanMesh() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.1f)
                last = now
                aim.update(dt)
                timeNanos.value = now
            }
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val t = timeNanos.value / 1_000_000_000f
        drawRect(Color.Black)

        val minDim = minOf(w, h)
        val unit = minDim / 7.5f
        val center = Offset(w * 0.5f, h * 0.6f)
        val diag = hypot(w, h)
        aim.center = center

        // Prism triangle, apex up, leaning slightly and tumbling at SPIN_RPM about the centre;
        // the beam entry and fan bend below read the rotated faces, so they sweep with it.
        val tilt = -6f * DEG + SPIN_RATE * ((timeNanos.value % SPIN_PERIOD_NS) / 1_000_000_000f)
        val top = rot(Offset(-0.02f, -1.0f) * unit, tilt)
        val bottomLeft = rot(Offset(-0.95f, 0.62f) * unit, tilt)
        val bottomRight = rot(Offset(0.95f, 0.58f) * unit, tilt)
        val vTop = center + top
        val vBl = center + bottomLeft
        val vBr = center + bottomRight

        // The beam always aims through the prism, from the pointer side or the default pose.
        val source = center + aim.dir * (diag * 1.5f)
        val through = normalize(center - source)
        val entry = triangleIntersection(through, source, vTop, vBl, vBr) ?: center

        // Fan re-aims like the reference: straight through, tilted by the entry face.
        val j0 = angleUp(through)
        val faceIn = faceNormal(entry - center, vTop - center, vBl - center, vBr - center)
        val bend = BEND_DEG * DEG * sin(2f * (j0 - angleUp(faceIn)))
        aim.smoothFanAngle(j0 + bend)

        // Faint ambient glow lifting the prism off the black (reference point lights).
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF141D33).copy(alpha = 0.3f), Color.Transparent),
                center = center,
                radius = minDim * 0.5f,
            ),
            radius = minDim * 0.5f,
            center = center,
        )

        // The rainbow fan and its hall of mirrors at the screen edges; the apex hides behind
        // the prism drawn on top of it. Colours are evaluated once here and every copy below
        // reuses them.
        fan.ensure(w, h)
        fan.updateColors(t)
        withTransform({ rotate(-aim.fanAngle / DEG, center) }) {
            fan.draw(this, center, 1f)
        }
        drawFanReflections(fan, center, aim.fanAngle, w, h)

        // Wide bloom wash along the fan axis.
        val axis = Offset(cos(aim.fanAngle), -sin(aim.fanAngle))
        val washAt = center + axis * (diag * 0.26f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3A5A6E).copy(alpha = 0.10f), Color.Transparent),
                center = washAt,
                radius = diag * 0.34f,
            ),
            radius = diag * 0.34f,
            center = washAt,
            blendMode = BlendMode.Plus,
        )

        // The beam.
        drawBeam(source, entry, unit, aim.strength)

        // The prism.
        drawPrism(center, vTop - center, vBl - center, vBr - center, unit, entry - center, aim.fanAngle)

        // Contact point: glows, vertical flare spike and drifting bokeh dots.
        drawContact(entry, unit, minDim, t, aim.strength)
    }
}

private val DEG = (Math.PI / 180.0).toFloat()
private const val BEND_DEG = 14.5f

/** The prism tumbles about its centre at this rate: 10 rpm is one turn every 6 s. */
private const val SPIN_RPM = 10f
private val SPIN_RATE = SPIN_RPM * 6f * DEG // rad/s (6°/s per rpm ≈ 1.047 rad/s at 10 rpm)
private val SPIN_PERIOD_NS = (60.0 / SPIN_RPM * 1e9).toLong()
// The spin phase is folded mod one turn in a Long first, so long clock uptimes cannot quantise
// the rotation into visible steps.

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

    fun set(pos: Offset, isPressed: Boolean) {
        x = pos.x
        y = pos.y
        pressed = isPressed
    }
}

/** Tracks where the beam comes from; eases back to the reference pose (-10, -.05) on release. */
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
        target = if (WallpaperPointer.pressed && center != Offset.Zero) {
            normalize(Offset(WallpaperPointer.x, WallpaperPointer.y) - center)
        } else {
            DEFAULT_DIR
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
    private val hueA = FloatArray(3)
    private val hueB = FloatArray(3)
    private val c = FloatArray(3)
    private val ir = FloatArray(3)

    /** Alan Zucconi's six-Gaussian visible spectrum fit. */
    private fun spectral6(w: Float, out: FloatArray) {
        val x = ((w - 400f) / 300f).coerceIn(0f, 1f)
        for (i in 0..2) {
            val d1 = c1[i] * (x - x1[i])
            val d2 = c2[i] * (x - x2[i])
            out[i] = (1f - d1 * d1 - y1[i]).coerceIn(0f, 1f) + (1f - d2 * d2 - y2[i]).coerceIn(0f, 1f)
        }
    }

    private fun physhue(hue: Float, ratio: Float, out: FloatArray) {
        for (i in 0..2) {
            val v = ((hue + i * ratio) % 1f + 1f) % 1f
            val s = abs(v * 2f - 1f).coerceIn(0f, 1f)
            out[i] = s * s * (3f - 2f * s)
        }
    }

    /** JuliaPoo's iridescence blob field. */
    private fun iridescence(angle: Float, thickness: Float, out: FloatArray) {
        val nxv = cos(angle)
        val lum = 0.05064f
        val luma = 0.01070f
        val f = (1f - nxv) * (1f - nxv)
        val dp = (nxv - 1f) * 0.5f
        physhue(thickness * 2.4f + dp, thickness / 2.8f, hueA)
        physhue(thickness * 3.2f + 0.1f + dp, thickness / 2.8f, hueB)
        for (i in 0..2) hueA[i] = hueA[i] + (hueB[i] - hueA[i]) * f
        val f12 = f * f * f * f * f * f * f * f * f * f * f * f
        out[0] = ((hueA[0] * lum + 0.9639f * luma) * 3f + f12) * 0.49639f
        out[1] = ((hueA[1] * lum + 0.78252f * luma) * 3f + f12) * 0.78252f
        out[2] = ((hueA[2] * lum + 0.18723f * luma) * 3f + f12) * 0.8723f
    }

    /**
     * Fan colour at fan-space [k] (red at k > 0, i.e. above the fan axis) and radius [uvY]
     * (0 at the apex, 1.12 at the fan's far edge), for flow time [t].
     */
    fun fanColor(k: Float, uvY: Float, t: Float): Color {
        val a = atan2(k * 0.5f, 1f) * 10f
        spectral6((k + 0.5f) * 300f + 400f + a, c)
        val l = 1f - smoothstep(0f, 1f, uvY)
        val bright = smoothstep(0f, 0.5f, c[0] + c[1] + c[2])
        val scale = l * bright * RAINBOW_GLOW
        if (scale <= 0f) return Color.Transparent
        iridescence(k * uvY * 0.7854f, 1f - uvY + t / 10f, ir)
        var sum = 0f
        for (i in 0..2) {
            c[i] = (c[i] / ir[i] / 20f * scale).coerceAtLeast(0f)
            sum += c[i]
        }
        val edge = smoothstep(0.05f, 1f, sum)
        if (edge <= 0f) return Color.Transparent
        return Color(
            red = srgb(c[0] * edge),
            green = srgb(c[1] * edge),
            blue = srgb(c[2] * edge),
        )
    }

    private fun srgb(v: Float): Float = v.coerceIn(0f, 1f).pow(1f / 2.2f)

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val tt = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return tt * tt * (3f - 2f * tt)
    }
}

private const val RAINBOW_GLOW = 2.5f // reference: rainbowIntensity
private const val K_MAX = 1.35f // spectral band edges clamp at |k| = 1
private const val UV_Y0 = 0.03f
private const val UV_Y1 = 1.12f
private const val NK = 97
private const val NR = 26

/**
 * The fan as a single triangle mesh with per-vertex colours straight from the reference shader
 * formula, so the blob field interpolates smoothly (as in the reference) instead of through
 * gradient stops. Colour evaluation is split out in [updateColors] and runs once per frame; every
 * mirror copy then just runs [draw] over the same mesh and colour array.
 */
private class FanMesh {
    private val count = NK * NR
    private val positions = FloatArray(count * 2)
    private val argb = IntArray(count)
    private var points: List<Offset> = emptyList()
    private var texCoords: List<Offset> = emptyList()
    private var indexList: List<Int> = emptyList()
    private val colorView = object : AbstractList<Color>() {
        override val size get() = count
        override fun get(index: Int): Color = Color(argb[index])
    }
    private val kValues = FloatArray(NK)
    private val uvYValues = FloatArray(NR)
    private val cornerAngles = FloatArray(4)
    private val paint = androidx.compose.ui.graphics.Paint()
    private var vertices: Vertices? = null
    private var cachedW = -1f
    private var cachedH = -1f

    /** Reach of the fan wedge from its apex along the fan axis, and the wedge half-angle. */
    var reach = 0f
        private set
    var halfSpread = 0f
        private set

    fun ensure(w: Float, h: Float) {
        if (w == cachedW && h == cachedH) return
        cachedW = w
        cachedH = h
        val q = hypot(w, h) + 1.5f * minOf(w, h) / 7.5f
        reach = UV_Y1 * q / 2f
        halfSpread = atan(K_MAX * UV_Y1 / 2f)
        for (i in 0 until NK) {
            val k = -K_MAX + 2f * K_MAX * i / (NK - 1)
            kValues[i] = k
            for (j in 0 until NR) {
                val uvY = UV_Y0 + (UV_Y1 - UV_Y0) * j / (NR - 1)
                if (i == 0) uvYValues[j] = uvY
                val p = (i * NR + j) * 2
                positions[p] = uvY * q / 2f
                positions[p + 1] = -k * uvY * q / 4f
            }
        }
        points = ArrayList<Offset>(count).apply {
            for (n in 0 until count) add(Offset(positions[n * 2], positions[n * 2 + 1]))
        }
        texCoords = List(count) { Offset.Zero }
        indexList = ArrayList<Int>((NK - 1) * (NR - 1) * 6).apply {
            for (i in 0 until NK - 1) {
                for (j in 0 until NR - 1) {
                    val a = i * NR + j
                    val b = (i + 1) * NR + j
                    val c = (i + 1) * NR + j + 1
                    val d = i * NR + j + 1
                    add(a); add(b); add(c)
                    add(a); add(c); add(d)
                }
            }
        }
    }

    /** Rebuild the flowing vertex colours for flow time [t]; once per frame, shared by copies. */
    fun updateColors(t: Float) {
        for (i in 0 until NK) {
            val k = kValues[i]
            for (j in 0 until NR) {
                argb[i * NR + j] = Spectrum.fanColor(k, uvYValues[j], t).toArgb()
            }
        }
        vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = points,
            textureCoordinates = texCoords,
            colors = colorView,
            indices = indexList,
        )
    }

    /**
     * Draw the fan at [center] at [brightness]: 1 for the unreflected fan, [WALL_REFLECT] per
     * bounce for a mirror copy. The Plus blend scales the vertex colours with the paint alpha,
     * so this is one drawVertices call per copy over the shared mesh and colours.
     */
    fun draw(scope: DrawScope, center: Offset, brightness: Float) {
        val mesh = vertices ?: return
        paint.alpha = brightness
        scope.translate(center.x, center.y) {
            drawIntoCanvas { canvas ->
                canvas.drawVertices(mesh, BlendMode.Plus, paint)
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

// Bounce bookkeeping for the fan's hall of mirrors at the screen edges.
private const val MAX_BOUNCES = 10
private const val WALL_REFLECT = 0.9f

// The screen edges, as mirrors for the fan.
private const val WALL_L = 0
private const val WALL_R = 1
private const val WALL_T = 2
private const val WALL_B = 3

/**
 * The screen is a hall of mirrors for the rainbow fan: the wedge that leaves through an edge
 * comes back in as its mirror image across that edge, [WALL_REFLECT] darker per bounce and
 * completely absorbed past [MAX_BOUNCES]. Copies are walked depth-first from the unreflected fan,
 * never back across the wall just crossed (that only undoes the bounce), and a branch is dropped
 * whole the moment its mirrored wedge cannot reach the screen: light that never re-enters the
 * room cannot bounce again. Each copy is the regular fan draw under the composed edge
 * reflections, clipped to the screen - only the spill back across the edge shows, never the
 * mirrored apex outside it. The unreflected fan is drawn separately and unclipped.
 */
private fun DrawScope.drawFanReflections(
    fan: FanMesh,
    center: Offset,
    fanAngle: Float,
    w: Float,
    h: Float,
) {
    reflectFan(fan, center, fanAngle, w, h, -1, 1, 0f, 0f, 1, WALL_REFLECT, WALL_L)
    reflectFan(fan, center, fanAngle, w, h, -1, 1, 2f * w, 0f, 1, WALL_REFLECT, WALL_R)
    reflectFan(fan, center, fanAngle, w, h, 1, -1, 0f, 0f, 1, WALL_REFLECT, WALL_T)
    reflectFan(fan, center, fanAngle, w, h, 1, -1, 0f, 2f * h, 1, WALL_REFLECT, WALL_B)
}

/**
 * One mirror image of the fan after [depth] bounces: the fan mapped by (x, y) -> (sx * x + bx,
 * sy * y + by), a composition of reflections across screen edges. [fromWall] is the edge whose
 * reflection produced this image; children reflect across the other three.
 */
private fun DrawScope.reflectFan(
    fan: FanMesh,
    center: Offset,
    fanAngle: Float,
    w: Float,
    h: Float,
    sx: Int,
    sy: Int,
    bx: Float,
    by: Float,
    depth: Int,
    brightness: Float,
    fromWall: Int,
) {
    if (!fan.wedgeReachesScreen(
            Offset(sx * center.x + bx, sy * center.y + by),
            Offset(sx * cos(fanAngle), -sy * sin(fanAngle)),
        )
    ) return

    clipRect(0f, 0f, w, h) {
        translate(bx, by) {
            scale(sx.toFloat(), sy.toFloat(), Offset.Zero) {
                withTransform({ rotate(-fanAngle / DEG, center) }) {
                    fan.draw(this, center, brightness)
                }
            }
        }
    }

    if (depth >= MAX_BOUNCES) return // the mirrors absorb everything past the tenth bounce
    val next = brightness * WALL_REFLECT
    for (wall in WALL_L..WALL_B) {
        if (wall == fromWall) continue // reflecting back across the wall just crossed undoes it
        when (wall) {
            WALL_L -> reflectFan(fan, center, fanAngle, w, h, -sx, sy, -bx, by, depth + 1, next, wall)
            WALL_R -> reflectFan(fan, center, fanAngle, w, h, -sx, sy, 2f * w - bx, by, depth + 1, next, wall)
            WALL_T -> reflectFan(fan, center, fanAngle, w, h, sx, -sy, bx, -by, depth + 1, next, wall)
            else -> reflectFan(fan, center, fanAngle, w, h, sx, -sy, bx, 2f * h - by, depth + 1, next, wall)
        }
    }
}

/* -------------------------------- scene drawing -------------------------------- */

private fun DrawScope.drawBeam(source: Offset, entry: Offset, unit: Float, strength: Float) {
    if ((entry - source).getDistance() < 1f) return
    val dir = normalize(entry - source)
    val perp = Offset(-dir.y, dir.x)

    // Wide anamorphic haze along the beam.
    val haze = Path().apply {
        moveTo(source.x + perp.x * unit * 1.1f, source.y + perp.y * unit * 1.1f)
        lineTo(entry.x + perp.x * unit * 0.45f, entry.y + perp.y * unit * 0.45f)
        lineTo(entry.x - perp.x * unit * 0.45f, entry.y - perp.y * unit * 0.45f)
        lineTo(source.x - perp.x * unit * 1.1f, source.y - perp.y * unit * 1.1f)
        close()
    }
    drawPath(
        path = haze,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = 0.05f * strength),
                Color.White.copy(alpha = 0.05f * strength),
            ),
            start = source,
            end = entry,
        ),
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.3f * strength),
                Color.White.copy(alpha = 0.55f * strength),
            ),
            start = source,
            end = entry,
        ),
        start = source,
        end = entry,
        strokeWidth = unit * 0.06f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus,
    )
    drawLine(
        brush = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.35f * strength),
                Color.White.copy(alpha = 0.9f * strength),
            ),
            start = source,
            end = entry,
        ),
        start = source,
        end = entry,
        strokeWidth = unit * 0.012f,
        cap = StrokeCap.Round,
        blendMode = BlendMode.Plus,
    )
}

private fun DrawScope.drawPrism(
    center: Offset,
    top: Offset,
    bottomLeft: Offset,
    bottomRight: Offset,
    unit: Float,
    entry: Offset,
    fanAngle: Float,
) {
    val t = center + top
    val bl = center + bottomLeft
    val br = center + bottomRight
    val blF = Offset(bl.x + unit * 0.02f, bl.y + unit * 0.12f)
    val brF = Offset(br.x + unit * 0.01f, br.y + unit * 0.13f)

    val body = Path().apply {
        moveTo(t.x, t.y)
        lineTo(bl.x, bl.y)
        lineTo(br.x, br.y)
        close()
    }

    // Dark glass body, split by the ridge into a shaded left face and a lit right face.
    drawPath(
        path = body,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF0D1119), Color(0xFF05070C)),
            start = t,
            end = bl,
        ),
    )
    val baseMid = Offset((bl.x + br.x) / 2f + unit * 0.1f, (bl.y + br.y) / 2f)
    val rightFace = Path().apply {
        moveTo(t.x, t.y)
        lineTo(baseMid.x, baseMid.y)
        lineTo(br.x, br.y)
        close()
    }
    drawPath(
        path = rightFace,
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFF131A26), Color(0xFF080B12)),
            start = t,
            end = br,
        ),
    )
    drawLine(Color(0xFFBFD8EE).copy(alpha = 0.2f), t, baseMid, unit * 0.01f)

    // Bottom facet sliver.
    val sliver = Path().apply {
        moveTo(bl.x, bl.y)
        lineTo(br.x, br.y)
        lineTo(brF.x, brF.y)
        lineTo(blF.x, blF.y)
        close()
    }
    drawPath(
        path = sliver,
        brush = Brush.verticalGradient(
            colors = listOf(Color(0xFF0A0E15), Color(0xFF1A2230)),
            startY = bl.y,
            endY = blF.y,
        ),
    )

    clipPath(body) {
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
        val e = center + entry
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
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF3A6E8E).copy(alpha = 0.18f), Color.Transparent),
                center = refl,
                radius = unit * 0.7f,
            ),
            radius = unit * 0.7f,
            center = refl,
            blendMode = BlendMode.Plus,
        )
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

private fun DrawScope.drawContact(entry: Offset, unit: Float, minDim: Float, t: Float, strength: Float) {
    // Core and glows where the beam lands.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.5f * strength),
                Color.White.copy(alpha = 0.1f * strength),
                Color.Transparent,
            ),
            center = entry,
            radius = unit * 0.625f,
        ),
        radius = unit * 0.625f,
        center = entry,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.75f * strength), Color.Transparent),
            center = entry,
            radius = unit * 0.075f,
        ),
        radius = unit * 0.075f,
        center = entry,
        blendMode = BlendMode.Plus,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.9f * strength),
        radius = unit * 0.02f,
        center = entry,
        blendMode = BlendMode.Plus,
    )

    // Vertical flare spike crossing the contact point.
    val spikeH = minDim * 1.05f
    val spike = Path().apply {
        moveTo(entry.x - unit * 0.008f, entry.y - spikeH * 0.45f)
        lineTo(entry.x + unit * 0.008f, entry.y - spikeH * 0.45f)
        lineTo(entry.x + unit * 0.02f, entry.y + spikeH * 0.55f)
        lineTo(entry.x - unit * 0.02f, entry.y + spikeH * 0.55f)
        close()
    }
    drawPath(
        path = spike,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = 0.5f * strength),
                Color.White.copy(alpha = 0.12f * strength),
                Color.White.copy(alpha = 0f),
            ),
            startY = entry.y - spikeH * 0.45f,
            endY = entry.y + spikeH * 0.55f,
        ),
        blendMode = BlendMode.Plus,
    )

    // Drifting bokeh dots (reference flare chain).
    for (i in flareDots.indices) {
        val s = flareDots[i]
        val dx = if (s > 1f) sin(t * s / 2f) else cos(t * s / 2f)
        val dy = if (s > 1f) cos(t * s) else atan2(t * s, 1f)
        val p = entry + Offset(dx * s / 8f, dy * s / 5f) * unit * 1.25f
        val radius = s * 0.45f * unit
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    dotColors[i].copy(alpha = 0.12f * strength),
                    dotColors[i].copy(alpha = 0.05f * strength),
                    Color.Transparent,
                ),
                center = p,
                radius = radius,
            ),
            radius = radius,
            center = p,
            blendMode = BlendMode.Plus,
        )
    }
}

/* -------------------------------- geometry -------------------------------- */

private fun raySegT(o: Offset, d: Offset, a: Offset, b: Offset): Float? {
    val s = b - a
    val denom = d.x * s.y - d.y * s.x
    if (abs(denom) < 1e-6f) return null
    val q = a - o
    val tt = (q.x * s.y - q.y * s.x) / denom
    val u = (q.x * d.y - q.y * d.x) / denom
    return if (tt >= 0f && u in 0f..1f) tt else null
}

/** Nearest hit of the ray [origin] + t·[dir] on the triangle outline. */
private fun triangleIntersection(dir: Offset, origin: Offset, a: Offset, b: Offset, c: Offset): Offset? {
    var bestT = Float.MAX_VALUE
    var best: Offset? = null
    for ((p0, p1) in listOf(a to b, b to c, c to a)) {
        val tt = raySegT(origin, dir, p0, p1)
        if (tt != null && tt < bestT) {
            bestT = tt
            best = origin + dir * tt
        }
    }
    return best
}

/** Outward normal of the triangle edge nearest to [p] (triangle centred on the origin). */
private fun faceNormal(p: Offset, top: Offset, bl: Offset, br: Offset): Offset {
    var best = Offset(-1f, 0f)
    var bestD = Float.MAX_VALUE
    for ((a, b) in listOf(top to bl, bl to br, br to top)) {
        val mid = (a + b) / 2f
        val d = (p - mid).getDistance()
        if (d < bestD) {
            bestD = d
            val e = normalize(b - a)
            best = Offset(-e.y, e.x) // outward for this screen-space winding
        }
    }
    return best
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
