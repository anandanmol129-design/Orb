package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.pie.PieItem

/**
 * The window that opens when a pie folder is chosen: its entries as a simple grid. The "+" at
 * the top right adds anything the pie can hold (through the same picker), and the "−" shows a
 * removal badge on every entry - tapping an entry then removes it. Tapping outside the window
 * dismisses it.
 */
@Composable
fun OrbFolderView(
    folder: PieItem.Folder,
    onOpen: (PieItem) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Per folder, so opening another folder starts without badges.
    var removing by remember(folder.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier
                // ~2 cm from the bottom edge, low enough to reach every entry with a thumb.
                .padding(start = 32.dp, end = 32.dp, bottom = 126.dp)
                .widthIn(max = 336.dp)
                // The window body swallows taps, so only the scrim dismisses.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = folder.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    FolderButton(glyph = "+", active = false, onClick = onAdd)
                    Spacer(Modifier.width(8.dp))
                    FolderButton(glyph = "−", active = removing, onClick = { removing = !removing })
                }
                Spacer(Modifier.size(8.dp))
                if (folder.children.isEmpty()) {
                    Text(
                        text = "Empty. Add items with +.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        itemsIndexed(folder.children) { index, item ->
                            FolderEntry(
                                item = item,
                                removing = removing,
                                onClick = {
                                    if (removing) onRemove(index) else onOpen(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One of the round "+" / "−" buttons at the top right of a folder window. */
@Composable
private fun FolderButton(
    glyph: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.titleLarge,
            color = if (active) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** One entry of a folder window: its icon in a round chip with the label underneath. */
@Composable
private fun FolderEntry(
    item: PieItem,
    removing: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                PieItemIcon(item = item, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (removing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "−",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(64.dp),
        )
    }
}
