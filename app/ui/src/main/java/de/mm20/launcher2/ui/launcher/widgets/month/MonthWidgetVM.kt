package de.mm20.launcher2.ui.launcher.widgets.month

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.mm20.launcher2.calendar.CalendarRepository
import de.mm20.launcher2.permissions.PermissionGroup
import de.mm20.launcher2.permissions.PermissionsManager
import de.mm20.launcher2.search.CalendarEvent
import de.mm20.launcher2.searchable.SavableSearchableRepository
import de.mm20.launcher2.searchable.VisibilityLevel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MonthWidgetVM : ViewModel(), KoinComponent {
    private val calendarRepository: CalendarRepository by inject()
    private val searchableRepository: SavableSearchableRepository by inject()
    private val permissionsManager: PermissionsManager by inject()

    val hasPermission = permissionsManager.hasPermission(PermissionGroup.Calendar)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    val month = MutableStateFlow(YearMonth.now())
    val selectedDate = MutableStateFlow(LocalDate.now())

    private val loadEvents = MutableStateFlow(true)

    /** Events of every day in the visible grid (the month plus a week of overhang either side). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val events = combine(month, loadEvents) { month, load -> month to load }
        .flatMapLatest { (month, load) ->
            if (!load) return@flatMapLatest flowOf(emptyMap())
            val zone = ZoneId.systemDefault()
            val first = month.atDay(1).minusDays(7)
            val last = month.atEndOfMonth().plusDays(7)
            combine(
                calendarRepository.findMany(
                    from = first.atStartOfDay(zone).toInstant().toEpochMilli(),
                    to = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                ),
                searchableRepository.getKeys(
                    includeTypes = listOf("calendar", "tasks.org", "plugin.calendar"),
                    maxVisibility = VisibilityLevel.SearchOnly,
                    limit = 9999,
                ),
            ) { events, hidden ->
                groupByDay(events.filter { it.key !in hidden && it.isCompleted != true }, first, last, zone)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private fun groupByDay(
        events: List<CalendarEvent>,
        first: LocalDate,
        last: LocalDate,
        zone: ZoneId,
    ): Map<LocalDate, List<CalendarEvent>> {
        val result = mutableMapOf<LocalDate, MutableList<CalendarEvent>>()
        for (event in events.sortedBy { it.startTime ?: it.endTime }) {
            val end = Instant.ofEpochMilli(event.endTime).atZone(zone).toLocalDate()
            // Tasks only have a due date; events occupy every day they span.
            val start = event.startTime?.takeIf { !event.isTask }
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?: end
            var day = maxOf(start, first)
            val lastDay = minOf(end, last)
            while (!day.isAfter(lastDay)) {
                result.getOrPut(day) { mutableListOf() }.add(event)
                day = day.plusDays(1)
            }
        }
        return result
    }

    fun setLoadEvents(load: Boolean) {
        loadEvents.value = load
    }

    fun previousMonth() {
        month.value = month.value.minusMonths(1)
    }

    fun nextMonth() {
        month.value = month.value.plusMonths(1)
    }

    fun selectDate(date: LocalDate) {
        selectedDate.value = date
        month.value = YearMonth.from(date)
    }

    fun goToToday() = selectDate(LocalDate.now())

    fun requestCalendarPermission(context: AppCompatActivity) {
        permissionsManager.requestPermission(context, PermissionGroup.Calendar)
    }
}
