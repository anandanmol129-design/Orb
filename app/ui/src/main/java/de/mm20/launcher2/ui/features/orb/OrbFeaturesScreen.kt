package de.mm20.launcher2.ui.features.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.board.BoardRepository
import de.mm20.launcher2.orb.board.BoardTaskEntity
import de.mm20.launcher2.orb.calendar.CalendarRepository
import de.mm20.launcher2.orb.tracker.TrackerRepository
import de.mm20.launcher2.ui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dayHeaderFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbFeaturesScreen(
    initialTab: Int,
    onBack: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    val titles = listOf("Calendar", "Tasks", "Timer")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orb") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        Column(modifier = Modifier.padding(insets)) {
            TabRow(selectedTabIndex = tab) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        text = { Text(title) },
                    )
                }
            }
            when (tab) {
                0 -> CalendarTab()
                1 -> BoardTab()
                2 -> TrackerTab()
            }
        }
    }
}

/* ------------------------------- Calendar ------------------------------- */

@Composable
private fun CalendarTab() {
    val repository: CalendarRepository = koinInject()
    val scope = rememberCoroutineScope()
    val now = remember { System.currentTimeMillis() }
    val events by remember {
        repository.events(now, now + 30L * 24 * 60 * 60 * 1000)
    }.collectAsState(emptyList())

    var showAdd by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        val grouped = remember(events) {
            events.groupBy { event ->
                LocalDate.ofInstant(Instant.ofEpochMilli(event.startTime), ZoneId.systemDefault())
            }
        }

        if (grouped.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No events in the next 30 days.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                grouped.forEach { (date, dayEvents) ->
                    item(key = "header-$date") {
                        Text(
                            text = dayHeaderFormatter.format(date),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(dayEvents, key = { it.id }) { event ->
                        OutlinedCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = event.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val start = LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(event.startTime),
                                        ZoneId.systemDefault(),
                                    )
                                    val end = LocalDateTime.ofInstant(
                                        Instant.ofEpochMilli(event.endTime),
                                        ZoneId.systemDefault(),
                                    )
                                    Text(
                                        text = if (event.allDay) "All day"
                                        else "${timeFormatter.format(start)} – ${timeFormatter.format(end)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { /* reserved for edit */ }) {}
                            }
                        }
                    }
                }
            }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
    }

    if (showAdd) {
        AddEventDialog(
            onDismiss = { showAdd = false },
            onCreate = { title, start, end ->
                scope.launch {
                    repository.create(
                        title = title,
                        startTime = start,
                        endTime = end,
                    )
                }
                showAdd = false
            },
        )
    }
}

@Composable
private fun AddEventDialog(
    onDismiss: () -> Unit,
    onCreate: (title: String, start: Long, end: Long) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var time by remember { mutableStateOf("09:00") }
    var durationMinutes by remember { mutableStateOf("60") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val start = runCatching {
                    LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull() ?: run {
                    error = "Use date YYYY-MM-DD and time HH:MM"
                    return@TextButton
                }
                val minutes = durationMinutes.toLongOrNull() ?: 60L
                if (title.isBlank()) {
                    error = "Add a title"
                    return@TextButton
                }
                onCreate(title.trim(), start, start + minutes * 60_000)
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        title = { Text("New event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; error = null },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it; error = null },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = time,
                    onValueChange = { time = it; error = null },
                    label = { Text("Start (HH:MM)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = durationMinutes,
                    onValueChange = { durationMinutes = it },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}

/* --------------------------------- Board -------------------------------- */

@Composable
private fun BoardTab() {
    val repository: BoardRepository = koinInject()
    val scope = rememberCoroutineScope()
    val columns by repository.columns().collectAsState(emptyList())
    val tasks by repository.tasks().collectAsState(emptyList())

    var showAdd by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(columns, key = { it.id }) { column ->
                val columnTasks = tasks.filter { it.columnId == column.id }
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = column.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = columnTasks.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    columnTasks.forEach { task ->
                        TaskCard(
                            task = task,
                            columns = columns.map { it.id to it.label },
                            onMove = { scope.launch { repository.moveTask(task.id, columnId = it) } },
                            onComplete = {
                                scope.launch { repository.completeTask(task.id, !task.completed) }
                            },
                            onDelete = { scope.launch { repository.deleteTask(task.id) } },
                        )
                    }
                    TextButton(onClick = { showAdd = true }) { Text("+ Add task") }
                }
            }
        }

        androidx.compose.material3.FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Text("+", style = MaterialTheme.typography.headlineSmall)
        }
    }

    if (showAdd) {
        AddTaskDialog(
            columns = columns.map { it.id to it.label },
            onDismiss = { showAdd = false },
            onCreate = { title, columnId ->
                scope.launch { repository.createTask(title = title, columnId = columnId) }
                showAdd = false
            },
        )
    }
}

@Composable
private fun TaskCard(
    task: BoardTaskEntity,
    columns: List<Pair<Long, String>>,
    onMove: (Long) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (task.completed) MaterialTheme.colorScheme.outline
                        else MaterialTheme.colorScheme.primary
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                task.notes?.let { notes ->
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Text("⋯") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    columns.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text("Move to $label") },
                            onClick = { onMove(id); menu = false },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(if (task.completed) "Mark incomplete" else "Complete") },
                        onClick = { onComplete(); menu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { onDelete(); menu = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddTaskDialog(
    columns: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onCreate: (title: String, columnId: Long?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var selectedColumn by remember { mutableStateOf(columns.firstOrNull()?.first) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = { onCreate(title.trim(), selectedColumn) },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    Button(onClick = { expanded = true }) {
                        Text(columns.firstOrNull { it.first == selectedColumn }?.second ?: "Column")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        columns.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { selectedColumn = id; expanded = false },
                            )
                        }
                    }
                }
            }
        },
    )
}

/* -------------------------------- Tracker ------------------------------- */

@Composable
private fun TrackerTab() {
    val repository: TrackerRepository = koinInject()
    val scope = rememberCoroutineScope()
    val running by repository.running().collectAsState(null)
    var label by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(running?.id) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val todayStart = remember {
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val todaySessions by remember {
        repository.sessions(todayStart, System.currentTimeMillis())
    }.collectAsState(emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val session = running
        if (session == null) {
            Text("Not tracking", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("What are you working on?") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { scope.launch { repository.start(label.ifBlank { "work" }) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start") }
        } else {
            Text(session.label, style = MaterialTheme.typography.titleLarge)
            Text(
                text = formatClock((now - session.startedAt).coerceAtLeast(0)),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(
                onClick = { scope.launch { repository.stop() } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop") }
        }

        Spacer(Modifier.size(8.dp))
        Text(
            "Today",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
        val totals = todaySessions
            .groupBy { it.label }
            .map { (name, sessions) ->
                name to sessions.sumOf {
                    (it.endedAt ?: System.currentTimeMillis()) - it.startedAt
                }
            }
            .sortedByDescending { it.second }

        if (totals.isEmpty()) {
            Text(
                "Nothing tracked yet today.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(totals) { (name, millis) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatClock(millis),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatClock(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}
