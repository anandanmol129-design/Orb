package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingLayout
import de.mm20.launcher2.orb.MediaRingSlot
import de.mm20.launcher2.orb.pie.PieItem
import de.mm20.launcher2.ui.R
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class EditDragMode { Resize, Move, Reorder, MediaReorder }

/**
 * The single gesture layer for edit mode. One full-screen pointer handler hit-tests every touch
 * with a fixed priority — resize handle, then orb body (move), then pie slices / media controls
 * (reorder) — so the slice, media and orb gestures never fight over the same event.
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
    // Taps on the orb that leave edit mode (the count bound to "Toggle edit mode"); 0 = off.
    exitTaps: Int = 0,
    onExit: () -> Unit = {},
    mediaEnabled: Boolean = false,
    mediaLayout: MediaRingLayout = MediaRingLayout.Arc,
    mediaArc: List<MediaRingAction> = emptyList(),
    mediaSlots: Map<MediaRingSlot, MediaRingAction> = emptyMap(),
    mediaGeometry: MediaRingGeometry? = null,
    mediaRingSlotPositions: Map<MediaRingSlot, Offset> = emptyMap(),
    rewindSeconds: Int = 10,
    forwardSeconds: Int = 10,
    accent: Color? = null,
    onMediaArcChange: (List<MediaRingAction>) -> Unit = {},
    onMediaRingChange: (Map<MediaRingSlot, MediaRingAction>) -> Unit = {},
) {
    val density = LocalDensity.current
    val handlePx = with(density) { 32.dp.toPx() }
    val lineWidthPx = with(density) { 2.dp.toPx() }
    val hitRadius = with(density) { 42.dp.toPx() }
    val mediaHitRadius = with(density) { 36.dp.toPx() }
    val trashHitRadius = with(density) { 44.dp.toPx() }
    val mediaTrashHitRadius = with(density) { 56.dp.toPx() }

    val currentItems = rememberUpdatedState(items)
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
    val currentExitTaps = rememberUpdatedState(exitTaps)
    val currentOnExit = rememberUpdatedState(onExit)
    val currentOnReorder = rememberUpdatedState(onReorder)
    val currentOnRemove = rememberUpdatedState(onRemove)
    val currentMediaEnabled = rememberUpdatedState(mediaEnabled)
    val currentMediaLayout = rememberUpdatedState(mediaLayout)
    val currentMediaArc = rememberUpdatedState(mediaArc)
    val currentMediaSlots = rememberUpdatedState(mediaSlots)
    val currentMediaGeometry = rememberUpdatedState(mediaGeometry)
    val currentMediaSlotPositions = rememberUpdatedState(mediaRingSlotPositions)
    val currentOnMediaArcChange = rememberUpdatedState(onMediaArcChange)
    val currentOnMediaRingChange = rememberUpdatedState(onMediaRingChange)

    val popOut = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        popOut.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 420f))
    }

    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragMediaArcIndex by remember { mutableIntStateOf(-1) }
    var dragMediaRingSlot by remember { mutableStateOf<MediaRingSlot?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var removing by remember { mutableStateOf(false) }
    val count = items.size
    val activeArc = remember(mediaArc) { mediaArc.filter { it != MediaRingAction.None } }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Consecutive quick taps on the orb, for leaving edit mode by gesture.
                    var tapCount = 0
                    var lastTapUp = 0L
                    val multiTapWindow = viewConfiguration.doubleTapTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val c = currentCenter.value
                        val r = currentSphere.value
                        val handle = Offset(c.x, c.y - r)
                        val itemsNow = currentItems.value
                        val slice = sliceHitIndex(down.position, c, currentPieRadius.value, itemsNow.size, hitRadius)
                        val sliceDist = if (slice >= 0) {
                            (down.position - slotPosition(slice, itemsNow.size, c, currentPieRadius.value)).getDistance()
                        } else Float.MAX_VALUE

                        var hitArc = -1
                        var hitRingSlot: MediaRingSlot? = null
                        var mediaDist = Float.MAX_VALUE
                        if (currentMediaEnabled.value) {
                            when (currentMediaLayout.value) {
                                MediaRingLayout.Arc -> {
                                    val geom = currentMediaGeometry.value
                                    if (geom != null) {
                                        for (i in geom.actions.indices) {
                                            val d = (down.position - geom.position(i)).getDistance()
                                            if (d <= mediaHitRadius && d < mediaDist) {
                                                mediaDist = d
                                                hitArc = i
                                            }
                                        }
                                    }
                                }
                                MediaRingLayout.Ring -> {
                                    val slotsNow = currentMediaSlots.value
                                    val positionsNow = currentMediaSlotPositions.value
                                    for (slot in MediaRingSlot.entries) {
                                        if ((slotsNow[slot] ?: MediaRingAction.None) == MediaRingAction.None) continue
                                        val pos = positionsNow[slot] ?: continue
                                        val d = (down.position - pos).getDistance()
                                        if (d <= mediaHitRadius && d < mediaDist) {
                                            mediaDist = d
                                            hitRingSlot = slot
                                        }
                                    }
                                }
                            }
                        }
                        val hasMediaHit = hitArc >= 0 || hitRingSlot != null

                        val mode = when {
                            (down.position - handle).getDistance() < handlePx -> EditDragMode.Resize
                            (down.position - c).getDistance() < r -> EditDragMode.Move
                            hasMediaHit && (slice < 0 || mediaDist < sliceDist) -> EditDragMode.MediaReorder
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
                                if (down.uptimeMillis - lastTapUp > multiTapWindow) tapCount = 0
                                // With a tap gesture bound, the orb only moves once the finger
                                // passes touch slop, so a tap doesn't nudge it.
                                var moved = currentExitTaps.value <= 0
                                var upTime = -1L
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!moved && (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop
                                    ) {
                                        moved = true
                                    }
                                    if (moved) {
                                        val rNow = currentSphere.value
                                        val newY = change.position.y.coerceIn(
                                            currentTopInset.value + rNow,
                                            currentScreenHeight.value - currentBottomInset.value - rNow,
                                        )
                                        onCenterYChange(newY)
                                    }
                                    change.consume()
                                    if (!change.pressed) {
                                        upTime = change.uptimeMillis
                                        break
                                    }
                                }
                                val isTap = !moved && upTime >= 0 &&
                                    upTime - down.uptimeMillis <= viewConfiguration.longPressTimeoutMillis
                                if (isTap && currentExitTaps.value > 0) {
                                    tapCount++
                                    lastTapUp = upTime
                                    if (tapCount >= currentExitTaps.value) {
                                        tapCount = 0
                                        currentOnExit.value()
                                    }
                                } else {
                                    tapCount = 0
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
                                    removing = (change.position - currentCenter.value).getDistance() < currentDeadZone.value ||
                                        (change.position - currentTrashCenter.value).getDistance() < trashHitRadius
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                val released = dragIndex
                                val wasRemoving = removing
                                dragIndex = -1
                                removing = false
                                onDragStateChange(false)
                                val latestItems = currentItems.value
                                if (released in latestItems.indices) {
                                    val nearTrash = (dragPos - currentTrashCenter.value).getDistance() < trashHitRadius
                                    if (wasRemoving || nearTrash) {
                                        currentOnRemove.value(released)
                                    } else {
                                        val target = pieSelectionIndex(dragPos, c, latestItems.size, currentDeadZone.value)
                                        if (target >= 0 && target != released) {
                                            val list = latestItems.toMutableList()
                                            val moved = list.removeAt(released)
                                            list.add(target, moved)
                                            currentOnReorder.value(list)
                                        }
                                    }
                                }
                            }

                            EditDragMode.MediaReorder -> {
                                dragMediaArcIndex = hitArc
                                dragMediaRingSlot = hitRingSlot
                                dragPos = down.position
                                removing = false
                                onDragStateChange(true)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    dragPos = change.position
                                    removing = (change.position - currentTrashCenter.value).getDistance() < mediaTrashHitRadius
                                    change.consume()
                                    if (!change.pressed) break
                                }
                                val releasedArc = dragMediaArcIndex
                                val releasedSlot = dragMediaRingSlot
                                val wasRemoving = removing ||
                                    (dragPos - currentTrashCenter.value).getDistance() < mediaTrashHitRadius
                                dragMediaArcIndex = -1
                                dragMediaRingSlot = null
                                removing = false
                                onDragStateChange(false)
                                if (releasedArc >= 0) {
                                    if (wasRemoving) {
                                        currentOnMediaArcChange.value(
                                            removeMediaArcAt(currentMediaArc.value, releasedArc),
                                        )
                                    } else {
                                        val geom = currentMediaGeometry.value
                                        if (geom != null && geom.actions.isNotEmpty()) {
                                            val target = (0 until geom.actions.size)
                                                .minByOrNull { (dragPos - geom.position(it)).getDistance() }
                                                ?: releasedArc
                                            if (target != releasedArc) {
                                                currentOnMediaArcChange.value(
                                                    reorderMediaArc(currentMediaArc.value, releasedArc, target),
                                                )
                                            }
                                        }
                                    }
                                } else if (releasedSlot != null) {
                                    if (wasRemoving) {
                                        currentOnMediaRingChange.value(
                                            currentMediaSlots.value + (releasedSlot to MediaRingAction.None),
                                        )
                                    } else {
                                        val slotPos = currentMediaSlotPositions.value
                                        val targetSlot = slotPos.entries
                                            .minByOrNull { (dragPos - it.value).getDistance() }
                                            ?.key
                                        if (targetSlot != null && targetSlot != releasedSlot) {
                                            currentOnMediaRingChange.value(
                                                swapMediaRingSlots(currentMediaSlots.value, releasedSlot, targetSlot),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            val popT = popOut.value
            val popScale = (0.5f + 0.5f * popT).coerceAtLeast(0.5f)
            val popAlpha = popT.coerceIn(0f, 1f)

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
                            position = lerp(center, animated, popT),
                            sizeDp = 62.dp,
                            elevated = false,
                            scale = popScale,
                            alpha = popAlpha,
                        )
                    }
                }
            }

            if (mediaEnabled) {
                val highlight = accent ?: MaterialTheme.colorScheme.primary
                val onHighlight = if (highlight.luminance() > 0.5f) Color.Black else Color.White
                when (mediaLayout) {
                    MediaRingLayout.Arc -> {
                        val geom = mediaGeometry
                        if (geom != null && activeArc.isNotEmpty()) {
                            val indexed = activeArc.mapIndexed { origIdx, action -> origIdx to action }
                            val displayArc: List<Triple<Int, MediaRingAction, Offset>> =
                                if (dragMediaArcIndex !in indexed.indices || removing) {
                                    indexed.mapIndexed { slot, (origIdx, action) ->
                                        Triple(origIdx, action, geom.position(slot))
                                    }
                                } else {
                                    val dragged = indexed[dragMediaArcIndex]
                                    val others = indexed.toMutableList().also { it.removeAt(dragMediaArcIndex) }
                                    val insertAt = (0 until geom.actions.size)
                                        .minByOrNull { (dragPos - geom.position(it)).getDistance() }
                                        ?: dragMediaArcIndex
                                    val order = others.also { it.add(insertAt.coerceIn(0, it.size), dragged) }
                                    order.mapIndexed { slot, (origIdx, action) ->
                                        Triple(
                                            origIdx,
                                            action,
                                            if (origIdx == dragMediaArcIndex) dragPos else geom.position(slot),
                                        )
                                    }
                                }

                            displayArc.forEach { (origIdx, action, target) ->
                                val isDragged = origIdx == dragMediaArcIndex
                                if (isDragged) {
                                    val sizeDp = 66.dp
                                    val sizePx = with(density) { sizeDp.toPx() }
                                    val container = if (removing) MaterialTheme.colorScheme.errorContainer else highlight
                                    val content = if (removing) MaterialTheme.colorScheme.onErrorContainer else onHighlight
                                    val border = if (removing) MaterialTheme.colorScheme.onErrorContainer else highlight
                                    MediaRingBubble(
                                        action = action,
                                        sizeDp = sizeDp,
                                        playing = false,
                                        aodActive = false,
                                        rewindSeconds = rewindSeconds,
                                        forwardSeconds = forwardSeconds,
                                        container = container,
                                        content = content,
                                        borderColor = border,
                                        modifier = Modifier.graphicsLayer {
                                            translationX = dragPos.x - sizePx / 2f
                                            translationY = dragPos.y - sizePx / 2f
                                        },
                                    )
                                } else {
                                    key("arc-item-$origIdx") {
                                        val animated by animateOffsetAsState(
                                            targetValue = target,
                                            animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
                                            label = "media-arc-edit-$origIdx",
                                        )
                                        val popped = lerp(center, animated, popT)
                                        val sizeDp = 52.dp
                                        val sizePx = with(density) { sizeDp.toPx() }
                                        MediaRingBubble(
                                            action = action,
                                            sizeDp = sizeDp,
                                            playing = false,
                                            aodActive = false,
                                            rewindSeconds = rewindSeconds,
                                            forwardSeconds = forwardSeconds,
                                            container = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                                            content = MaterialTheme.colorScheme.onSurface,
                                            borderColor = highlight.copy(alpha = 0.55f),
                                            modifier = Modifier.graphicsLayer {
                                                translationX = popped.x - sizePx / 2f
                                                translationY = popped.y - sizePx / 2f
                                                scaleX = popScale
                                                scaleY = popScale
                                                alpha = popAlpha
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    MediaRingLayout.Ring -> {
                        val slotPositions = mediaRingSlotPositions
                        // Subtle guides for all 8 compass slots so empty drop targets are visible.
                        val emptySizeDp = 44.dp
                        val emptySizePx = with(density) { emptySizeDp.toPx() }
                        MediaRingSlot.entries.forEach { slot ->
                            val pos = slotPositions[slot] ?: return@forEach
                            val popped = lerp(center, pos, popT)
                            Box(
                                modifier = Modifier
                                    .size(emptySizeDp)
                                    .graphicsLayer {
                                        translationX = popped.x - emptySizePx / 2f
                                        translationY = popped.y - emptySizePx / 2f
                                        scaleX = popScale
                                        scaleY = popScale
                                        alpha = popAlpha
                                    }
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.32f))
                                    .border(1.dp, highlight.copy(alpha = 0.25f), CircleShape),
                            )
                        }

                        val targetSlot = if (dragMediaRingSlot != null && !removing) {
                            slotPositions.entries
                                .minByOrNull { (dragPos - it.value).getDistance() }
                                ?.key
                        } else null

                        MediaRingSlot.entries.forEach { slot ->
                            val action = mediaSlots[slot] ?: MediaRingAction.None
                            if (action == MediaRingAction.None) return@forEach
                            val isDragged = slot == dragMediaRingSlot
                            if (isDragged) {
                                val sizeDp = 66.dp
                                val sizePx = with(density) { sizeDp.toPx() }
                                val container = if (removing) MaterialTheme.colorScheme.errorContainer else highlight
                                val content = if (removing) MaterialTheme.colorScheme.onErrorContainer else onHighlight
                                val border = if (removing) MaterialTheme.colorScheme.onErrorContainer else highlight
                                MediaRingBubble(
                                    action = action,
                                    sizeDp = sizeDp,
                                    playing = false,
                                    aodActive = false,
                                    rewindSeconds = rewindSeconds,
                                    forwardSeconds = forwardSeconds,
                                    container = container,
                                    content = content,
                                    borderColor = border,
                                    modifier = Modifier.graphicsLayer {
                                        translationX = dragPos.x - sizePx / 2f
                                        translationY = dragPos.y - sizePx / 2f
                                    },
                                )
                            } else {
                                val previewSlot = if (slot == targetSlot && dragMediaRingSlot != null) {
                                    dragMediaRingSlot!!
                                } else {
                                    slot
                                }
                                val target = slotPositions[previewSlot] ?: return@forEach
                                key("ring-slot-${slot.name}") {
                                    val animated by animateOffsetAsState(
                                        targetValue = target,
                                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
                                        label = "media-ring-edit-${slot.name}",
                                    )
                                    val popped = lerp(center, animated, popT)
                                    val sizeDp = 52.dp
                                    val sizePx = with(density) { sizeDp.toPx() }
                                    MediaRingBubble(
                                        action = action,
                                        sizeDp = sizeDp,
                                        playing = false,
                                        aodActive = false,
                                        rewindSeconds = rewindSeconds,
                                        forwardSeconds = forwardSeconds,
                                        container = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
                                        content = MaterialTheme.colorScheme.onSurface,
                                        borderColor = highlight.copy(alpha = 0.55f),
                                        modifier = Modifier.graphicsLayer {
                                            translationX = popped.x - sizePx / 2f
                                            translationY = popped.y - sizePx / 2f
                                            scaleX = popScale
                                            scaleY = popScale
                                            alpha = popAlpha
                                        },
                                    )
                                }
                            }
                        }
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

        val dragHint = when {
            removing -> "Release to remove"
            dragMediaArcIndex >= 0 -> {
                val action = activeArc.getOrNull(dragMediaArcIndex)
                if (action != null) {
                    "${action.hoverLabel(playing = false, rewindSeconds = rewindSeconds, forwardSeconds = forwardSeconds)} · drag to reorder"
                } else {
                    "Drag to reorder · drag to the trash to remove"
                }
            }
            dragMediaRingSlot != null -> {
                val action = mediaSlots[dragMediaRingSlot]
                if (action != null && action != MediaRingAction.None) {
                    "${action.hoverLabel(playing = false, rewindSeconds = rewindSeconds, forwardSeconds = forwardSeconds)} · drag to a slot"
                } else {
                    "Drag to reorder · drag to the trash to remove"
                }
            }
            else -> "Drag to reorder · drag to the trash to remove"
        }

        Text(
            text = dragHint,
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
    scale: Float = 1f,
    alpha: Float = 1f,
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
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
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
