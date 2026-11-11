package de.mm20.launcher2.ui.launcher.wallpaper

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// All physics runs in plane units (1.0 = 0.62 * min screen dimension) and simulated seconds
// (TIME_SCALE simulated seconds per real second).
private const val GM = 1.1e-3
private const val TIME_SCALE = 5.0
private const val WALL_BOUNCE = 0.75
private const val BODY_BOUNCE = 0.75

/** Longest integration substep; frames are split so fast slingshots stay stable. */
private const val MAX_SUBSTEP = 0.011
private const val MAX_SUBSTEPS = 24

/** Hard speed cap so nothing tunnels through walls or runs away. ~4.5 plane units per real second. */
private const val MAX_SPEED = 0.9

/** Finger attractor: softened inverse-square pull, ~12x the sun's gravity at mid orbits. */
private const val FINGER_GM = 0.014
private const val FINGER_SOFTENING = 0.045

/** Outward kick on finger release, strongest for bodies right under the finger. */
private const val RELEASE_KICK = 0.35
private const val RELEASE_KICK_RANGE = 0.15

/** Drag-throw velocity multiplier (1.0 = body leaves exactly at finger speed). */
private const val THROW_GAIN = 1.3

/** After a disturbance bodies fly free for RECOVER_DELAY, then the orbit spring fades in over RECOVER_RAMP. */
private const val RECOVER_DELAY = 2.5
private const val RECOVER_RAMP = 2.5

/** Orbit spring: radial stiffness/damping (slightly underdamped) and tangential speed correction. */
private const val SPRING_K = 1.1
private const val SPRING_C = 0.85
private const val TANGENT_K = 1.0

/** Sun spring back to the centre after it has been thrown. */
private const val SUN_SPRING_K = 0.8
private const val SUN_SPRING_C = 0.75

/** Visual radii are in min-dimension units; collision radii are the drawn size in plane units. */
private const val PLANE_SCALE = 0.62f

private val SunColor = Color(0xFFFFD889)

private data class PlanetSpec(
    val radius: Double,
    val visualRadius: Float,
    val color: Color,
    val ring: Boolean = false,
)

// Bodies are drawn at twice their original size; orbits are spread out so the inner planet
// clears the sun and the outer one still fits a portrait screen. Neighbouring orbits can
// overlap at conjunction, so settled planets pass by each other (see resolveCollisions).
private val planetSpecs = listOf(
    PlanetSpec(0.21, 0.022f, Color(0xFF9E9E9E)),
    PlanetSpec(0.27, 0.032f, Color(0xFFE6C87A)),
    PlanetSpec(0.33, 0.034f, Color(0xFF4A90D9)),
    PlanetSpec(0.39, 0.030f, Color(0xFFD9544A)),
    PlanetSpec(0.47, 0.072f, Color(0xFFD9A066)),
    PlanetSpec(0.555, 0.064f, Color(0xFFE8D5A3), ring = true),
    PlanetSpec(0.63, 0.044f, Color(0xFF7AD0E0)),
    PlanetSpec(0.69, 0.042f, Color(0xFF4A6BD9)),
)

private class Body(
    var x: Double,
    var y: Double,
    var vx: Double,
    var vy: Double,
    val visualRadius: Float,
    val color: Color,
    val ring: Boolean = false,
    /** Home orbit radius around the sun (0 for the sun, whose home is the origin). */
    val home: Double = 0.0,
) {
    val collisionRadius = (visualRadius / PLANE_SCALE).toDouble()

    /** Simulated seconds since the body was last grabbed, pulled, released or hit. */
    var calm = Double.MAX_VALUE
}

/** 0 while flying free after a disturbance, easing to 1 as the orbit spring takes over. */
private fun recoveryWeight(calm: Double): Double {
    val t = ((calm - RECOVER_DELAY) / RECOVER_RAMP).coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

private fun clampSpeed(b: Body) {
    val sp2 = b.vx * b.vx + b.vy * b.vy
    if (sp2 > MAX_SPEED * MAX_SPEED) {
        val s = MAX_SPEED / sqrt(sp2)
        b.vx *= s
        b.vy *= s
    }
}

private class SolarSystem {
    val sun = Body(0.0, 0.0, 0.0, 0.0, 0.096f, SunColor)
    val planets = planetSpecs.map { spec ->
        Body(0.0, 0.0, 0.0, 0.0, spec.visualRadius, spec.color, spec.ring, spec.radius)
    }
    private val bodies = listOf(sun) + planets

    fun reset() {
        val random = Random(System.nanoTime())
        sun.x = 0.0
        sun.y = 0.0
        sun.vx = 0.0
        sun.vy = 0.0
        sun.calm = Double.MAX_VALUE
        planets.forEach { p ->
            val angle = random.nextDouble() * 2.0 * Math.PI
            val r = p.home
            p.x = r * cos(angle)
            p.y = r * sin(angle)
            val v = sqrt(GM / r)
            p.vx = -v * sin(angle)
            p.vy = v * cos(angle)
            p.calm = Double.MAX_VALUE
        }
    }

    /**
     * Advances the simulation by [dt] simulated seconds. When [attract] is set, every free planet
     * is pulled hard toward ([fingerX], [fingerY]) with no damping, so it whips around the finger.
     * Otherwise each planet, after a short free flight, is sprung back onto its home orbit.
     */
    fun step(dt: Double, dragged: Body?, attract: Boolean, fingerX: Double, fingerY: Double) {
        val steps = ceil(dt / MAX_SUBSTEP).toInt().coerceIn(1, MAX_SUBSTEPS)
        val h = dt / steps
        repeat(steps) {
            if (dragged !== sun) {
                sun.calm += h
                val w = recoveryWeight(sun.calm)
                if (w > 0.0) {
                    sun.vx += (-SUN_SPRING_K * sun.x - SUN_SPRING_C * sun.vx) * w * h
                    sun.vy += (-SUN_SPRING_K * sun.y - SUN_SPRING_C * sun.vy) * w * h
                }
                clampSpeed(sun)
                sun.x += sun.vx * h
                sun.y += sun.vy * h
            }
            for (p in planets) {
                if (dragged === p) continue
                val dx = p.x - sun.x
                val dy = p.y - sun.y
                val r2 = dx * dx + dy * dy
                val r = sqrt(r2).coerceAtLeast(1e-4)
                val g = GM / maxOf(r2 * r, 1e-9)
                var ax = -g * dx
                var ay = -g * dy
                if (attract) {
                    // Sharp, softened 1/d^2 pull toward the finger; no damping, so bodies
                    // overshoot and slingshot around it.
                    val fx = fingerX - p.x
                    val fy = fingerY - p.y
                    val d2 = fx * fx + fy * fy + FINGER_SOFTENING * FINGER_SOFTENING
                    val inv = FINGER_GM / (d2 * sqrt(d2))
                    ax += fx * inv
                    ay += fy * inv
                    p.calm = 0.0
                } else {
                    p.calm += h
                    val w = recoveryWeight(p.calm)
                    if (w > 0.0) {
                        // Orbit spring in the sun's frame: radial spring to the home radius,
                        // tangential pull toward prograde circular speed.
                        val ux = dx / r
                        val uy = dy / r
                        val rvx = p.vx - sun.vx
                        val rvy = p.vy - sun.vy
                        val vr = rvx * ux + rvy * uy
                        val vt = -rvx * uy + rvy * ux
                        val ar = -SPRING_K * (r - p.home) - SPRING_C * vr
                        val at = TANGENT_K * (sqrt(GM / r) - vt)
                        ax += (ar * ux - at * uy) * w
                        ay += (ar * uy + at * ux) * w
                    }
                }
                p.vx += ax * h
                p.vy += ay * h
                clampSpeed(p)
                p.x += p.vx * h
                p.y += p.vy * h
            }
            resolveCollisions(dragged)
        }
    }

    /**
     * Body-to-body collisions: separate overlaps, then bounce approaching pairs apart.
     * Only pairs involving the sun or a body still in its free-flight phase collide, so
     * settled planets on neighbouring orbits glide past each other instead of jostling.
     * A hit body inherits the striker's disturbance, so knock-ons slingshot too.
     */
    private fun resolveCollisions(dragged: Body?) {
        for (i in bodies.indices) {
            val a = bodies[i]
            for (j in i + 1 until bodies.size) {
                val b = bodies[j]
                if (a !== sun && a.calm >= RECOVER_DELAY && b.calm >= RECOVER_DELAY) continue
                val ra = a.collisionRadius
                val rb = b.collisionRadius
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dist = sqrt(dx * dx + dy * dy)
                val overlap = ra + rb - dist
                if (overlap <= 0.0) continue
                // Unit normal from a to b; fixed axis for degenerate zero-distance contact.
                val nx = if (dist > 1e-12) dx / dist else 1.0
                val ny = if (dist > 1e-12) dy / dist else 0.0
                // Shaded spheres: mass proportional to radius cubed; dragged body is immovable.
                val invMassA = if (a === dragged) 0.0 else 1.0 / (ra * ra * ra)
                val invMassB = if (b === dragged) 0.0 else 1.0 / (rb * rb * rb)
                val invSum = invMassA + invMassB
                if (invSum <= 0.0) continue
                a.x -= nx * (overlap * invMassA / invSum)
                a.y -= ny * (overlap * invMassA / invSum)
                b.x += nx * (overlap * invMassB / invSum)
                b.y += ny * (overlap * invMassB / invSum)
                val vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                if (vn < 0.0) {
                    val impulse = -(1.0 + BODY_BOUNCE) * vn / invSum
                    a.vx -= impulse * invMassA * nx
                    a.vy -= impulse * invMassA * ny
                    b.vx += impulse * invMassB * nx
                    b.vy += impulse * invMassB * ny
                    val c = min(a.calm, b.calm)
                    a.calm = c
                    b.calm = c
                }
            }
        }
    }

    /** Finger lifted after pulling: kick nearby planets outward so the swarm bursts apart. */
    fun releaseFinger(fingerX: Double, fingerY: Double) {
        for (p in planets) {
            val dx = p.x - fingerX
            val dy = p.y - fingerY
            val d = sqrt(dx * dx + dy * dy)
            if (d < 1e-6) continue
            val q = d / RELEASE_KICK_RANGE
            val k = RELEASE_KICK / (1.0 + q * q)
            p.vx += dx / d * k
            p.vy += dy / d * k
            clampSpeed(p)
            p.calm = 0.0
        }
    }

    /**
     * Dragged body released with finger velocity ([vx], [vy]) in plane units per real second.
     * Throws carry straight through at finger speed (times [THROW_GAIN]); the orbit spring
     * brings the body home afterwards. A gentle drop gets circular speed so it doesn't fall in.
     */
    fun release(body: Body, vx: Double, vy: Double, isSun: Boolean) {
        val tvx = vx / TIME_SCALE * THROW_GAIN
        val tvy = vy / TIME_SCALE * THROW_GAIN
        body.calm = 0.0
        body.vx = tvx
        body.vy = tvy
        if (!isSun) {
            val dx = body.x - sun.x
            val dy = body.y - sun.y
            val r = sqrt(dx * dx + dy * dy).coerceAtLeast(0.02)
            val vCirc = sqrt(GM / r)
            if (sqrt(tvx * tvx + tvy * tvy) < 0.4 * vCirc) {
                body.vx = sun.vx - dy / r * vCirc
                body.vy = sun.vy + dx / r * vCirc
            }
        }
        clampSpeed(body)
    }

    /** The screen edges are walls: bodies bounce back into view instead of leaving. */
    fun confine(camera: SolarCamera, dragged: Body?) {
        if (camera.w <= 0f) return
        val c0 = camera.screenToPlane(0f, 0f)
        val c1 = camera.screenToPlane(camera.w, 0f)
        val c2 = camera.screenToPlane(0f, camera.h)
        val c3 = camera.screenToPlane(camera.w, camera.h)
        val minX = minOf(c0[0], c1[0], c2[0], c3[0])
        val maxX = maxOf(c0[0], c1[0], c2[0], c3[0])
        val minY = minOf(c0[1], c1[1], c2[1], c3[1])
        val maxY = maxOf(c0[1], c1[1], c2[1], c3[1])
        for (body in bodies) {
            if (body === dragged) continue
            val m = body.collisionRadius
            if (body.x < minX + m) {
                body.x = minX + m
                body.vx = abs(body.vx) * WALL_BOUNCE
            }
            if (body.x > maxX - m) {
                body.x = maxX - m
                body.vx = -abs(body.vx) * WALL_BOUNCE
            }
            if (body.y < minY + m) {
                body.y = minY + m
                body.vy = abs(body.vy) * WALL_BOUNCE
            }
            if (body.y > maxY - m) {
                body.y = maxY - m
                body.vy = -abs(body.vy) * WALL_BOUNCE
            }
        }
    }
}

private class SolarCamera {
    var w = 0f
    var h = 0f
    var cx = 0f
    var cy = 0f
    var s = 0f
    var minDim = 0f
    var sinT = 0.4794f
    var cosT = 0.8776f
    val d = 2.2f

    fun update(width: Float, height: Float) {
        w = width
        h = height
        cx = width / 2f
        cy = height / 2f
        minDim = minOf(width, height)
        s = minDim * PLANE_SCALE
    }

    fun planeToScreen(x: Double, y: Double): FloatArray {
        val yf = y.toFloat()
        val factor = d / (d + yf * sinT)
        val sx = cx + x.toFloat() * factor * s
        val sy = cy - yf * cosT * factor * s
        return floatArrayOf(sx, sy, factor)
    }

    fun screenToPlane(sx: Float, sy: Float): DoubleArray {
        val dy = cy - sy
        val denom = cosT * s * d - dy * sinT
        val y = if (abs(denom) > 1e-6f) (dy * d) / denom else 0f
        val factor = d / (d + y * sinT)
        val x = (sx - cx) / (factor * s)
        return doubleArrayOf(x.toDouble(), y.toDouble())
    }
}

private class DragState {
    var body: Body? = null
    var active = false
    /** Pressed on empty space: the finger is an attractor at ([fingerX], [fingerY]). */
    var attracting = false
    var fingerX = 0.0
    var fingerY = 0.0
    var wasPressed = false
    var lastX = 0.0
    var lastY = 0.0
    var velX = 0.0
    var velY = 0.0
    var lastUptime = 0L
}

/**
 * Touch handling, fed from [WallpaperPointer] each frame. Pressing on a body grabs it
 * (drag and fling); pressing on empty space turns the finger into a strong attractor for
 * the planets, and lifting it bursts them outward.
 */
private fun stepDrag(
    drag: DragState,
    system: SolarSystem,
    camera: SolarCamera,
    pressed: Boolean,
    position: Offset,
    uptimeMillis: Long,
) {
    if (pressed && !drag.wasPressed) {
        drag.body = pickBody(position, system, camera)
        val p = camera.screenToPlane(position.x, position.y)
        if (drag.body != null) {
            drag.active = true
            drag.body!!.vx = 0.0
            drag.body!!.vy = 0.0
            drag.body!!.calm = 0.0
            drag.lastX = p[0]
            drag.lastY = p[1]
            drag.velX = 0.0
            drag.velY = 0.0
            drag.lastUptime = uptimeMillis
        } else if (camera.w > 0f) {
            drag.attracting = true
            drag.fingerX = p[0]
            drag.fingerY = p[1]
        }
    } else if (pressed && drag.active) {
        val p = camera.screenToPlane(position.x, position.y)
        val dt = (uptimeMillis - drag.lastUptime) / 1000.0
        if (dt > 0.001) {
            val nx = (p[0] - drag.lastX) / dt
            val ny = (p[1] - drag.lastY) / dt
            drag.velX = drag.velX * 0.5 + nx * 0.5
            drag.velY = drag.velY * 0.5 + ny * 0.5
        }
        drag.body!!.x = p[0]
        drag.body!!.y = p[1]
        drag.body!!.calm = 0.0
        drag.lastX = p[0]
        drag.lastY = p[1]
        drag.lastUptime = uptimeMillis
    } else if (pressed && drag.attracting) {
        val p = camera.screenToPlane(position.x, position.y)
        drag.fingerX = p[0]
        drag.fingerY = p[1]
    } else if (!pressed && drag.active) {
        system.release(drag.body!!, drag.velX, drag.velY, drag.body === system.sun)
        drag.active = false
        drag.body = null
    } else if (!pressed && drag.attracting) {
        system.releaseFinger(drag.fingerX, drag.fingerY)
        drag.attracting = false
    }
    drag.wasPressed = pressed
}

private class Star(
    val x: Float,
    val y: Float,
    val size: Float,
    val phase: Float,
    val brightness: Float,
)

/**
 * A tilted, perspective view of a small solar system. Touch interaction:
 * - press on the sun or a planet to grab it; drag and let go to fling it at finger speed;
 * - press on empty space to turn the finger into a strong attractor: planets snap in, whip
 *   around it and slingshot out; lifting the finger bursts the swarm outward.
 * After a short free flight every disturbed body is sprung back onto its home orbit (and a
 * thrown sun back to the centre). Screen edges are bouncy walls.
 */
@Composable
fun SolarSystemWallpaper(modifier: Modifier = Modifier) {
    val resetGeneration = rememberResetGeneration()
    val system = remember { SolarSystem().also { it.reset() } }
    val camera = remember { SolarCamera() }
    val drag = remember { DragState() }
    val frame = remember { mutableLongStateOf(0L) }
    val stars = remember { List(160) { makeStar() } }

    LaunchedEffect(resetGeneration) {
        system.reset()
        drag.active = false
        drag.attracting = false
        drag.body = null
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.0 else (now - last) / 1_000_000_000.0
                last = now
                if (dt > 0.0 && dt < 0.1) {
                    system.step(dt * TIME_SCALE, drag.body, drag.attracting, drag.fingerX, drag.fingerY)
                    system.confine(camera, drag.body)
                }
                stepDrag(
                    drag,
                    system,
                    camera,
                    WallpaperPointer.pressed,
                    Offset(WallpaperPointer.x, WallpaperPointer.y),
                    now / 1_000_000,
                )
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        camera.update(size.width, size.height)
        val t = frame.longValue / 1_000_000_000f

        drawRect(Color(0xFF02040A))
        drawRect(
            brush = Brush.radialGradient(
                colors = NebulaColors,
                center = Offset(camera.w * 0.5f, camera.h * 0.42f),
                radius = camera.minDim * 0.9f,
            ),
        )

        // Starfield: soft glints.
        for (star in stars) {
            val twinkle = 0.5f + 0.5f * sin(t * 0.8f + star.phase)
            val alpha = star.brightness * (0.25f + 0.75f * twinkle)
            val c = Offset(star.x * camera.w, star.y * camera.h)
            val r = star.size * camera.minDim
            drawCircle(Color(0xFF9EC4FF).copy(alpha = alpha * 0.22f), radius = r * 3f, center = c)
            drawCircle(Color(0xFFEAF4FF).copy(alpha = alpha), radius = r, center = c)
        }

        val sunProj = camera.planeToScreen(system.sun.x, system.sun.y)
        val entries = system.planets + system.sun
        val sorted = entries.sortedByDescending { it.y }

        for (body in sorted) {
            val proj = camera.planeToScreen(body.x, body.y)
            val sx = proj[0]
            val sy = proj[1]
            val factor = proj[2]
            val isSun = body === system.sun
            val radius = body.visualRadius * factor * camera.minDim

            if (isSun) {
                drawSun(sx, sy, radius)
            } else {
                if (body.ring) drawRing(sx, sy, radius, front = false)
                drawPlanet(sx, sy, radius, body.color, sunProj[0], sunProj[1])
                if (body.ring) drawRing(sx, sy, radius, front = true)
            }
        }
    }
}

private fun DrawScope.drawSun(sx: Float, sy: Float, radius: Float) {
    val c = Offset(sx, sy)
    drawCircle(
        brush = Brush.radialGradient(
            colors = SunGlowColors,
            center = c,
            radius = radius * 6f,
        ),
        radius = radius * 6f,
        center = c,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = SunBodyColors,
            center = Offset(sx - radius * 0.15f, sy - radius * 0.15f),
            radius = radius * 1.4f,
        ),
        radius = radius,
        center = c,
    )
    drawCircle(Color(0xFFFFFFFF).copy(alpha = 0.85f), radius = radius * 0.34f, center = c)
    // Anamorphic glint.
    drawOval(
        brush = Brush.horizontalGradient(
            colors = GlintColors,
        ),
        topLeft = Offset(sx - radius * 3.2f, sy - radius * 0.06f),
        size = Size(radius * 6.4f, radius * 0.12f),
    )
}

private fun DrawScope.drawRing(sx: Float, sy: Float, radius: Float, front: Boolean) {
    val topLeft = Offset(sx - radius * 2.1f, sy - radius * 0.5f)
    val size = Size(radius * 4.2f, radius * 1.0f)
    drawArc(
        brush = Brush.horizontalGradient(
            colors = RingColors,
        ),
        startAngle = if (front) 0f else 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = topLeft,
        size = size,
        style = Stroke(width = radius * 0.18f),
    )
}

private fun DrawScope.drawPlanet(
    sx: Float,
    sy: Float,
    radius: Float,
    color: Color,
    sunX: Float,
    sunY: Float,
) {
    if (radius <= 0.3f) return
    val dx = sunX - sx
    val dy = sunY - sy
    val len = sqrt(dx * dx + dy * dy)
    val lx = if (len > 0.001f) dx / len else -0.7f
    val ly = if (len > 0.001f) dy / len else -0.7f
    val c = Offset(sx, sy)
    val lit = Offset(sx + lx * radius * 0.5f, sy + ly * radius * 0.5f)
    val dark = Offset(sx - lx * radius * 0.6f, sy - ly * radius * 0.6f)

    // Sphere: day side toward the sun, night side falling off.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(lighten(color, 0.65f), color, darken(color, 0.75f)),
            center = lit,
            radius = radius * 1.6f,
        ),
        radius = radius,
        center = c,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = NightColors,
            center = dark,
            radius = radius * 1.4f,
        ),
        radius = radius,
        center = c,
    )
    // Rim light on the sun-facing edge.
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(lighten(color, 0.8f).copy(alpha = 0.8f), Color.Transparent),
            start = lit,
            end = dark,
        ),
        radius = radius * 0.97f,
        center = c,
        style = Stroke(width = max(0.6f, radius * 0.12f)),
    )
    // Soft specular.
    drawCircle(
        brush = Brush.radialGradient(
            colors = SpecularColors,
            center = lit,
            radius = radius * 0.45f,
        ),
        radius = radius * 0.45f,
        center = lit,
    )
}

private fun pickBody(position: Offset, system: SolarSystem, camera: SolarCamera): Body? {
    var best: Body? = null
    var bestDist = Float.MAX_VALUE
    for (body in system.planets + system.sun) {
        val proj = camera.planeToScreen(body.x, body.y)
        val dx = position.x - proj[0]
        val dy = position.y - proj[1]
        val dist = sqrt(dx * dx + dy * dy)
        val grabRadius = maxOf(body.visualRadius * proj[2] * camera.minDim + 36f, 48f)
        if (dist < grabRadius && dist < bestDist) {
            bestDist = dist
            best = body
        }
    }
    return best
}

private val NebulaColors = listOf(Color(0xFF0A1226).copy(alpha = 0.5f), Color.Transparent)
private val SunGlowColors = listOf(
    Color(0xFFFFF3C4).copy(alpha = 0.5f),
    Color(0xFFFFB347).copy(alpha = 0.16f),
    Color.Transparent,
)
private val SunBodyColors = listOf(Color(0xFFFFFDF2), Color(0xFFFFE9A8), Color(0xFFFF9A2E))
private val GlintColors = listOf(
    Color.Transparent,
    Color(0xFFFFF6D8).copy(alpha = 0.22f),
    Color.Transparent,
)
private val RingColors = listOf(
    Color(0xFFD9C58A).copy(alpha = 0.18f),
    Color(0xFFEFE0B4).copy(alpha = 0.55f),
    Color(0xFFD9C58A).copy(alpha = 0.18f),
)
private val NightColors = listOf(Color.Transparent, Color(0xFF02040A).copy(alpha = 0.55f))
private val SpecularColors = listOf(Color.White.copy(alpha = 0.5f), Color.Transparent)

private fun makeStar(): Star {
    val random = Random(System.nanoTime())
    return Star(
        x = random.nextFloat(),
        y = random.nextFloat(),
        size = 0.0006f + random.nextFloat() * 0.0016f,
        phase = random.nextFloat() * (2f * Math.PI).toFloat(),
        brightness = 0.3f + random.nextFloat() * 0.7f,
    )
}

private fun lighten(color: Color, amount: Float): Color {
    return lerp(color, Color.White, amount)
}

private fun darken(color: Color, amount: Float): Color {
    return lerp(color, Color.Black, amount)
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    return Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        a.alpha + (b.alpha - a.alpha) * t,
    )
}
