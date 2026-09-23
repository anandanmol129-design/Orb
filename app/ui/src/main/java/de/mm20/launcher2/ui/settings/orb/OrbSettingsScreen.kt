package de.mm20.launcher2.ui.settings.orb

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.applications.AppRepository
import kotlinx.coroutines.flow.map
import de.mm20.launcher2.orb.OrbFill
import de.mm20.launcher2.orb.OrbShape
import de.mm20.launcher2.orb.pie.PieActionType
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.orb.pie.PieRepository
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.launcher.orb.appPieId
import de.mm20.launcher2.ui.launcher.orb.folderId
import de.mm20.launcher2.ui.launcher.orb.orbBodyPath
import de.mm20.launcher2.voice.AiProvider
import de.mm20.launcher2.voice.VoiceSettings
import org.koin.compose.koinInject

/**
 * Orb's own settings: cloud voice configuration and the radial menu editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbSettingsScreen(
    onBack: () -> Unit,
) {
    val voiceSettings: VoiceSettings = koinInject()
    val pieRepository: PieRepository = koinInject()
    val appRepository: AppRepository = koinInject()

    var cloudEnabled by remember { mutableStateOf(voiceSettings.cloudEnabled) }
    var provider by remember { mutableStateOf(voiceSettings.provider) }
    var providerExpanded by remember { mutableStateOf(false) }
    var endpoint by remember { mutableStateOf(voiceSettings.endpoint) }
    var model by remember { mutableStateOf(voiceSettings.model) }
    var apiKey by remember { mutableStateOf(voiceSettings.apiKey) }

    var pieItems by remember { mutableStateOf(pieRepository.items()) }
    var showAppPicker by remember { mutableStateOf(false) }
    var orbShape by remember { mutableStateOf(pieRepository.orbShape()) }
    var orbFill by remember { mutableStateOf(pieRepository.orbFill()) }

    fun persist(items: List<PieItem>) {
        pieItems = items
        pieRepository.save(items)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Orb settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back_24px), contentDescription = "Back")
                    }
                },
            )
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Voice") }
            item {
                OutlinedCard {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Cloud commands", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Understand complex requests like \"meeting with Sam tomorrow at 3\".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = cloudEnabled,
                                onCheckedChange = {
                                    cloudEnabled = it
                                    voiceSettings.cloudEnabled = it
                                },
                            )
                        }
                        if (cloudEnabled) {
                            Spacer(Modifier.size(12.dp))
                            ExposedDropdownMenuBox(
                                expanded = providerExpanded,
                                onExpandedChange = { providerExpanded = it },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedTextField(
                                    value = AiProvider.fromName(provider).displayName,
                                    onValueChange = {},
                                    readOnly = true,
                                    singleLine = true,
                                    label = { Text("Provider") },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerExpanded)
                                    },
                                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                                )
                                ExposedDropdownMenu(
                                    expanded = providerExpanded,
                                    onDismissRequest = { providerExpanded = false },
                                ) {
                                    AiProvider.entries.forEach { p ->
                                        DropdownMenuItem(
                                            text = { Text(p.displayName) },
                                            onClick = {
                                                provider = p.name
                                                voiceSettings.provider = p.name
                                                if (p.defaultEndpoint.isNotBlank()) {
                                                    endpoint = p.defaultEndpoint
                                                    voiceSettings.endpoint = p.defaultEndpoint
                                                }
                                                if (p.defaultModel.isNotBlank()) {
                                                    model = p.defaultModel
                                                    voiceSettings.model = p.defaultModel
                                                }
                                                providerExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            OutlinedTextField(
                                value = endpoint,
                                onValueChange = {
                                    endpoint = it
                                    voiceSettings.endpoint = it
                                },
                                label = { Text("Endpoint") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(8.dp))
                            OutlinedTextField(
                                value = model,
                                onValueChange = {
                                    model = it
                                    voiceSettings.model = it
                                },
                                label = { Text("Model") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(8.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = {
                                    apiKey = it
                                    voiceSettings.apiKey = it
                                },
                                label = { Text("API key") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "On-device commands (open apps, timers, alarms, flashlight, search) always work offline.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { SectionTitle("Shape") }
            item {
                OutlinedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Choose the orb's body shape.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            OrbShape.entries.forEach { shape ->
                                ShapeOption(
                                    shape = shape,
                                    fill = orbFill,
                                    selected = shape == orbShape,
                                    onClick = {
                                        orbShape = shape
                                        pieRepository.saveOrbShape(shape)
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.size(16.dp))
                        Text(
                            "Style",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OrbFill.entries.forEach { fill ->
                                FillOption(
                                    fill = fill,
                                    shape = orbShape,
                                    selected = fill == orbFill,
                                    onClick = {
                                        orbFill = fill
                                        pieRepository.saveOrbFill(fill)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Pie menu") }
            items(pieItems, key = { it.id }) { item ->
                val index = pieItems.indexOf(item)
                OutlinedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            enabled = index > 0,
                            onClick = {
                                val list = pieItems.toMutableList()
                                val moved = list.removeAt(index)
                                list.add(index - 1, moved)
                                persist(list)
                            },
                        ) { Text("Up") }
                        TextButton(
                            enabled = index < pieItems.lastIndex,
                            onClick = {
                                val list = pieItems.toMutableList()
                                val moved = list.removeAt(index)
                                list.add(index + 1, moved)
                                persist(list)
                            },
                        ) { Text("Down") }
                        TextButton(onClick = {
                            persist(pieItems.toMutableList().also { it.removeAt(index) })
                        }) { Text("Remove") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AddActionButton(
                        existing = pieItems,
                        onAdd = { persist(pieItems + it) },
                    )
                    Button(onClick = { showAppPicker = true }) { Text("Add app") }
                    TextButton(onClick = {
                        persist(PieRepository.defaults())
                    }) { Text("Reset") }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            appRepository = appRepository,
            onDismiss = { showAppPicker = false },
            onPick = { packageName, className, label ->
                persist(
                    pieItems + PieItem.App(
                        id = appPieId(packageName, className),
                        label = label,
                    )
                )
                showAppPicker = false
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ShapeOption(
    shape: OrbShape,
    fill: OrbFill,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(34.dp)) {
                val r = size.minDimension / 2f
                val path = orbBodyPath(shape, Offset.Zero, r)
                val bounds = path.getBounds()
                val offset = Offset(
                    center.x - (bounds.left + bounds.width / 2f),
                    center.y - (bounds.top + bounds.height / 2f),
                )
                path.translate(offset)
                if (fill == OrbFill.Outline) {
                    drawPath(path, color = tint, style = Stroke(width = r * 0.18f))
                } else {
                    drawPath(path, color = tint)
                }
            }
        }
        Text(
            text = shape.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FillOption(
    fill: OrbFill,
    shape: OrbShape,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(34.dp)) {
                val r = size.minDimension / 2f
                val path = orbBodyPath(shape, Offset.Zero, r)
                val bounds = path.getBounds()
                val offset = Offset(
                    center.x - (bounds.left + bounds.width / 2f),
                    center.y - (bounds.top + bounds.height / 2f),
                )
                path.translate(offset)
                if (fill == OrbFill.Outline) {
                    drawPath(path, color = tint, style = Stroke(width = r * 0.18f))
                } else {
                    drawPath(path, color = tint)
                }
            }
        }
        Text(
            text = fill.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddActionButton(
    existing: List<PieItem>,
    onAdd: (PieItem) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val used = existing.filterIsInstance<PieItem.Action>().map { it.type }.toSet()
    val available = PieActionType.entries.filter { it !in used && it != PieActionType.Edit }

    Box {
        Button(onClick = { expanded = true }) { Text("Add action") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("App folder") },
                onClick = {
                    onAdd(PieItem.Folder(folderId(), "Folder"))
                    expanded = false
                },
            )
            if (available.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("All actions added") },
                    onClick = { expanded = false },
                )
            } else {
                available.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.name) },
                        onClick = {
                            onAdd(PieItem.Action(type.name.lowercase(), type, type.name))
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    appRepository: AppRepository,
    onDismiss: () -> Unit,
    onPick: (packageName: String, className: String, label: String) -> Unit,
) {
    val apps by remember { appRepository.findMany().map { it.toList() } }
        .collectAsState(emptyList())
    var query by remember { mutableStateOf("") }

    val filtered = remember(apps, query) {
        val all = apps.sortedBy { it.label.lowercase() }
        if (query.isBlank()) all.take(40)
        else all.filter { it.label.contains(query, ignoreCase = true) }.take(40)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Add an app") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(filtered, key = { appPieId(it.componentName.packageName, it.componentName.className) }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(
                                        app.componentName.packageName,
                                        app.componentName.className,
                                        app.label,
                                    )
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = app.label.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
    )
}
