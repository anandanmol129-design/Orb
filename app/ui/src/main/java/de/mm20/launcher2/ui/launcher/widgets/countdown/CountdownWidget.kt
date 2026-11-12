package de.mm20.launcher2.ui.launcher.widgets.countdown

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.widgets.CountdownWidget
import de.mm20.launcher2.widgets.CountdownWidgetConfig
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** The next day this countdown points at, taking yearly repetition into account. */
internal fun CountdownWidgetConfig.resolveTarget(today: LocalDate): LocalDate? {
    val target = targetEpochDay?.let { LocalDate.ofEpochDay(it) } ?: return null
    if (!repeatYearly || !target.isBefore(today)) return target
    // plusYears keeps Feb 29 anniversaries sane by clamping to Feb 28 in common years.
    val thisYear = target.plusYears((today.year - target.year).toLong())
    return if (thisYear.isBefore(today)) target.plusYears((today.year - target.year + 1).toLong())
    else thisYear
}

@Composable
fun CountdownWidget(widget: CountdownWidget, onConfigure: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var today by remember { mutableStateOf(LocalDate.now()) }

    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                today = LocalDate.now()
                delay(60_000)
            }
        }
    }

    val config = widget.config
    val target = config.resolveTarget(today)
    val title = config.title.ifBlank { stringResource(R.string.countdown_widget_default_title) }

    if (target == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onConfigure)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(R.drawable.hourglass_bottom_20px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.countdown_widget_setup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val days = ChronoUnit.DAYS.between(today, target).toInt()
    val accent = if (days < 0) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (days == 0) {
                Text(
                    text = stringResource(R.string.countdown_widget_today),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            } else {
                Text(
                    text = abs(days).toString(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (days != 0) {
                            append(
                                pluralStringResource(
                                    if (days > 0) R.plurals.countdown_widget_days_left
                                    else R.plurals.countdown_widget_days_since,
                                    abs(days),
                                )
                            )
                            append(" · ")
                        }
                        append(target.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Elapsed share of the wait. Yearly countdowns always span the year before the target.
        val start = if (config.repeatYearly) target.minusYears(1)
        else config.startEpochDay?.let { LocalDate.ofEpochDay(it) }
        if (start != null && start.isBefore(target)) {
            val total = ChronoUnit.DAYS.between(start, target).toFloat()
            val progress by animateFloatAsState(
                (ChronoUnit.DAYS.between(start, today) / total).coerceIn(0f, 1f),
                animationSpec = spring(stiffness = 60f),
                label = "countdown",
            )
            val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            ) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                val width = (size.width * progress).coerceAtLeast(size.height)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.5f), accent),
                        endX = width,
                    ),
                    size = Size(width, size.height),
                    cornerRadius = radius,
                )
                // Marker for today at the leading edge.
                drawCircle(
                    color = accent,
                    radius = size.height * 0.9f,
                    center = Offset(width - size.height / 2, size.height / 2),
                )
            }
        }
    }
}
