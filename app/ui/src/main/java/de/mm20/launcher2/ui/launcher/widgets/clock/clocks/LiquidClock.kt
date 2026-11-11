package de.mm20.launcher2.ui.launcher.widgets.clock.clocks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.mm20.launcher2.ui.locals.LocalDarkTheme
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/** Fill level (0..1 of the glyph ink height) at second 0 and at the end of the minute. */
private const val LiquidEmptyLevel = 0.06f
private const val LiquidFullLevel = 0.96f

/** How long the liquid takes to drain when the minute rolls over. */
private const val LiquidDrainMillis = 1400f

/** Wave clock wraps every 10 minutes; wave frequencies are chosen so this wrap is seamless. */
private const val LiquidWavePeriodMillis = 600_000L
private const val LiquidTwoPi = (2.0 * PI).toFloat()

/** Approximate cap height of digits relative to the font size. */
private const val LiquidCapHeightRatio = 0.72f

@Composable
fun LiquidClock(
    time: Long,
    compact: Boolean,
    showSeconds: Boolean,
    twentyFourHours: Boolean,
    useThemeColor: Boolean,
    darkColors: Boolean,
) {
    val verticalLayout = !compact

    val parsed = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())
    val hour = if (twentyFourHours) parsed.hour else (((parsed.hour + 11) % 12) + 1)
    val minute = parsed.minute
    val second = parsed.second

    val liquidColor = if (useThemeColor) {
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
    val contentColor = LocalContentColor.current

    val text = remember(hour, minute, twentyFourHours) {
        val h = if (twentyFourHours) hour.toString().padStart(2, '0') else hour.toString()
        "$h:${minute.toString().padStart(2, '0')}"
    }

    val baseStyle = MaterialTheme.typography.displayLarge
    val textStyle = remember(baseStyle, verticalLayout) {
        baseStyle.copy(
            fontSize = if (verticalLayout) 96.sp else 44.sp,
            lineHeight = if (verticalLayout) 96.sp else 44.sp,
            fontWeight = FontWeight.Black,
            fontFeatureSettings = "tnum",
        )
    }

    val textMeasurer = rememberTextMeasurer()
    val layout = remember(text, textStyle, textMeasurer) {
        textMeasurer.measure(AnnotatedString(text), style = textStyle, maxLines = 1, softWrap = false)
    }

    // Wall clock time, updated every frame. `time` only ticks once per (whole) second,
    // so the smooth fill level and the wave phase are derived from this instead.
    // It is only read inside the draw lambda, so updates only trigger a redraw.
    var frameWallMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis {
                frameWallMillis = System.currentTimeMillis()
            }
        }
    }

    val wavePath = remember { Path() }
    val surfacePath = remember { Path() }

    val density = LocalDensity.current
    val widthDp = with(density) { layout.size.width.toDp() }
    val heightDp = with(density) { layout.size.height.toDp() }

    Row(
        modifier = Modifier.padding(
            top = if (verticalLayout) 8.dp else 0.dp,
            bottom = if (verticalLayout) 8.dp else 0.dp,
        ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom,
    ) {
        Canvas(
            modifier = Modifier
                .size(widthDp, heightDp)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val now = frameWallMillis
            val w = size.width
            val fontPx = textStyle.fontSize.toPx()
            val inkBottom = layout.firstBaseline
            val inkTop = inkBottom - fontPx * LiquidCapHeightRatio
            val inkHeight = inkBottom - inkTop

            val level = liquidLevelAt(now)
            val surfaceY = inkBottom - inkHeight * level

            val t = (now % LiquidWavePeriodMillis) / 1000f
            // 0.5 Hz and 0.3 Hz: both complete an integer number of cycles in 600 s.
            val phase1 = t * LiquidTwoPi * 0.5f
            val phase2 = t * LiquidTwoPi * 0.3f
            val amp1 = inkHeight * 0.045f
            val amp2 = inkHeight * 0.03f
            val k1 = LiquidTwoPi / (fontPx * 1.6f)
            val k2 = LiquidTwoPi / (fontPx * 0.9f)

            val step = 3.dp.toPx()
            wavePath.reset()
            surfacePath.reset()
            // wavePath covers everything *above* the liquid surface; it is erased from the glyphs.
            wavePath.moveTo(0f, 0f)
            var x = 0f
            var first = true
            while (true) {
                val y = surfaceY +
                        amp1 * sin(x * k1 + phase1) +
                        amp2 * sin(x * k2 - phase2 + 1.3f)
                wavePath.lineTo(x, y)
                if (first) {
                    surfacePath.moveTo(x, y)
                    first = false
                } else {
                    surfacePath.lineTo(x, y)
                }
                if (x >= w) break
                x = (x + step).coerceAtMost(w)
            }
            wavePath.lineTo(w, 0f)
            wavePath.close()

            // 1. Glyph mask.
            // drawStyle must be explicit: the stroke from step 5 otherwise sticks to the shared text paint.
            drawText(layout, color = liquidColor, topLeft = Offset.Zero, drawStyle = Fill)
            // 2. Erase the glyphs above the liquid surface, leaving liquid-filled glyphs below it.
            drawPath(wavePath, color = Color.Black, blendMode = BlendMode.DstOut)
            // 3. Bright meniscus line on the liquid surface, only where liquid is.
            drawPath(
                surfacePath,
                color = contentColor.copy(alpha = 0.55f),
                style = Stroke(width = 1.5.dp.toPx()),
                blendMode = BlendMode.SrcAtop,
            )
            // 4. Empty "glass" behind the liquid.
            drawText(
                layout,
                color = contentColor,
                topLeft = Offset.Zero,
                alpha = 0.16f,
                drawStyle = Fill,
                blendMode = BlendMode.DstOver,
            )
            // 5. Glass outline on top for legibility of the empty part.
            drawText(
                layout,
                color = liquidColor,
                topLeft = Offset.Zero,
                alpha = 0.6f,
                drawStyle = Stroke(width = (if (verticalLayout) 1.5.dp else 1.dp).toPx()),
            )
        }

        if (showSeconds) {
            Text(
                modifier = Modifier.padding(
                    start = if (verticalLayout) 6.dp else 4.dp,
                    bottom = with(density) {
                        (layout.size.height - layout.firstBaseline).toDp()
                    },
                ),
                text = second.toString().padStart(2, '0'),
                style = (if (verticalLayout) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.labelMedium).copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    color = liquidColor,
                ),
            )
        }
    }
}

/**
 * Fill level for the given wall clock time: rises linearly through the minute and,
 * right after the minute rolls over, drains smoothly from full down to the new level.
 */
private fun liquidLevelAt(wallMillis: Long): Float {
    val msInMinute = (wallMillis % 60_000L).toFloat()
    val rising = LiquidEmptyLevel + (LiquidFullLevel - LiquidEmptyLevel) * (msInMinute / 60_000f)
    if (msInMinute >= LiquidDrainMillis) return rising
    val p = msInMinute / LiquidDrainMillis
    val inv = 1f - p
    val eased = 1f - inv * inv * inv
    return LiquidFullLevel + (rising - LiquidFullLevel) * eased
}
