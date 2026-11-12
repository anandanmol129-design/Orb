package de.mm20.launcher2.ui.launcher.widgets.month

import android.text.format.DateUtils
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.MissingPermissionBanner
import de.mm20.launcher2.widgets.MonthWidget
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private const val MaxAgendaItems = 3

@Composable
fun MonthWidget(widget: MonthWidget) {
    val viewModel: MonthWidgetVM = viewModel(key = "month-widget-${widget.id}")
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val month by viewModel.month.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val events by viewModel.events.collectAsState()
    val hasPermission by viewModel.hasPermission.collectAsState()

    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                today = LocalDate.now()
                delay(60_000)
            }
        }
    }

    val wantsEvents = widget.config.showEvents || widget.config.showAgenda
    LaunchedEffect(wantsEvents) {
        viewModel.setLoadEvents(wantsEvents)
    }

    val firstDayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(bottom = 12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.goToToday() }) {
                Text(
                    text = formatMonth(context, month),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { viewModel.previousMonth() }) {
                Icon(
                    painterResource(R.drawable.chevron_backward_24px),
                    contentDescription = stringResource(R.string.month_widget_previous),
                )
            }
            IconButton(onClick = { viewModel.nextMonth() }) {
                Icon(
                    painterResource(R.drawable.chevron_forward_24px),
                    contentDescription = stringResource(R.string.month_widget_next),
                )
            }
        }

        Row(Modifier.padding(horizontal = 12.dp)) {
            for (i in 0 until 7) {
                val day = firstDayOfWeek.plus(i.toLong())
                Text(
                    text = day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day == DayOfWeek.SUNDAY) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally { it / 3 * direction } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 3 * direction } + fadeOut())
            },
            label = "month",
        ) { shownMonth ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                for (week in weeksOf(shownMonth, firstDayOfWeek)) {
                    Row {
                        for (date in week) {
                            DayCell(
                                date = date,
                                inMonth = YearMonth.from(date) == shownMonth,
                                isToday = date == today,
                                isSelected = date == selectedDate,
                                events = if (widget.config.showEvents) events[date].orEmpty() else emptyList(),
                                onClick = { viewModel.selectDate(date) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        if (wantsEvents && hasPermission == false) {
            MissingPermissionBanner(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                text = stringResource(R.string.missing_permission_calendar_widget),
                onClick = { viewModel.requestCalendarPermission(context as AppCompatActivity) },
            )
        } else if (widget.config.showAgenda) {
            Agenda(date = selectedDate, events = events[selectedDate].orEmpty())
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    events: List<CalendarEvent>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val selection by animateFloatAsState(if (isSelected) 1f else 0f, label = "daySelection")
    val fill = if (isToday) colors.primary else colors.secondaryContainer
    val onFill = if (isToday) colors.onPrimary else colors.onSecondaryContainer

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .graphicsLayer {
                        alpha = selection
                        scaleX = 0.6f + 0.4f * selection
                        scaleY = 0.6f + 0.4f * selection
                    }
                    .background(fill, CircleShape)
            )
            if (isToday && !isSelected) {
                Box(
                    Modifier
                        .size(30.dp)
                        .border(1.5.dp, colors.primary, CircleShape)
                )
            }
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    isSelected -> onFill
                    isToday -> colors.primary
                    !inMonth -> colors.onSurface.copy(alpha = 0.32f)
                    else -> colors.onSurface
                },
            )
        }
        Row(
            modifier = Modifier
                .padding(top = 2.dp)
                .height(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (event in events.take(3)) {
                Box(
                    Modifier
                        .size(4.dp)
                        .background(
                            event.dotColor(colors.primary).copy(alpha = if (inMonth) 1f else 0.4f),
                            CircleShape,
                        )
                )
            }
        }
    }
}

@Composable
private fun Agenda(date: LocalDate, events: List<CalendarEvent>) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp),
    ) {
        Text(
            text = DateUtils.formatDateTime(
                context,
                date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_NO_YEAR,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (events.isEmpty()) {
            Text(
                text = stringResource(R.string.month_widget_no_events),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        for (event in events.take(MaxAgendaItems)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { event.launch(context, null) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(32.dp)
                        .background(
                            event.dotColor(MaterialTheme.colorScheme.primary),
                            RoundedCornerShape(2.dp),
                        )
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text(
                        text = event.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = event.formatTime(context, date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (events.size > MaxAgendaItems) {
            Text(
                text = stringResource(R.string.month_widget_more, events.size - MaxAgendaItems),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private fun CalendarEvent.dotColor(fallback: Color): Color {
    return color?.takeIf { it != 0 }?.let { Color(it).copy(alpha = 1f) } ?: fallback
}

/** Time range of an event as seen from [date], which matters for events spanning several days. */
private fun CalendarEvent.formatTime(context: android.content.Context, date: LocalDate): String {
    val zone = ZoneId.systemDefault()
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val start = startTime
    val coversDay = start != null && start <= dayStart && endTime >= dayEnd - 1
    if (allDay || coversDay) return context.getString(R.string.month_widget_all_day)
    if (start == null || isTask) {
        return DateUtils.formatDateTime(context, endTime, DateUtils.FORMAT_SHOW_TIME)
    }
    return DateUtils.formatDateRange(
        context,
        maxOf(start, dayStart),
        minOf(endTime, dayEnd - 1),
        DateUtils.FORMAT_SHOW_TIME,
    )
}

private fun formatMonth(context: android.content.Context, month: YearMonth): String {
    return DateUtils.formatDateTime(
        context,
        month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_MONTH_DAY or DateUtils.FORMAT_SHOW_YEAR,
    )
}

/** The rows of the month grid, padded with days of the neighbouring months. */
private fun weeksOf(month: YearMonth, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> {
    val first = month.atDay(1)
    val lead = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    var day = first.minusDays(lead.toLong())
    val weeks = mutableListOf<List<LocalDate>>()
    while (!day.isAfter(month.atEndOfMonth())) {
        weeks += List(7) { day.plusDays(it.toLong()) }
        day = day.plusDays(7)
    }
    return weeks
}
