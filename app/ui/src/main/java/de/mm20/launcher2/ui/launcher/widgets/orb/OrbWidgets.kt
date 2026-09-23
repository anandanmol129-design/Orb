package de.mm20.launcher2.ui.launcher.widgets.orb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.board.BoardRepository
import de.mm20.launcher2.orb.calendar.CalendarRepository
import de.mm20.launcher2.orb.tracker.TrackerRepository
import de.mm20.launcher2.widgets.OrbBoardWidget
import de.mm20.launcher2.widgets.OrbCalendarWidget
import de.mm20.launcher2.widgets.OrbTrackerWidget
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayFormatter = DateTimeFormatter.ofPattern("EEE d MMM")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun OrbCalendarWidget(widget: OrbCalendarWidget) {
    val repository: CalendarRepository = koinInject()
    val events by remember(widget.config.upcomingCount) {
        repository.upcoming(System.currentTimeMillis(), widget.config.upcomingCount)
    }.collectAsState(emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Upcoming",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (events.isEmpty()) {
            Text(
                text = "Nothing scheduled. Ask Orb to add an event.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            events.forEach { event ->
                val zone = ZoneId.systemDefault()
                val start = LocalDate.ofInstant(Instant.ofEpochMilli(event.startTime), zone)
                val time = java.time.LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(event.startTime),
                    zone,
                ).format(timeFormatter)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (event.allDay) dayFormatter.format(start) else "$time",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(52.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
fun OrbBoardWidget(widget: OrbBoardWidget) {
    val repository: BoardRepository = koinInject()
    val columns by repository.columns().collectAsState(emptyList())
    val tasks by repository.tasks().collectAsState(emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Tasks",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEach { column ->
                Column {
                    Text(
                        text = column.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = tasks.count { it.columnId == column.id }.toString(),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            }
        }
        val visible = tasks
            .filter { !it.completed || widget.config.showDone }
            .take(4)
        if (visible.isEmpty()) {
            Text(
                text = "No tasks yet. Say \"add buy milk to Backlog\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            visible.forEach { task ->
                val column = columns.firstOrNull { it.id == task.columnId }?.label.orEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "•",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = task.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = column,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun OrbTrackerWidget(widget: OrbTrackerWidget) {
    val repository: TrackerRepository = koinInject()
    val running by repository.running().collectAsState(null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(running?.id) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val todaySessions by remember {
        val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.sessions(start, System.currentTimeMillis())
    }.collectAsState(emptyList())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Time tracker",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val session = running
        if (session == null) {
            Text(
                text = "Not tracking. Say \"start tracking writing\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = session.label,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = formatDuration((now - session.startedAt).coerceAtLeast(0)),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (widget.config.showTodayTotal) {
            val total = todaySessions.sumOf {
                (it.endedAt ?: System.currentTimeMillis()) - it.startedAt
            }
            Text(
                text = "Today: ${formatDuration(total)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
