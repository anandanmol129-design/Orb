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
    AnimatedVisibility(actions.isNotEmpty()) {
        LazyRow(
            modifier = Modifier
                .consumeAllScrolling()
                .height(48.dp)
                .padding(bottom = if (reverse) 0.dp else 8.dp, top = if (reverse) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            contentPadding = PaddingValues(start = 8.dp, end = 4.dp)
        ) {
            items(actions) {
                val highlighted = it == highlightedAction
                if (it.icon == SearchActionIcon.Custom) {
                    // App and custom-image actions are recognisable by their icon alone.
                    Surface(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
                            .semantics { contentDescription = it.label },
                        shape = AssistChipDefaults.shape,
                        color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer
                        else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (highlighted) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.outlineVariant
                        ),
                        onClick = { it.start(context) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            SearchActionIcon(
                                action = it,
                                size = 20.dp,
                            )
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