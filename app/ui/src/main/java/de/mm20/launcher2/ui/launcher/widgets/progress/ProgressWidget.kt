package de.mm20.launcher2.ui.launcher.widgets.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.utils.formatPercent
import de.mm20.launcher2.widgets.ProgressWidget
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** How far through a period we are, and how many equal segments that period is drawn with. */
private data class Period(val fraction: Float, val segments: Int)

@Composable
fun ProgressWidget(widget: ProgressWidget) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = LocalDateTime.now()
                // Wake up just after the next minute starts.
                delay(60_000L - (System.currentTimeMillis() % 60_000L) + 50)
            }
        }
    }

    val config = widget.config
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (config.showDay) {
            ProgressRow(stringResource(R.string.progress_widget_day), dayProgress(now), colors.primary)
        }
        if (config.showWeek) {
            ProgressRow(stringResource(R.string.progress_widget_week), weekProgress(now), colors.secondary)
        }
        if (config.showMonth) {
            ProgressRow(stringResource(R.string.progress_widget_month), monthProgress(now), colors.tertiary)
        }
        if (config.showYear) {
            ProgressRow(stringResource(R.string.progress_widget_year), yearProgress(now), colors.primary)
        }
    }
}

@Composable
private fun ProgressRow(label: String, period: Period, color: Color) {
    val fraction by animateFloatAsState(
        period.fraction,
        animationSpec = spring(stiffness = 60f),
        label = "progress",
    )
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(14.dp)
        ) {
            val n = period.segments
            // Many segments need tighter gaps to stay readable.
            val gap = (if (n > 16) 1.5.dp else 3.dp).toPx()
            val segmentWidth = (size.width - gap * (n - 1)) / n
            val radius = CornerRadius(minOf(segmentWidth, size.height) / 2.5f)
            val position = fraction * n
            for (i in 0 until n) {
                val left = i * (segmentWidth + gap)
                val fill = (position - i).coerceIn(0f, 1f)
                drawRoundRect(
                    color = track,
                    topLeft = Offset(left, 0f),
                    size = Size(segmentWidth, size.height),
                    cornerRadius = radius,
                )
                if (fill > 0f) {
                    // The segment in progress fills from the bottom, like a level.
                    val h = size.height * fill
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left, size.height - h),
                        size = Size(segmentWidth, h),
                        cornerRadius = radius,
                    )
                }
            }
        }
        Text(
            text = formatPercent(period.fraction * 100f),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
            maxLines = 1,
        )
    }
}

private fun elapsed(start: LocalDateTime, now: LocalDateTime, end: LocalDateTime): Float {
    val total = ChronoUnit.SECONDS.between(start, end).coerceAtLeast(1)
    return (ChronoUnit.SECONDS.between(start, now).toFloat() / total).coerceIn(0f, 1f)
}

private fun dayProgress(now: LocalDateTime): Period {
    val start = now.toLocalDate().atStartOfDay()
    return Period(elapsed(start, now, start.plusDays(1)), 24)
}

private fun weekProgress(now: LocalDateTime): Period {
    val firstDay = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val daysIn = (now.dayOfWeek.value - firstDay.value + 7) % 7
    val start = now.toLocalDate().minusDays(daysIn.toLong()).atStartOfDay()
    return Period(elapsed(start, now, start.plusDays(7)), 7)
}

private fun monthProgress(now: LocalDateTime): Period {
    val start = now.toLocalDate().withDayOfMonth(1).atStartOfDay()
    return Period(elapsed(start, now, start.plusMonths(1)), now.toLocalDate().lengthOfMonth())
}

private fun yearProgress(now: LocalDateTime): Period {
    val start = now.toLocalDate().withDayOfYear(1).atStartOfDay()
    return Period(elapsed(start, now, start.plusYears(1)), 12)
}
