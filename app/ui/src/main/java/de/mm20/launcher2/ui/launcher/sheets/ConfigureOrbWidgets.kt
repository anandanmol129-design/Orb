package de.mm20.launcher2.ui.launcher.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.preferences.Preference
import de.mm20.launcher2.ui.component.preferences.SwitchPreference
import de.mm20.launcher2.widgets.CountdownWidget
import de.mm20.launcher2.widgets.DeviceWidget
import de.mm20.launcher2.widgets.MonthWidget
import de.mm20.launcher2.widgets.ProgressWidget
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun ColumnScope.ConfigureDeviceWidget(
    widget: DeviceWidget,
    onWidgetUpdated: (DeviceWidget) -> Unit,
) {
    val config = widget.config
    OutlinedCard {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.device_widget_show_temperature),
                iconPadding = false,
                value = config.showTemperature,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showTemperature = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.device_widget_show_storage),
                iconPadding = false,
                value = config.showStorage,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showStorage = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.device_widget_show_memory),
                iconPadding = false,
                value = config.showMemory,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showMemory = it))) },
            )
        }
    }
}

@Composable
fun ColumnScope.ConfigureMonthWidget(
    widget: MonthWidget,
    onWidgetUpdated: (MonthWidget) -> Unit,
) {
    val config = widget.config
    OutlinedCard {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.month_widget_show_events),
                iconPadding = false,
                value = config.showEvents,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showEvents = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.month_widget_show_agenda),
                iconPadding = false,
                value = config.showAgenda,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showAgenda = it))) },
            )
        }
    }
}

@Composable
fun ColumnScope.ConfigureProgressWidget(
    widget: ProgressWidget,
    onWidgetUpdated: (ProgressWidget) -> Unit,
) {
    val config = widget.config
    OutlinedCard {
        Column(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = stringResource(R.string.progress_widget_day),
                iconPadding = false,
                value = config.showDay,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showDay = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.progress_widget_week),
                iconPadding = false,
                value = config.showWeek,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showWeek = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.progress_widget_month),
                iconPadding = false,
                value = config.showMonth,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showMonth = it))) },
            )
            SwitchPreference(
                title = stringResource(R.string.progress_widget_year),
                iconPadding = false,
                value = config.showYear,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(showYear = it))) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnScope.ConfigureCountdownWidget(
    widget: CountdownWidget,
    onWidgetUpdated: (CountdownWidget) -> Unit,
) {
    val config = widget.config
    // Typed text is kept locally so the cursor does not jump while the widget is saved.
    var title by rememberSaveable(widget.id) { mutableStateOf(config.title) }
    var showDatePicker by remember { mutableStateOf(false) }

    OutlinedTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        value = title,
        onValueChange = {
            title = it
            onWidgetUpdated(widget.copy(config = config.copy(title = it)))
        },
        singleLine = true,
        label = { Text(stringResource(R.string.countdown_widget_title)) },
    )

    OutlinedCard {
        Column(Modifier.fillMaxWidth()) {
            Preference(
                title = stringResource(R.string.countdown_widget_date),
                iconPadding = false,
                summary = config.targetEpochDay
                    ?.let {
                        LocalDate.ofEpochDay(it)
                            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
                    }
                    ?: stringResource(R.string.countdown_widget_date_unset),
                onClick = { showDatePicker = true },
            )
            SwitchPreference(
                title = stringResource(R.string.countdown_widget_repeat),
                summary = stringResource(R.string.countdown_widget_repeat_summary),
                iconPadding = false,
                value = config.repeatYearly,
                onValueChanged = { onWidgetUpdated(widget.copy(config = config.copy(repeatYearly = it))) },
            )
        }
    }

    if (showDatePicker) {
        // The picker works in UTC midnights, which map 1:1 onto epoch days.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (config.targetEpochDay ?: LocalDate.now().toEpochDay()) * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        val day = state.selectedDateMillis?.let { Math.floorDiv(it, 86_400_000L) }
                        if (day != null && day != config.targetEpochDay) {
                            onWidgetUpdated(
                                widget.copy(
                                    config = config.copy(
                                        title = title,
                                        targetEpochDay = day,
                                        // A new target restarts the progress bar from today.
                                        startEpochDay = LocalDate.now().toEpochDay(),
                                    )
                                )
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        ) {
            DatePicker(state)
        }
    }
}
