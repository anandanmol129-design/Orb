package de.mm20.launcher2.ui.launcher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gravity sand: a falling-sand automaton of fine coloured grains that pile up with a natural
 * angle of repose and pour like an hourglass. Gravity follows the real device tilt, so tilting
 * the phone pours the pile to the new low side and turning it over makes it rain the other way
 * (lying flat falls back to straight down). A finger is a solid round obstacle that carves
 * through the sand and shoves grains aside; holding it still pours a thin stream of new sand.
 * Each grain takes its colour from a slowly cycling warm palette when it is created, so the
 * pile builds up coloured strata. When everything has settled a sparse drizzle keeps it alive.
 */
@Composable
fun GravitySandWallpaper(modifier: Modifier = Modifier) {
    val sim = remember { SandSim() }
    val gravity = remember { SandGravity() }
    val timeNanos = remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val resetGeneration = rememberResetGeneration()

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        @Suppress("DEPRECATION")
        val display: Display? = try {
            windowManager?.defaultDisplay
        } catch (e: Exception) {
            null
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val v0 = event.values[0]
                val v1 = event.values[1]
                // Remap device axes to the current screen orientation (x right, y up).
                val ax: Float
                val ay: Float
                when (display?.rotation ?: Surface.ROTATION_0) {
                    Surface.ROTATION_90 -> {
                        ax = -v1; ay = v0
                    }
                    Surface.ROTATION_180 -> {
                        ax = -v0; ay = -v1
                    }
                    Surface.ROTATION_270 -> {
                        ax = v1; ay = -v0
                    }
                    else -> {
                        ax = v0; ay = v1
                    }
                }
                // The sensor reports the reaction to gravity; flip into screen space (y down).
                gravity.onSample(-ax, ay)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensorManager != null && sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose {
            sensorManager?.unregisterListener(listener)
        }
    }

    LaunchedEffect(resetGeneration) {
        if (resetGeneration > 0) sim.requestStream(RESUME_STREAM_SECONDS)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = now
                if (sim.ready) {
                    sim.frame(dt, gravity.resolve())
                }
                timeNanos.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        timeNanos.longValue // redraw is driven by the simulation loop
        sim.ensure(size.width, size.height, density)
        val bg = sim.background ?: return@Canvas
        drawRect(bg)
        val grains = sim.image ?: return@Canvas
        val glow = sim.glowImage ?: return@Canvas
        drawImage(
            image = glow,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sim.glowW, sim.glowH),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(sim.glowDstW, sim.glowDstH),
            alpha = GLOW_ALPHA,
            blendMode = BlendMode.Plus,
            filterQuality = FilterQuality.Low,
        )
        drawImage(
            image = grains,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sim.gw, sim.gh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(sim.dstW, sim.dstH),
            filterQuality = FilterQuality.None,
        )
    }
}

private const val CELL_DP = 3.5f
private const val MAX_CELLS = 42_000
private const val STEP_RATE = 120f
private const val MAX_STEPS_PER_FRAME = 3
private const val INITIAL_FILL = 0.27f
private const val CAP_FRACTION = 0.37f
private const val FINGER_DP = 22f
private const val STILL_DP = 10f
private const val POUR_DELAY = 0.4f
private const val POUR_RATE = 110f
private const val STREAM_RATE = 240f
private const val STREAM_SECONDS = 7f
private const val RESUME_STREAM_SECONDS = 3.5f
private const val DRIZZLE_RATE = 16f
private const val QUIET_SECONDS = 3f
private const val SECONDS_PER_STOP = 7f
private const val LUT_PER_STOP = 64
private const val GLOW_BLOCK = 4
private const val GLOW_GAIN = 1.1f
private const val GLOW_ALPHA = 0.45f

// The 8 grid directions in angle order with y pointing down: 0 = right, 2 = down, 4 = left, 6 = up.
private val DIR_X = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val DIR_Y = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
private const val DIR_DOWN = 2
private val OCTANT = (PI / 4).toFloat()
private val OCTANT_HYSTERESIS = (PI / 8).toFloat() + 0.14f

private val WARM_STOPS = intArrayOf(
    0xFFE8A13F.toInt(), // amber
    0xFFDA6F7C.toInt(), // rose
    0xFFF4D98F.toInt(), // pale gold
    0xFFC4623A.toInt(), // burnt copper
    0xFFF1B48C.toInt(), // peach
    0xFFB65A7A.toInt(), // dusky mauve
)
private val COOL_ACCENTS = intArrayOf(
    0xFF6EC4CB.toInt(), // teal
    0xFF8F9DEB.toInt(), // periwinkle
    0xFFA4DCC3.toInt(), // sea glass
)

/** Low-pass filtered in-plane gravity, quantised to one of the 8 grid directions. */
private class SandGravity {
    private var gx = 0f
    private var gy = 9.81f
    private var hasData = false
    private var flat = false
    private var dir = DIR_DOWN

    fun onSample(x: Float, y: Float) {
        if (!hasData) {
            gx = x
            gy = y
            hasData = true
        } else {
            gx += (x - gx) * 0.15f
            gy += (y - gy) * 0.15f
        }
    }

    fun resolve(): Int {
        if (!hasData) return DIR_DOWN
        val m = sqrt(gx * gx + gy * gy)
        if (flat) {
            if (m > 2.6f) flat = false
        } else if (m < 1.6f) {
            flat = true
        }
        if (flat) {
            dir = DIR_DOWN
            return dir
        }
        val angle = atan2(gy, gx)
        var diff = angle - dir * OCTANT
        while (diff > PI.toFloat()) diff -= 2f * PI.toFloat()
        while (diff < -PI.toFloat()) diff += 2f * PI.toFloat()
        // Only switch once well past the octant boundary so a hand-held phone doesn't flicker.
        if (abs(diff) > OCTANT_HYSTERESIS) {
            dir = ((angle / OCTANT).roundToInt() + 8) and 7
        }
        return dir
    }
}

/** Allocation-free xorshift generator. */
private class SandRng(seed: Int) {
    private var s = if (seed == 0) 0x2545F491 else seed

    fun next(): Int {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        s = x
        return x
    }

    fun float(): Float = (next() ushr 8) / 16_777_216f

    fun below(n: Int): Int = (next() ushr 1) % n

    fun bit(): Boolean = next() and 0x100 != 0
}

private class SandSim {
    var ready = false
        private set
    var gw = 0
        private set
    var gh = 0
        private set
    var dstW = 0
        private set
    var dstH = 0
        private set
    var glowW = 0
        private set
    var glowH = 0
        private set
    var glowDstW = 0
        private set
    var glowDstH = 0
        private set
    var image: ImageBitmap? = null
        private set
    var glowImage: ImageBitmap? = null
        private set
    var background: Brush? = null
        private set

    private var width = 0f
    private var height = 0f
    private var cellW = 1f
    private var cellH = 1f
    private var fingerR = 6f
    private var stillPx = 30f

    // Each cell holds the grain's ARGB colour, or 0 when empty.
    private var cells = IntArray(0)
    // Step id in which a grain last arrived in the cell, so it moves at most once per step.
    private var stamp = IntArray(0)
    private var solid = ByteArray(0)
    private var pixels = IntArray(0)
    private var bitmap: Bitmap? = null
    private var glowBitmap: Bitmap? = null
    private var glowPixels = IntArray(0)
    private var sumR = IntArray(0)
    private var sumG = IntArray(0)
    private var sumB = IntArray(0)
    private var tmpR = IntArray(0)
    private var tmpG = IntArray(0)
    private var tmpB = IntArray(0)

    private var count = 0
    private var cap = 0
    private var stepId = 0
    private var dir = DIR_DOWN
    private val rng = SandRng(System.nanoTime().toInt())
    private val lut = IntArray(WARM_STOPS.size * LUT_PER_STOP)

    private var time = 0f
    private var palettePhase = 0f
    private var stepAcc = 0f
    private var streamLeft = 0f
    private var streamAcc = 0f
    private var drizzleAcc = 0f
    private var pourAcc = 0f
    private var quiet = 0f
    private var drizzling = false
    private var wasPressed = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorT = 0f

    // Bounds of the cells currently marked solid by the finger.
    private var sx0 = 0
    private var sy0 = 0
    private var sx1 = -1
    private var sy1 = -1

    init {
        val n = WARM_STOPS.size
        for (k in lut.indices) {
            val a = WARM_STOPS[k / LUT_PER_STOP]
            val b = WARM_STOPS[(k / LUT_PER_STOP + 1) % n]
            var f = (k % LUT_PER_STOP) / LUT_PER_STOP.toFloat()
            f = f * f * (3f - 2f * f)
            lut[k] = mix(a, b, f)
        }
    }

    fun ensure(w: Float, h: Float, density: Float) {
        if (w == width && h == height) return
        if (w < 1f || h < 1f) return
        width = w
        height = h
        val cellPx = max(CELL_DP * density, sqrt(w * h / MAX_CELLS))
        gw = max(8, (w / cellPx).roundToInt())
        gh = max(8, (h / cellPx).roundToInt())
        cellW = w / gw
        cellH = h / gh
        dstW = w.roundToInt()
        dstH = h.roundToInt()
        fingerR = FINGER_DP * density / cellW
        stillPx = STILL_DP * density

        val n = gw * gh
        cells = IntArray(n)
        stamp = IntArray(n)
        solid = ByteArray(n)
        pixels = IntArray(n)
        cap = (n * CAP_FRACTION).toInt()
        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        image = bmp.asImageBitmap()

        glowW = (gw + GLOW_BLOCK - 1) / GLOW_BLOCK
        glowH = (gh + GLOW_BLOCK - 1) / GLOW_BLOCK
        glowDstW = (glowW * GLOW_BLOCK * cellW).roundToInt()
        glowDstH = (glowH * GLOW_BLOCK * cellH).roundToInt()
        val gn = glowW * glowH
        glowPixels = IntArray(gn)
        sumR = IntArray(gn)
        sumG = IntArray(gn)
        sumB = IntArray(gn)
        tmpR = IntArray(gn)
        tmpG = IntArray(gn)
        tmpB = IntArray(gn)
        val gbmp = Bitmap.createBitmap(glowW, glowH, Bitmap.Config.ARGB_8888)
        glowBitmap = gbmp
        glowImage = gbmp.asImageBitmap()

        background = Brush.verticalGradient(
            listOf(Color(0xFF0D0A13), Color(0xFF07060A), Color(0xFF110B0C)),
        )
        sx1 = -1
        sy1 = -1
        seed()
        render()
        ready = true
    }

    fun requestStream(seconds: Float) {
        streamLeft = max(streamLeft, seconds)
    }

    /** Lays down a settled, gently undulating pile with strata, plus a stream pouring in. */
    private fun seed() {
        cells.fill(0)
        stamp.fill(0)
        solid.fill(0)
        count = 0
        stepId = 0
        val phi1 = rng.float() * 6.283f
        val phi2 = rng.float() * 6.283f
        val start = rng.float() * WARM_STOPS.size
        val strataSpan = 3.2f
        val depthScale = 1f / (gh * 0.4f)
        for (x in 0 until gw) {
            val frac = INITIAL_FILL + 0.05f * sin(x * 0.05f + phi1) + 0.03f * sin(x * 0.13f + phi2)
            val top = (gh - (frac * gh).toInt()).coerceIn(0, gh)
            val wave = 0.05f * sin(x * 0.07f + phi2 * 2f)
            for (y in top until gh) {
                val depth = (gh - y) * depthScale
                cells[y * gw + x] = grainColor(start + (depth + wave) * strataSpan)
                count++
            }
        }
        palettePhase = start + (INITIAL_FILL / 0.4f + 0.15f) * strataSpan
        streamLeft = STREAM_SECONDS
        drizzling = false
        quiet = 0f
    }

    fun frame(dt: Float, newDir: Int) {
        time += dt
        palettePhase += dt / SECONDS_PER_STOP
        if (newDir != dir) {
            dir = newDir
            drizzling = false
            quiet = 0f
        }

        val pressed = WallpaperPointer.pressed
        if (pressed) {
            val px = WallpaperPointer.x
            val py = WallpaperPointer.y
            val mx = px - anchorX
            val my = py - anchorY
            if (!wasPressed || mx * mx + my * my > stillPx * stillPx) {
                anchorX = px
                anchorY = py
                anchorT = time
            }
            applyFinger(px, py)
            if (time - anchorT > POUR_DELAY) {
                pourAcc += dt * POUR_RATE
                while (pourAcc >= 1f) {
                    pourAcc -= 1f
                    pourGrain(px, py)
                }
            }
            drizzling = false
            quiet = 0f
        } else {
            if (wasPressed) clearSolid()
            pourAcc = 0f
        }
        wasPressed = pressed

        if (streamLeft > 0f) {
            streamLeft -= dt
            streamAcc += dt * STREAM_RATE
            val centre = 0.5f + 0.28f * sin(time * 0.45f)
            val jitter = 3f / max(gw, gh)
            while (streamAcc >= 1f) {
                streamAcc -= 1f
                spawnAtUpEdge(centre + (rng.float() - 0.5f) * jitter)
            }
        }
        if (drizzling) {
            drizzleAcc += dt * DRIZZLE_RATE
            while (drizzleAcc >= 1f) {
                drizzleAcc -= 1f
                spawnAtUpEdge(rng.float())
            }
        }

        stepAcc += dt * STEP_RATE
        var steps = stepAcc.toInt()
        if (steps > MAX_STEPS_PER_FRAME) steps = MAX_STEPS_PER_FRAME
        stepAcc = min(stepAcc - steps, 1f)
        var moves = 0
        for (s in 0 until steps) moves += step()
        if (steps > 0) {
            if (moves == 0 && !pressed && streamLeft <= 0f) {
                quiet += dt
                if (quiet > QUIET_SECONDS) drizzling = true
            } else {
                quiet = 0f
            }
        }

        render()
    }

    /** One automaton step; returns how many grains moved. */
    private fun step(): Int {
        stepId++
        val dx = DIR_X[dir]
        val dy = DIR_Y[dir]
        val a = (dir + 7) and 7
        val b = (dir + 1) and 7
        val ax = DIR_X[a]
        val ay = DIR_Y[a]
        val bx = DIR_X[b]
        val by = DIR_Y[b]
        val odd = stepId and 1 == 1

        // Scan from the "down" side so grains land in rows already processed this step.
        val yDown = dy > 0 || (dy == 0 && odd)
        var y = if (yDown) gh - 1 else 0
        val ys = if (yDown) -1 else 1
        var moves = 0
        for (row in 0 until gh) {
            val xRight = if (dx > 0) true else if (dx < 0) false else ((stepId + y) and 1) == 0
            var x = if (xRight) gw - 1 else 0
            val xs = if (xRight) -1 else 1
            val rowBase = y * gw
            for (col in 0 until gw) {
                val i = rowBase + x
                val c = cells[i]
                if (c != 0 && stamp[i] != stepId) {
                    var target = freeCell(x + dx, y + dy)
                    // A small chance to cling on slopes makes avalanches less mechanical.
                    if (target < 0 && rng.next() and 7 != 0) {
                        if (rng.bit()) {
                            target = freeCell(x + ax, y + ay)
                            if (target < 0) target = freeCell(x + bx, y + by)
                        } else {
                            target = freeCell(x + bx, y + by)
                            if (target < 0) target = freeCell(x + ax, y + ay)
                        }
                    }
                    if (target >= 0) {
                        cells[target] = c
                        cells[i] = 0
                        stamp[target] = stepId
                        moves++
                    }
                }
                x += xs
            }
            y += ys
        }
        return moves
    }

    private fun freeCell(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= gw || y >= gh) return -1
        val j = y * gw + x
        return if (cells[j] == 0 && solid[j].toInt() == 0) j else -1
    }

    private fun clearSolid() {
        for (y in sy0..sy1) {
            val base = y * gw
            for (x in sx0..sx1) solid[base + x] = 0
        }
        sx1 = -1
        sy1 = -1
    }

    /** Marks the finger disc solid and shoves any grains inside it radially out of the disc. */
    private fun applyFinger(px: Float, py: Float) {
        clearSolid()
        val cx = px / cellW
        val cy = py / cellH
        val r = fingerR
        val r2 = r * r
        val x0 = floor(cx - r).toInt().coerceAtLeast(0)
        val x1 = floor(cx + r).toInt().coerceAtMost(gw - 1)
        val y0 = floor(cy - r).toInt().coerceAtLeast(0)
        val y1 = floor(cy + r).toInt().coerceAtMost(gh - 1)
        if (x0 > x1 || y0 > y1) return
        sx0 = x0
        sx1 = x1
        sy0 = y0
        sy1 = y1
        for (y in y0..y1) {
            val fy = y + 0.5f - cy
            for (x in x0..x1) {
                val fx = x + 0.5f - cx
                if (fx * fx + fy * fy <= r2) solid[y * gw + x] = 1
            }
        }
        val upX = -DIR_X[dir].toFloat()
        val upY = -DIR_Y[dir].toFloat()
        for (y in y0..y1) {
            val fy = y + 0.5f - cy
            for (x in x0..x1) {
                val i = y * gw + x
                val c = cells[i]
                if (c == 0 || solid[i].toInt() == 0) continue
                cells[i] = 0
                val fx = x + 0.5f - cx
                val len = sqrt(fx * fx + fy * fy)
                val ux: Float
                val uy: Float
                if (len < 0.01f) {
                    ux = upX
                    uy = upY
                } else {
                    ux = fx / len
                    uy = fy / len
                }
                var placed = false
                var k = 0
                while (k < 16 && !placed) {
                    val d = r + 0.6f + k * 0.8f
                    val j = freeCell(floor(cx + ux * d).toInt(), floor(cy + uy * d).toInt())
                    if (j >= 0) {
                        cells[j] = c
                        placed = true
                    }
                    k++
                }
                if (!placed) count--
            }
        }
    }

    private fun pourGrain(px: Float, py: Float) {
        val dx = DIR_X[dir].toFloat()
        val dy = DIR_Y[dir].toFloat()
        val norm = if (dx != 0f && dy != 0f) 0.7071f else 1f
        val off = fingerR + 1.2f
        val lateral = (rng.float() - 0.5f) * 2.4f
        val fx = px / cellW + (dx * off - dy * lateral) * norm
        val fy = py / cellH + (dy * off + dx * lateral) * norm
        val j = freeCell(floor(fx).toInt(), floor(fy).toInt())
        if (j >= 0) addGrain(j)
    }

    /** Spawns a grain on the edge opposite gravity; [u] is the position along that edge (0..1). */
    private fun spawnAtUpEdge(u: Float) {
        val dx = DIR_X[dir]
        val dy = DIR_Y[dir]
        val x: Int
        val y: Int
        if (dy != 0) {
            x = (u * gw).toInt().coerceIn(0, gw - 1)
            y = if (dy > 0) 0 else gh - 1
        } else {
            y = (u * gh).toInt().coerceIn(0, gh - 1)
            x = if (dx > 0) 0 else gw - 1
        }
        val j = freeCell(x, y)
        if (j >= 0) addGrain(j)
    }

    private fun addGrain(j: Int) {
        if (count >= cap) removeRandomGrain()
        cells[j] = grainColor(palettePhase)
        stamp[j] = stepId
        count++
    }

    private fun removeRandomGrain() {
        val n = cells.size
        for (attempt in 0 until 40) {
            val k = rng.below(n)
            if (cells[k] != 0) {
                cells[k] = 0
                count--
                return
            }
        }
    }

    private fun grainColor(phase: Float): Int {
        val n = lut.size
        var idx = (phase * LUT_PER_STOP).toInt() % n
        if (idx < 0) idx += n
        idx = (idx + rng.below(13) - 6 + n) % n
        var base = lut[idx]
        // Cool accents are rare, but every so often a whole band of them comes through.
        val accentOdds = if (sin(palettePhase * 0.7f) > 0.93f) 5 else 45
        if (rng.below(accentOdds) == 0) base = COOL_ACCENTS[rng.below(COOL_ACCENTS.size)]
        val bright = if (rng.below(50) == 0) 1.3f else 0.76f + 0.3f * rng.float()
        val r = min(255, (((base shr 16) and 0xFF) * bright).toInt())
        val g = min(255, (((base shr 8) and 0xFF) * bright).toInt())
        val b = min(255, ((base and 0xFF) * bright).toInt())
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Writes grains into the bitmap (surface grains lit a little) and rebuilds the glow map. */
    private fun render() {
        val bmp = bitmap ?: return
        val gbmp = glowBitmap ?: return
        val dx = DIR_X[dir]
        val dy = DIR_Y[dir]
        sumR.fill(0)
        sumG.fill(0)
        sumB.fill(0)
        for (y in 0 until gh) {
            val uy = y - dy
            val uyIn = uy >= 0 && uy < gh
            val rowBase = y * gw
            val glowRow = (y / GLOW_BLOCK) * glowW
            for (x in 0 until gw) {
                val i = rowBase + x
                val c = cells[i]
                if (c == 0) {
                    pixels[i] = 0
                    continue
                }
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val ux = x - dx
                if (uyIn && ux >= 0 && ux < gw && cells[uy * gw + ux] == 0) {
                    pixels[i] = (0xFF shl 24) or
                        ((r + ((255 - r) shr 2)) shl 16) or
                        ((g + ((255 - g) shr 2)) shl 8) or
                        (b + ((255 - b) shr 2))
                } else {
                    pixels[i] = c
                }
                val k = glowRow + x / GLOW_BLOCK
                sumR[k] += r
                sumG[k] += g
                sumB[k] += b
            }
        }
        bmp.setPixels(pixels, 0, gw, 0, 0, gw, gh)

        // 3x3 box blur over the block sums so the haze is soft before bilinear upscaling.
        val w = glowW
        val h = glowH
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val k = base + x
                var r = sumR[k]
                var g = sumG[k]
                var b = sumB[k]
                if (x > 0) {
                    r += sumR[k - 1]; g += sumG[k - 1]; b += sumB[k - 1]
                }
                if (x < w - 1) {
                    r += sumR[k + 1]; g += sumG[k + 1]; b += sumB[k + 1]
                }
                tmpR[k] = r
                tmpG[k] = g
                tmpB[k] = b
            }
        }
        val scale = GLOW_GAIN / (GLOW_BLOCK * GLOW_BLOCK * 9f)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val k = base + x
                var r = tmpR[k]
                var g = tmpG[k]
                var b = tmpB[k]
                if (y > 0) {
                    r += tmpR[k - w]; g += tmpG[k - w]; b += tmpB[k - w]
                }
                if (y < h - 1) {
                    r += tmpR[k + w]; g += tmpG[k + w]; b += tmpB[k + w]
                }
                val rr = min(255, (r * scale).toInt())
                val gg = min(255, (g * scale).toInt())
                val bb = min(255, (b * scale).toInt())
                glowPixels[k] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        gbmp.setPixels(glowPixels, 0, w, 0, 0, w, h)
    }
}

private fun mix(a: Int, b: Int, f: Float): Int {
    val r = (((a shr 16) and 0xFF) + (((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * f).toInt()
    val g = (((a shr 8) and 0xFF) + (((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * f).toInt()
    val bl = ((a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * f).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}
