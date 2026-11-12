package de.mm20.launcher2.ui.launcher.search.common.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.search.SavableSearchable
import de.mm20.launcher2.ui.locals.LocalGridSettings
import de.mm20.launcher2.ui.theme.transparency.transparency

/**
 * One card per category with a single horizontally scrolling row of icons,
 * so every category takes the same compact height.
 */
fun <T : SavableSearchable> LazyListScope.RowResults(
    key: String,
    items: List<T>,
    columns: Int,
    reverse: Boolean = false,
    highlightedKey: String? = null,
) {
    if (items.isEmpty()) return
    item(key = "$key-row", contentType = { "search-row" }) {
        BoxWithConstraints(
            modifier = Modifier
                .animateItem()
                .fillMaxWidth()
                .padding(
                    top = if (reverse) 8.dp else 0.dp,
                    bottom = if (reverse) 0.dp else 8.dp,
                )
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = MaterialTheme.transparency.surface),
                    MaterialTheme.shapes.medium,
                )
        ) {
            val itemWidth = (maxWidth - 8.dp) / columns.coerceAtLeast(1)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) {
                items(items, key = { it.key }) {
                    GridItem(
                        modifier = Modifier.width(itemWidth),
                        item = it,
                        showLabels = LocalGridSettings.current.showLabels,
                        highlight = it.key == highlightedKey,
                    )
                }
            }
        }
    }
}
