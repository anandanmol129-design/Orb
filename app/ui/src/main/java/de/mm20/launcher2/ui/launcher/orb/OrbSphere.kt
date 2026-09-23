package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.KeyframesSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.OrbShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * The assistant sphere: a matte white body with two small black dot eyes. The body can take
 * several rounded "blob" shapes (see [OrbShape]); the face and all motion stay the same.
 *
 * Fully monochrome — voice states are expressed purely through body language:
 * a floating drift and squashy breathing while idle, an eager squash-stretch bob while
 * listening, a slow rock with darting glances while thinking, a hop with a happy face on
 * success and a shivering wince on error.
 *
 * The whole body moves as one soft mass: every offset, tilt and squash applies to the face
 * too. The eyes never leave their position on the face; they "look" toward [lookAt] by
 * shifting their gaze (spring-eased, with quick random saccades while idle so the gaze never
 * glides like a machine), blink by squashing (the right eye trailing the left slightly), and
 * carry a small glint. Brows ride along and pose per state. Every state change fires a quick
 * squash-and-stretch flinch so transitions feel physical.
 *
 * While media plays, the idle body thumps along to a groove ([playing]), like a music
 * visualizer.
 */
@Composable
fun OrbSphere(
    state: OrbState,
    modifier: Modifier = Modifier,
    lookAt: Offset = Offset.Zero,
    orbCenter: Offset = Offset.Zero,
    orbRadius: Float = 0f,
    shape: OrbShape = OrbShape.Circle,
    fill: OrbFill = OrbFill.Filled,
    playing: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "orb")

    // Slow clock: idle drift, thinking rock, sleep sway.
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = PhaseSpec,
        label = "phase",
    )

    // Fast clock: listening bob, error shiver, success wiggle.
    val fast by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = FastSpec,
        label = "fast",
    )

    // Breathing clock for the idle body scale.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = BreathSpec,
        label = "breath",
    )

    // Double blink every cycle; the right eye lags the left a touch so it reads as a real face.
    val blinkL by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = BlinkLRepeat,
        label = "blinkL",
    )
    val blinkR by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = BlinkRRepeat,
        label = "blinkR",
    )

    // Saccades: the gaze holds on a spot, then jumps to a new one instead of gliding.
    val saccadeTick = phase / SaccadeStep
    val tick = floor(saccadeTick)
    val jump = ((saccadeTick - tick) / 0.14f).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    val sx0 = hash1d(tick)
    val sx1 = hash1d(tick + 1f)
    val sy0 = hash1d(tick + 31.7f)
    val sy1 = hash1d(tick + 32.7f)
    val saccadeX = sx0 + (sx1 - sx0) * jump
    val saccadeY = sy0 + (sy1 - sy0) * jump

    // Gaze follows the target. While the pie is open it tracks the finger with a very stiff
    // spring (the same approach the pie editor uses for its icons); otherwise a softer spring
    // that turns the saccade steps into quick eye flicks.
    val gazeTarget = when (state) {
        OrbState.Idle -> Offset(
            cos(phase * 0.7f) * 0.45f + saccadeX * 0.3f,
            sin(phase * 0.9f) * 0.35f + saccadeY * 0.22f,
        )
        // Thinking: look up, glancing around in quick jumps.
        OrbState.Thinking -> Offset(
            cos(phase * 2.5f) * 0.5f + saccadeX * 0.35f,
            -0.35f + saccadeY * 0.2f,
        )
        OrbState.Listening -> Offset(0f, -0.3f)
        OrbState.Error -> Offset(0f, 0.4f)
        OrbState.Success -> Offset(0f, -0.1f)
        OrbState.Pie -> lookAt
        OrbState.Sleep -> Offset(0f, 0.15f)
    }
    val gazeSpec: AnimationSpec<Float> = if (state == OrbState.Pie) GazeSpecPie else GazeSpecSoft
    val gazeX by animateFloatAsState(gazeTarget.x, gazeSpec, label = "gazeX")
    val gazeY by animateFloatAsState(gazeTarget.y, gazeSpec, label = "gazeY")

    // How far the dot eyes open; eased so states cross-fade instead of snapping. Blinking is
    // layered on top, and thinking squints the two eyes to slightly different sizes.
    val openness by animateFloatAsState(
        targetValue = when (state) {
            OrbState.Listening -> 1.35f
            OrbState.Thinking -> 0.62f
            OrbState.Error -> 0.4f
            OrbState.Success -> 1.1f
            OrbState.Sleep -> 0.06f
            OrbState.Idle, OrbState.Pie -> 1f
        },
        animationSpec = EyeOpenSpec,
        label = "eyeOpen",
    )
    val openL = openness * blinkL * if (state == OrbState.Thinking) 0.8f else 1f
    val openR = openness * blinkR * if (state == OrbState.Thinking) 1.1f else 1f

    // Brows: raised and arched when alert, one cocked while thinking, slammed down on error,
    // and tilted along with the gaze elsewhere.
    val browRaise by animateFloatAsState(
        targetValue = when (state) {
            OrbState.Listening -> 0.06f
            OrbState.Thinking -> 0.045f
            OrbState.Success -> 0.08f
            OrbState.Pie -> 0.02f
            OrbState.Error -> -0.015f
            OrbState.Idle -> 0.01f
            OrbState.Sleep -> 0f
        },
        animationSpec = BrowSpec,
        label = "browRaise",
    )
    val browTiltL by animateFloatAsState(
        targetValue = when (state) {
            OrbState.Listening -> -8f
            OrbState.Thinking -> -18f
            OrbState.Success -> -14f
            OrbState.Error -> -26f
            OrbState.Pie -> lookAt.x * 10f
            OrbState.Idle -> gazeX * 6f
            OrbState.Sleep -> 0f
        },
        animationSpec = BrowSpec,
        label = "browTiltL",
    )
    val browTiltR by animateFloatAsState(
        targetValue = when (state) {
            OrbState.Listening -> 8f
            OrbState.Thinking -> 4f
            OrbState.Success -> 14f
            OrbState.Error -> 26f
            OrbState.Pie -> lookAt.x * 10f
            OrbState.Idle -> gazeX * 6f
            OrbState.Sleep -> 0f
        },
        animationSpec = BrowSpec,
        label = "browTiltR",
    )

    // Fling on every state change: a fast squash and a springy recovery, so the orb visibly
    // flinches into each new state.
    val reaction = remember { Animatable(1f) }
    LaunchedEffect(state) {
        reaction.snapTo(0.84f)
        reaction.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }

    // Success: a scale pop plus a hop that springs back down through the rest position.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == OrbState.Success) {
            pop.snapTo(1.2f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
        } else {
            pop.snapTo(1f)
        }
    }
    val hop = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == OrbState.Success) {
            hop.snapTo(-1f)
            hop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        } else {
            hop.snapTo(0f)
        }
    }

    // The groove: one thump every half second while media plays. Both values are sampled in the
    // draw phase only, so the pulse never recomposes the face.
    val thump by transition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = ThumpRepeat,
        label = "thump",
    )
    val groove by animateFloatAsState(
        targetValue = if (playing && state == OrbState.Idle) 1f else 0f,
        animationSpec = GrooveSpec,
        label = "groove",
    )

    val ink = if (fill == OrbFill.Outline) Color.White else Color(0xFF17181C)

    Canvas(modifier) {
        val c = if (orbCenter == Offset.Zero) center else orbCenter
        val baseR = if (orbRadius > 0f) orbRadius else size.minDimension * 0.28f

        val isSleep = state == OrbState.Sleep

        // How hard the current groove thump hits, sampled at draw time.
        val beat = thump * groove

        // Breathing, the success pop and the beat scale the whole body. A sleeping orb stays at
        // rest.
        val breathe = 1f + 0.04f * sin(breath)
        val r = if (isSleep) baseR * 0.97f else baseR * breathe * pop.value * (1f + 0.09f * beat)

        // Body language: positional drift, tilt and squash-stretch per state.
        var mx = 0f
        var my = 0f
        var tiltDeg = 0f
        var sx = 1f
        var sy = 1f
        when (state) {
            // Lazy float around the rest position, leaning with the drift. With music playing,
            // the body also stretches and thumps up on each beat like a soft mass keeping time.
            OrbState.Idle -> {
                mx = sin(phase * 0.7f) * r * 0.05f
                my = cos(phase * 0.5f) * r * 0.04f - beat * r * 0.05f
                tiltDeg = sin(phase * 0.5f + 1.3f) * 3.5f
                sy = 1f + 0.05f * beat
                sx = 1f - 0.035f * beat
            }
            // Eager bob: stretched while lifted, squashed while settled.
            OrbState.Listening -> {
                val th = fast * 2f
                my = sin(th) * r * 0.06f
                tiltDeg = cos(th) * 4f
                sy = 1f + 0.055f * sin(th)
                sx = 1f - 0.045f * sin(th)
            }
            // Concentrating: slow rock and hover.
            OrbState.Thinking -> {
                mx = sin(phase * 1.1f) * r * 0.025f
                my = sin(phase * 1.5f) * r * 0.02f
                tiltDeg = sin(phase * 3f) * 8f
                sy = 1f + 0.02f * sin(phase * 3f)
                sx = 1f - 0.015f * sin(phase * 3f)
            }
            // Happy wiggle after the hop.
            OrbState.Success -> {
                tiltDeg = sin(fast * 4f) * 3.5f
                sy = 1f + 0.03f * sin(fast * 4f)
                sx = 1f - 0.025f * sin(fast * 4f)
            }
            // Shivering wobble, sagging a little.
            OrbState.Error -> {
                val th = fast * 5f
                mx = sin(th) * r * 0.07f
                my = r * 0.03f
                tiltDeg = sin(th + 1.1f) * 7f
                sy = 1f - 0.03f * abs(sin(th))
                sx = 1f + 0.03f * abs(sin(th))
            }
            // Lean toward the finger that is steering it.
            OrbState.Pie -> {
                mx = lookAt.x * r * 0.07f
                my = lookAt.y * r * 0.05f
                tiltDeg = lookAt.x * 6f
            }
            // Barely breathing sway.
            OrbState.Sleep -> {
                my = sin(phase * 0.4f) * r * 0.012f
                tiltDeg = sin(phase * 0.4f) * 1.5f
            }
        }
        // State-change flinch: quick squash with a matching sideways bulge.
        sy *= reaction.value
        sx *= 2f - reaction.value
        // Success hop: stretched on the way up, squashed through the landing overshoot.
        my += hop.value * r * 0.3f
        sy *= 1f - 0.22f * hop.value
        sx *= 1f + 0.18f * hop.value

        val body = c + Offset(mx, my)
        val bodyPath = orbBodyPath(shape, body, r)

        if (!isSleep && fill != OrbFill.Outline) {
            // Soft neutral drop shadow so the white body reads on any wallpaper. It tracks the
            // body's position but not its tilt or squash.
            val shadowCenter = body + Offset(0f, baseR * 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = ShadowColors,
                    center = shadowCenter,
                    radius = baseR * 1.3f,
                ),
                radius = baseR * 1.3f,
                center = shadowCenter,
            )
        }

        // Body and face share one transform so the orb moves as a single soft mass: the squash
        // is applied in the body's own frame, then the whole thing is tilted.
        rotate(tiltDeg, pivot = body) {
            scale(sx, sy, pivot = body) {
                when {
                    isSleep -> {
                        // Sleeping: a transparent outline with a thin edge, so the assistant
                        // reads as "off" while the wallpaper stays visible through the centre.
                        drawPath(
                            path = bodyPath,
                            color = Color(0xFFAFB0B8),
                            style = Stroke(width = maxOf(r * 0.022f, 1.5f)),
                        )
                    }
                    fill == OrbFill.Outline -> {
                        // Hollow body: just the edge, so the wallpaper shows through the centre.
                        drawPath(
                            path = bodyPath,
                            color = Color(0xFF8E9099),
                            style = Stroke(width = maxOf(r * 0.055f, 2f)),
                        )
                    }
                    else -> {
                        // Matte white body with the faintest top-left shading for volume.
                        drawPath(
                            path = bodyPath,
                            brush = Brush.radialGradient(
                                colors = BodyColors,
                                center = body + Offset(-r * 0.25f, -r * 0.3f),
                                radius = r * 1.8f,
                            ),
                        )
                        drawPath(
                            path = bodyPath,
                            color = Color(0xFFD9DAE1),
                            style = Stroke(width = r * 0.03f),
                        )
                    }
                }

                if (!isSleep) {
                    val placement = eyePlacement(shape)
                    val eyeDx = r * placement[0]
                    val eyeY = body.y + r * placement[1]
                    val er = r * 0.11f
                    val gx = gazeX * r * 0.38f
                    val gy = gazeY * r * 0.38f

                    when {
                        state == OrbState.Success -> {
                            // Happy, closed-smile eyes.
                            val arcR = r * 0.17f
                            for (s in Signs) {
                                val dx = s * eyeDx
                                drawArc(
                                    color = ink,
                                    startAngle = 200f,
                                    sweepAngle = 140f,
                                    useCenter = false,
                                    topLeft = Offset(body.x + dx - arcR, eyeY - arcR),
                                    size = Size(arcR * 2, arcR * 2),
                                    style = Stroke(width = r * 0.09f, cap = StrokeCap.Round),
                                )
                            }
                        }
                        state == OrbState.Error -> {
                            // Wince: >_< chevrons.
                            val w = er * 1.5f
                            val h = er * 1.35f
                            val stroke = r * 0.07f
                            for (s in Signs) {
                                val apex = Offset(body.x + s * eyeDx + gx, eyeY + gy)
                                drawLine(ink, apex, apex + Offset(s * w, -h), stroke, cap = StrokeCap.Round)
                                drawLine(ink, apex, apex + Offset(s * w, h), stroke, cap = StrokeCap.Round)
                            }
                        }
                        else -> {
                            // Two small black dots. They blink (squash) and look (shift their
                            // gaze) but never wander from their position on the face.
                            for (s in Signs) {
                                val open = if (s < 0f) openL else openR
                                val ec = Offset(body.x + s * eyeDx + gx, eyeY + gy)
                                drawOval(
                                    color = ink,
                                    topLeft = Offset(ec.x - er, ec.y - er * open),
                                    size = Size(er * 2f, er * 2f * open),
                                )
                                if (fill != OrbFill.Outline && open > 0.55f) {
                                    // Catch-light, offset up-left and along the gaze.
                                    drawCircle(
                                        color = Color.White,
                                        radius = er * 0.3f,
                                        center = ec + Offset(-er * 0.32f, -er * 0.38f * open),
                                    )
                                }
                            }
                        }
                    }

                    // Brows.
                    val browHalf = er * 1.15f
                    for (s in Signs) {
                        val browTilt = if (s < 0f) browTiltL else browTiltR
                        val bcx = body.x + s * eyeDx + gx * 0.5f
                        val bcy = eyeY - er * 2.8f - browRaise * r
                        rotate(browTilt, pivot = Offset(bcx, bcy)) {
                            drawLine(
                                color = ink,
                                start = Offset(bcx - browHalf, bcy),
                                end = Offset(bcx + browHalf, bcy),
                                strokeWidth = r * 0.045f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Per-eye blink: a double blink every cycle, shifted by [leadMs]. */
private fun blinkSpec(leadMs: Int): KeyframesSpec<Float> = keyframes {
    durationMillis = 6000
    1f at 0
    1f at (4400 + leadMs)
    0.06f at (4520 + leadMs)
    1f at (4640 + leadMs)
    0.06f at (4760 + leadMs)
    1f at (4880 + leadMs)
    1f at 6000
}

/** One saccade step of the 6s slow clock. */
private val SaccadeStep = (PI / 6).toFloat()

/** Deterministic pseudo-random value in -1..1, so saccades are reproducible. */
private fun hash1d(x: Float): Float {
    val v = sin(x * 12.9898f) * 43758.5453f
    return (v - floor(v)) * 2f - 1f
}

/**
 * The eye position for a shape, as factors of the body radius. Most shapes share the default
 * face; a few (like the point-up triangle) need the eyes lowered into their wider base so they
 * stay inside the body.
 */
private val EyeDefault = floatArrayOf(0.42f, -0.05f)
private val EyeTriangle = floatArrayOf(0.3f, 0.22f)

private fun eyePlacement(shape: OrbShape): FloatArray = when (shape) {
    OrbShape.Triangle -> EyeTriangle
    else -> EyeDefault
}

/** One half-second groove bar: a quick punch up, then a soft settle. */
private val ThumpSpec: KeyframesSpec<Float> = keyframes {
    durationMillis = 500
    0f at 0
    1f at 55
    0f at 300
    0f at 500
}

/** Fades the groove in and out when playback starts and stops. */
private val GrooveSpec = spring<Float>(dampingRatio = 0.9f, stiffness = 400f)

// Hoisted specs and lookup tables. The body and draw lambda run every animation frame while the
// orb idles, so nothing here may be rebuilt per frame.
private val PhaseSpec = infiniteRepeatable<Float>(tween(6000, easing = LinearEasing), RepeatMode.Restart)
private val FastSpec = infiniteRepeatable<Float>(tween(900, easing = LinearEasing), RepeatMode.Restart)
private val BreathSpec = infiniteRepeatable<Float>(tween(4200, easing = LinearEasing), RepeatMode.Restart)
private val BlinkLRepeat = infiniteRepeatable<Float>(blinkSpec(leadMs = 0), RepeatMode.Restart)
private val BlinkRRepeat = infiniteRepeatable<Float>(blinkSpec(leadMs = 60), RepeatMode.Restart)
private val ThumpRepeat = infiniteRepeatable<Float>(ThumpSpec, RepeatMode.Restart)
private val GazeSpecPie = spring<Float>(dampingRatio = 1f, stiffness = 4000f)
private val GazeSpecSoft = spring<Float>(dampingRatio = 0.65f, stiffness = 900f)
private val EyeOpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)
private val BrowSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val Signs = floatArrayOf(-1f, 1f)
private val ShadowColors = listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent)
private val BodyColors = listOf(Color.White, Color(0xFFF5F5F8), Color(0xFFE4E5EB))
