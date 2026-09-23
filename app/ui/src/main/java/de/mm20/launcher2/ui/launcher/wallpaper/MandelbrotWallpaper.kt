package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

private const val RES_W = 320
private const val MAX_H = 800
private const val ZOOM_RATE = 0.06
private const val MAX_ITER = 180
private const val LN2 = 0.6931472f
private const val CYCLE_RATE = 26f
private const val GLOW = 0.4f

private val INTERIOR = 0xFF27283B.toInt()

private val targets = listOf(
    -0.743643887037151 to 0.13182590420533,
    -0.7453 to 0.1127,
    -0.16 to 1.0405,
    -0.7269 to 0.1889,
    0.285 to 0.01,
    -1.768 to 0.0,
    -0.1 to 0.65,
    0.35 to 0.35,
    -0.622 to 0.42,
)

private val palette: IntArray = buildPalette()

private class FractalParams {
    var pendingRandomize = true
    var isJulia = false
    var jRe = 0.0
    var jIm = 0.0
    var centerRe = -0.5
    var centerIm = 0.0
    var viewW = 3.0

    fun randomize() {
        val random = Random(System.nanoTime())
        val target = targets[random.nextInt(targets.size)]
        isJulia = random.nextBoolean()
        if (isJulia) {
            jRe = target.first
            jIm = target.second
            centerRe = 0.0
            centerIm = 0.0
            viewW = 2.4
        } else {
            centerRe = target.first
            centerIm = target.second
            viewW = 0.8 + random.nextFloat() * 1.4
        }
    }
}

private class FractalViewport {
    var w = 0f
    var h = 0f
}

/**
 * A slowly zooming Mandelbrot/Julia fractal rendered into a low resolution buffer and
 * upscaled smoothly. A fresh random fractal is chosen whenever the screen is unlocked.
 */
@Composable
fun MandelbrotWallpaper(modifier: Modifier = Modifier) {
    val resetGeneration = rememberResetGeneration()
    val fractal = remember { FractalParams() }
    val viewport = remember { FractalViewport() }
    val bitmap = remember { Bitmap.createBitmap(RES_W, MAX_H, Bitmap.Config.ARGB_8888) }
    val pixels = remember { IntArray(RES_W * MAX_H) }
    val image = remember { bitmap.asImageBitmap() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(resetGeneration) {
        fractal.pendingRandomize = true
    }

    // Advance the zoom and publish rendered frames on the UI thread; the heavy per-pixel render
    // runs on a background dispatcher with a snapshot of the parameters, so nothing shared is
    // mutated cross-thread and the UI thread never blocks on the fractal.
    LaunchedEffect(Unit) {
        var last = 0L
        var rendering = false
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else (now - last) / 1_000_000_000f
            last = now
            if (fractal.pendingRandomize) {
                fractal.pendingRandomize = false
                fractal.randomize()
            }
            if (dt > 0f && dt < 0.1f) {
                fractal.viewW *= exp(-dt * ZOOM_RATE)
                if (fractal.viewW < 1e-4) {
                    fractal.randomize()
                }
            }
            if (!rendering && viewport.w > 0f) {
                val rh = (RES_W * viewport.h / viewport.w).toInt().coerceIn(60, MAX_H)
                val isJulia = fractal.isJulia
                val jRe = fractal.jRe
                val jIm = fractal.jIm
                val centerRe = fractal.centerRe
                val centerIm = fractal.centerIm
                val viewW = fractal.viewW
                rendering = true
                launch(Dispatchers.Default) {
                    renderFractal(pixels, RES_W, rh, isJulia, jRe, jIm, centerRe, centerIm, viewW, MAX_ITER, palette)
                    withContext(Dispatchers.Main) {
                        bitmap.setPixels(pixels, 0, RES_W, 0, 0, RES_W, rh)
                        frame.longValue = now
                        rendering = false
                    }
                }
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by published frames
        viewport.w = size.width
        viewport.h = size.height
        val rh = (RES_W * size.height / size.width).toInt().coerceIn(60, MAX_H)
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(RES_W, rh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low,
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Color(0xFF05060E).copy(alpha = 0.5f)),
                center = Offset(size.width * 0.5f, size.height * 0.45f),
                radius = size.maxDimension * 0.75f,
            ),
        )
    }
}

private fun renderFractal(
    pixels: IntArray,
    w: Int,
    h: Int,
    isJulia: Boolean,
    jRe: Double,
    jIm: Double,
    centerRe: Double,
    centerIm: Double,
    viewW: Double,
    maxIter: Int,
    palette: IntArray,
) {
    val viewH = viewW * (h.toDouble() / w)
    val stepW = viewW / w
    val stepH = viewH / h
    val juliaRe = jRe.toFloat()
    val juliaIm = jIm.toFloat()
    for (py in 0 until h) {
        val im0 = (centerIm + (h / 2.0 - py) * stepH).toFloat()
        var idx = py * w
        for (px in 0 until w) {
            val re0 = (centerRe + (px - w / 2.0) * stepW).toFloat()
            val cr = if (isJulia) juliaRe else re0
            val ci = if (isJulia) juliaIm else im0
            var zr = if (isJulia) re0 else 0f
            var zi = if (isJulia) im0 else 0f
            var n = 0
            var zr2 = zr * zr
            var zi2 = zi * zi
            while (zr2 + zi2 <= 4f && n < maxIter) {
                zi = 2f * zr * zi + ci
                zr = zr2 - zi2 + cr
                zr2 = zr * zr
                zi2 = zi * zi
                n++
            }
            pixels[idx] = if (n >= maxIter) {
                INTERIOR
            } else {
                val logZn = ln(zr2 + zi2) * 0.5f
                val nu = ln(logZn / LN2) / LN2
                val smooth = n + 1f - nu
                val t = smooth * CYCLE_RATE
                val pi = ((t % palette.size) + palette.size) % palette.size
                val base = palette[pi.toInt().coerceIn(0, palette.size - 1)]
                val glow = exp(-2.2f * maxOf(logZn - LN2, 0f)) * GLOW * 255f
                val r = ((base shr 16 and 0xFF) + glow).toInt().coerceAtMost(255)
                val g = ((base shr 8 and 0xFF) + glow * 0.97f).toInt().coerceAtMost(255)
                val b = ((base and 0xFF) + glow * 0.9f).toInt().coerceAtMost(255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            idx++
        }
    }
}

private fun buildPalette(): IntArray {
    // Cyclic aurora bands: navy -> indigo -> violet -> sky -> aqua -> teal -> navy.
    val keys = listOf(
        Color(0xFF272F44),
        Color(0xFF495C95),
        Color(0xFF8272C4),
        Color(0xFF73B2E4),
        Color(0xFF67DAD3),
        Color(0xFF44A0B2),
        Color(0xFF3D6198),
        Color(0xFF272F44),
    )
    val size = 512
    val out = IntArray(size)
    val segments = keys.size - 1
    for (i in 0 until size) {
        val t = i.toFloat() / size * segments
        val seg = t.toInt().coerceAtMost(segments - 1)
        val local = t - seg
        out[i] = lerpColor(keys[seg], keys[seg + 1], local).toArgb()
    }
    return out
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    return Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}
