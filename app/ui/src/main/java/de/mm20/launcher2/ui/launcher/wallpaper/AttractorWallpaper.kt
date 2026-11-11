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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Strange attractor: a few thousand glowing tracers all riding the same chaotic flow, so the
 * cloud draws a luminous 3D sculpture of the attractor that slowly turns in space. Every
 * ~50 seconds the flow morphs into the next attractor (Lorenz, Aizawa, Halvorsen, Chen, Thomas)
 * by cross-fading the velocity fields, so the sculpture melts into a new shape without a cut.
 * Tracers are coloured by speed (deep blue when slow, through violet and pink, to white-hot when
 * fast) and nearer tracers are brighter and thicker. Dragging spins the sculpture (horizontal
 * yaws, vertical pitches); on release it coasts and eases back to its ambient rotation.
 */
@Composable
fun AttractorWallpaper(modifier: Modifier = Modifier) {
    val sim = remember { AttractorSim() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        if (!sim.ready) withContext(Dispatchers.Default) { sim.prepare() }
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                sim.updateRotation(dt, WallpaperPointer.pressed, WallpaperPointer.x, WallpaperPointer.y)
                sim.step(dt)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        sim.render(this)
    }
}

private const val PARTICLES = 1800
private const val TRAIL = 18 // history samples per tracer (plus its live head)
private const val SUBSTEPS = 3
private const val MAX_DT = 0.05f
private const val HISTORY_INTERVAL = 1f / 60f // trails keep the same length at 60 and 120 Hz
private const val TARGET_SPEED = 1.15f // mean tracer speed in normalised units per second
private const val ESCAPE_RADIUS_SQ = 6.25f
private const val RESERVOIR = 4096
private const val HOLD_SECONDS = 44f
private const val MORPH_SECONDS = 8f

private const val HUE_BINS = 7
private const val INTENSITY_BINS = 6
private const val GLOW_BINS = 2 // the brightest intensity bins also get a wide soft halo
private const val BINS = HUE_BINS * INTENSITY_BINS

private const val TWO_PI = 6.2831855f
private const val AMBIENT_YAW = TWO_PI / 50f
private const val BASE_PITCH = 0.38f
private const val DRAG_GAIN = 3.4f
private const val CAMERA_DISTANCE = 3.6f

private const val LORENZ = 0
private const val AIZAWA = 1
private const val HALVORSEN = 2
private const val CHEN = 3
private const val THOMAS = 4
private const val KINDS = 5

// Slow to fast: electric blue, indigo, violet, magenta, hot pink, white-hot.
private val PALETTE = intArrayOf(
    0xFF2448E0.toInt(),
    0xFF2F86FF.toInt(),
    0xFF5A5CFF.toInt(),
    0xFF8C4BFF.toInt(),
    0xFFCF4AF2.toInt(),
    0xFFFF5EA9.toInt(),
    0xFFFFE8F3.toInt(),
)

private class AttractorSim {
    @Volatile
    var ready = false
        private set

    // Tracer state lives in a normalised space: the attractor is centred on the origin and fits
    // in [-1, 1], with the "up" axis of the attractor mapped to y.
    private val px = FloatArray(PARTICLES)
    private val py = FloatArray(PARTICLES)
    private val pz = FloatArray(PARTICLES)
    private val speed = FloatArray(PARTICLES)
    private var meanSpeed = TARGET_SPEED

    private val hist = FloatArray(PARTICLES * TRAIL * 3)
    private var head = 0
    private var historyClock = 0f

    // Per-attractor fit, measured once by integrating a long trajectory.
    private val centreX = FloatArray(KINDS)
    private val centreY = FloatArray(KINDS)
    private val centreZ = FloatArray(KINDS)
    private val scale = FloatArray(KINDS) { 1f }
    private val velScale = FloatArray(KINDS) { 1f }
    private val reservoir = Array(KINDS) { FloatArray(RESERVOIR * 3) }

    private var current = LORENZ
    private var next = AIZAWA
    private var phase = 0f
    private var morph = 0f
    var time = 0f
        private set

    // Scratch outputs of the field functions; fields avoid allocating return values.
    private var fx = 0f
    private var fy = 0f
    private var fz = 0f
    private var ex = 0f
    private var ey = 0f
    private var ez = 0f
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f

    // Rotation state.
    private var yaw = 0.6f
    private var yawVel = AMBIENT_YAW
    private var pitchOff = 0f
    private var pitchVel = 0f
    private var wasPressed = false
    private var lastX = 0f
    private var lastY = 0f

    // Projection and draw buffers.
    private val projX = FloatArray(PARTICLES * TRAIL)
    private val projY = FloatArray(PARTICLES * TRAIL)
    private val projD = FloatArray(PARTICLES * TRAIL)
    private val curX = FloatArray(PARTICLES)
    private val curY = FloatArray(PARTICLES)
    private val curD = FloatArray(PARTICLES)
    private val segBin = ByteArray(PARTICLES * TRAIL)
    private val lines = FloatArray(PARTICLES * TRAIL * 4)
    private val binCount = IntArray(BINS)
    private val binStart = IntArray(BINS)
    private val binFill = IntArray(BINS)
    private var ox = 0f
    private var oy = 0f
    private var od = 0f
    private var m00 = 1f
    private var m01 = 0f
    private var m02 = 0f
    private var m10 = 0f
    private var m11 = 1f
    private var m12 = 0f
    private var m20 = 0f
    private var m21 = 0f
    private var m22 = 1f
    private var originX = 0f
    private var originY = 0f
    private var projScale = 1f

    private val corePaints = Array(BINS) { newPaint() }
    private val glowPaints = Array(HUE_BINS * GLOW_BINS) { newPaint() }

    private var width = 0f
    private var height = 0f
    var minSide = 0f
        private set
    private var baseBrush: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))
    private var glowBrush: Brush = baseBrush
    private var vignetteBrush: Brush = baseBrush

    private fun newPaint() = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    // ---------------------------------------------------------------- setup

    fun prepare() {
        for (k in 0 until KINDS) fit(k)
        if (velScale[current] == 0f) current = nextUsable(current)
        next = nextUsable(current)
        for (i in 0 until PARTICLES) placeOn(i, current)
        ready = true
    }

    /** The attractor after [kind], skipping any whose fit failed (velScale 0). */
    private fun nextUsable(kind: Int): Int {
        var k = (kind + 1) % KINDS
        while (velScale[k] == 0f && k != kind) k = (k + 1) % KINDS
        return k
    }

    /** Integrates one long trajectory of [kind] to find its centre, extent, speed and samples. */
    private fun fit(kind: Int) {
        var x: Float
        var y: Float
        var z: Float
        val h: Float
        when (kind) {
            LORENZ -> { x = 1f; y = 1f; z = 1f; h = 0.005f }
            AIZAWA -> { x = 0.1f; y = 0f; z = 0f; h = 0.01f }
            HALVORSEN -> { x = -1.48f; y = -1.51f; z = 2.04f; h = 0.005f }
            CHEN -> { x = -0.1f; y = 0.5f; z = -0.6f; h = 0.003f }
            else -> { x = 1.1f; y = 1.1f; z = -0.01f; h = 0.05f }
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var speedSum = 0.0
        val samples = reservoir[kind]
        val stride = 3
        val transient = 3000
        val total = transient + RESERVOIR * stride
        for (n in 0 until total) {
            rawField(kind, x, y, z)
            val mx = x + 0.5f * h * fx
            val my = y + 0.5f * h * fy
            val mz = z + 0.5f * h * fz
            rawField(kind, mx, my, mz)
            x += h * fx
            y += h * fy
            z += h * fz
            if (n < transient) continue
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            minZ = min(minZ, z); maxZ = max(maxZ, z)
            speedSum += sqrt(fx * fx + fy * fy + fz * fz)
            val s = (n - transient) / stride
            if ((n - transient) % stride == 0 && s < RESERVOIR) {
                samples[s * 3] = x
                samples[s * 3 + 1] = y
                samples[s * 3 + 2] = z
            }
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val cz = (minZ + maxZ) / 2f
        val sc = max(maxX - minX, max(maxY - minY, maxZ - minZ)) / 2f
        val meanRaw = (speedSum / (total - transient)).toFloat()
        val sane = sc.isFinite() && sc > 0f && meanRaw.isFinite() && meanRaw > 0f &&
            cx.isFinite() && cy.isFinite() && cz.isFinite()
        if (!sane) {
            // Should never happen with these parameters; keep the flow harmless if it does.
            centreX[kind] = 0f; centreY[kind] = 0f; centreZ[kind] = 0f
            scale[kind] = 1f
            velScale[kind] = 0f
            for (i in samples.indices) samples[i] = (Random.nextFloat() - 0.5f) * 1.2f
            return
        }
        centreX[kind] = cx
        centreY[kind] = cy
        centreZ[kind] = cz
        scale[kind] = sc
        // Normalised velocity = timeScale * f / scale; choosing timeScale so the mean normalised
        // speed is TARGET_SPEED makes the scale cancel out.
        velScale[kind] = TARGET_SPEED / meanRaw
        for (s in 0 until RESERVOIR) {
            val rx = samples[s * 3]
            val ry = samples[s * 3 + 1]
            val rz = samples[s * 3 + 2]
            samples[s * 3] = (rx - cx) / sc
            samples[s * 3 + 1] = (rz - cz) / sc // attractor z is "up"
            samples[s * 3 + 2] = (ry - cy) / sc
        }
    }

    /** Puts tracer [i] on a random known point of attractor [kind] and collapses its trail. */
    private fun placeOn(i: Int, kind: Int) {
        val samples = reservoir[kind]
        val s = Random.nextInt(RESERVOIR) * 3
        val x = samples[s] + (Random.nextFloat() - 0.5f) * 0.004f
        val y = samples[s + 1] + (Random.nextFloat() - 0.5f) * 0.004f
        val z = samples[s + 2] + (Random.nextFloat() - 0.5f) * 0.004f
        px[i] = x
        py[i] = y
        pz[i] = z
        speed[i] = meanSpeed
        val base = i * TRAIL * 3
        for (k in 0 until TRAIL) {
            hist[base + k * 3] = x
            hist[base + k * 3 + 1] = y
            hist[base + k * 3 + 2] = z
        }
    }

    // ---------------------------------------------------------------- dynamics

    /** Raw attractor ODE in its own coordinates; writes the derivative to fx, fy, fz. */
    private fun rawField(kind: Int, x: Float, y: Float, z: Float) {
        when (kind) {
            LORENZ -> {
                fx = 10f * (y - x)
                fy = x * (28f - z) - y
                fz = x * y - (8f / 3f) * z
            }
            AIZAWA -> {
                val zb = z - 0.7f
                fx = zb * x - 3.5f * y
                fy = 3.5f * x + zb * y
                fz = 0.6f + 0.95f * z - z * z * z / 3f -
                    (x * x + y * y) * (1f + 0.25f * z) + 0.1f * z * x * x * x
            }
            HALVORSEN -> {
                val a = 1.89f
                fx = -a * x - 4f * y - 4f * z - y * y
                fy = -a * y - 4f * z - 4f * x - z * z
                fz = -a * z - 4f * x - 4f * y - x * x
            }
            CHEN -> {
                fx = 35f * (y - x)
                fy = (28f - 35f) * x - x * z + 28f * y
                fz = x * y - 3f * z
            }
            else -> {
                val b = 0.208186f
                fx = sin(y) - b * x
                fy = sin(z) - b * y
                fz = sin(x) - b * z
            }
        }
    }

    /** Field of attractor [kind] in normalised space; writes ex, ey, ez. */
    private fun normField(kind: Int, x: Float, y: Float, z: Float) {
        val s = scale[kind]
        rawField(kind, centreX[kind] + s * x, centreY[kind] + s * z, centreZ[kind] + s * y)
        val k = velScale[kind]
        ex = k * fx
        ey = k * fz
        ez = k * fy
    }

    /** The morphing flow: a smooth blend of the current and next attractor's velocity fields. */
    private fun velocity(x: Float, y: Float, z: Float) {
        val m = morph
        if (m <= 0f) {
            normField(current, x, y, z)
            vx = ex; vy = ey; vz = ez
        } else if (m >= 1f) {
            normField(next, x, y, z)
            vx = ex; vy = ey; vz = ez
        } else {
            normField(current, x, y, z)
            val ax = ex
            val ay = ey
            val az = ez
            normField(next, x, y, z)
            val a = 1f - m
            vx = a * ax + m * ex
            vy = a * ay + m * ey
            vz = a * az + m * ez
        }
    }

    fun step(dt: Float) {
        if (!ready || dt <= 0f) return
        time += dt
        phase += dt
        if (phase >= HOLD_SECONDS + MORPH_SECONDS) {
            phase -= HOLD_SECONDS + MORPH_SECONDS
            current = next
            next = nextUsable(next)
        }
        val u = ((phase - HOLD_SECONDS) / MORPH_SECONDS).coerceIn(0f, 1f)
        morph = u * u * (3f - 2f * u)

        val h = dt / SUBSTEPS
        val hh = 0.5f * h
        var speedSum = 0f
        for (i in 0 until PARTICLES) {
            var x = px[i]
            var y = py[i]
            var z = pz[i]
            for (s in 0 until SUBSTEPS) {
                velocity(x, y, z)
                val mx = x + hh * vx
                val my = y + hh * vy
                val mz = z + hh * vz
                velocity(mx, my, mz)
                x += h * vx
                y += h * vy
                z += h * vz
            }
            // Also false for NaN, so a diverged tracer is caught here too.
            if (x * x + y * y + z * z < ESCAPE_RADIUS_SQ) {
                px[i] = x
                py[i] = y
                pz[i] = z
                val sp = sqrt(vx * vx + vy * vy + vz * vz)
                speed[i] = sp
                speedSum += sp
            } else {
                placeOn(i, if (morph < 0.5f) current else next)
                speedSum += meanSpeed
            }
        }
        val avg = speedSum / PARTICLES
        if (avg.isFinite() && avg > 1e-4f) meanSpeed += (avg - meanSpeed) * min(1f, dt * 2f)

        historyClock += dt
        if (historyClock >= HISTORY_INTERVAL) {
            historyClock = if (historyClock > 2f * HISTORY_INTERVAL) 0f else historyClock - HISTORY_INTERVAL
            for (i in 0 until PARTICLES) {
                val o = (i * TRAIL + head) * 3
                hist[o] = px[i]
                hist[o + 1] = py[i]
                hist[o + 2] = pz[i]
            }
            head = (head + 1) % TRAIL
        }
    }

    fun updateRotation(dt: Float, pressed: Boolean, x: Float, y: Float) {
        val side = minSide
        if (pressed && wasPressed && side > 0f && dt > 0f) {
            val dYaw = (x - lastX) / side * DRAG_GAIN
            val dPitch = (y - lastY) / side * DRAG_GAIN
            yaw += dYaw
            pitchOff += dPitch
            val follow = min(1f, dt * 20f)
            yawVel += (dYaw / dt - yawVel) * follow
            pitchVel += (dPitch / dt - pitchVel) * follow
        } else if (pressed && !wasPressed) {
            // Catching the sculpture stops it; the drag velocity is measured from here.
            yawVel = 0f
            pitchVel = 0f
        } else if (!pressed) {
            yawVel = yawVel.coerceIn(-14f, 14f)
            pitchVel = pitchVel.coerceIn(-14f, 14f)
            yawVel += (AMBIENT_YAW - yawVel) * (1f - exp(-dt * 0.7f))
            pitchVel *= exp(-dt * 1.4f)
            yaw += yawVel * dt
            pitchOff += pitchVel * dt
            while (pitchOff > TWO_PI / 2f) pitchOff -= TWO_PI
            while (pitchOff < -TWO_PI / 2f) pitchOff += TWO_PI
            pitchOff *= exp(-dt * 0.35f) // slowly settle back to the resting tilt
        }
        while (yaw > TWO_PI) yaw -= TWO_PI
        while (yaw < 0f) yaw += TWO_PI
        wasPressed = pressed
        lastX = x
        lastY = y
    }

    // ---------------------------------------------------------------- rendering

    private fun ensureSize(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        minSide = min(w, h)
        val centre = Offset(w / 2f, h / 2f)
        baseBrush = Brush.verticalGradient(
            listOf(Color(0xFF040410), Color(0xFF07061A), Color(0xFF020208)),
        )
        glowBrush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF3A2490).copy(alpha = 0.34f),
                Color(0xFF16246A).copy(alpha = 0.16f),
                Color.Transparent,
            ),
            center = centre,
            radius = minSide * 0.85f,
        )
        vignetteBrush = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.65f)),
            center = centre,
            radius = max(w, h) * 0.75f,
        )
        val base = minSide * 0.0016f
        for (hue in 0 until HUE_BINS) {
            for (b in 0 until INTENSITY_BINS) {
                val f = (b + 1f) / INTENSITY_BINS
                val p = corePaints[hue * INTENSITY_BINS + b]
                p.color = PALETTE[hue]
                p.alpha = ((0.08f + 0.47f * f.pow(1.6f)) * 255f).toInt()
                p.strokeWidth = base * (0.7f + 1.2f * b / (INTENSITY_BINS - 1))
            }
            for (g in 0 until GLOW_BINS) {
                val p = glowPaints[hue * GLOW_BINS + g]
                p.color = PALETTE[hue]
                p.alpha = ((0.045f + 0.03f * g) * 255f).toInt()
                p.strokeWidth = base * (5.5f + 3f * g)
            }
        }
    }

    private fun computeMatrix() {
        val pitch = BASE_PITCH + 0.12f * sin(time * 0.09f) + pitchOff
        val roll = 0.22f + 0.06f * sin(time * 0.053f)
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cr = cos(roll)
        val sr = sin(roll)
        // R = Rz(roll) * Rx(pitch) * Ry(yaw)
        val a00 = cy; val a01 = 0f; val a02 = sy
        val a10 = sp * sy; val a11 = cp; val a12 = -sp * cy
        val a20 = -cp * sy; val a21 = sp; val a22 = cp * cy
        m00 = cr * a00 - sr * a10; m01 = cr * a01 - sr * a11; m02 = cr * a02 - sr * a12
        m10 = sr * a00 + cr * a10; m11 = sr * a01 + cr * a11; m12 = sr * a02 + cr * a12
        m20 = a20; m21 = a21; m22 = a22
    }

    /** Projects a normalised point; writes screen ox, oy and nearness od in 0..1. */
    private fun project(x: Float, y: Float, z: Float) {
        val rx = m00 * x + m01 * y + m02 * z
        val ry = m10 * x + m11 * y + m12 * z
        val rz = m20 * x + m21 * y + m22 * z
        val f = CAMERA_DISTANCE / (CAMERA_DISTANCE - rz)
        ox = originX + rx * projScale * f
        oy = originY - ry * projScale * f
        od = ((rz + 1.2f) / 2.4f).coerceIn(0f, 1f)
    }

    fun render(scope: DrawScope) {
        val w = scope.size.width
        val h = scope.size.height
        if (w <= 0f || h <= 0f) return
        ensureSize(w, h)
        scope.drawRect(baseBrush)
        scope.drawRect(glowBrush, alpha = 0.8f + 0.2f * sin(time * 0.31f))
        if (ready) drawCloud(scope)
        scope.drawRect(vignetteBrush)
    }

    private fun drawCloud(scope: DrawScope) {
        computeMatrix()
        originX = width / 2f
        originY = height / 2f
        projScale = minSide * 0.42f

        for (n in 0 until PARTICLES * TRAIL) {
            val o = n * 3
            project(hist[o], hist[o + 1], hist[o + 2])
            projX[n] = ox
            projY[n] = oy
            projD[n] = od
        }
        for (i in 0 until PARTICLES) {
            project(px[i], py[i], pz[i])
            curX[i] = ox
            curY[i] = oy
            curD[i] = od
        }

        // Pass 1: classify each trail segment into a (hue, intensity) bin and count the bins.
        binCount.fill(0)
        val invMean = 1f / max(meanSpeed, 1e-4f)
        // The live head moves between history samples; shift ages so trails fade smoothly.
        val headFrac = (historyClock / HISTORY_INTERVAL).coerceIn(0f, 1f)
        var seg = 0
        for (i in 0 until PARTICLES) {
            val t = ((speed[i] * invMean - 0.35f) / 1.5f - (1f - curD[i]) * 0.25f).coerceIn(0f, 1f)
            val hue = (t * (HUE_BINS - 1) + 0.5f).toInt()
            var d = curD[i]
            for (a in 0 until TRAIL) {
                val age = (a + headFrac) / TRAIL
                val fade = 1f - age
                val intensity = fade * fade * (0.3f + 0.7f * d)
                if (intensity < 0.035f) {
                    segBin[seg] = -1
                } else {
                    val b = min((intensity * INTENSITY_BINS).toInt(), INTENSITY_BINS - 1)
                    val bin = hue * INTENSITY_BINS + b
                    segBin[seg] = bin.toByte()
                    binCount[bin]++
                }
                d = projD[i * TRAIL + (head - 1 - a + 2 * TRAIL) % TRAIL]
                seg++
            }
        }
        var acc = 0
        for (b in 0 until BINS) {
            binStart[b] = acc
            binFill[b] = acc
            acc += binCount[b]
        }

        // Pass 2: write each segment's endpoints into its bin's range of the shared buffer.
        seg = 0
        for (i in 0 until PARTICLES) {
            var x0 = curX[i]
            var y0 = curY[i]
            for (a in 0 until TRAIL) {
                val n = i * TRAIL + (head - 1 - a + 2 * TRAIL) % TRAIL
                val x1 = projX[n]
                val y1 = projY[n]
                val bin = segBin[seg].toInt()
                if (bin >= 0) {
                    val o = binFill[bin] * 4
                    lines[o] = x0
                    lines[o + 1] = y0
                    lines[o + 2] = x1
                    lines[o + 3] = y1
                    binFill[bin]++
                }
                x0 = x1
                y0 = y1
                seg++
            }
        }

        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (hue in 0 until HUE_BINS) {
                for (g in 0 until GLOW_BINS) {
                    val bin = hue * INTENSITY_BINS + INTENSITY_BINS - GLOW_BINS + g
                    val count = binCount[bin]
                    if (count > 0) nc.drawLines(lines, binStart[bin] * 4, count * 4, glowPaints[hue * GLOW_BINS + g])
                }
            }
            for (bin in 0 until BINS) {
                val count = binCount[bin]
                if (count > 0) nc.drawLines(lines, binStart[bin] * 4, count * 4, corePaints[bin])
            }
        }
    }
}
