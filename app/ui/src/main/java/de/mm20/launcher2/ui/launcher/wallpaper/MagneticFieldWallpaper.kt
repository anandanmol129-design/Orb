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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Magnetic field: a dense grid of compass needles, like iron filings on glass, tracing the field
 * of a few slow-drifting magnetic poles that wander the screen on Lissajous paths. Every needle is
 * a damped torsional spring loosely coupled to its neighbours, so it overshoots and settles and
 * disturbances ripple across the grid. Needles grow, brighten and warm from steel blue toward
 * amber where the field is strong; their north ends are tinted.
 *
 * Touching turns the finger into a strong pole whose polarity alternates with each new press,
 * so successive touches flip the nearby needles. On release the finger's pole fades out over
 * about a second and the needles relax back in a visible wave.
 */
@Composable
fun MagneticFieldWallpaper(modifier: Modifier = Modifier) {
    val field = remember { MagneticField() }
    val frame = remember { mutableLongStateOf(0L) }
    val generation = rememberResetGeneration()

    // Moving the poles somewhere new on every unlock makes the grid swing into a fresh pattern.
    LaunchedEffect(generation) { field.reseed() }

    LaunchedEffect(Unit) {
        var last = 0L
        var wasPressed = false
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                val pressed = WallpaperPointer.pressed
                field.pointer(WallpaperPointer.x, WallpaperPointer.y, pressed, pressed && !wasPressed)
                wasPressed = pressed
                field.step(dt)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        field.ensure(size.width, size.height)
        field.draw(this)
    }
}

private const val MAX_DT = 1f / 30f
private const val TARGET_NEEDLES = 2000f
private const val BUCKETS = 14
private const val GLOW_FROM_BUCKET = 5

// Field model; positions are in units of the screen width.
private const val AMBIENT_POLES = 3
private const val AMBIENT_SOFT = 0.06f
private const val BACKGROUND_FIELD = 0.35f
private const val FIELD_REF = 4f
private const val FINGER_CHARGE = 3.5f
private const val FINGER_SOFT = 0.04f
private const val FINGER_ATTACK = 0.08f
private const val FINGER_RELEASE = 0.33f

// Needle dynamics.
private const val K_MIN = 6f
private const val K_FIELD = 60f
private const val COUPLING = 18f
private const val DAMPING = 3f
private const val AGITATION = 0.05f

// Needle geometry as fractions of the grid spacing.
private const val LEN_MIN = 0.2f
private const val LEN_GAIN = 0.22f
private const val TIP_START = 0.2f

private val AMBIENT_CHARGE = floatArrayOf(1f, -1f, 0.75f)
private val LISSAJOUS_FX = floatArrayOf(0.110f, 0.083f, 0.137f)
private val LISSAJOUS_FY = floatArrayOf(0.077f, 0.131f, 0.059f)

private val BODY_STOPS = floatArrayOf(0f, 0.3f, 0.6f, 0.82f, 1f)
private val BODY_COLORS = arrayOf(
    Color(0xFF2A4560).copy(alpha = 0.55f),
    Color(0xFF4F7FAE).copy(alpha = 0.75f),
    Color(0xFF9FD8FF).copy(alpha = 0.95f),
    Color(0xFFF4F1E8),
    Color(0xFFFFC069),
)
private val TIP_STOPS = floatArrayOf(0f, 0.5f, 1f)
private val TIP_COLORS = arrayOf(
    Color(0xFF5A2E4A).copy(alpha = 0.5f),
    Color(0xFFD9506A).copy(alpha = 0.85f),
    Color(0xFFFF7A4D),
)

private val NORTH_GLOW = Color(0xFFFF7A59)
private val SOUTH_GLOW = Color(0xFF3FA9FF)

private fun ramp(stops: FloatArray, colors: Array<Color>, v: Float): Color {
    for (i in 1 until stops.size) {
        if (v <= stops[i]) {
            val t = (v - stops[i - 1]) / (stops[i] - stops[i - 1])
            return lerp(colors[i - 1], colors[i], t.coerceIn(0f, 1f))
        }
    }
    return colors.last()
}

private fun linePaint(color: Color, additive: Boolean): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        this.color = color.toArgb()
        if (additive) xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

private fun glowBrush(color: Color, radius: Float): Brush = Brush.radialGradient(
    colors = listOf(
        color.copy(alpha = 0.35f),
        color.copy(alpha = 0.12f),
        color.copy(alpha = 0.03f),
        Color.Transparent,
    ),
    center = Offset.Zero,
    radius = radius,
)

private class MagneticField {
    private var width = 0f
    private var height = 0f
    private var cols = 0
    private var rows = 0
    private var count = 0
    private var spacing = 0f

    // Per-needle state. Positions in pixels (px*) and in width units (u*).
    private var pxs = FloatArray(0)
    private var pys = FloatArray(0)
    private var uxs = FloatArray(0)
    private var uys = FloatArray(0)
    private var angle = FloatArray(0)
    private var omega = FloatArray(0)
    private var cosA = FloatArray(0)
    private var sinA = FloatArray(0)
    private var fieldX = FloatArray(0) // unit field direction
    private var fieldY = FloatArray(0)
    private var strength = FloatArray(0) // 0..1 saturating field magnitude
    private var stiffness = FloatArray(0)
    private var needsAlign = false

    private var time = 0f
    private val phase = FloatArray(AMBIENT_POLES * 2)

    // Ambient poles followed by the finger pole.
    private val poleX = FloatArray(AMBIENT_POLES + 1)
    private val poleY = FloatArray(AMBIENT_POLES + 1)
    private val poleQ = FloatArray(AMBIENT_POLES + 1)
    private val poleSoft2 = FloatArray(AMBIENT_POLES + 1)

    private var pointerPressed = false
    private var fingerSign = -1f
    private var fingerLevel = 0f

    // Line endpoints grouped by brightness so each group is one native drawLines call.
    private var bodyPts = Array(BUCKETS) { FloatArray(0) }
    private var tipPts = Array(BUCKETS) { FloatArray(0) }
    private val bodyCount = IntArray(BUCKETS)
    private val tipCount = IntArray(BUCKETS)

    private val bodyPaints = Array(BUCKETS) { b ->
        linePaint(ramp(BODY_STOPS, BODY_COLORS, bucketLevel(b)), additive = false)
    }
    private val tipPaints = Array(BUCKETS) { b ->
        linePaint(ramp(TIP_STOPS, TIP_COLORS, bucketLevel(b)), additive = false)
    }
    private val glowPaints = Array(BUCKETS) { b ->
        val v = bucketLevel(b)
        val c = ramp(BODY_STOPS, BODY_COLORS, v)
        linePaint(c.copy(alpha = 0.05f + 0.2f * v), additive = true)
    }

    private var backdrop: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))
    private var northGlow: Brush = backdrop
    private var southGlow: Brush = backdrop
    private var northTouch: Brush = backdrop
    private var southTouch: Brush = backdrop
    private var ambientGlowRadius = 0f
    private var touchGlowRadius = 0f

    init {
        reseed()
        for (k in 0 until AMBIENT_POLES) {
            poleQ[k] = AMBIENT_CHARGE[k]
            poleSoft2[k] = AMBIENT_SOFT * AMBIENT_SOFT
        }
        poleSoft2[AMBIENT_POLES] = FINGER_SOFT * FINGER_SOFT
    }

    private fun bucketLevel(b: Int): Float = b / (BUCKETS - 1f)

    fun reseed() {
        for (i in phase.indices) phase[i] = Random.nextFloat() * 2f * PI.toFloat()
    }

    fun ensure(w: Float, h: Float) {
        if ((w == width && h == height) || w <= 0f || h <= 0f) return
        width = w
        height = h

        val target = sqrt(w * h / TARGET_NEEDLES)
        cols = max(8, (w / target).roundToInt())
        rows = max(8, (h / target).roundToInt())
        count = cols * rows
        spacing = w / cols
        val rowSpacing = h / rows

        pxs = FloatArray(count)
        pys = FloatArray(count)
        uxs = FloatArray(count)
        uys = FloatArray(count)
        angle = FloatArray(count)
        omega = FloatArray(count)
        cosA = FloatArray(count)
        sinA = FloatArray(count)
        fieldX = FloatArray(count)
        fieldY = FloatArray(count)
        strength = FloatArray(count)
        stiffness = FloatArray(count)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                pxs[i] = (c + 0.5f) * spacing
                pys[i] = (r + 0.5f) * rowSpacing
                uxs[i] = pxs[i] / w
                uys[i] = pys[i] / w
                cosA[i] = 1f
            }
        }
        bodyPts = Array(BUCKETS) { FloatArray(count * 4) }
        tipPts = Array(BUCKETS) { FloatArray(count * 4) }
        needsAlign = true

        for (b in 0 until BUCKETS) {
            val v = bucketLevel(b)
            val stroke = spacing * (0.07f + 0.07f * v)
            bodyPaints[b].strokeWidth = stroke
            tipPaints[b].strokeWidth = stroke
            glowPaints[b].strokeWidth = spacing * (0.22f + 0.25f * v)
        }

        backdrop = Brush.verticalGradient(
            listOf(Color(0xFF05070D), Color(0xFF0A0F1C), Color(0xFF04050A)),
        )
        ambientGlowRadius = w * 0.45f
        touchGlowRadius = w * 0.3f
        northGlow = glowBrush(NORTH_GLOW, ambientGlowRadius)
        southGlow = glowBrush(SOUTH_GLOW, ambientGlowRadius)
        northTouch = glowBrush(NORTH_GLOW, touchGlowRadius)
        southTouch = glowBrush(SOUTH_GLOW, touchGlowRadius)
    }

    fun pointer(x: Float, y: Float, pressed: Boolean, newPress: Boolean) {
        if (width <= 0f) return
        if (newPress) fingerSign = -fingerSign
        pointerPressed = pressed
        if (pressed) {
            poleX[AMBIENT_POLES] = x / width
            poleY[AMBIENT_POLES] = y / width
        }
    }

    fun step(dt: Float) {
        if (count == 0) return
        time += dt

        val aspect = height / width
        for (k in 0 until AMBIENT_POLES) {
            poleX[k] = 0.5f + 0.42f * sin(time * LISSAJOUS_FX[k] + phase[2 * k])
            poleY[k] = aspect * (0.5f + 0.40f * sin(time * LISSAJOUS_FY[k] + phase[2 * k + 1]))
        }
        fingerLevel = if (pointerPressed) {
            fingerLevel + (1f - fingerLevel) * (1f - exp(-dt / FINGER_ATTACK))
        } else {
            fingerLevel * exp(-dt / FINGER_RELEASE)
        }
        poleQ[AMBIENT_POLES] = FINGER_CHARGE * fingerSign * fingerLevel

        computeField()
        if (needsAlign) {
            for (i in 0 until count) {
                val a = atan2(fieldY[i], fieldX[i]) + (Random.nextFloat() - 0.5f) * 0.9f
                angle[i] = a
                cosA[i] = cos(a)
                sinA[i] = sin(a)
            }
            needsAlign = false
        }

        val substeps = if (dt > 0.02f) 2 else 1
        val h = dt / substeps
        repeat(substeps) { integrate(h) }
    }

    private fun computeField() {
        val poles = if (fingerLevel > 0.002f) AMBIENT_POLES + 1 else AMBIENT_POLES
        val bgAngle = time * 0.045f
        val bgX = BACKGROUND_FIELD * cos(bgAngle)
        val bgY = BACKGROUND_FIELD * sin(bgAngle)
        for (i in 0 until count) {
            val x = uxs[i]
            val y = uys[i]
            var bx = bgX
            var by = bgY
            // 2D monopoles (1/r falloff): field lines run from positive to negative poles.
            for (p in 0 until poles) {
                val dx = x - poleX[p]
                val dy = y - poleY[p]
                val f = poleQ[p] / (dx * dx + dy * dy + poleSoft2[p])
                bx += dx * f
                by += dy * f
            }
            val mag = sqrt(bx * bx + by * by)
            val inv = 1f / (mag + 1e-4f)
            fieldX[i] = bx * inv
            fieldY[i] = by * inv
            val e = mag / (mag + FIELD_REF)
            strength[i] = e
            stiffness[i] = K_MIN + K_FIELD * e
        }
    }

    private fun integrate(h: Float) {
        val cols = cols
        val rows = rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val i = r * cols + c
                val ci = cosA[i]
                val si = sinA[i]
                // Torque of a magnetic dipole: m x B, with m the needle's north direction.
                var torque = stiffness[i] * (ci * fieldY[i] - si * fieldX[i])
                // Neighbour coupling, sum of sin(theta_j - theta_i), carries disturbances as waves.
                var coupling = 0f
                if (c > 0) coupling += sinA[i - 1] * ci - cosA[i - 1] * si
                if (c < cols - 1) coupling += sinA[i + 1] * ci - cosA[i + 1] * si
                if (r > 0) coupling += sinA[i - cols] * ci - cosA[i - cols] * si
                if (r < rows - 1) coupling += sinA[i + cols] * ci - cosA[i + cols] * si
                torque += COUPLING * coupling - DAMPING * omega[i]
                omega[i] += torque * h
            }
        }
        val twoPi = 2f * PI.toFloat()
        for (i in 0 until count) {
            var a = angle[i] + omega[i] * h
            if (a > PI.toFloat()) a -= twoPi else if (a < -PI.toFloat()) a += twoPi
            angle[i] = a
            cosA[i] = cos(a)
            sinA[i] = sin(a)
        }
    }

    private fun buildLines() {
        bodyCount.fill(0)
        tipCount.fill(0)
        val maxBucket = BUCKETS - 1
        for (i in 0 until count) {
            val v = ((strength[i] - 0.12f) * 1.35f + AGITATION * abs(omega[i])).coerceIn(0f, 1f)
            val b = (v * maxBucket + 0.5f).toInt()
            val half = spacing * (LEN_MIN + LEN_GAIN * v)
            val dx = cosA[i] * half
            val dy = sinA[i] * half
            val x = pxs[i]
            val y = pys[i]

            val body = bodyPts[b]
            var k = bodyCount[b]
            body[k] = x - dx
            body[k + 1] = y - dy
            body[k + 2] = x + dx
            body[k + 3] = y + dy
            bodyCount[b] = k + 4

            val tip = tipPts[b]
            k = tipCount[b]
            tip[k] = x + dx * TIP_START
            tip[k + 1] = y + dy * TIP_START
            tip[k + 2] = x + dx
            tip[k + 3] = y + dy
            tipCount[b] = k + 4
        }
    }

    fun draw(scope: DrawScope) {
        scope.drawRect(backdrop)
        if (count == 0) return

        for (k in 0 until AMBIENT_POLES) {
            scope.translate(poleX[k] * width, poleY[k] * width) {
                drawCircle(
                    brush = if (poleQ[k] > 0f) northGlow else southGlow,
                    radius = ambientGlowRadius,
                    center = Offset.Zero,
                    alpha = 0.32f * abs(poleQ[k]),
                    blendMode = BlendMode.Plus,
                )
            }
        }
        if (fingerLevel > 0.01f) {
            scope.translate(poleX[AMBIENT_POLES] * width, poleY[AMBIENT_POLES] * width) {
                drawCircle(
                    brush = if (fingerSign > 0f) northTouch else southTouch,
                    radius = touchGlowRadius,
                    center = Offset.Zero,
                    alpha = 0.6f * fingerLevel,
                    blendMode = BlendMode.Plus,
                )
            }
        }

        buildLines()
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (b in GLOW_FROM_BUCKET until BUCKETS) {
                if (bodyCount[b] > 0) nc.drawLines(bodyPts[b], 0, bodyCount[b], glowPaints[b])
            }
            for (b in 0 until BUCKETS) {
                if (bodyCount[b] > 0) nc.drawLines(bodyPts[b], 0, bodyCount[b], bodyPaints[b])
            }
            for (b in 0 until BUCKETS) {
                if (tipCount[b] > 0) nc.drawLines(tipPts[b], 0, tipCount[b], tipPaints[b])
            }
        }
    }
}
