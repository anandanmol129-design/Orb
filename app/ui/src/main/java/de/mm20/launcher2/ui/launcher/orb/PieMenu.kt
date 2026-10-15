package de.mm20.launcher2.ui.launcher.orb

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.Process
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import de.mm20.launcher2.orb.pie.PieActionType
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.ui.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The radial menu that surrounds the sphere while it is held.
 *
 * [selectedIndex] is computed by the caller from the finger angle; relying on the caller
 * keeps hit-testing and drawing using exactly the same geometry.
 */
@Composable
fun PieMenu(
    items: List<PieItem>,
    center: Offset,
    radiusPx: Float,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val density = LocalDensity.current
    val count = items.size

    Box(modifier) {
        items.forEachIndexed { index, item ->
            val angle = -Math.PI / 2 + index * (2 * Math.PI / count)
            val x = center.x + radiusPx * cos(angle).toFloat()
            val y = center.y + radiusPx * sin(angle).toFloat()

            val selected = index == selectedIndex && selectedIndex >= 0
            val sizeDp = if (selected) 78.dp else 62.dp
            val sizePx = with(density) { sizeDp.toPx() }

            val container = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
            }
            val content = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (x - sizePx / 2).roundToInt(),
                            (y - sizePx / 2).roundToInt(),
                        )
                    }
                    .size(sizeDp)
                    .clip(CircleShape)
                    .background(container)
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                PieItemIcon(item = item, tint = content)
            }
        }
    }
}

@Composable
internal fun PieItemIcon(item: PieItem, tint: Color) {
    when (item) {
        is PieItem.App -> AppPieIcon(key = item.id, fallback = item.label, tint = tint)

        is PieItem.Shortcut -> ShortcutPieIcon(
            packageName = item.packageName,
            shortcutId = item.shortcutId,
            fallback = item.label,
            tint = tint,
        )

        is PieItem.Folder -> Icon(
            painter = painterResource(R.drawable.apps_24px),
            contentDescription = item.label,
            tint = tint,
        )

        is PieItem.Action -> Icon(
            painter = painterResource(item.type.iconRes()),
            contentDescription = item.label,
            tint = tint,
        )
    }
}

/** Loads an app shortcut's own icon, falling back to its initial. */
@Composable
private fun ShortcutPieIcon(
    packageName: String,
    shortcutId: String,
    fallback: String,
    tint: Color,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, shortcutId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val launcherApps =
                    context.getSystemService<LauncherApps>() ?: return@withContext null
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
            }.getOrNull()?.toBitmap(96, 96)?.asImageBitmap()
        }
    }

    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = fallback,
            modifier = Modifier
                .fillMaxSize(0.55f)
                .clip(CircleShape),
        )
    } else {
        Text(
            text = fallback.take(1).uppercase(),
            color = tint,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

/** Loads the installed app's own icon, falling back to its initial. */
@Composable
private fun AppPieIcon(key: String, fallback: String, tint: Color) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) {
            val component = ComponentName.unflattenFromString(key)
                ?: return@withContext null
            runCatching {
                context.packageManager.getActivityIcon(component)?.toBitmap(96, 96)
            }.getOrNull()?.asImageBitmap()
        }
    }

    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = fallback,
            modifier = Modifier
                .fillMaxSize(0.55f)
                .clip(CircleShape),
        )
    } else {
        Text(
            text = fallback.take(1).uppercase(),
            color = tint,
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@DrawableRes
internal fun PieActionType.iconRes(): Int = when (this) {
    PieActionType.Voice -> R.drawable.ic_mic
    PieActionType.Search -> R.drawable.search_24px
    PieActionType.Feed -> R.drawable.news_24px
    PieActionType.Widgets -> R.drawable.widgets_24px
    PieActionType.Settings -> R.drawable.settings_24px
    PieActionType.Edit -> R.drawable.edit_24px
}

/**
 * Returns the selected index for a finger at [pointer] given [center], or -1 when the
 * finger is inside the dead zone (which cancels the action).
 */
fun pieSelectionIndex(
    pointer: Offset,
    center: Offset,
    count: Int,
    deadZonePx: Float,
): Int {
    if (count <= 0) return -1
    val dx = pointer.x - center.x
    val dy = pointer.y - center.y
    if (dx * dx + dy * dy < deadZonePx * deadZonePx) return -1

    var angle = Math.atan2(dy.toDouble(), dx.toDouble())
    // Rotate so that index 0 sits at the top and selection increases clockwise.
    angle += Math.PI / 2
    if (angle < 0) angle += 2 * Math.PI
    val sector = 2 * Math.PI / count
    val index = Math.floor((angle + sector / 2) / sector).toInt() % count
    return ((index % count) + count) % count
}
