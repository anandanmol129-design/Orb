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
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random

// Flocking rules from https://github.com/beneater/boids. The simulation runs in dp (one world
// unit = 1dp, converted to pixels only when drawing) so birds, spacing and speed look the same on
// every screen density. Distances are tuned to the bird size: MIN_DISTANCE is just above the
// widest wingspan so birds never merge into blobs, and VISUAL_RANGE is ~3x that.
private const val NUM_BOIDS = 45
private const val VISUAL_RANGE = 95f // dp
private const val CENTERING_FACTOR = 0.005f
private const val MIN_DISTANCE = 30f // dp
private const val AVOID_FACTOR = 0.05f
private const val MATCHING_FACTOR = 0.05f
private const val SPEED_LIMIT = 5f // dp per frame
private const val MARGIN = 64f // dp
private const val TURN_FACTOR = 0.35f // dp per frame^2
private const val INITIAL_SPEED = 1.7f // dp per frame, max per axis

// Population dynamics: birds breed while in flocks and die in isolation.
private const val MIN_BOIDS = 15
private const val MAX_BOIDS = 60
private const val FLOCK_MIN_NEIGHBORS = 2
private const val BREED_INTERVAL = 45f
private const val ALONE_GRACE = 8f
private const val OFFSPRING_POS_JITTER = 14f // dp
private const val OFFSPRING_VEL_JITTER = 0.7f // dp per frame

// Bird silhouette: nose-to-tail length in dp at mid depth (far birds 0.7x, near birds 1.3x).
// The fully spread wingspan is 2 * BIRD_WING_REACH * length.
private const val BIRD_LENGTH = 16f // dp
private const val BIRD_DEPTH_SCALE_MIN = 0.7f
private const val BIRD_DEPTH_SCALE_RANGE = 0.6f
private const val BIRD_WING_REACH = 0.78f

// Wing flap: phase advance per frame is FLAP_BASE + speed * FLAP_PER_SPEED (radians), and the
// apparent span oscillates between (1 - 2 * FLAP_DEPTH) and 1 of the full span.
private const val FLAP_BASE = 0.12f
private const val FLAP_PER_SPEED = 0.05f
private const val FLAP_DEPTH = 0.16f
private const val TWO_PI = (2.0 * Math.PI).toFloat()

// Music: while it plays, every wingbeat locks to the 120 BPM groove (wings fully spread on each
// beat) and the flock flies MUSIC_SPEED_LIFT faster; both blend back out as the groove fades.
private const val BEAT_SECONDS = 0.5f
private const val BEAT_PEAK_SECONDS = 0.055f
private const val FLAP_LOCK = 0.12f
private const val MUSIC_SPEED_LIFT = 0.06f

private const val POINTER_RADIUS = 70f // dp
private const val POINTER_FORCE = 5f // dp per frame

private class Bird {
    var x = 0f
    var y = 0f
    var dx = 0f
    var dy = 0f
    var depth = 0f
    var flapPhase = 0f

    // Population state: seconds in a flock since the last offspring (or since it
    // was last alone), seconds alone since it was last in a flock, and the
    // neighbor count within VISUAL_RANGE from the latest population pass.
    var breedTime = 0f
    var aloneTime = 0f
    var neighbors = 0

    init {
        val random = Random(System.nanoTime())
        x = random.nextFloat()
        y = random.nextFloat()
        dx = (random.nextFloat() * 2f - 1f) * INITIAL_SPEED
        dy = (random.nextFloat() * 2f - 1f) * INITIAL_SPEED
        depth = random.nextFloat()
        flapPhase = random.nextFloat() * TWO_PI
    }

    fun spawnOffspring(): Bird {
        val random = Random(System.nanoTime())
        return Bird().also {
            it.x = x + (random.nextFloat() * 2f - 1f) * OFFSPRING_POS_JITTER
            it.y = y + (random.nextFloat() * 2f - 1f) * OFFSPRING_POS_JITTER
            it.dx = dx + (random.nextFloat() * 2f - 1f) * OFFSPRING_VEL_JITTER
            it.dy = dy + (random.nextFloat() * 2f - 1f) * OFFSPRING_VEL_JITTER
        }
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

/** Simulation bounds in dp, plus the px-per-dp factor used to draw and to map pointer input. */
private class BoidsViewport {
    var w = 0f
    var h = 0f
    var density = 1f
}

/**
 * A flock of birds using the classic boids algorithm (cohesion, separation, alignment with a
 * speed limit and edge avoidance), after the reference implementation at eater.net/boids.
 * The simulation runs in dp so the flock looks the same on any screen density. Birds are drawn
 * as filled swept-wing silhouettes (~11-21dp long, up to ~33dp wingspan) whose wings flap faster
 * the faster they fly; they are tinted light against the dark upper sky and dark against the
 * bright horizon so they stay readable everywhere. Birds breed while in flocks and die in
 * isolation, keeping the flock between MIN_BOIDS and MAX_BOIDS.
 * While music plays the wingbeats lock to the beat and the flock flies a touch faster.
 */
@Composable
fun BoidsWallpaper(modifier: Modifier = Modifier) {
    val birds = remember { MutableList(NUM_BOIDS) { Bird() } }
    val scratchPath = remember { Path() }
    val viewport = remember { BoidsViewport() }
    val pointer = remember { BoidsPointerState() }
    val frame = remember { mutableLongStateOf(0L) }
    val pulse = remember { MusicPulse() }

    LaunchedEffect(Unit) {
        var initialized = false
        var lastFrameNanos = 0L
        var lastBeat = 0f
        var sinceBeat = 0f
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
                    pointer.setTarget(
                        Offset(WallpaperPointer.x / viewport.density, WallpaperPointer.y / viewport.density),
                        WallpaperPointer.pressed,
                    )
                    val dt = if (lastFrameNanos == 0L) {
                        0f
                    } else {
                        ((now - lastFrameNanos) / 1_000_000_000.0).toFloat()
                    }
                    pulse.update(now)
                    // Re-sync the beat clock on each thump's rising edge (the pulse rests at 0
                    // for ~200 ms between thumps).
                    if (pulse.beat > 0f && lastBeat == 0f) sinceBeat = 0f else sinceBeat += dt
                    lastBeat = pulse.beat
                    val flapTarget = TWO_PI * (sinceBeat - BEAT_PEAK_SECONDS) / BEAT_SECONDS
                    stepBoids(birds, viewport, pointer, pulse.groove, flapTarget, TWO_PI * dt / BEAT_SECONDS)
                    updatePopulation(birds, dt)
                }
                lastFrameNanos = now
                frame.longValue = now
            }
        }
    }

    Canvas(modifier = modifier) {
        frame.longValue // redraw is driven by the simulation loop
        val px = density
        viewport.density = px
        viewport.w = size.width / px
        viewport.h = size.height / px

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

        // Flock: filled swept-wing silhouettes, one reused path.
        for (bird in birds) {
            val speed = sqrt(bird.dx * bird.dx + bird.dy * bird.dy)
            val dirX: Float
            val dirY: Float
            if (speed > 1e-4f) {
                dirX = bird.dx / speed
                dirY = bird.dy / speed
            } else {
                dirX = 1f
                dirY = 0f
            }
            val len = BIRD_LENGTH * (BIRD_DEPTH_SCALE_MIN + bird.depth * BIRD_DEPTH_SCALE_RANGE) * px
            val span = 1f - FLAP_DEPTH + FLAP_DEPTH * cos(bird.flapPhase)
            buildBirdPath(scratchPath, bird.x * px, bird.y * px, dirX, dirY, len, span)

            // Light against the dark upper sky, dark against the bright horizon; nearer birds
            // get the stronger contrast, farther ones fade toward the haze.
            val skyT = (bird.y / viewport.h).coerceIn(0f, 1f)
            val brightSky = smoothStep(0.38f, 0.72f, skyT)
            val onDark = lerp(BirdOnDarkFar, BirdOnDarkNear, bird.depth)
            val onLight = lerp(BirdOnLightFar, BirdOnLightNear, bird.depth)
            val body = lerp(onDark, onLight, brightSky)
            drawPath(scratchPath, body.copy(alpha = 0.72f + bird.depth * 0.28f))
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

private val BirdOnDarkFar = Color(0xFF8E8AA6)
private val BirdOnDarkNear = Color(0xFFEDE2D8)
private val BirdOnLightFar = Color(0xFF5E5268)
private val BirdOnLightNear = Color(0xFF121626)

private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * Writes a swept-wing bird silhouette into [path] (reset first), centered at ([cx], [cy]) in px
 * and pointing along the unit vector ([dirX], [dirY]). Shape coordinates are in the bird's frame:
 * u forward along the heading and v sideways, both in units of [len]. [span] scales how far the
 * wingtips reach sideways (the flap).
 */
private fun buildBirdPath(
    path: Path,
    cx: Float,
    cy: Float,
    dirX: Float,
    dirY: Float,
    len: Float,
    span: Float,
) {
    // World point = center + u * dir + v * perp, with perp = (-dirY, dirX).
    fun wx(u: Float, v: Float) = cx + (u * dirX - v * dirY) * len
    fun wy(u: Float, v: Float) = cy + (u * dirY + v * dirX) * len

    val reach = BIRD_WING_REACH * span
    path.reset()
    path.moveTo(wx(0.50f, 0f), wy(0.50f, 0f)) // head
    // Right side, head to tail.
    path.lineTo(wx(0.30f, 0.07f), wy(0.30f, 0.07f)) // neck
    path.lineTo(wx(0.12f, 0.10f), wy(0.12f, 0.10f)) // leading wing root
    path.quadraticTo( // curved leading edge, swept back to the wingtip
        wx(0.06f, 0.55f * reach), wy(0.06f, 0.55f * reach),
        wx(-0.24f, reach), wy(-0.24f, reach),
    )
    path.quadraticTo( // trailing edge back to the body
        wx(-0.12f, 0.42f * reach), wy(-0.12f, 0.42f * reach),
        wx(-0.14f, 0.10f), wy(-0.14f, 0.10f),
    )
    path.lineTo(wx(-0.50f, 0.13f), wy(-0.50f, 0.13f)) // tail corner
    path.lineTo(wx(-0.40f, 0f), wy(-0.40f, 0f)) // tail notch
    // Left side, mirrored, tail to head.
    path.lineTo(wx(-0.50f, -0.13f), wy(-0.50f, -0.13f))
    path.lineTo(wx(-0.14f, -0.10f), wy(-0.14f, -0.10f))
    path.quadraticTo(
        wx(-0.12f, -0.42f * reach), wy(-0.12f, -0.42f * reach),
        wx(-0.24f, -reach), wy(-0.24f, -reach),
    )
    path.quadraticTo(
        wx(0.06f, -0.55f * reach), wy(0.06f, -0.55f * reach),
        wx(0.12f, -0.10f), wy(0.12f, -0.10f),
    )
    path.lineTo(wx(0.30f, -0.07f), wy(0.30f, -0.07f))
    path.close()
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

/**
 * [groove] (0..1) blends each wingbeat from free flapping toward [flapTarget], the beat-locked flap
 * phase, which advances [beatAdvance] radians this frame, and lifts the flight speed slightly.
 */
private fun stepBoids(
    birds: List<Bird>,
    viewport: BoidsViewport,
    pointer: BoidsPointerState,
    groove: Float,
    flapTarget: Float,
    beatAdvance: Float,
) {
    val move = 1f + MUSIC_SPEED_LIFT * groove
    for (boid in birds) {
        flyTowardsCenter(boid, birds)
        avoidOthers(boid, birds)
        matchVelocity(boid, birds)
        repelFromPointer(boid, pointer)
        limitSpeed(boid)
        keepWithinBounds(boid, viewport)

        boid.x += boid.dx * move
        boid.y += boid.dy * move

        val speed = sqrt(boid.dx * boid.dx + boid.dy * boid.dy)
        val free = FLAP_BASE + speed * FLAP_PER_SPEED
        if (groove > 0f) {
            boid.flapPhase += free + (beatAdvance - free) * groove + flapLock(boid.flapPhase, flapTarget) * groove
        } else {
            boid.flapPhase += free
        }
        if (boid.flapPhase > TWO_PI) boid.flapPhase -= TWO_PI
        if (boid.flapPhase < 0f) boid.flapPhase += TWO_PI
    }
}

/**
 * Population dynamics, applied after the movement step: birds with at least
 * [FLOCK_MIN_NEIGHBORS] neighbors within [VISUAL_RANGE] breed every [BREED_INTERVAL] seconds,
 * birds with none die after [ALONE_GRACE] seconds, and the population is hard-clamped to
 * [MIN_BOIDS]..[MAX_BOIDS]. Between those two states (exactly one neighbor) no timer runs.
 */
private fun updatePopulation(birds: MutableList<Bird>, dt: Float) {
    // One O(n^2) neighbor count pass over the post-step positions.
    for (bird in birds) bird.neighbors = 0
    for (i in birds.indices) {
        val a = birds[i]
        for (j in i + 1 until birds.size) {
            if (distance(a, birds[j]) < VISUAL_RANGE) {
                a.neighbors++
                birds[j].neighbors++
            }
        }
    }

    for (bird in birds) {
        when {
            bird.neighbors >= FLOCK_MIN_NEIGHBORS -> {
                bird.breedTime += dt
                bird.aloneTime = 0f
            }
            bird.neighbors == 0 -> {
                bird.aloneTime += dt
                bird.breedTime = 0f
            }
            // Exactly one neighbor: neither in a flock nor alone, no timer runs.
        }
    }

    // Deaths: in-place removal over a backward index loop, never below MIN_BOIDS.
    var i = birds.size - 1
    while (i >= 0) {
        val bird = birds[i]
        if (bird.aloneTime >= ALONE_GRACE && birds.size > MIN_BOIDS) {
            birds.removeAt(i)
        }
        i--
    }

    // Births: only birds present before this pass breed; newborns land beyond that
    // boundary and join the count on the next step. Never above MAX_BOIDS.
    val grown = birds.size
    i = 0
    while (i < grown) {
        val bird = birds[i]
        if (bird.breedTime >= BREED_INTERVAL && birds.size < MAX_BOIDS) {
            bird.breedTime = 0f
            birds.add(bird.spawnOffspring())
        }
        i++
    }
}

/** A small step pulling [phase] toward [target] the short way round the circle. */
private fun flapLock(phase: Float, target: Float): Float {
    var d = (target - phase) % TWO_PI
    if (d > PI_F) d -= TWO_PI else if (d < -PI_F) d += TWO_PI
    return d * FLAP_LOCK
}

private const val PI_F = Math.PI.toFloat()

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
