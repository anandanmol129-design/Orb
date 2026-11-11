package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Maximum swing angle, in degrees, either side of vertical. */
private const val PendulumAmplitudeDeg = 25f
private const val PendulumAmplitudeRad = PendulumAmplitudeDeg * (PI / 180.0)

/** Full period (there and back) in ms: one half-swing per second, like a seconds pendulum. */
private const val PendulumPeriodMs = 2000L

/** Number of ghost bobs in the motion trail, and the time step between them. */
private const val PendulumTrailCount = 5
private const val PendulumTrailStepMs = 30L

/**
 * Pendulum angle (radians, 0 = straight down, positive = towards +x) at the given wall-clock time.
 * Simple harmonic motion phased so that the bob is at an extreme exactly on every whole second.
 */
private fun pendulumAngleAt(epochMs: Long): Float {
    val phase = epochMs.mod(PendulumPeriodMs).toDouble() / (PendulumPeriodMs / 2).toDouble() * PI
    return (PendulumAmplitudeRad * cos(phase)).toFloat()
}

@Composable
fun PendulumClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val verticalLayout = !compact

    val color = if (useThemeColor) {
        if (!darkColors) {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.primary
        }
    } else {
        LocalContentColor.current
    }

    // Real wall-clock millis, updated every frame. Only read inside the draw lambda, so
    // per-frame updates invalidate the Canvas draw phase only, not composition.
    val nowState = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            nowState.longValue = System.currentTimeMillis()
        }
    }

    if (verticalLayout) {
        Column(
            modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PendulumDigits(
                time = time,
                compact = false,
                showSeconds = showSeconds,
                twentyFourHours = twentyFourHours,
                color = color,
            )
            PendulumCanvas(
                nowState = nowState,
                color = color,
                compact = false,
                width = 112.dp,
                height = 92.dp,
            )
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PendulumDigits(
                time = time,
                compact = true,
                showSeconds = false,
                twentyFourHours = twentyFourHours,
                color = color,
            )
            Spacer(modifier = Modifier.width(6.dp))
            PendulumCanvas(
                nowState = nowState,
                color = color,
                compact = true,
                width = 36.dp,
                height = 48.dp,
            )
        }
    }
}

@Composable
private fun PendulumDigits(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    color: Color,
) {
    val parsed = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val hour = if (twentyFourHours) parsed.hour else ((parsed.hour + 11) % 12) + 1
    val hhmm = pendulumTwoDigits(hour) + ":" + pendulumTwoDigits(parsed.minute)

    val mainStyle = (if (compact) MaterialTheme.typography.displaySmall
    else MaterialTheme.typography.displayMedium).copy(
        fontWeight = FontWeight.Light,
        fontFeatureSettings = "tnum",
        color = color,
    )

    Row {
        Text(
            modifier = Modifier.alignByBaseline(),
            text = hhmm,
            style = mainStyle,
        )
        if (showSeconds) {
            Text(
                modifier = Modifier.alignByBaseline(),
                text = ":" + pendulumTwoDigits(parsed.second),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Light,
                    fontFeatureSettings = "tnum",
                    color = color.copy(alpha = color.alpha * 0.7f),
                ),
            )
        }
    }
}

private fun pendulumTwoDigits(value: Int): String =
    if (value < 10) "0$value" else value.toString()

@Composable
private fun PendulumCanvas(
    nowState: MutableLongState,
    color: Color,
    compact: Boolean,
    width: Dp,
    height: Dp,
) {
    val density = LocalDensity.current
    // Pre-built stroke for the swing-path arc so the draw path doesn't allocate it per frame.
    val arcStroke = remember(density, compact) {
        with(density) {
            Stroke(
                width = (if (compact) 1.dp else 1.5.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }
    }

    Canvas(modifier = Modifier.size(width = width, height = height)) {
        pendulumDraw(
            now = nowState.longValue,
            color = color,
            compact = compact,
            arcStroke = arcStroke,
        )
    }
}

private fun DrawScope.pendulumDraw(
    now: Long,
    color: Color,
    compact: Boolean,
    arcStroke: Stroke,
) {
    val pivotY = (if (compact) 3.dp else 6.dp).toPx()
    val bobRadius = (if (compact) 4.5.dp else 9.dp).toPx()
    val rodWidth = (if (compact) 1.dp else 2.dp).toPx()
    val pivotRadius = (if (compact) 2.dp else 3.5.dp).toPx()
    val mountHalf = (if (compact) 4.dp else 8.dp).toPx()
    val tickHalf = (if (compact) 2.5.dp else 5.dp).toPx()

    val pivotX = size.width / 2f
    val pivot = Offset(pivotX, pivotY)
    // Rod length: as long as fits vertically (bob included).
    val rodLength = size.height - pivotY - bobRadius - 1.dp.toPx()

    val faint = color.copy(alpha = color.alpha * 0.3f)

    // Swing-path arc (Compose arcs: 0deg = +x, clockwise, so straight down = 90deg).
    drawArc(
        color = faint,
        startAngle = 90f - PendulumAmplitudeDeg,
        sweepAngle = 2f * PendulumAmplitudeDeg,
        useCenter = false,
        topLeft = Offset(pivotX - rodLength, pivotY - rodLength),
        size = Size(rodLength * 2f, rodLength * 2f),
        style = arcStroke,
    )

    // Tick marks at the two extremes.
    val ampF = PendulumAmplitudeRad.toFloat()
    val tickSin = sin(ampF)
    val tickCos = cos(ampF)
    val tickColor = color.copy(alpha = color.alpha * 0.55f)
    for (side in 0..1) {
        val sx = if (side == 0) -tickSin else tickSin
        drawLine(
            color = tickColor,
            start = Offset(pivotX + sx * (rodLength - tickHalf), pivotY + tickCos * (rodLength - tickHalf)),
            end = Offset(pivotX + sx * (rodLength + tickHalf), pivotY + tickCos * (rodLength + tickHalf)),
            strokeWidth = rodWidth,
            cap = StrokeCap.Round,
        )
    }

    // Small mount bar the pivot hangs from.
    drawLine(
        color = faint,
        start = Offset(pivotX - mountHalf, pivotY),
        end = Offset(pivotX + mountHalf, pivotY),
        strokeWidth = rodWidth,
        cap = StrokeCap.Round,
    )

    // Motion trail: ghost bobs at earlier times, oldest (faintest) first.
    for (k in PendulumTrailCount downTo 1) {
        val a = pendulumAngleAt(now - k * PendulumTrailStepMs)
        val fade = 1f - k.toFloat() / (PendulumTrailCount + 1).toFloat()
        drawCircle(
            color = color.copy(alpha = color.alpha * 0.32f * fade),
            radius = bobRadius * (0.75f + 0.25f * fade),
            center = Offset(pivotX + rodLength * sin(a), pivotY + rodLength * cos(a)),
        )
    }

    // Rod and bob.
    val angle = pendulumAngleAt(now)
    val bob = Offset(pivotX + rodLength * sin(angle), pivotY + rodLength * cos(angle))
    drawLine(
        color = color,
        start = pivot,
        end = bob,
        strokeWidth = rodWidth,
        cap = StrokeCap.Round,
    )
    drawCircle(color = color, radius = bobRadius, center = bob)

    // Pivot.
    drawCircle(color = color, radius = pivotRadius, center = pivot)
}
