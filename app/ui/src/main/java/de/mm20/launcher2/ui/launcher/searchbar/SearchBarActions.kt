package de.mm20.launcher2.ui.launcher.searchbar

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.searchactions.actions.SearchAction
import de.mm20.launcher2.searchactions.actions.SearchActionIcon
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.app.SearchManager
import android.content.ComponentName
import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import de.mm20.launcher2.searchactions.actions.WebsearchAction
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.R
import de.mm20.launcher2.ui.component.SearchActionIcon
import de.mm20.launcher2.ui.modifier.consumeAllScrolling
import de.mm20.launcher2.ui.settings.SettingsActivity

@Composable
fun ColumnScope.SearchBarActions(
    modifier: Modifier = Modifier,
    actions: List<SearchAction>,
    highlightedAction: SearchAction?,
    reverse: Boolean = false,
) {
    val context = LocalContext.current
    // Icon-only actions are 70% of a home screen icon, so they read as apps, not chips.
    val iconSize = (LocalGridSettings.current.iconSize * 0.7f).dp
    val rowHeight = maxOf(48.dp, iconSize + 16.dp)
    // Web search hands the query to the system's web search app (Google), so show its icon.
    val webSearchIcon = remember(context) {
        runCatching {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
            // The default handler; with no default set this is the system chooser, so fall
            // back to Google, then to any handler.
            val default = pm.resolveActivity(intent, 0)?.activityInfo
                ?.takeIf { it.packageName != "android" }
            val handlers = pm.queryIntentActivities(intent, 0).map { it.activityInfo }
            val info = default
                ?: handlers.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
                ?: handlers.firstOrNull()
            info?.let { ComponentName(it.packageName, it.name) }
        }.getOrNull()
    }
    AnimatedVisibility(actions.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .consumeAllScrolling()
                .height(rowHeight)
                .padding(bottom = if (reverse) 0.dp else 8.dp, top = if (reverse) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp)
        ) {
            items(actions) {
                val highlighted = it == highlightedAction
                val isWebSearch = it is WebsearchAction && webSearchIcon != null
                if (it.icon == SearchActionIcon.Custom || isWebSearch) {
                    // App, custom-image and web search actions are recognisable by their icon.
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .size(iconSize + 4.dp)
                            .clip(CircleShape)
                            .then(
                                if (highlighted) Modifier
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .border(1.5.dp, MaterialTheme.colorScheme.secondary, CircleShape)
                                else Modifier
                            )
                            .clickable {
                                val web = it as? WebsearchAction
                                if (isWebSearch && web != null) {
                                    // Straight to the app whose icon is shown, not a chooser.
                                    val started = runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_WEB_SEARCH)
                                                .setComponent(webSearchIcon)
                                                .putExtra(SearchManager.QUERY, web.query)
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.isSuccess
                                    if (!started) it.start(context)
                                } else it.start(context)
                            }
                            .semantics { contentDescription = it.label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isWebSearch) {
                            WebSearchAppIcon(webSearchIcon!!, iconSize)
                        } else {
                            SearchActionIcon(action = it, size = iconSize)
                        }
                    }
                } else AssistChip(
                    modifier = Modifier.padding(4.dp),
                    colors = if (highlighted) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else AssistChipDefaults.assistChipColors(),
                    border = if (highlighted) {
                        AssistChipDefaults.assistChipBorder(
                            true,
                            borderColor = MaterialTheme.colorScheme.secondary,
                        )
                    } else AssistChipDefaults.assistChipBorder(true),
                    onClick = {
                        it.start(context)
                    },
                    label = { Text(it.label) },
                    leadingIcon = {
                        SearchActionIcon(
                            action = it,
                            size = AssistChipDefaults.IconSize,
                        )
                    }
                )
            }
            item {
                SmallFloatingActionButton(
                    modifier = Modifier.padding(start = 4.dp),
                    elevation = FloatingActionButtonDefaults.bottomAppBarFabElevation(),
                    onClick = {
                        context.startActivity(
                            Intent(context, SettingsActivity::class.java).apply {
                                putExtra(SettingsActivity.EXTRA_ROUTE, SettingsActivity.ROUTE_SEARCH_ACTIONS)
                            }
                        )
                    }
                ) {

                    Icon(painterResource(R.drawable.edit_24px), contentDescription = null)
                }
            }
        }
    }
}

/** The icon of the app that handles web searches (normally Google), falling back to its app icon. */
@Composable
private fun WebSearchAppIcon(component: ComponentName, size: Dp) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, component) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching { pm.getActivityIcon(component) }.getOrNull()
                ?: runCatching { pm.getApplicationIcon(component.packageName) }.getOrNull()
        }
    }
    AsyncImage(
        model = drawable,
        contentDescription = null,
        modifier = Modifier.size(size),
    )
}
