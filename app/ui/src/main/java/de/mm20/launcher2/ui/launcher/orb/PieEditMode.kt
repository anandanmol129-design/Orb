package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.ui.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class EditDragMode { Resize, Move, Reorder }

/**
 * The single gesture layer for edit mode. One full-screen pointer handler hit-tests every touch
 * with a fixed priority — resize handle, then orb body (move), then a pie slice (reorder) — so the
 * slice/ord gestures never fight over the same event.
 */
@Composable
fun PieEditMode(
    items: List<PieItem>,
    center: Offset,
    pieRadius: Float,
    deadZonePx: Float,
    sphereRadius: Float,
    minRadius: Float,
    maxRadius: Float,
    topInsetPx: Float,
    bottomInsetPx: Float,
    screenHeight: Float,
    trashCenter: Offset,
    onReorder: (List<PieItem>) -> Unit,
    onRemove: (Int) -> Unit,
    onRadiusChange: (Float) -> Unit,
    onCenterYChange: (Float) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val handlePx = with(density) { 32.dp.toPx() }
    val lineWidthPx = with(density) { 2.dp.toPx() }
    val hitRadius = with(density) { 48.dp.toPx() }
    val trashHitRadius = with(density) { 44.dp.toPx() }

    val currentCenter = rememberUpdatedState(center)
    val currentSphere = rememberUpdatedState(sphereRadius)
    val currentPieRadius = rememberUpdatedState(pieRadius)
    val currentDeadZone = rememberUpdatedState(deadZonePx)
    val currentMinRadius = rememberUpdatedState(minRadius)
    val currentMaxRadius = rememberUpdatedState(maxRadius)
    val currentTopInset = rememberUpdatedState(topInsetPx)
    val currentBottomInset = rememberUpdatedState(bottomInsetPx)
    val currentScreenHeight = rememberUpdatedState(screenHeight)
    val currentTrashCenter = rememberUpdatedState(trashCenter)

    var dragIndex by remember { mutableStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var removing by remember { mutableStateOf(false) }
    val count = items.size

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = currentCenter.value
                        val r = currentSphere.value
                        val handle = Offset(c.x, c.y - r)
                        val slice = sliceHitIndex(down.position, c, currentPieRadius.value, count, hitRadius)
                        val mode = when {
                            (down.position - handle).getDistance() < handlePx -> EditDragMode.Resize
                            (down.position - c).getDistance() < r -> EditDragMode.Move
                            slice >= 0 -> EditDragMode.Reorder
                            else -> return@awaitEachGesture
                        }
                        down.consume()

                        when (mode) {
                            EditDragMode.Resize -> {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val newRadius = (currentCenter.value.y - change.position.y)
                                        .coerceIn(currentMinRadius.value, currentMaxRadius.value)
                                    onRadiusChange(newRadius)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }

                            EditDragMode.Move -> {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    val rNow = currentSphere.value
                                    val newY = change.position.y.coerceIn(
                                        currentTopInset.value + rNow,
                                        currentScreenHeight.value - currentBottomInset.value - rNow,
                                    )
                                    onCenterYChange(newY)
                                    change.consume()
                                    if (!change.pressed) break
                                }
                            }

                            EditDragMode.Reorder -> {
                                dragIndex = slice
                                dragPos = down.position
                                removing = false
                                onDragStateChange(true)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    dragPos = change.position
                                    removing = (change.position - currentCenter.value).getDistance() < currentDeadZone.value
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                val released = dragIndex
                                val wasRemoving = removing
                                dragIndex = -1
                                removing = false
                                onDragStateChange(false)
                                if (released in items.indices) {
                                    val nearTrash = (dragPos - currentTrashCenter.value).getDistance() < trashHitRadius
                                    if (wasRemoving || nearTrash) {
                                        onRemove(released)
                                    } else {
                                        val target = pieSelectionIndex(dragPos, c, items.size, currentDeadZone.value)
                                        if (target >= 0 && target != released) {
                                            val list = items.toMutableList()
                                            val moved = list.removeAt(released)
                                            list.add(target, moved)
                                            onReorder(list)
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            // Live display order: the dragged item occupies the slot under the finger,
            // every other item slides into the slot it vacated.
            val display: List<Triple<PieItem, Offset, Boolean>> =
                if (dragIndex !in items.indices) {
                    items.mapIndexed { slot, item ->
                        Triple(item, slotPosition(slot, count, center, pieRadius), false)
                    }
                } else {
                    val dragged = items[dragIndex]
                    val others = items.toMutableList().also { it.removeAt(dragIndex) }
                    val insertAt = pieSelectionIndex(dragPos, center, count, deadZonePx)
                    val order = if (insertAt < 0) items else others.also { it.add(insertAt, dragged) }
                    order.mapIndexed { slot, item ->
                        val isDragged = item.id == dragged.id
                        Triple(
                            item,
                            if (isDragged) dragPos else slotPosition(slot, count, center, pieRadius),
                            isDragged,
                        )
                    }
                }

            display.forEach { (item, target, isDragged) ->
                if (isDragged) {
                    PieSliceBubble(
                        item = item,
                        position = dragPos,
                        sizeDp = 78.dp,
                        elevated = true,
                        removing = removing,
                    )
                } else {
                    key(item.id) {
                        val animated by animateOffsetAsState(
                            targetValue = target,
                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
                            label = "pie-edit-${item.id}",
                        )
                        PieSliceBubble(
                            item = item,
                            position = animated,
                            sizeDp = 62.dp,
                            elevated = false,
                        )
                    }
                }
            }
        }

        val handle = Offset(center.x, center.y - sphereRadius)

        // Slider line from the orb's centre up to its top edge.
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (handle.x - lineWidthPx / 2f).roundToInt(),
                        handle.y.roundToInt(),
                    )
                }
                .size(2.dp, with(density) { sphereRadius.toDp() })
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        )

        // Resize handle at the orb's edge.
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (handle.x - handlePx / 2f).roundToInt(),
                        (handle.y - handlePx / 2f).roundToInt(),
                    )
                }
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.unfold_more_24px),
                contentDescription = "Resize",
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }

        Text(
            text = "Drag to reorder · drag to the trash to remove",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 96.dp),
        )
    }
}

@Composable
private fun PieSliceBubble(
    item: PieItem,
    position: Offset,
    sizeDp: Dp,
    elevated: Boolean,
    removing: Boolean = false,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }
    val container = when {
        removing -> MaterialTheme.colorScheme.errorContainer
        elevated -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f)
    }
    val content = when {
        removing -> MaterialTheme.colorScheme.onErrorContainer
        elevated -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(sizeDp)
            .graphicsLayer {
                translationX = position.x - sizePx / 2f
                translationY = position.y - sizePx / 2f
            }
            .clip(CircleShape)
            .background(container)
            .then(
                if (elevated) {
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

private fun slotPosition(slot: Int, count: Int, center: Offset, radiusPx: Float): Offset {
    val angle = -Math.PI / 2 + slot * (2 * Math.PI / count)
    return Offset(
        center.x + radiusPx * cos(angle).toFloat(),
        center.y + radiusPx * sin(angle).toFloat(),
    )
}

private fun sliceHitIndex(
    pointer: Offset,
    center: Offset,
    radiusPx: Float,
    count: Int,
    hitRadius: Float,
): Int {
    if (count <= 0) return -1
    for (i in 0 until count) {
        val pos = slotPosition(i, count, center, radiusPx)
        val dx = pointer.x - pos.x
        val dy = pointer.y - pos.y
        if (dx * dx + dy * dy <= hitRadius * hitRadius) return i
    }
    return -1
}
