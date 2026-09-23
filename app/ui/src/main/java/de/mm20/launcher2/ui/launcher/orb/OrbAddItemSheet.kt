package de.mm20.launcher2.ui.launcher.orb

import android.content.pm.LauncherApps
import android.os.Process
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import de.mm20.launcher2.applications.AppRepository
import de.mm20.launcher2.appshortcuts.AppShortcutRepository
import de.mm20.launcher2.orb.pie.PieActionType
import de.mm20.launcher2.preferences.WidgetScreenTarget
import de.mm20.launcher2.search.AppShortcut
import de.mm20.launcher2.search.Application
import de.mm20.launcher2.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The "+" picker shown in orb edit mode and by a folder's "+". Lets the user add apps (all of
 * them), app shortcuts, folders, and built-in actions to the menu.
 */
@Composable
internal fun OrbAddItemSheet(
    appRepository: AppRepository,
    appShortcutRepository: AppShortcutRepository,
    existingActions: Set<PieActionType>,
    onDismiss: () -> Unit,
    onPickApp: (packageName: String, className: String, label: String) -> Unit,
    onPickShortcut: (packageName: String, shortcutId: String, label: String) -> Unit,
    onPickAction: (PieActionType) -> Unit,
    onPickWidgets: (WidgetScreenTarget) -> Unit,
    onPickFolder: () -> Unit,
) {
    var tab by remember { mutableStateOf(AddTab.Apps) }
    var query by remember { mutableStateOf("") }

    val apps by remember { appRepository.findMany().map { it.toList() } }
        .collectAsState(emptyList())
    val shortcuts by remember {
        appShortcutRepository.findMany(
            manifest = true,
            dynamic = true,
            pinned = true,
            cached = true,
            limit = 1000,
        ).map { it.toList() }
    }.collectAsState(emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        title = { Text("Add to menu") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AddTab.entries.forEach { t ->
                        FilterChip(
                            selected = tab == t,
                            onClick = { tab = t },
                            label = { Text(t.label) },
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                when (tab) {
                    AddTab.Apps -> AppList(
                        apps = apps,
                        query = query,
                        onPick = onPickApp,
                    )

                    AddTab.Shortcuts -> ShortcutList(
                        shortcuts = shortcuts,
                        query = query,
                        onPick = onPickShortcut,
                    )

                    AddTab.Actions -> ActionList(
                        existing = existingActions,
                        onPick = onPickAction,
                        onPickWidgets = onPickWidgets,
                        onPickFolder = onPickFolder,
                    )
                }
            }
        },
    )
}

private enum class AddTab(val label: String) {
    Apps("Apps"),
    Shortcuts("Shortcuts"),
    Actions("Actions"),
}

@Composable
private fun AppList(
    apps: List<Application>,
    query: String,
    onPick: (packageName: String, className: String, label: String) -> Unit,
) {
    val filtered = remember(apps, query) {
        val sorted = apps.sortedBy { it.label.lowercase() }
        if (query.isBlank()) sorted
        else sorted.filter { it.label.contains(query, ignoreCase = true) }
    }
    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        items(filtered, key = { appPieId(it.componentName.packageName, it.componentName.className) }) { app ->
            AppRow(app = app) {
                onPick(
                    app.componentName.packageName,
                    app.componentName.className,
                    app.label,
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: Application, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.componentName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getActivityIcon(app.componentName)
            }.getOrNull()?.toBitmap(64, 64)?.asImageBitmap()
        }
    }
    PickRow(label = app.label, icon = icon, onClick = onClick)
}

@Composable
private fun ShortcutList(
    shortcuts: List<AppShortcut>,
    query: String,
    onPick: (packageName: String, shortcutId: String, label: String) -> Unit,
) {
    val grouped = remember(shortcuts, query) {
        val filtered = if (query.isBlank()) shortcuts
        else shortcuts.filter { 
            it.label.contains(query, ignoreCase = true) || 
            (it.appName ?: "").contains(query, ignoreCase = true) 
        }
        filtered.groupBy { it.packageName }
            .map { (pkg, items) ->
                AppGroup(
                    packageName = pkg ?: "",
                    appName = items.firstOrNull()?.appName ?: pkg ?: "",
                    shortcuts = items.sortedBy { it.label.lowercase() },
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    var expandedPkgs by remember { mutableStateOf(setOf<String>()) }

    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        for (group in grouped) {
            val expanded = group.packageName in expandedPkgs
            item(key = "header:${group.packageName}") {
                val context = LocalContext.current
                val appIcon by produceState<ImageBitmap?>(null, group.packageName) {
                    value = withContext(Dispatchers.IO) {
                        runCatching {
                            val intent = context.packageManager.getLaunchIntentForPackage(group.packageName)
                            intent?.component?.let { context.packageManager.getActivityIcon(it) }
                        }.getOrNull()?.toBitmap(64, 64)?.asImageBitmap()
                    }
                }
                AppHeaderRow(
                    label = group.appName,
                    icon = appIcon,
                    expanded = expanded,
                    count = group.shortcuts.size,
                    onClick = {
                        expandedPkgs = if (expanded) {
                            expandedPkgs - group.packageName
                        } else {
                            expandedPkgs + group.packageName
                        }
                    },
                )
            }
            if (expanded) {
                items(
                    group.shortcuts,
                    key = { it.key },
                ) { shortcut ->
                    val shortcutId = remember(shortcut.key, group.packageName) {
                        shortcut.key.removePrefix("shortcut://").removePrefix("${group.packageName}/")
                    }
                    ShortcutRow(
                        packageName = group.packageName,
                        shortcutId = shortcutId,
                        label = shortcut.label,
                        indented = true,
                    ) {
                        onPick(group.packageName, shortcutId, shortcut.label)
                    }
                }
            }
        }
    }
}

private data class AppGroup(
    val packageName: String,
    val appName: String,
    val shortcuts: List<AppShortcut>,
)

@Composable
private fun ShortcutRow(
    packageName: String,
    shortcutId: String,
    label: String,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName, shortcutId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val launcherApps =
                    context.getSystemService(LauncherApps::class.java) ?: return@withContext null
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(packageName)
                    .setShortcutIds(listOf(shortcutId))
                    .setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
                    )
                val info = launcherApps.getShortcuts(query, Process.myUserHandle())
                    ?.firstOrNull() ?: return@withContext null
                launcherApps.getShortcutIconDrawable(
                    info,
                    context.resources.displayMetrics.densityDpi,
                )
            }.getOrNull()?.toBitmap(64, 64)?.asImageBitmap()
        }
    }
    PickRow(label = label, icon = icon, indented = indented, onClick = onClick)
}

@Composable
private fun AppHeaderRow(
    label: String,
    icon: ImageBitmap?,
    expanded: Boolean,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )
            } else {
                Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.arrow_drop_up_24px else R.drawable.arrow_drop_down_24px
            ),
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ActionList(
    existing: Set<PieActionType>,
    onPick: (PieActionType) -> Unit,
    onPickWidgets: (WidgetScreenTarget) -> Unit,
    onPickFolder: () -> Unit,
) {
    val available = PieActionType.entries.filter {
        it != PieActionType.Edit && it != PieActionType.Widgets && it !in existing
    }
    LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
        item(key = "folder") {
            PickRow(label = "App folder", iconRes = R.drawable.apps_24px, onClick = onPickFolder)
        }
        // One entry per widgets page so several different pages can be assigned.
        items(WidgetScreenTarget.entries, key = { "widgets-${it.name}" }) { target ->
            PickRow(
                label = "Widgets ${target.name.removePrefix("Widgets")}",
                iconRes = PieActionType.Widgets.iconRes(),
            ) {
                onPickWidgets(target)
            }
        }
        items(available, key = { it.name }) { type ->
            PickRow(label = type.name, iconRes = type.iconRes()) {
                onPick(type)
            }
        }
    }
}

@Composable
private fun PickRow(
    label: String,
    iconRes: Int? = null,
    icon: ImageBitmap? = null,
    indented: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = if (indented) 28.dp else 0.dp,
                top = 8.dp,
                bottom = 8.dp,
                end = 0.dp,
            ),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            when {
                iconRes != null -> Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )

                icon != null -> Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp).clip(CircleShape),
                )

                else -> Text(
                    text = label.take(1).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
