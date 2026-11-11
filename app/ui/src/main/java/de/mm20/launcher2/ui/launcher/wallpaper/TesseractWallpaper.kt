package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import android.graphics.Color as AndroidColor

/**
 * Tesseract: a 4D hypercube slowly rotating through the XW, YW and ZW planes (plus a gentle XY
 * and XZ turn), perspective-projected 4D to 3D to 2D, so the inner and outer cubes continuously
 * fold inside out through each other. It is centred on the assistant orb and sized so the outer
 * cube frames it. Edges are drawn as neon lines coloured by depth: near edges are brighter,
 * thicker and shift from cyan to white, far edges are dimmer, thinner and violet. The palette
 * drifts slowly over minutes, and a faint lagging ghost pose shows up as a trail when spinning.
 *
 * Dragging spins the 4D rotation (horizontal drives XW, vertical drives YW); on release it coasts
 * and eases back to the ambient speeds. A quick tap gives a brief pulse of brightness. Every time
 * the launcher resumes the rotation phase is randomised. While music plays the edges and vertices
 * brighten and the figure swells slightly on each [MusicPulse] beat, and it turns a touch faster.
 */
@Composable
fun TesseractWallpaper(modifier: Modifier = Modifier) {
    val tesseract = remember { Tesseract() }
    val pulse = remember { MusicPulse() }
    val frame = remember { mutableLongStateOf(0L) }
    val generation = rememberResetGeneration()

    LaunchedEffect(generation) { tesseract.randomizePhase() }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                pulse.update(now)
                tesseract.music(pulse)
                tesseract.step(dt, WallpaperPointer.x, WallpaperPointer.y, WallpaperPointer.pressed)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        tesseract.ensure(size.width, size.height)
        tesseract.draw(this)
    }
}

private const val MAX_DT = 0.05f
private const val TWO_PI = (2.0 * PI).toFloat()

// Plane indices into the angle/speed arrays.
private const val XW = 0
private const val YW = 1
private const val ZW = 2
private const val XY = 3
private const val XZ = 4

// Ambient angular speeds (rad/s), mutually incommensurate so the pose never repeats.
private val AMBIENT_SPEED = floatArrayOf(0.211f, 0.147f, 0.089f, 0.053f, 0.031f)

// Projection: eye distances in 4D and 3D, and screen scale as a fraction of the width.
private const val DIST_W = 4f
private const val DIST_Z = 6f
private const val SCALE = 0.26f
private const val CENTER_Y = 0.52f

// Depth mix for colouring (t = 0.5 + W * w + Z * z3, clamped to 0..1).
private const val DEPTH_W = 0.17f
private const val DEPTH_Z = 0.13f
private const val BUCKETS = 6

// Touch.
private const val DRAG_GAIN = 3.2f // radians per screen width dragged
private const val DRAG_SMOOTH = 0.06f
private const val COAST_TAU = 1.4f
private const val MAX_SPIN = 6f
private const val TAP_TIME = 0.25f
private const val TAP_SLOP = 0.03f
private const val PULSE_TAU = 0.45f
private const val PULSE_GAIN = 0.8f

// Music: brightness and size lift on each beat, ambient spin boost while music plays.
private const val MUSIC_BRIGHT = 0.07f
private const val MUSIC_SWELL = 0.025f
private const val MUSIC_SPIN = 0.2f

// Ghost pose lags the real one by roughly this time constant.
private const val GHOST_LAG = 0.18f

// Palette (HSV hue in degrees) and its slow drift.
private const val HUE_FAR = 262f
private const val HUE_NEAR = 188f
private const val DRIFT_A = 24f
private const val DRIFT_PERIOD_A = 211f
private const val DRIFT_B = 10f
private const val DRIFT_PERIOD_B = 73f

private fun additivePaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
}

private fun alphaByte(a: Float): Int = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24

private class Tesseract {
    // 16 vertices at (+-1, +-1, +-1, +-1), stored as x, y, z, w.
    private val base = FloatArray(64).also { v ->
        for (i in 0 until 16) for (k in 0 until 4) v[i * 4 + k] = if ((i shr k) and 1 == 1) 1f else -1f
    }

    // 32 edges: vertex pairs whose indices differ in exactly one bit.
    private val edges = IntArray(64).also { e ->
        var n = 0
        for (i in 0 until 16) for (k in 0 until 4) {
            val j = i xor (1 shl k)
            if (j > i) {
                e[n++] = i
                e[n++] = j
            }
        }
    }

    private val angle = FloatArray(5)
    private val sx = FloatArray(16)
    private val sy = FloatArray(16)
    private val depth = FloatArray(16)
    private val ghostX = FloatArray(16)
    private val ghostY = FloatArray(16)
    private var ghostValid = false
    private var projected = false

    private val linePts = Array(BUCKETS) { FloatArray(32 * 4) }
    private val lineCount = IntArray(BUCKETS)
    private val dotPts = Array(BUCKETS) { FloatArray(16 * 2) }
    private val dotCount = IntArray(BUCKETS)
    private val ghostPts = FloatArray(32 * 4)

    private val wideGlow = Array(BUCKETS) { additivePaint() }
    private val midGlow = Array(BUCKETS) { additivePaint() }
    private val core = Array(BUCKETS) { additivePaint() }
    private val dotGlow = Array(BUCKETS) { additivePaint() }
    private val dotCore = Array(BUCKETS) { additivePaint() }
    private val ghostPaint = additivePaint()
    private val hsv = FloatArray(3)

    private var width = 0f
    private var height = 0f
    private var cx = 0f
    private var cy = 0f
    private var scale = 0f
    private var backdrop: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))

    private var time = 0f
    private var pulse = 0f
    private var spinXW = 0f
    private var spinYW = 0f
    private var wasPressed = false
    private var lastX = 0f
    private var lastY = 0f
    private var pressAge = 0f
    private var travel = 0f
    private var beat = 0f
    private var groove = 0f

    init {
        randomizePhase()
    }

    fun randomizePhase() {
        for (i in angle.indices) angle[i] = Random.nextFloat() * TWO_PI
        ghostValid = false
    }

    fun ensure(w: Float, h: Float) {
        if ((w == width && h == height) || w <= 0f || h <= 0f) return
        width = w
        height = h
        cx = w * 0.5f
        cy = h * CENTER_Y
        scale = SCALE * min(w, h * 0.75f)
        val unit = min(w, h)
        for (b in 0 until BUCKETS) {
            val t = b / (BUCKETS - 1f)
            wideGlow[b].strokeWidth = unit * (0.020f + 0.016f * t)
            midGlow[b].strokeWidth = unit * (0.007f + 0.007f * t)
            core[b].strokeWidth = unit * (0.0020f + 0.0032f * t)
            dotGlow[b].strokeWidth = unit * (0.022f + 0.022f * t)
            dotCore[b].strokeWidth = unit * (0.007f + 0.008f * t)
        }
        ghostPaint.strokeWidth = unit * 0.004f
        backdrop = Brush.radialGradient(
            colors = listOf(Color(0xFF170F33), Color(0xFF0C0820), Color(0xFF050311), Color(0xFF020107)),
            center = Offset(cx, cy),
            radius = max(w, h) * 0.75f,
        )
        ghostValid = false
    }

    /** Takes this frame's music groove; both stay 0 when nothing plays. */
    fun music(pulse: MusicPulse) {
        groove = pulse.groove
        beat = pulse.beat
    }

    fun step(dt: Float, px: Float, py: Float, pressed: Boolean) {
        time += dt
        if (width > 0f) pointer(dt, px, py, pressed)
        val ambient = if (groove > 0f) 1f + MUSIC_SPIN * groove else 1f
        for (k in angle.indices) {
            var a = angle[k] + AMBIENT_SPEED[k] * ambient * dt
            if (k == XW) a += spinXW * dt
            if (k == YW) a += spinYW * dt
            angle[k] = a % TWO_PI
        }
        pulse *= exp(-dt / PULSE_TAU)
        if (width > 0f) project(dt)
    }

    private fun pointer(dt: Float, px: Float, py: Float, pressed: Boolean) {
        if (pressed && !wasPressed) {
            lastX = px
            lastY = py
            pressAge = 0f
            travel = 0f
        }
        if (pressed) {
            val dx = px - lastX
            val dy = py - lastY
            lastX = px
            lastY = py
            pressAge += dt
            travel += abs(dx) + abs(dy)
            // Follow the finger directly; the smoothed velocity is what carries over on release.
            val ax = dx * DRAG_GAIN / width
            val ay = dy * DRAG_GAIN / width
            angle[XW] += ax
            angle[YW] += ay
            if (dt > 0f) {
                val s = 1f - exp(-dt / DRAG_SMOOTH)
                spinXW += ((ax / dt).coerceIn(-MAX_SPIN, MAX_SPIN) - spinXW) * s
                spinYW += ((ay / dt).coerceIn(-MAX_SPIN, MAX_SPIN) - spinYW) * s
            }
        } else {
            if (wasPressed && pressAge < TAP_TIME && travel < TAP_SLOP * width) pulse = 1f
            val decay = exp(-dt / COAST_TAU)
            spinXW *= decay
            spinYW *= decay
        }
        wasPressed = pressed
    }

    private fun project(dt: Float) {
        val cXW = cos(angle[XW]); val sXW = sin(angle[XW])
        val cYW = cos(angle[YW]); val sYW = sin(angle[YW])
        val cZW = cos(angle[ZW]); val sZW = sin(angle[ZW])
        val cXY = cos(angle[XY]); val sXY = sin(angle[XY])
        val cXZ = cos(angle[XZ]); val sXZ = sin(angle[XZ])
        val g = if (ghostValid) 1f - exp(-dt / GHOST_LAG) else 1f
        val sc = if (beat > 0f) scale * (1f + MUSIC_SWELL * beat) else scale
        for (i in 0 until 16) {
            var x = base[i * 4]
            var y = base[i * 4 + 1]
            var z = base[i * 4 + 2]
            var w = base[i * 4 + 3]
            var t = x * cXW - w * sXW; w = x * sXW + w * cXW; x = t
            t = y * cYW - w * sYW; w = y * sYW + w * cYW; y = t
            t = z * cZW - w * sZW; w = z * sZW + w * cZW; z = t
            t = x * cXY - y * sXY; y = x * sXY + y * cXY; x = t
            t = x * cXZ - z * sXZ; z = x * sXZ + z * cXZ; x = t

            val s4 = DIST_W / (DIST_W - w)
            val x3 = x * s4
            val y3 = y * s4
            val z3 = z * s4
            val s3 = DIST_Z / max(DIST_Z - z3, 0.5f) * sc
            val px = cx + x3 * s3
            val py = cy + y3 * s3
            sx[i] = px
            sy[i] = py
            depth[i] = (0.5f + DEPTH_W * w + DEPTH_Z * z3).coerceIn(0f, 1f)
            ghostX[i] += (px - ghostX[i]) * g
            ghostY[i] += (py - ghostY[i]) * g
        }
        ghostValid = true
        projected = true
    }

    private fun updateColors() {
        val drift = DRIFT_A * sin(time * TWO_PI / DRIFT_PERIOD_A) + DRIFT_B * sin(time * TWO_PI / DRIFT_PERIOD_B)
        val bright = 1f + PULSE_GAIN * pulse + MUSIC_BRIGHT * beat
        for (b in 0 until BUCKETS) {
            val t = b / (BUCKETS - 1f)
            hsv[0] = ((HUE_FAR + (HUE_NEAR - HUE_FAR) * t + drift) % 360f + 360f) % 360f
            hsv[1] = 0.82f - 0.64f * t * t * t
            hsv[2] = 0.55f + 0.45f * t
            val rgb = AndroidColor.HSVToColor(hsv) and 0xFFFFFF
            wideGlow[b].color = rgb or alphaByte((0.025f + 0.05f * t) * bright)
            midGlow[b].color = rgb or alphaByte((0.05f + 0.12f * t) * bright)
            core[b].color = rgb or alphaByte((0.3f + 0.7f * t) * bright)
            dotGlow[b].color = rgb or alphaByte((0.06f + 0.14f * t) * bright)
            dotCore[b].color = rgb or alphaByte((0.45f + 0.55f * t) * bright)
            if (b == 1) {
                val spin = min(1f, (abs(spinXW) + abs(spinYW)) * 0.5f)
                ghostPaint.color = rgb or alphaByte((0.06f + 0.22f * spin) * bright)
            }
        }
    }

    private fun bucket(t: Float): Int = (t * (BUCKETS - 1) + 0.5f).toInt().coerceIn(0, BUCKETS - 1)

    private fun buildGeometry() {
        lineCount.fill(0)
        dotCount.fill(0)
        var n = 0
        for (e in 0 until 32) {
            val a = edges[e * 2]
            val c = edges[e * 2 + 1]
            val b = bucket((depth[a] + depth[c]) * 0.5f)
            val pts = linePts[b]
            val k = lineCount[b]
            pts[k] = sx[a]; pts[k + 1] = sy[a]; pts[k + 2] = sx[c]; pts[k + 3] = sy[c]
            lineCount[b] = k + 4
            ghostPts[n] = ghostX[a]; ghostPts[n + 1] = ghostY[a]
            ghostPts[n + 2] = ghostX[c]; ghostPts[n + 3] = ghostY[c]
            n += 4
        }
        for (i in 0 until 16) {
            val b = bucket(depth[i])
            val k = dotCount[b]
            dotPts[b][k] = sx[i]
            dotPts[b][k + 1] = sy[i]
            dotCount[b] = k + 2
        }
    }

    fun draw(scope: DrawScope) {
        scope.drawRect(backdrop)
        if (!projected) return
        updateColors()
        buildGeometry()
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.drawLines(ghostPts, 0, ghostPts.size, ghostPaint)
            for (b in 0 until BUCKETS) {
                val c = lineCount[b]
                if (c == 0) continue
                nc.drawLines(linePts[b], 0, c, wideGlow[b])
                nc.drawLines(linePts[b], 0, c, midGlow[b])
            }
            for (b in 0 until BUCKETS) {
                if (lineCount[b] > 0) nc.drawLines(linePts[b], 0, lineCount[b], core[b])
            }
            for (b in 0 until BUCKETS) {
                val c = dotCount[b]
                if (c == 0) continue
                nc.drawPoints(dotPts[b], 0, c, dotGlow[b])
                nc.drawPoints(dotPts[b], 0, c, dotCore[b])
            }
        }
    }
}
