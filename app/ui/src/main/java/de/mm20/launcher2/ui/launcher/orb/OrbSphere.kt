package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.animation.core.snap
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.OrbShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The assistant sphere: a matte white body with two small black dot eyes. The body can take
 * several rounded "blob" shapes (see [OrbShape]); the face and all motion stay the same.
 *
 * Monochrome apart from a faint blush — voice states are expressed purely through body language:
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
 * While media plays ([playing]) the orb becomes a music visualizer: its edge ripples with the
 * live spectrum of whatever is playing ([audio]; a synthetic groove when that is unavailable),
 * while the body itself holds still; the body (filled or outline) wears the track's
 * [coverArt], rimmed in the art's [accent] colour.
 *
 * It also behaves like a small pet. It has a mouth that smiles, gapes, frowns and yawns. A
 * finger resting on it ([pressed]) squishes it like jelly; every tap ([pokeCount]) and every
 * return to the home screen ([greetCount]) gets a delighted perk with squinting eyes and a
 * blush. A finger anywhere else on screen ([attention], a -1..1 look vector) is watched
 * intently. Left alone, it gets up to things on its own: a hop, a wiggle, a curious head tilt,
 * a yawn.
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
    coverArt: ImageBitmap? = null,
    /** Identifies the playing track; a change while playing flips the orb to the new one. */
    trackKey: Any? = null,
    /** Which way the last track change went: 1 next (also when unknown), -1 previous. */
    skipDirection: Int = 1,
    accent: Color? = null,
    audio: OrbAudio? = null,
    pressed: Boolean = false,
    pokeCount: Int = 0,
    greetCount: Int = 0,
    attention: () -> Offset? = { null },
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
    val focus = if (state == OrbState.Idle) attention() else null
    val gazeTarget = when (state) {
        OrbState.Idle -> focus ?: Offset(
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
    val gazeSpec: AnimationSpec<Float> =
        if (state == OrbState.Pie || focus != null) GazeSpecPie else GazeSpecSoft
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

    // Mouth: how far the corners turn up (-1 frown .. 1 smile) and how far it hangs open.
    val smile by animateFloatAsState(
        targetValue = when (state) {
            OrbState.Idle -> 0.5f
            OrbState.Pie -> 0.75f
            OrbState.Listening -> 0.3f
            OrbState.Thinking -> 0f
            OrbState.Success -> 1f
            OrbState.Error -> -0.8f
            OrbState.Sleep -> 0f
        },
        animationSpec = MouthSpec,
        label = "smile",
    )
    val mouthOpen by animateFloatAsState(
        targetValue = when {
            state == OrbState.Listening -> 0.5f
            // Watching a finger somewhere on screen: a small "ooh".
            focus != null -> 0.3f
            else -> 0f
        },
        animationSpec = MouthSpec,
        label = "mouthOpen",
    )

    // A finger resting on the orb squishes it; the loose spring makes it wobble like jelly on
    // the way down and again when it is let go.
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = PressSpec,
        label = "press",
    )

    // Delight: squinting eyes, a wider smile and a blush, with a little perk up off the ground.
    // Fired by a tap and, held a bit longer, by coming back to the home screen.
    val joy = remember { Animatable(0f) }
    val perk = remember { Animatable(0f) }
    LaunchedEffect(pokeCount) {
        if (pokeCount == 0) return@LaunchedEffect
        launch {
            perk.snapTo(-0.4f)
            perk.animateTo(0f, PerkSpec)
        }
        joy.snapTo(1f)
        delay(380)
        joy.animateTo(0f, tween(420))
    }
    LaunchedEffect(greetCount) {
        if (greetCount == 0) return@LaunchedEffect
        launch {
            perk.snapTo(-0.7f)
            perk.animateTo(0f, PerkSpec)
        }
        joy.snapTo(1f)
        delay(700)
        joy.animateTo(0f, tween(500))
    }

    // Left alone, the orb amuses itself: every few seconds it picks something to do. [act] runs
    // 0..1 across the antic and is only sampled while drawing.
    val act = remember { Animatable(0f) }
    var actKind by remember { mutableIntStateOf(ActNone) }
    val calm = state == OrbState.Idle && !pressed && !playing
    LaunchedEffect(calm) {
        actKind = ActNone
        act.snapTo(0f)
        if (!calm) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(3500, 8000))
            val kind = Random.nextInt(1, ActCount)
            actKind = kind
            act.animateTo(1f, tween(ActDurations[kind], easing = LinearEasing))
            actKind = ActNone
            act.snapTo(0f)
        }
    }

    val mouthPath = remember { Path() }

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

    val baseInk = if (fill == OrbFill.Outline) Color.White else Color(0xFF17181C)

    // Music visualizer. [vis] fades the reactive edge in while something plays and the orb is
    // idle or showing its menu; [levels] are the per-band edge heights, eased every frame
    // towards the live spectrum (or a synthetic groove) and only read while drawing.
    val vis by animateFloatAsState(
        targetValue = if (playing && (state == OrbState.Idle || state == OrbState.Pie)) 1f else 0f,
        animationSpec = GrooveSpec,
        label = "vis",
    )
    val levels = remember { FloatArray(OrbAudio.BANDS) }
    // The sea: [WaveAmp] how tall the travelling waves stand, and the phases of the three
    // waves that roll around the ring. Louder music and harder hits make them taller and faster.
    val sea = remember { FloatArray(SeaSize) }
    val frameTick = remember { mutableLongStateOf(0L) }
    val targets = remember { FloatArray(OrbAudio.BANDS) }
    val blurred = remember { FloatArray(OrbAudio.BANDS) }
    LaunchedEffect(playing, audio) {
        var start = 0L
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (start == 0L) start = now
            val dt = if (last == 0L) 1f / 60f else ((now - last) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            last = now
            val t = (now - start) / 1_000_000_000f
            val live = playing && audio?.live == true
            for (i in targets.indices) {
                targets[i] = when {
                    !playing -> 0f
                    live -> audio!!.levels[i]
                    else -> {
                        // No spectrum: a groove whose bass rides the thump and a slow wave rolls
                        // around the ring.
                        val low = 1f - i / targets.size.toFloat()
                        0.12f + 0.55f * thump * low * low +
                            0.22f * (0.5f + 0.5f * sin(t * 2.4f - i * 0.45f))
                    }
                }
            }
            // Neighbouring bands pull towards each other a little, so the edge keeps its shape
            // as one surface rather than separate columns.
            for (i in targets.indices) {
                val l = targets[(i - 1).coerceAtLeast(0)]
                val r = targets[(i + 1).coerceAtMost(targets.size - 1)]
                blurred[i] = 0.2f * l + 0.6f * targets[i] + 0.2f * r
            }
            blurred.copyInto(targets)
            // Frame-rate independent easing: rise fast, fall gently.
            val rise = 1f - exp(-dt * 28f)
            val fall = 1f - exp(-dt * 7f)
            var any = false
            var energy = 0f
            for (i in levels.indices) {
                val target = targets[i]
                val cur = levels[i]
                levels[i] = cur + (target - cur) * if (target > cur) rise else fall
                if (levels[i] > 0.002f) any = true
                energy += levels[i]
            }
            energy /= levels.size
            // Turbulence: overall loudness plus how far it just jumped above its recent level.
            val surge = (energy - sea[SeaSlow]).coerceAtLeast(0f)
            sea[SeaSlow] += (energy - sea[SeaSlow]) * (1f - exp(-dt * 2f))
            val storm = (energy * 1.3f + surge * 4f).coerceIn(0f, 1.3f)
            val ampTarget = if (playing) storm * 0.55f else 0f
            sea[WaveAmp] += (ampTarget - sea[WaveAmp]) * (1f - exp(-dt * if (ampTarget > sea[WaveAmp]) 14f else 3f))
            if (sea[WaveAmp] > 0.002f) any = true
            sea[Kick] *= exp(-dt * 4.5f)
            if (sea[Kick] > 0.01f) any = true
            // Calm music rolls slowly; a storm whips the waves round.
            val speed = 0.6f + 3.4f * storm
            sea[Phase1] += dt * 1.7f * speed
            sea[Phase2] += dt * 2.6f * speed
            sea[Phase3] += dt * 4.1f * speed
            frameTick.longValue = now
            if (!playing && !any) break
        }
    }
    val waveMeasure = remember { PathMeasure() }
    val artReveal = remember { Path() }
    val holePath = remember { Path() }
    val artClip = remember { Path() }

    // Cover art fades in over a filled body; the last art is kept so it can fade back out.
    var shownArt by remember { mutableStateOf<ImageBitmap?>(null) }
    if (coverArt != null && coverArt !== shownArt) shownArt = coverArt
    // Cover art grows out of the centre while the face is sucked into it; when it stops, the
    // face grows back out and pushes the art off the edge. [artIncoming] says which way.
    val artIncoming = coverArt != null && state != OrbState.Sleep
    // Track changes flip the orb over to the new track's art (or face), so the reveal
    // animation is skipped while a flip runs.
    val flip = remember { Animatable(1f) }
    var flipDir by remember { mutableIntStateOf(1) }
    var flipOldArt by remember { mutableStateOf<ImageBitmap?>(null) }
    val lastTrack = remember { arrayOfNulls<Any>(1) }
    val flipping = flip.value < 1f
    val artShow by animateFloatAsState(
        targetValue = if (artIncoming) 1f else 0f,
        animationSpec = if (flipping) snap() else tween(ArtRevealMs, easing = FastOutSlowInEasing),
        label = "art",
    )
    LaunchedEffect(trackKey) {
        val old = lastTrack[0]
        lastTrack[0] = trackKey
        if (old != null && trackKey != null && old != trackKey && playing && state != OrbState.Sleep) {
            // What the orb shows right now is the side that turns away.
            flipOldArt = if (artShow > 0.5f) shownArt else null
            flipDir = if (skipDirection < 0) -1 else 1
            flip.snapTo(0f)
            flip.animateTo(1f, tween(FlipMs, easing = FastOutSlowInEasing))
        }
    }
    // The art hitting the orb's edge knocks the visualizer dots outward.
    LaunchedEffect(artIncoming) {
        if (artIncoming && !flipping) {
            delay(ArtHitsEdgeMs)
            if (playing) sea[Kick] = 1f
        }
    }
    val flipCamera = remember { android.graphics.Camera() }
    val flipMatrix = remember { android.graphics.Matrix() }
    val rim by animateColorAsState(
        targetValue = accent ?: if (playing) Color.White else Color(0xFF8E9099),
        animationSpec = tween(600),
        label = "rim",
    )

    Canvas(modifier) {
        val c = if (orbCenter == Offset.Zero) center else orbCenter
        val baseR = if (orbRadius > 0f) orbRadius else size.minDimension * 0.28f

        val isSleep = state == OrbState.Sleep

        // Redraw every visualizer frame. The body itself keeps still to music; only the dots move.
        frameTick.longValue
        // While music plays the body settles down so the dot ring carries all the motion.
        val still = 1f - 0.85f * groove
        val waveAmt = if (isSleep) 0f else vis
        val ink = lerp(baseInk, Color.White, artShow)

        // Breathing and the success pop scale the whole body. A sleeping orb stays at rest.
        val breathe = 1f + 0.04f * sin(breath) * still
        val r = if (isSleep) baseR * 0.97f else baseR * breathe * pop.value

        // Body language: positional drift, tilt and squash-stretch per state.
        var mx = 0f
        var my = 0f
        var tiltDeg = 0f
        var sx = 1f
        var sy = 1f
        when (state) {
            // Lazy float around the rest position, leaning with the drift; held almost still
            // while music plays.
            OrbState.Idle -> {
                mx = sin(phase * 0.7f) * r * 0.05f * still
                my = cos(phase * 0.5f) * r * 0.04f * still
                tiltDeg = sin(phase * 0.5f + 1.3f) * 3.5f * still
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
        // Idle antics, layered on the drift. [arc] rises and falls once over the antic.
        val actT = act.value
        val arc = sin(actT * PI.toFloat())
        var eyeScale = 1f
        var yawn = 0f
        when (actKind) {
            // Two little bounces, the second smaller.
            ActHop -> {
                val bounce = abs(sin(actT * 2f * PI.toFloat())) * (1f - 0.5f * actT)
                my -= bounce * r * 0.16f
                sy *= 1f + 0.06f * bounce
                sx *= 1f - 0.045f * bounce
            }
            // A happy side-to-side shimmy.
            ActWiggle -> {
                tiltDeg += sin(actT * 6f * PI.toFloat()) * 9f * arc
                mx += sin(actT * 6f * PI.toFloat()) * r * 0.03f * arc
            }
            // Head cocked to one side, eyes wide: "hm?"
            ActCurious -> {
                tiltDeg += arc * 13f
                mx += arc * r * 0.03f
                eyeScale = 1f + 0.25f * arc
            }
            // A long stretch with the eyes squeezed shut and the mouth wide open.
            ActYawn -> {
                yawn = arc
                eyeScale = 1f - 0.9f * arc
                sy *= 1f + 0.05f * arc
                sx *= 1f - 0.03f * arc
            }
        }
        // The body leans a touch into wherever it is looking.
        if (state == OrbState.Idle) mx += gazeX * r * 0.03f
        // Squished under a finger, eyes a little wider at the attention.
        sy *= 1f - 0.08f * press
        sx *= 1f + 0.06f * press
        eyeScale *= 1f + 0.12f * press
        // Tap / greeting perk: a small stretch up off the ground.
        my += perk.value * r * 0.22f
        sy *= 1f - 0.16f * perk.value
        sx *= 1f + 0.12f * perk.value
        val glee = joy.value

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
                // The reactive edge: columns of dots around the body that grow outward and
                // fall back with each spectrum band.
                if (waveAmt > 0.01f) {
                    drawDotRing(
                        measure = waveMeasure,
                        bodyPath = bodyPath,
                        body = body,
                        r = r,
                        levels = levels,
                        sea = sea,
                        amount = waveAmt,
                        color = rim,
                    )
                }
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
                            color = rim,
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
                // Cover art (filled and outline alike), centre-cropped into the body, with a
                // soft scrim so the face stays readable on any artwork.
                // A flip turns the art and face over horizontally: the old side for the first
                // half, the new side after. Next turns right-to-left, previous left-to-right.
                val f = flip.value
                val oldSide = f < 0.5f
                val showSide = if (oldSide) (if (flipOldArt != null) 1f else 0f) else artShow
                val flipNative = drawContext.canvas.nativeCanvas
                if (f < 1f) {
                    val turn = (if (oldSide) f * 180f else f * 180f - 180f) * flipDir
                    flipCamera.save()
                    flipCamera.setLocation(0f, 0f, -16f)
                    flipCamera.rotateY(turn)
                    flipCamera.getMatrix(flipMatrix)
                    flipCamera.restore()
                    flipMatrix.preTranslate(-body.x, -body.y)
                    flipMatrix.postTranslate(body.x, body.y)
                    flipNative.save()
                    flipNative.concat(flipMatrix)
                }
                val art = if (oldSide && f < 1f) flipOldArt else shownArt
                if (!isSleep && art != null && showSide > 0.01f) {
                    // Arriving: a disc growing from the centre. Leaving: a ring whose hole grows
                    // from the centre until it is pushed off the edge.
                    val reach = r * 1.45f
                    artReveal.rewind()
                    if (showSide >= 0.999f) {
                        artReveal.addRect(Rect(body, reach * 2f))
                    } else if (artIncoming) {
                        artReveal.addOval(Rect(body, showSide * reach))
                    } else {
                        artReveal.addRect(Rect(body, reach * 2f))
                        holePath.rewind()
                        holePath.addOval(Rect(body, (1f - showSide) * reach))
                        artReveal.op(artReveal, holePath, PathOperation.Difference)
                    }
                    artClip.rewind()
                    artClip.op(bodyPath, artReveal, PathOperation.Intersect)
                    clipPath(artClip) {
                        val b = bodyPath.getBounds()
                        val side = maxOf(b.width, b.height)
                        val srcSide = min(art.width, art.height)
                        drawImage(
                            image = art,
                            srcOffset = IntOffset(
                                (art.width - srcSide) / 2,
                                (art.height - srcSide) / 2,
                            ),
                            srcSize = IntSize(srcSide, srcSide),
                            dstOffset = IntOffset(
                                (b.center.x - side / 2f).toInt(),
                                (b.center.y - side / 2f).toInt(),
                            ),
                            dstSize = IntSize(side.toInt() + 1, side.toInt() + 1),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = ArtScrim,
                                center = body,
                                radius = r * 1.3f,
                            ),
                            radius = r * 1.6f,
                            center = body,
                        )
                    }
                    drawPath(
                        path = bodyPath,
                        color = rim,
                        alpha = showSide,
                        style = Stroke(width = r * 0.035f),
                    )
                }

                // The face is sucked into the centre as the art arrives, and grows back out of it
                // when the art leaves.
                val faceScale = 1f - showSide
                if (!isSleep && faceScale > 0.01f) {
                    val faceLayer = faceScale < 0.99f
                    if (faceLayer) {
                        drawContext.canvas.save()
                        drawContext.canvas.translate(body.x, body.y)
                        drawContext.canvas.scale(faceScale, faceScale)
                        drawContext.canvas.translate(-body.x, -body.y)
                    }
                    val placement = eyePlacement(shape)
                    val eyeDx = r * placement[0]
                    val eyeY = body.y + r * placement[1]
                    val er = r * 0.11f
                    val gx = gazeX * r * 0.38f
                    val gy = gazeY * r * 0.38f

                    // Blush: only on the filled body, where there is a cheek to colour.
                    val blush = maxOf(glee, press * 0.6f, if (state == OrbState.Success) 1f else 0f)
                    if (fill != OrbFill.Outline && blush > 0.02f) {
                        val bw = r * 0.2f
                        val bh = r * 0.11f
                        for (s in Signs) {
                            drawOval(
                                color = BlushColor,
                                topLeft = Offset(
                                    body.x + s * eyeDx * 1.3f + gx * 0.6f - bw / 2f,
                                    eyeY + r * 0.2f + gy * 0.6f - bh / 2f,
                                ),
                                size = Size(bw, bh),
                                alpha = 0.45f * blush,
                            )
                        }
                    }

                    when {
                        state == OrbState.Success || glee > 0.35f -> {
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
                                val open = (if (s < 0f) openL else openR) * eyeScale
                                val ec = Offset(body.x + s * eyeDx + gx, eyeY + gy)
                                drawOval(
                                    color = ink,
                                    topLeft = Offset(ec.x - er, ec.y - er * open),
                                    size = Size(er * 2f, er * 2f * open),
                                )
                                if (fill != OrbFill.Outline && artShow < 0.5f && open > 0.55f) {
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
                        val bcy = eyeY - er * 2.8f - (browRaise + 0.03f * glee + 0.025f * yawn) * r
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

                    // Mouth. It rides along with the gaze like the rest of the face.
                    val mcx = body.x + gx * 0.7f
                    val mcy = eyeY + r * placement[2] + gy * 0.7f
                    val gape = maxOf(mouthOpen, yawn)
                    if (gape > 0.12f) {
                        // Open: a small "o" that grows into a yawn.
                        val rx = r * (0.06f + 0.05f * gape)
                        val ry = r * 0.15f * gape
                        drawOval(
                            color = ink,
                            topLeft = Offset(mcx - rx, mcy - ry * 0.6f),
                            size = Size(rx * 2f, ry * 2f),
                        )
                    } else {
                        // Closed: one stroke whose middle sags into a smile or arches into a
                        // frown. An error makes it tremble.
                        var curve = (smile + 0.5f * glee).coerceIn(-1f, 1f)
                        if (state == OrbState.Error) curve += 0.15f * sin(fast * 5f)
                        val half = r * (0.14f + 0.06f * abs(curve))
                        val sag = r * 0.13f * curve
                        mouthPath.rewind()
                        mouthPath.moveTo(mcx - half, mcy - sag * 0.35f)
                        mouthPath.quadraticTo(mcx, mcy + sag, mcx + half, mcy - sag * 0.35f)
                        drawPath(
                            path = mouthPath,
                            color = ink,
                            style = Stroke(width = r * 0.05f, cap = StrokeCap.Round),
                        )
                    }
                    if (faceLayer) drawContext.canvas.restore()
                }
                if (f < 1f) flipNative.restore()
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

/**
 * Draws the music visualizer: [DotColumns] columns of dots standing on the body outline, each
 * lit outward as far as its spectrum band reaches (bass at the bottom, highs at the top,
 * mirrored left and right). The innermost dot always glows faintly so the ring reads at rest;
 * the tip dot fades in and out so the columns grow smoothly. Odd columns sit half a step out
 * for a sparkling, staggered look. Allocation-free.
 */
private fun DrawScope.drawDotRing(
    measure: PathMeasure,
    bodyPath: Path,
    body: Offset,
    r: Float,
    levels: FloatArray,
    sea: FloatArray,
    amount: Float,
    color: Color,
) {
    measure.setPath(bodyPath, true)
    val len = measure.length
    if (len <= 0f) return
    val last = levels.size - 1
    val gap = r * 0.1f
    val step = r * 0.068f
    val dot = r * 0.024f
    for (i in 0 until DotColumns) {
        val d = len * i / DotColumns
        val p = measure.getPosition(d)
        val tan = measure.getTangent(d)
        var nx = tan.y
        var ny = -tan.x
        val rx = p.x - body.x
        val ry = p.y - body.y
        if (rx * nx + ry * ny < 0f) {
            nx = -nx
            ny = -ny
        }
        // 0 at the top of the body, 1 at the bottom, the same on both sides; bass at the bottom.
        val u = abs(atan2(rx, -ry)) / PI.toFloat()
        val f = u * last
        val lo = f.toInt().coerceIn(0, last)
        val hi = (lo + 1).coerceAtMost(last)
        val a = levels[last - lo]
        val b = levels[last - hi]
        val x = f - lo
        val band = a + (b - a) * x * x * (3f - 2f * x)
        // Three waves travelling around the whole ring (whole numbers of crests so they meet
        // seamlessly), crossing each other like a choppy sea.
        val theta = atan2(rx, -ry)
        val crest = 0.5f + 0.5f * (
            0.55f * sin(3f * theta - sea[Phase1]) +
                0.3f * sin(5f * theta + sea[Phase2]) +
                0.15f * sin(9f * theta - sea[Phase3])
            )
        val level = (band * 0.65f + sea[WaveAmp] * crest * 1.2f + sea[Kick] * 0.8f).coerceIn(0f, 1f)
        val lit = level * DotsPerColumn
        val stagger = if (i % 2 == 1) step * 0.5f else 0f
        // Loud columns also swell their dots, so peaks read as bursts rather than lines.
        val swell = 0.8f + 0.5f * level
        for (k in 0 until DotsPerColumn) {
            val on = if (k == 0) maxOf(0.3f, lit.coerceAtMost(1f)) else (lit - k).coerceIn(0f, 1f)
            if (on <= 0.01f) break
            val off = gap + stagger + k * step
            drawCircle(
                color = color,
                radius = dot * swell * (1f - 0.04f * k),
                center = Offset(p.x + nx * off, p.y + ny * off),
                alpha = on * amount,
            )
        }
    }
}

private const val DotColumns = 84
private const val DotsPerColumn = 13

// Slots of the visualizer's sea state.
private const val WaveAmp = 0
private const val Phase1 = 1
private const val Phase2 = 2
private const val Phase3 = 3
private const val SeaSlow = 4
private const val Kick = 5
private const val SeaSize = 6

private const val ArtRevealMs = 1100
/** About when the growing art reaches the body's edge, with [ArtRevealMs] and its easing. */
private const val ArtHitsEdgeMs = 460L
private const val FlipMs = 700

/** One saccade step of the 6s slow clock. */
private val SaccadeStep = (PI / 6).toFloat()

/** Deterministic pseudo-random value in -1..1, so saccades are reproducible. */
private fun hash1d(x: Float): Float {
    val v = sin(x * 12.9898f) * 43758.5453f
    return (v - floor(v)) * 2f - 1f
}

/**
 * The eye position for a shape, as factors of the body radius: how far each eye sits from the
 * middle, how far the eye line sits below the centre, and how far the mouth sits below that. Most shapes share the default
 * face; a few (like the point-up triangle) need the eyes lowered into their wider base so they
 * stay inside the body.
 */
private val EyeDefault = floatArrayOf(0.42f, -0.05f, 0.4f)
private val EyeTriangle = floatArrayOf(0.3f, 0.22f, 0.27f)

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
private val MouthSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)
private val PressSpec = spring<Float>(dampingRatio = 0.35f, stiffness = 700f)
private val PerkSpec = spring<Float>(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow)
private val BlushColor = Color(0xFFFF8FA3)

// Idle antics, and how long each one runs.
private const val ActNone = 0
private const val ActHop = 1
private const val ActWiggle = 2
private const val ActCurious = 3
private const val ActYawn = 4
private const val ActCount = 5
private val ActDurations = intArrayOf(0, 700, 900, 1500, 2200)
private val Signs = floatArrayOf(-1f, 1f)
private val ShadowColors = listOf(Color.Black.copy(alpha = 0.16f), Color.Transparent)
private val ArtScrim = listOf(Color.Black.copy(alpha = 0.32f), Color.Black.copy(alpha = 0.08f))
private val BodyColors = listOf(Color.White, Color(0xFFF5F5F8), Color(0xFFE4E5EB))
