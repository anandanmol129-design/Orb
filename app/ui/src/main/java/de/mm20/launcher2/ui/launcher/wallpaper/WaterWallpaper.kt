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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Still water: a dark glassy surface at rest. Touching drops stones — concentric waves spread
 * from the contact point across a real wave field and shove the motes drifting in the water
 * around. A held finger drags a wake; waves die out and the surface settles flat again.
 */
@Composable
fun WaterWallpaper(modifier: Modifier = Modifier) {
    val field = remember { RippleField() }
    val motes = remember { List(MOTE_COUNT) { Mote() } }
    val mesh = remember { WaterMesh() }
    val timeNanos = remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        var wasPressed = false
        var wakeX = Float.NaN
        var wakeY = Float.NaN
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now

                if (mesh.width > 0f) {
                    val gx = WallpaperPointer.x / mesh.width * (GW - 1)
                    val gy = WallpaperPointer.y / mesh.height * (GH - 1)
                    if (WallpaperPointer.pressed) {
                        if (!wasPressed) {
                            field.disturb(gx, gy, STONE_RADIUS, STONE_AMP)
                            wakeX = gx
                            wakeY = gy
                        } else {
                            val dx = gx - wakeX
                            val dy = gy - wakeY
                            val dist = kotlin.math.hypot(dx, dy)
                            if (dist > WAKE_STEP) {
                                field.disturb(gx, gy, WAKE_RADIUS, min(1f, dist / 4f) * WAKE_AMP)
                                wakeX = gx
                                wakeY = gy
                            }
                        }
                    }
                }
                wasPressed = WallpaperPointer.pressed

                field.step()
                field.slopes()
                if (dt > 0f) {
                    for (m in motes) m.update(dt, field, mesh.width, mesh.height)
                }
                timeNanos.value = now
            }
        }
    }

    Canvas(modifier = modifier) {
        timeNanos.value // redraw is driven by the simulation loop
        mesh.ensure(size.width, size.height)
        mesh.draw(this, field)
        for (m in motes) drawMote(this, m, size.width, size.height, field)
    }
}

private const val GW = 57
private const val GH = 125
private const val DAMPING = 0.988f
private const val STONE_RADIUS = 2.6f
private const val STONE_AMP = 0.9f
private const val WAKE_RADIUS = 1.6f
private const val WAKE_AMP = 0.3f
private const val WAKE_STEP = 0.8f
private const val MOTE_COUNT = 42

/** Classic damped wave field on a coarse grid; flat at rest, rings when disturbed. */
private class RippleField {
    private var h = FloatArray(GW * GH)
    private var prev = FloatArray(GW * GH)
    val dx = FloatArray(GW * GH)
    val dy = FloatArray(GW * GH)

    fun step() {
        val cur = h
        val old = prev
        for (y in 1 until GH - 1) {
            var i = y * GW + 1
            for (x in 1 until GW - 1) {
                val v = (cur[i - 1] + cur[i + 1] + cur[i - GW] + cur[i + GW]) * 0.5f - old[i]
                old[i] = v * DAMPING
                i++
            }
        }
        prev = cur
        h = old
    }

    fun slopes() {
        val cur = h
        for (y in 1 until GH - 1) {
            var i = y * GW + 1
            for (x in 1 until GW - 1) {
                dx[i] = (cur[i + 1] - cur[i - 1]) * 0.5f
                dy[i] = (cur[i + GW] - cur[i - GW]) * 0.5f
                i++
            }
        }
    }

    fun disturb(gx: Float, gy: Float, radius: Float, amp: Float) {
        val x0 = max(1, (gx - radius - 1).toInt())
        val x1 = min(GW - 2, (gx + radius + 1).toInt())
        val y0 = max(1, (gy - radius - 1).toInt())
        val y1 = min(GH - 2, (gy + radius + 1).toInt())
        val r2 = radius * radius
        for (y in y0..y1) {
            for (x in x0..x1) {
                val ddx = x - gx
                val ddy = y - gy
                val d2 = ddx * ddx + ddy * ddy
                if (d2 < r2 * 4f) {
                    h[y * GW + x] += amp * exp(-d2 / r2)
                }
            }
        }
    }

    fun heightAt(gx: Float, gy: Float): Float = bilinear(h, gx, gy)
    fun dxAt(gx: Float, gy: Float): Float = bilinear(dx, gx, gy)
    fun dyAt(gx: Float, gy: Float): Float = bilinear(dy, gx, gy)

    private fun bilinear(a: FloatArray, gx: Float, gy: Float): Float {
        val x = gx.coerceIn(0f, (GW - 2).toFloat())
        val y = gy.coerceIn(0f, (GH - 2).toFloat())
        val xi = x.toInt()
        val yi = y.toInt()
        val fx = x - xi
        val fy = y - yi
        val i = yi * GW + xi
        val top = a[i] + (a[i + 1] - a[i]) * fx
        val bot = a[i + GW] + (a[i + GW + 1] - a[i + GW]) * fx
        return top + (bot - top) * fy
    }
}

/** Motes drifting in the water; shoved by wave slopes and bobbing on wave crests. */
private class Mote(
    x: Float,
    y: Float,
    val size: Float,
    val alpha: Float,
    private val phase: Float,
) {
    var x = x
    var y = y
    private var vx = 0f
    private var vy = 0f

    constructor() : this(
        x = Random.nextFloat(),
        y = Random.nextFloat(),
        size = 0.6f + Random.nextFloat() * 1.2f,
        alpha = 0.16f + Random.nextFloat() * 0.3f,
        phase = Random.nextFloat() * (2f * PI.toFloat()),
    )

    fun update(dt: Float, field: RippleField, w: Float, h: Float) {
        if (w <= 0f || h <= 0f) return
        val gx = x * (GW - 1)
        val gy = y * (GH - 1)
        val sx = field.dxAt(gx, gy)
        val sy = field.dyAt(gx, gy)
        vx += (-sx * 42f + sin(phase) * 0.35f) * dt
        vy += (-sy * 42f + cos(phase * 1.7f) * 0.35f) * dt
        val damp = exp(-dt * 1.8f)
        vx *= damp
        vy *= damp
        x += vx * dt
        y += vy * dt
        if (x < -0.01f) x += 1.02f else if (x > 1.01f) x -= 1.02f
        if (y < -0.01f) y += 1.02f else if (y > 1.01f) y -= 1.02f
    }

    fun bob(field: RippleField): Float = field.heightAt(x * (GW - 1), y * (GH - 1))
}

private fun drawMote(scope: DrawScope, m: Mote, w: Float, h: Float, field: RippleField) {
    if (w <= 0f || h <= 0f) return
    val bob = m.bob(field)
    val radius = w * 0.0022f * m.size * (1f + max(0f, bob) * 0.5f)
    val a = (m.alpha * (1f + max(0f, bob) * 1.4f)).coerceIn(0f, 0.8f)
    scope.drawCircle(
        color = Color(0.62f, 0.76f, 0.86f, a),
        radius = radius,
        center = Offset(m.x * w, m.y * h),
    )
}

/**
 * The water surface as one triangle mesh shaded from the wave field: a dark gradient body with
 * wave crests lifting the tone and slopes facing the light catching sharp glints.
 */
private class WaterMesh {
    var width = 0f
        private set
    var height = 0f
        private set

    private val count = GW * GH
    private val argb = IntArray(count)
    private var points: List<Offset> = emptyList()
    private var texCoords: List<Offset> = emptyList()
    private var indexList: List<Int> = emptyList()
    private val paint = androidx.compose.ui.graphics.Paint()
    private val colorView = object : AbstractList<Color>() {
        override val size get() = count
        override fun get(index: Int): Color = Color(argb[index])
    }

    fun ensure(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        points = ArrayList<Offset>(count).apply {
            for (y in 0 until GH) {
                for (x in 0 until GW) {
                    add(Offset(x * w / (GW - 1), y * h / (GH - 1)))
                }
            }
        }
        texCoords = List(count) { Offset.Zero }
        indexList = ArrayList<Int>((GW - 1) * (GH - 1) * 6).apply {
            for (y in 0 until GH - 1) {
                for (x in 0 until GW - 1) {
                    val a = y * GW + x
                    val b = (y + 1) * GW + x
                    val c = (y + 1) * GW + x + 1
                    val d = y * GW + x + 1
                    add(a); add(b); add(c)
                    add(a); add(c); add(d)
                }
            }
        }
    }

    fun draw(scope: DrawScope, field: RippleField) {
        if (width <= 0f) return
        for (y in 0 until GH) {
            val row = y.toFloat() / (GH - 1)
            val base = 0.022f + 0.028f * sin(row * PI.toFloat())
            for (x in 0 until GW) {
                argb[y * GW + x] = shade(field, x, y, base)
            }
        }
        val vertices = Vertices(
            vertexMode = VertexMode.Triangles,
            positions = points,
            textureCoordinates = texCoords,
            colors = colorView,
            indices = indexList,
        )
        scope.drawIntoCanvas { canvas ->
            canvas.drawVertices(vertices, BlendMode.SrcOver, paint)
        }
    }

    private fun shade(field: RippleField, x: Int, y: Int, base: Float): Int {
        val i = y * GW + x
        val x0 = max(0, x - 1)
        val x1 = min(GW - 1, x + 1)
        val yy0 = max(0, y - 1)
        val y1 = min(GH - 1, y + 1)
        val hv = field.heightAt(x.toFloat(), y.toFloat())
        val gx = (field.heightAt(x1.toFloat(), y.toFloat()) - field.heightAt(x0.toFloat(), y.toFloat())) * 0.5f
        val gy = (field.heightAt(x.toFloat(), y1.toFloat()) - field.heightAt(x.toFloat(), yy0.toFloat())) * 0.5f
        val lit = hv * 0.30f - gx * 0.75f - gy * 0.6f
        val crest = max(0f, hv)
        val g = max(0f, -gx * 0.55f - gy * 0.45f)
        val glint = g * g * g * g * 3f
        val r = base * 0.6f + lit * 0.45f + crest * 0.35f + glint
        val gr = base * 0.9f + lit * 0.60f + crest * 0.50f + glint
        val b = base * 1.5f + lit * 0.85f + crest * 0.75f + glint
        return Color(
            red = r.coerceIn(0f, 1f),
            green = gr.coerceIn(0f, 1f),
            blue = b.coerceIn(0f, 1f),
        ).toArgb()
    }
}
