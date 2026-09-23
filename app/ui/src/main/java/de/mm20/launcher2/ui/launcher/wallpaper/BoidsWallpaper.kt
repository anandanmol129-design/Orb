package de.mm20.launcher2.ui.launcher.wallpaper

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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.isActive
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// Parameters ported verbatim from https://github.com/beneater/boids
private const val NUM_BOIDS = 80
private const val VISUAL_RANGE = 75f
private const val CENTERING_FACTOR = 0.005f
private const val MIN_DISTANCE = 20f
private const val AVOID_FACTOR = 0.05f
private const val MATCHING_FACTOR = 0.05f
private const val SPEED_LIMIT = 15f
private const val MARGIN = 200f
private const val TURN_FACTOR = 1f

private const val BIRD_LENGTH = 16f
private const val BIRD_HALF_WIDTH = 5f

private const val POINTER_RADIUS = 180f
private const val POINTER_FORCE = 14f

private class Bird {
    var x = 0f
    var y = 0f
    var dx = 0f
    var dy = 0f
    var depth = 0f

    init {
        val random = Random(System.nanoTime())
        x = random.nextFloat()
        y = random.nextFloat()
        dx = random.nextFloat() * 10f - 5f
        dy = random.nextFloat() * 10f - 5f
        depth = random.nextFloat()
    }
}

private class BoidsPointerState {
    var x = Float.NaN
    var y = Float.NaN
    var active = false

    fun setTarget(position: Offset, pressed: Boolean) {
        x = position.x
        y = position.y
        active = pressed
    }
}

private class BoidsViewport {
    var w = 0f
    var h = 0f
}

/**
 * A flock of birds using the classic boids algorithm (cohesion, separation, alignment with a
 * speed limit and edge avoidance), matching the reference implementation at eater.net/boids.
 */
@Composable
fun BoidsWallpaper(modifier: Modifier = Modifier) {
    val birds = remember { List(NUM_BOIDS) { Bird() } }
    val scratchPath = remember { Path() }
    val viewport = remember { BoidsViewport() }
    val pointer = remember { BoidsPointerState() }
    val frame = remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var initialized = false
        while (isActive) {
            withFrameNanos { now ->
                if (viewport.w > 0f) {
                    if (!initialized) {
                        for (bird in birds) {
                            bird.x *= viewport.w
                            bird.y *= viewport.h
                        }
                        initialized = true
                    }
                    pointer.setTarget(Offset(WallpaperPointer.x, WallpaperPointer.y), WallpaperPointer.pressed)
                    stepBoids(birds, viewport, pointer)
                }
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        viewport.w = size.width
        viewport.h = size.height

        drawRect(
            brush = Brush.verticalGradient(
                colors = SkyColors,
                startY = 0f,
                endY = size.height,
            )
        )

        // Low sun haze near the horizon.
        val sunAt = Offset(size.width * 0.62f, size.height * 0.96f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = SunHazeColors,
                center = sunAt,
                radius = size.minDimension * 0.7f,
            ),
            radius = size.minDimension * 0.7f,
            center = sunAt,
        )

        // Flock: depth-hazed gull silhouettes.
        for (bird in birds) {
            val scale = 0.55f + bird.depth * 0.85f
            val heading = atan2(bird.dy, bird.dx)
            val dirX = cos(heading)
            val dirY = sin(heading)
            val perpX = -sin(heading)
            val perpY = cos(heading)
            val len = BIRD_LENGTH * scale
            val halfW = BIRD_HALF_WIDTH * scale

            val tip = Offset(bird.x, bird.y)
            val backLeft = Offset(
                bird.x - dirX * len + perpX * halfW,
                bird.y - dirY * len + perpY * halfW,
            )
            val notch = Offset(
                bird.x - dirX * len * 0.55f,
                bird.y - dirY * len * 0.55f,
            )
            val backRight = Offset(
                bird.x - dirX * len - perpX * halfW,
                bird.y - dirY * len - perpY * halfW,
            )

            scratchPath.reset()
            scratchPath.moveTo(tip.x, tip.y)
            scratchPath.lineTo(backLeft.x, backLeft.y)
            scratchPath.lineTo(notch.x, notch.y)
            scratchPath.lineTo(backRight.x, backRight.y)
            scratchPath.close()
            val body = lerp(Color(0xFF6E6278), Color(0xFF0E1320), bird.depth)
            drawPath(scratchPath, body.copy(alpha = 0.55f + bird.depth * 0.4f))
        }

        // Vignette.
        drawRect(
            brush = Brush.radialGradient(
                colors = VignetteColors,
                center = Offset(size.width * 0.5f, size.height * 0.45f),
                radius = size.maxDimension * 0.75f,
            ),
        )
    }
}

private val SkyColors = listOf(
    Color(0xFF121A30),
    Color(0xFF23304C),
    Color(0xFF45496A),
    Color(0xFF7A5D62),
    Color(0xFFBE8A62),
    Color(0xFFEAB77C),
)
private val SunHazeColors = listOf(
    Color(0xFFFFD9A0).copy(alpha = 0.35f),
    Color(0xFFFFB870).copy(alpha = 0.12f),
    Color.Transparent,
)
private val VignetteColors = listOf(Color.Transparent, Color(0xFF0B0E1A).copy(alpha = 0.45f))

private fun stepBoids(birds: List<Bird>, viewport: BoidsViewport, pointer: BoidsPointerState) {
    for (boid in birds) {
        flyTowardsCenter(boid, birds)
        avoidOthers(boid, birds)
        matchVelocity(boid, birds)
        repelFromPointer(boid, pointer)
        limitSpeed(boid)
        keepWithinBounds(boid, viewport)

        boid.x += boid.dx
        boid.y += boid.dy
    }
}

private fun repelFromPointer(boid: Bird, pointer: BoidsPointerState) {
    if (!pointer.active || !pointer.x.isFinite() || !pointer.y.isFinite()) return
    val dx = boid.x - pointer.x
    val dy = boid.y - pointer.y
    val dist2 = dx * dx + dy * dy
    if (dist2 < POINTER_RADIUS * POINTER_RADIUS && dist2 > 0f) {
        val dist = sqrt(dist2)
        val push = (1f - dist / POINTER_RADIUS) * POINTER_FORCE
        boid.dx += dx / dist * push
        boid.dy += dy / dist * push
    }
}

private fun flyTowardsCenter(boid: Bird, birds: List<Bird>) {
    var centerX = 0f
    var centerY = 0f
    var numNeighbors = 0
    for (other in birds) {
        if (distance(boid, other) < VISUAL_RANGE) {
            centerX += other.x
            centerY += other.y
            numNeighbors++
        }
    }
    if (numNeighbors > 0) {
        centerX /= numNeighbors
        centerY /= numNeighbors
        boid.dx += (centerX - boid.x) * CENTERING_FACTOR
        boid.dy += (centerY - boid.y) * CENTERING_FACTOR
    }
}

private fun avoidOthers(boid: Bird, birds: List<Bird>) {
    var moveX = 0f
    var moveY = 0f
    for (other in birds) {
        if (other !== boid) {
            if (distance(boid, other) < MIN_DISTANCE) {
                moveX += boid.x - other.x
                moveY += boid.y - other.y
            }
        }
    }
    boid.dx += moveX * AVOID_FACTOR
    boid.dy += moveY * AVOID_FACTOR
}

private fun matchVelocity(boid: Bird, birds: List<Bird>) {
    var avgDX = 0f
    var avgDY = 0f
    var numNeighbors = 0
    for (other in birds) {
        if (distance(boid, other) < VISUAL_RANGE) {
            avgDX += other.dx
            avgDY += other.dy
            numNeighbors++
        }
    }
    if (numNeighbors > 0) {
        avgDX /= numNeighbors
        avgDY /= numNeighbors
        boid.dx += (avgDX - boid.dx) * MATCHING_FACTOR
        boid.dy += (avgDY - boid.dy) * MATCHING_FACTOR
    }
}

private fun limitSpeed(boid: Bird) {
    val speed = sqrt(boid.dx * boid.dx + boid.dy * boid.dy)
    if (speed > SPEED_LIMIT) {
        boid.dx = boid.dx / speed * SPEED_LIMIT
        boid.dy = boid.dy / speed * SPEED_LIMIT
    }
}

private fun keepWithinBounds(boid: Bird, viewport: BoidsViewport) {
    if (boid.x < MARGIN) {
        boid.dx += TURN_FACTOR
    }
    if (boid.x > viewport.w - MARGIN) {
        boid.dx -= TURN_FACTOR
    }
    if (boid.y < MARGIN) {
        boid.dy += TURN_FACTOR
    }
    if (boid.y > viewport.h - MARGIN) {
        boid.dy -= TURN_FACTOR
    }
}

private fun distance(a: Bird, b: Bird): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}
