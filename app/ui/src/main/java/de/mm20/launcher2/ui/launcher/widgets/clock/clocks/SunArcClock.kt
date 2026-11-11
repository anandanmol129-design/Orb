package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import palettes.TonalPalette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Fixed day window: sunrise at 06:00, sunset at 18:00 (in minutes of the day).
 */
private const val SunArcSunriseMinute = 6 * 60
private const val SunArcHalfDayMinutes = 12 * 60

/**
 * Star positions around the arc, used at night:
 * (position along arc 0..1, distance factor relative to arc radius, size factor, twinkle phase).
 */
private val SunArcStars = listOf(
    floatArrayOf(0.10f, 0.62f, 1.0f, 0.0f),
    floatArrayOf(0.24f, 1.16f, 0.8f, 1.7f),
    floatArrayOf(0.38f, 0.74f, 1.2f, 3.1f),
    floatArrayOf(0.55f, 1.20f, 0.9f, 4.4f),
    floatArrayOf(0.66f, 0.58f, 0.7f, 2.3f),
    floatArrayOf(0.80f, 1.12f, 1.1f, 5.2f),
    floatArrayOf(0.92f, 0.70f, 0.8f, 0.9f),
)

private val SunArcSunriseColor = Color(0xFFFF8A3D)
private val SunArcNoonColor = Color(0xFFFFF4C8)
private val SunArcMoonColor = Color(0xFFD4E3FF)

@Composable
fun SunArcClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val parsed = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val hour = parsed.hour
    val minute = parsed.minute
    val second = parsed.second

    val minuteOfDay = hour * 60 + minute + second / 60f
    val isDay = hour in 6..17
    val targetProgress = (if (isDay) {
        (minuteOfDay - SunArcSunriseMinute) / SunArcHalfDayMinutes
    } else {
        // minutes since 18:00, wrapping past midnight
        ((minuteOfDay - (SunArcSunriseMinute + SunArcHalfDayMinutes) + 24 * 60) % (24 * 60)) /
                SunArcHalfDayMinutes
    }).coerceIn(0f, 1f)

    val progress = remember { Animatable(targetProgress) }
    LaunchedEffect(targetProgress) {
        if (abs(progress.value - targetProgress) > 0.5f) {
            // day/night switch: jump back to the left horizon instead of sweeping backwards
            progress.snapTo(targetProgress)
        } else {
            progress.animateTo(
                targetProgress,
                animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing)
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "sunArcTransition")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunArcPulse"
    )
    val rayRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 60_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sunArcRays"
    )
    val twinkle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sunArcTwinkle"
    )

    val contentColor = LocalContentColor.current

    // Text / accent colour: same approach as DigitalClock2 / SegmentClock
    val accentColor = if (useThemeColor) {
        if (!darkColors) {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.primaryContainer
        } else {
            if (LocalDarkTheme.current) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.primary
        }
    } else {
        contentColor
    }

    val bodyColor: Color = if (useThemeColor) {
        if (isDay) {
            Color(
                TonalPalette.fromInt(MaterialTheme.colorScheme.primary.toArgb())
                    .tone(if (darkColors) 50 else 80)
            )
        } else {
            Color(
                TonalPalette.fromInt(MaterialTheme.colorScheme.tertiary.toArgb())
                    .tone(if (darkColors) 40 else 90)
            )
        }
    } else {
        if (isDay) {
            // 0 at the horizon, 1 at midday
            val elevation = sin(progress.value * PI).toFloat().coerceIn(0f, 1f)
            lerp(SunArcSunriseColor, SunArcNoonColor, elevation)
        } else {
            SunArcMoonColor
        }
    }

    val arcColor = contentColor
    val travelledColor = if (useThemeColor) accentColor else contentColor

    val canvasModifier = if (compact) {
        Modifier.size(width = 64.dp, height = 36.dp)
    } else {
        Modifier
            .padding(top = 8.dp)
            .size(width = 224.dp, height = 116.dp)
    }

    val timeText = remember(hour, minute, second, showSeconds, twentyFourHours, compact) {
        val h = if (twentyFourHours) hour else ((hour + 11) % 12) + 1
        buildString {
            append(h.toString().padStart(2, '0'))
            append(':')
            append(minute.toString().padStart(2, '0'))
            if (showSeconds && !compact) {
                append(':')
                append(second.toString().padStart(2, '0'))
            }
        }
    }

    val textStyle = (if (compact) MaterialTheme.typography.displaySmall
    else MaterialTheme.typography.displayMedium).copy(
        fontWeight = FontWeight.Normal,
        fontFeatureSettings = "tnum",
        color = accentColor,
    )

    val canvas: @Composable () -> Unit = {
        Canvas(modifier = canvasModifier) {
            SunArcDrawScene(
                progress = progress.value,
                isDay = isDay,
                compact = compact,
                arcColor = arcColor,
                travelledColor = travelledColor,
                bodyColor = bodyColor,
                outlineColor = contentColor,
                starColor = contentColor,
                pulse = pulse,
                rayRotation = rayRotation,
                twinkle = twinkle,
            )
        }
    }

    if (compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            canvas()
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = timeText, style = textStyle)
        }
    } else {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            canvas()
            Text(
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                text = timeText,
                style = textStyle,
            )
        }
    }
}

private fun DrawScope.SunArcDrawScene(
    progress: Float,
    isDay: Boolean,
    compact: Boolean,
    arcColor: Color,
    travelledColor: Color,
    bodyColor: Color,
    outlineColor: Color,
    starColor: Color,
    pulse: Float,
    rayRotation: Float,
    twinkle: Float,
) {
    val bottomPad = if (compact) 2.dp.toPx() else 4.dp.toPx()
    val horizonY = size.height - bottomPad
    val cx = size.width / 2f
    val radius = min(size.width * 0.42f, (size.height - bottomPad) * 0.80f)
    val bodyRadius = radius * (if (compact) 0.15f else 0.11f)

    val strokeWidth = if (compact) 1.dp.toPx() else 1.5.dp.toPx()
    val travelledWidth = if (compact) 1.5.dp.toPx() else 2.dp.toPx()
    val dash = if (compact) 2.dp.toPx() else 4.dp.toPx()

    val arcTopLeft = Offset(cx - radius, horizonY - radius)
    val arcSize = Size(radius * 2f, radius * 2f)

    // Horizon line
    val horizonOverhang = if (compact) 4.dp.toPx() else 14.dp.toPx()
    drawLine(
        color = arcColor.copy(alpha = 0.5f),
        start = Offset(cx - radius - horizonOverhang, horizonY),
        end = Offset(cx + radius + horizonOverhang, horizonY),
        strokeWidth = 1.dp.toPx(),
        cap = StrokeCap.Round,
    )

    // Full dashed arc
    drawArc(
        color = arcColor.copy(alpha = 0.4f),
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = arcTopLeft,
        size = arcSize,
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        ),
    )

    // Travelled part, solid
    if (progress > 0f) {
        drawArc(
            color = travelledColor.copy(alpha = 0.85f),
            startAngle = 180f,
            sweepAngle = 180f * progress,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = travelledWidth, cap = StrokeCap.Round),
        )
    }

    // Stars (night only)
    if (!isDay) {
        for (star in SunArcStars) {
            val a = (PI + star[0] * PI).toFloat()
            val d = radius * star[1]
            val pos = Offset(cx + d * cos(a), horizonY + d * sin(a))
            if (pos.y > horizonY - 2.dp.toPx() || pos.y < 0f) continue
            val alpha = 0.25f + 0.65f * (0.5f + 0.5f * sin(twinkle + star[3]))
            val r = (if (compact) 0.6.dp.toPx() else 1.2.dp.toPx()) * star[2]
            drawCircle(color = starColor.copy(alpha = alpha), radius = r, center = pos)
        }
    }

    // Body position on the arc
    val angle = (PI + progress * PI).toFloat()
    val bodyCenter = Offset(cx + radius * cos(angle), horizonY + radius * sin(angle))

    // Glow
    val glowRadius = bodyRadius * (if (isDay) 3.2f else 2.4f) * pulse
    val glowAlpha = if (isDay) 0.55f else 0.3f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                bodyColor.copy(alpha = glowAlpha),
                bodyColor.copy(alpha = glowAlpha * 0.35f),
                Color.Transparent,
            ),
            center = bodyCenter,
            radius = glowRadius,
        ),
        radius = glowRadius,
        center = bodyCenter,
    )

    if (isDay) {
        // Slowly rotating short rays
        val rayCount = 8
        val rayInner = bodyRadius * 1.35f
        val rayOuter = bodyRadius * 1.85f
        val rayWidth = if (compact) 1.dp.toPx() else 1.5.dp.toPx()
        rotate(degrees = rayRotation, pivot = bodyCenter) {
            for (i in 0 until rayCount) {
                val a = (i * 2 * PI / rayCount).toFloat()
                drawLine(
                    color = bodyColor.copy(alpha = 0.9f),
                    start = Offset(
                        bodyCenter.x + rayInner * cos(a),
                        bodyCenter.y + rayInner * sin(a)
                    ),
                    end = Offset(
                        bodyCenter.x + rayOuter * cos(a),
                        bodyCenter.y + rayOuter * sin(a)
                    ),
                    strokeWidth = rayWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
        drawCircle(color = bodyColor, radius = bodyRadius, center = bodyCenter)
        // Thin outline in the content colour keeps the disc legible on any wallpaper
        drawCircle(
            color = outlineColor.copy(alpha = 0.35f),
            radius = bodyRadius,
            center = bodyCenter,
            style = Stroke(width = 1.dp.toPx()),
        )
    } else {
        // Crescent moon: disc minus an offset disc
        val full = Path().apply {
            addOval(Rect(center = bodyCenter, radius = bodyRadius))
        }
        val cut = Path().apply {
            addOval(
                Rect(
                    center = bodyCenter + Offset(bodyRadius * 0.55f, -bodyRadius * 0.3f),
                    radius = bodyRadius * 0.9f
                )
            )
        }
        val crescent = Path().apply {
            op(full, cut, PathOperation.Difference)
        }
        drawPath(path = crescent, color = bodyColor)
        drawPath(
            path = crescent,
            color = outlineColor.copy(alpha = 0.35f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
