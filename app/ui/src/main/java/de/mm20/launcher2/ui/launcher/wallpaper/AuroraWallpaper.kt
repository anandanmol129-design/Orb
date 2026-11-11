package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Aurora: translucent curtains of green, teal and violet light hanging over a deep night sky
 * with faintly twinkling stars and a dark mountain ridge. The curtains undulate slowly on layered
 * sines, with vertical rays that are brightest at their sharp lower edge and fade upward.
 *
 * It follows the voice assistant through [WallpaperVoice]: calm and dim when idle; surging in
 * height, ripple and brightness with the (smoothed) mic level while listening, with a ripple
 * running outward along the curtains on loud onsets; a sideways shimmer drifting toward violet
 * while thinking; a brief green-white bloom on success and a red/magenta flush on error. While
 * music plays the curtains swell gently at about 2 Hz. A finger locally brightens the curtains
 * and bends them toward it. Every change is cross-faded; nothing snaps.
 */
@Composable
fun AuroraWallpaper(modifier: Modifier = Modifier) {
    val scene = remember { AuroraScene() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1_000_000_000f).coerceIn(0f, MAX_DT)
                last = now
                scene.update(dt)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the frame loop
        scene.ensure(size.width, size.height)
        scene.draw(this)
    }
}

private const val MAX_DT = 0.05f
private const val TWO_PI = (2.0 * PI).toFloat()

private const val RIBBONS = 4
private const val RAYS = 56
private const val GLOWS = 7
private const val STARS = 130
private const val RIPPLES = 4
private const val RIPPLE_SPEED = 0.75f

private const val RAY_ALPHA = 0.9f
private const val GLOW_ALPHA = 0.11f
private const val UPPER_ALPHA = 0.55f

// Fraction of a ray's length that its soft lower fringe hangs below the bright edge.
private const val FEATHER = 0.05f
private const val SPRITE_W = 32
private const val SPRITE_H = 192

// Per-ribbon layout, back to front: resting baseline and ray length (fractions of height),
// overall brightness, ray width factor and a phase that decorrelates the motion.
private val RIBBON_BASE = floatArrayOf(0.40f, 0.52f, 0.60f, 0.68f)
private val RIBBON_HEIGHT = floatArrayOf(0.26f, 0.36f, 0.24f, 0.17f)
private val RIBBON_BRIGHT = floatArrayOf(0.55f, 1.0f, 0.8f, 0.6f)
private val RIBBON_WIDTH = floatArrayOf(0.8f, 1.0f, 1.0f, 1.15f)
private val RIBBON_PHASE = floatArrayOf(0.0f, 2.1f, 4.3f, 5.7f)

// Resting palette (rgb per ribbon): the lower edge blends between A and B along the curtain,
// the upper fringe uses UP.
private val PAL_LOW_A = floatArrayOf(
    0.10f, 0.85f, 0.80f,
    0.25f, 1.00f, 0.50f,
    0.15f, 0.90f, 0.85f,
    0.30f, 1.00f, 0.60f,
)
private val PAL_LOW_B = floatArrayOf(
    0.20f, 1.00f, 0.55f,
    0.10f, 0.95f, 0.70f,
    0.35f, 1.00f, 0.45f,
    0.20f, 0.80f, 1.00f,
)
private val PAL_UP = floatArrayOf(
    0.50f, 0.25f, 1.00f,
    0.65f, 0.25f, 0.95f,
    0.90f, 0.25f, 0.70f,
    0.55f, 0.35f, 1.00f,
)
private val THINK_LOW = floatArrayOf(0.42f, 0.40f, 1.00f)
private val THINK_UP = floatArrayOf(0.72f, 0.30f, 1.00f)
private val BLOOM_LOW = floatArrayOf(0.80f, 1.00f, 0.88f)
private val BLOOM_UP = floatArrayOf(0.55f, 1.00f, 0.75f)
private val ERROR_LOW = floatArrayOf(1.00f, 0.22f, 0.32f)
private val ERROR_UP = floatArrayOf(0.95f, 0.15f, 0.62f)

private class AuroraScene {
    var width = 0f
        private set
    var height = 0f
        private set

    private var clock = 0f
    // Aurora time: integrates a state-dependent speed so speeding up never jumps the phase.
    private var flow = 0f

    private var listenW = 0f
    private var thinkW = 0f
    private var musicW = 0f
    private var level = 0f
    private var levelFast = 0f
    private var levelSlow = 0f
    private var onsetCooldown = 0f
    private var successKick = 0f
    private var success = 0f
    private var errorKick = 0f
    private var error = 0f
    private var lastState = WallpaperVoice.IDLE
    private var beatPhase = 0f
    private var sweep = 0f
    private var shimmer = 0f
    private var touchW = 0f
    private var touchX = 0f
    private var touchY = 0f

    private val rippleAge = FloatArray(RIPPLES)
    private val rippleStrength = FloatArray(RIPPLES)
    private var nextRipple = 0

    // Derived once per frame.
    private var brightness = 0.6f
    private var waveAmp = 1f
    private var heightGain = 1f
    private var swell = 0f
    private var musicE = 0f
    private var listenLevel = 0f
    private var sweep1 = 0f
    private var sweep2 = 0f
    private val lowA = FloatArray(RIBBONS * 3)
    private val lowB = FloatArray(RIBBONS * 3)
    private val up = FloatArray(RIBBONS * 3)

    private val jitter = FloatArray(RIBBONS * RAYS)
    private val starX = FloatArray(STARS)
    private val starY = FloatArray(STARS)
    private val starSize = FloatArray(STARS)
    private val starBright = FloatArray(STARS)
    private val starPhase = FloatArray(STARS)
    private val starFreq = FloatArray(STARS)
    private val starTint = FloatArray(STARS)

    // Ray sprites are ALPHA_8 bitmaps: drawn with a paint, they take the paint's colour, so one
    // pair of sprites serves every colour without allocating shaders per frame.
    private val lowerSprite = buildSprite(lower = true)
    private val upperSprite = buildSprite(lower = false)
    private val spriteSrc = Rect(0, 0, SPRITE_W, SPRITE_H)
    private val dst = RectF()
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        setXfermode(PorterDuffXfermode(PorterDuff.Mode.ADD))
    }

    private var skyBrush: Brush = Brush.verticalGradient(listOf(Color.Black, Color.Black))
    private var glowBrush: Brush = skyBrush
    private var violetBrush: Brush = skyBrush
    private var bloomBrush: Brush = skyBrush
    private var errorBrush: Brush = skyBrush
    private var hazeBrush: Brush = skyBrush
    private val farRidge = Path()
    private val nearRidge = Path()

    // Outputs of sample().
    private var sx = 0f
    private var sy = 0f
    private var sh = 0f
    private var sTouch = 0f
    private var sRipple = 0f
    private var sEnv = 0f

    init {
        val rnd = Random(20261111)
        for (i in jitter.indices) jitter[i] = 0.65f + 0.35f * rnd.nextFloat()
        for (k in 0 until STARS) {
            starX[k] = rnd.nextFloat()
            val r = rnd.nextFloat()
            starY[k] = r * r * 0.86f
            val z = rnd.nextFloat()
            starSize[k] = 0.5f + 1.2f * z * z * z
            starBright[k] = 0.2f + 0.6f * rnd.nextFloat()
            starPhase[k] = rnd.nextFloat() * TWO_PI
            starFreq[k] = 0.5f + 2.0f * rnd.nextFloat()
            starTint[k] = rnd.nextFloat()
        }
    }

    fun ensure(w: Float, h: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        val r = max(w, h)
        skyBrush = Brush.verticalGradient(
            0f to Color(0xFF010209),
            0.35f to Color(0xFF040A1A),
            0.72f to Color(0xFF061325),
            1f to Color(0xFF02060C),
            startY = 0f,
            endY = h,
        )
        glowBrush = Brush.radialGradient(
            colors = listOf(Color(0x4020D49A), Color(0x1810806A), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.55f),
            radius = r * 0.6f,
        )
        violetBrush = Brush.radialGradient(
            colors = listOf(Color(0x305A2EC8), Color(0x10301A80), Color.Transparent),
            center = Offset(w * 0.45f, h * 0.3f),
            radius = r * 0.55f,
        )
        bloomBrush = Brush.radialGradient(
            colors = listOf(Color(0x60B8FFD8), Color(0x2050E0A0), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = r * 0.65f,
        )
        errorBrush = Brush.radialGradient(
            colors = listOf(Color(0x58FF3060), Color(0x20A01860), Color.Transparent),
            center = Offset(w * 0.5f, h * 0.5f),
            radius = r * 0.65f,
        )
        hazeBrush = Brush.verticalGradient(
            listOf(Color.Transparent, Color(0x1A2BD69A), Color(0x0C2BD69A)),
            startY = h * 0.72f,
            endY = h * 0.92f,
        )
        buildRidge(farRidge, w, h, 0.875f, 0.03f, 1.9f)
        buildRidge(nearRidge, w, h, 0.915f, 0.022f, 4.4f)
    }

    fun update(dt: Float) {
        clock += dt
        val state = WallpaperVoice.state
        val raw = if (state == WallpaperVoice.LISTENING) WallpaperVoice.level.coerceIn(0f, 1f) else 0f

        // Fast attack, slow release; the fast/slow pair detects onsets.
        level = follow(level, raw, dt, if (raw > level) 16f else 2.2f)
        levelFast = follow(levelFast, raw, dt, if (raw > levelFast) 40f else 9f)
        levelSlow = follow(levelSlow, raw, dt, 1.3f)

        listenW = follow(listenW, if (state == WallpaperVoice.LISTENING) 1f else 0f, dt, 3.5f)
        thinkW = follow(thinkW, if (state == WallpaperVoice.THINKING) 1f else 0f, dt, 2.2f)
        val music = WallpaperVoice.musicPlaying && state == WallpaperVoice.IDLE
        musicW = follow(musicW, if (music) 1f else 0f, dt, 1.1f)

        if (state != lastState) {
            if (state == WallpaperVoice.SUCCESS) successKick = 1f
            if (state == WallpaperVoice.ERROR) errorKick = 1f
            lastState = state
        }
        successKick *= exp(-dt * 2.4f)
        errorKick *= exp(-dt * 1.9f)
        // Follow the decaying kick quickly so even the onset ramps up instead of snapping.
        success = follow(success, successKick, dt, 14f)
        error = follow(error, errorKick, dt, 12f)

        onsetCooldown -= dt
        val rise = levelFast - levelSlow
        if (rise > 0.14f && levelFast > 0.12f && onsetCooldown <= 0f) {
            rippleAge[nextRipple] = 0f
            rippleStrength[nextRipple] = (rise * 2.5f).coerceAtMost(1f)
            nextRipple = (nextRipple + 1) % RIPPLES
            onsetCooldown = 0.3f
        }
        for (k in 0 until RIPPLES) {
            if (rippleStrength[k] > 0f) {
                rippleAge[k] += dt
                if (rippleAge[k] > 2.5f) rippleStrength[k] = 0f
            }
        }

        musicE = musicW * musicW * (3f - 2f * musicW)
        beatPhase = (beatPhase + dt * 2f * TWO_PI) % TWO_PI
        val p = 0.5f - 0.5f * cos(beatPhase)
        swell = musicE * p * p

        sweep = (sweep + dt * 0.2f) % 1f
        sweep1 = sweep * 1.6f - 0.3f
        sweep2 = ((sweep + 0.5f) % 1f) * 1.6f - 0.3f
        shimmer += dt * thinkW * 2.4f

        listenLevel = level * listenW
        flow += dt * (1f + 0.5f * listenW + 1.4f * listenLevel + 0.35f * thinkW + 0.4f * musicE)

        brightness = 0.6f + 0.25f * listenW + 0.8f * listenLevel + 0.15f * musicE + 0.25f * swell +
            0.15f * thinkW + 1.1f * success + 0.3f * error
        waveAmp = 1f + 1.6f * listenLevel + 0.35f * swell
        heightGain = 1f + 0.75f * listenLevel + 0.1f * swell + 0.08f * thinkW + 0.25f * success

        val pressed = WallpaperPointer.pressed
        if (pressed) {
            if (touchW < 0.02f) {
                touchX = WallpaperPointer.x
                touchY = WallpaperPointer.y
            } else {
                touchX = follow(touchX, WallpaperPointer.x, dt, 18f)
                touchY = follow(touchY, WallpaperPointer.y, dt, 18f)
            }
        }
        touchW = follow(touchW, if (pressed) 1f else 0f, dt, if (pressed) 6f else 2.2f)

        val tm = 0.7f * thinkW
        val em = 0.85f * error.coerceAtMost(1f)
        val bm = (1.1f * success).coerceAtMost(1f)
        for (i in 0 until RIBBONS * 3) {
            val c = i % 3
            lowA[i] = mix(PAL_LOW_A[i], THINK_LOW[c], ERROR_LOW[c], BLOOM_LOW[c], tm, em, bm)
            lowB[i] = mix(PAL_LOW_B[i], THINK_LOW[c], ERROR_LOW[c], BLOOM_LOW[c], tm, em, bm)
            up[i] = mix(PAL_UP[i], THINK_UP[c], ERROR_UP[c], BLOOM_UP[c], tm, em, bm)
        }
    }

    fun draw(scope: DrawScope) {
        if (width <= 0f) return
        drawSky(scope)
        drawStars(scope)
        scope.drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            for (i in 0 until RIBBONS) drawRibbon(nc, i)
        }
        scope.drawRect(hazeBrush, alpha = (0.5f + 0.4f * brightness).coerceIn(0f, 1f), blendMode = BlendMode.Plus)
        scope.drawPath(farRidge, Color(0xFF040912))
        scope.drawPath(nearRidge, Color(0xFF010307))
    }

    private fun drawSky(scope: DrawScope) {
        scope.drawRect(skyBrush)
        scope.drawRect(
            glowBrush,
            alpha = (0.45f + 0.4f * (brightness - 0.6f)).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
        )
        scope.drawRect(
            violetBrush,
            alpha = (0.55f + 0.45f * thinkW).coerceIn(0f, 1f),
            blendMode = BlendMode.Plus,
        )
        if (success > 0.004f) {
            scope.drawRect(bloomBrush, alpha = success.coerceAtMost(1f), blendMode = BlendMode.Plus)
        }
        if (error > 0.004f) {
            scope.drawRect(errorBrush, alpha = error.coerceAtMost(1f), blendMode = BlendMode.Plus)
        }
    }

    private fun drawStars(scope: DrawScope) {
        val base = width * 0.0016f
        for (k in 0 until STARS) {
            val tw = 0.6f + 0.4f * sin(clock * starFreq[k] + starPhase[k])
            val a = (starBright[k] * tw).coerceIn(0f, 1f)
            val t = starTint[k]
            val color = Color(0.75f + 0.25f * t, 0.85f + 0.05f * t, 1f - 0.2f * t, a)
            val center = Offset(starX[k] * width, starY[k] * height)
            val radius = base * starSize[k]
            if (starSize[k] > 1.2f) {
                scope.drawCircle(color.copy(alpha = a * 0.12f), radius * 3.5f, center)
            }
            scope.drawCircle(color, radius, center)
        }
    }

    private fun drawRibbon(nc: android.graphics.Canvas, i: Int) {
        val t = flow
        val ph = RIBBON_PHASE[i]
        val bright = RIBBON_BRIGHT[i]
        val ci = i * 3

        // Broad, faint glow behind the rays so the curtain reads as one luminous sheet.
        val glowHalf = width * 0.17f
        for (g in 0 until GLOWS) {
            val s = g / (GLOWS - 1f)
            sample(i, s)
            val a = GLOW_ALPHA * bright * brightness * sEnv * (1f + 1.2f * sTouch)
            if (a < 0.004f) continue
            val m = 0.5f + 0.5f * sin(3.1f * s + 0.05f * t + ph)
            paint.color = argb(
                a,
                lowA[ci] + (lowB[ci] - lowA[ci]) * m,
                lowA[ci + 1] + (lowB[ci + 1] - lowA[ci + 1]) * m,
                lowA[ci + 2] + (lowB[ci + 2] - lowA[ci + 2]) * m,
            )
            dst.set(sx - glowHalf, sy - sh * 1.1f, sx + glowHalf, sy + sh * FEATHER * 2f)
            nc.drawBitmap(lowerSprite, spriteSrc, dst, paint)
        }

        val half = width * 1.2f / (RAYS - 1) * 1.3f * RIBBON_WIDTH[i]
        for (j in 0 until RAYS) {
            val s = j / (RAYS - 1f)
            sample(i, s)
            val k = 0.5f + 0.25f * sin(37f * s + 0.9f * t + ph + shimmer) +
                0.15f * sin(71f * s - 1.6f * t + 2f * ph) +
                0.10f * sin(17f * s + 0.4f * t)
            val streak = 0.12f + 0.88f * k * k
            val len = sh * (0.84f + 0.16f * sin(29f * s + 0.8f * t + ph))
            val d1 = (s - sweep1) / 0.08f
            val d2 = (s - sweep2) / 0.12f
            val sweepGain = 1f + thinkW * (1.2f * exp(-d1 * d1) + 0.7f * exp(-d2 * d2))
            val a = (
                RAY_ALPHA * bright * brightness * sEnv * streak * jitter[i * RAYS + j] * sweepGain *
                    (1f + 1.3f * sTouch) + 0.3f * sRipple * brightness * bright
                ).coerceIn(0f, 1f)
            if (a < 0.004f) continue

            val m = 0.5f + 0.5f * sin(3.1f * s + 0.05f * t + ph)
            val white = (0.35f * sTouch + 0.3f * sRipple).coerceAtMost(0.7f)
            var r = lowA[ci] + (lowB[ci] - lowA[ci]) * m
            var gr = lowA[ci + 1] + (lowB[ci + 1] - lowA[ci + 1]) * m
            var b = lowA[ci + 2] + (lowB[ci + 2] - lowA[ci + 2]) * m
            r += (1f - r) * white
            gr += (1f - gr) * white
            b += (1f - b) * white

            dst.set(sx - half, sy - len, sx + half, sy + len * FEATHER)
            paint.color = argb(a, r, gr, b)
            nc.drawBitmap(lowerSprite, spriteSrc, dst, paint)
            paint.color = argb(a * UPPER_ALPHA * (1f + 0.5f * thinkW), up[ci], up[ci + 1], up[ci + 2])
            nc.drawBitmap(upperSprite, spriteSrc, dst, paint)
        }
    }

    /** Curtain geometry of ribbon [i] at position [s] (0..1 along it), written to the s* fields. */
    private fun sample(i: Int, s: Float) {
        val t = flow
        val ph = RIBBON_PHASE[i]
        val w = width
        val h = height
        var x = w * (-0.1f + 1.2f * s) + w * 0.028f * waveAmp * sin(8.5f * s + 0.37f * t + ph)
        var y = h * (
            RIBBON_BASE[i] + 0.012f * sin(0.13f * t + ph) +
                waveAmp * (0.038f * sin(5f * s + 0.21f * t + ph) + 0.016f * sin(13f * s - 0.47f * t + 2f * ph))
            )
        var len = h * RIBBON_HEIGHT[i] * (0.72f + 0.28f * sin(7f * s + 0.29f * t + 3f * ph)) * heightGain

        // Onset ripples run outward from the centre in both directions.
        var rip = 0f
        if (listenW > 0.001f) {
            val dist = abs(s - 0.5f)
            for (k in 0 until RIPPLES) {
                val str = rippleStrength[k]
                if (str <= 0f) continue
                val age = rippleAge[k]
                val d = dist - age * RIPPLE_SPEED
                rip += str * exp(-1.5f * age) * exp(-d * d / 0.0036f)
            }
            rip *= listenW
        }
        y -= h * 0.035f * rip
        len *= 1f + 0.3f * rip

        var g = 0f
        if (touchW > 0.001f) {
            val dx = (x - touchX) / (0.16f * w)
            val dy = (y - 0.4f * len - touchY) / (0.22f * h)
            g = touchW * exp(-(dx * dx + dy * dy))
            x += (touchX - x) * 0.10f * g
            y += (touchY - y) * 0.18f * g
            len *= 1f + 0.25f * g
        }

        val e = 0.5f + 0.5f * sin(4.4f * s + 0.11f * t + 1.7f * ph)
        sEnv = 0.3f + 0.7f * e * e
        sx = x
        sy = y
        sh = len
        sTouch = g
        sRipple = rip
    }
}

private fun follow(current: Float, target: Float, dt: Float, rate: Float): Float =
    current + (target - current) * (1f - exp(-rate * dt))

private fun mix(v: Float, think: Float, err: Float, bloom: Float, tm: Float, em: Float, bm: Float): Float {
    var x = v
    x += (think - x) * tm
    x += (err - x) * em
    x += (bloom - x) * bm
    return x
}

private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
    ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or
        ((r.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 16) or
        ((g.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 8) or
        (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * A soft vertical ray as an alpha mask. Rows run from the top of the ray (s = 1) down past the
 * bright lower edge (s = 0) into a short fringe. The lower sprite is the bright edge fading
 * upward; the upper one is a hump high in the ray for the violet fringe.
 */
private fun buildSprite(lower: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(SPRITE_W, SPRITE_H, Bitmap.Config.ALPHA_8)
    val stride = bmp.rowBytes
    val bytes = ByteArray(stride * SPRITE_H)
    val edge = exp(-6.25f)
    for (row in 0 until SPRITE_H) {
        val v = (row + 0.5f) / SPRITE_H
        val s = 1f - v * (1f + FEATHER)
        val vertical = if (lower) {
            if (s < 0f) {
                smoothstep(-FEATHER, 0f, s)
            } else {
                val q = 1f - s
                (0.62f * exp(-3.6f * s) + 0.38f * q * q) * (1f - s * s * s * s * s * s)
            }
        } else {
            if (s < 0f) 0f else {
                val d = (s - 0.55f) / 0.24f
                0.9f * exp(-d * d) * smoothstep(1f, 0.8f, s)
            }
        }
        for (col in 0 until SPRITE_W) {
            val x = ((col + 0.5f) / SPRITE_W) * 2f - 1f
            val horizontal = ((exp(-(x / 0.4f) * (x / 0.4f)) - edge) / (1f - edge)).coerceIn(0f, 1f)
            val a = (vertical * horizontal).coerceIn(0f, 1f)
            bytes[row * stride + col] = (a * 255f + 0.5f).toInt().toByte()
        }
    }
    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    return bmp
}

private fun buildRidge(path: Path, w: Float, h: Float, base: Float, amp: Float, seed: Float) {
    path.reset()
    path.moveTo(0f, h)
    val steps = 48
    for (k in 0..steps) {
        val u = k / steps.toFloat()
        val y = h * (
            base + amp * sin(u * 7.1f + seed) + amp * 0.5f * sin(u * 17.3f + seed * 1.7f) +
                amp * 0.22f * sin(u * 41f + seed * 2.3f)
            )
        path.lineTo(u * w, y)
    }
    path.lineTo(w, h)
    path.close()
}
