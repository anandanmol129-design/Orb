package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.MediaArcSize
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingLayout
import de.mm20.launcher2.orb.MediaRingSlot
import de.mm20.launcher2.ui.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the media controls sit: [actions] (only non-empty ones) at [angles] (degrees,
 * clockwise from the right) on a circle of [radius] around the pie. Controls are identified by
 * their index in [actions].
 */
data class MediaRingGeometry(
    val center: Offset,
    val radius: Float,
    val pieRadius: Float,
    val actions: List<MediaRingAction>,
    val angles: List<Float>,
) {
    fun position(index: Int): Offset {
        val a = angles[index] * PI.toFloat() / 180f
        return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
    }

    /** Half the angle between neighbouring controls, the reach of each one when picking. */
    val pickRangeDeg: Float = run {
        val sorted = angles.sorted()
        if (sorted.size < 2) return@run 22.5f
        var minGap = 360f
        for (i in sorted.indices) {
            val next = if (i + 1 < sorted.size) sorted[i + 1] else sorted[0] + 360f
            minGap = minOf(minGap, next - sorted[i])
        }
        (minGap / 2f).coerceAtMost(22.5f)
    }
}

/**
 * Lays the media controls out around [center].
 *
 * [MediaRingLayout.Arc]: the non-empty [arc] controls sit side by side, in order from left to
 * right, on an arc above the orb; if that doesn't fit on screen, below it, then beside it.
 *
 * [MediaRingLayout.Ring]: each [ring] control sits at its own direction around the orb.
 *
 * Either way, when controls would fall off screen they are spread, in order, across the widest
 * stretch of the circle that is on screen.
 */
fun mediaRingGeometry(
    center: Offset,
    pieRadius: Float,
    radius: Float,
    itemRadius: Float,
    bounds: Rect,
    layout: MediaRingLayout,
    ring: Map<MediaRingSlot, MediaRingAction>,
    arc: List<MediaRingAction>,
): MediaRingGeometry {
    fun fits(deg: Float): Boolean {
        val a = deg * PI.toFloat() / 180f
        val x = center.x + radius * cos(a)
        val y = center.y + radius * sin(a)
        return x - itemRadius >= bounds.left && x + itemRadius <= bounds.right &&
            y - itemRadius >= bounds.top && y + itemRadius <= bounds.bottom
    }
    fun geometry(actions: List<MediaRingAction>, angles: List<Float>) =
        MediaRingGeometry(center, radius, pieRadius, actions, angles)

    val actions: List<MediaRingAction>
    val preferred: List<Float>
    when (layout) {
        MediaRingLayout.Ring -> {
            val active = MediaRingSlot.entries.filter { (ring[it] ?: MediaRingAction.None) != MediaRingAction.None }
            actions = active.map { ring.getValue(it) }
            preferred = active.map { it.angleDeg }
            if (active.isEmpty() || preferred.all { fits(it) }) return geometry(actions, preferred)
        }

        MediaRingLayout.Arc -> {
            actions = arc.filter { it != MediaRingAction.None }
            if (actions.isEmpty()) return geometry(actions, emptyList())
            // Neighbours just touch: the angle a control's width takes up on the circle.
            val chord = (itemRadius * 1.7f / (2f * radius)).coerceAtMost(1f)
            val step = (2f * asin(chord) * 180f / PI.toFloat()).coerceAtLeast(12f)
            val n = actions.size
            fun arcAround(dir: Float, sign: Float) =
                List(n) { i -> dir + sign * (i - (n - 1) / 2f) * step }
            // Above (left to right), below (still left to right), right, left.
            val options = listOf(arcAround(-90f, 1f), arcAround(90f, -1f), arcAround(0f, 1f), arcAround(180f, -1f))
            options.firstOrNull { o -> o.all { fits(it) } }?.let { return geometry(actions, it) }
            preferred = options.first()
        }
    }

    return geometry(actions, spreadOnScreen(preferred, ::fits))
}

/**
 * Spreads controls whose [preferred] angles don't all fit across the widest run of on-screen
 * directions, keeping their clockwise order.
 */
private fun spreadOnScreen(preferred: List<Float>, fits: (Float) -> Boolean): List<Float> {
    // Widest run of on-screen directions, sampled every 2 degrees around the circle.
    val step = 2
    val ok = BooleanArray(360 / step) { fits(it * step.toFloat()) }
    if (ok.none { it }) return preferred
    var bestStart = 0
    var bestLen = 0
    if (ok.all { it }) {
        bestLen = ok.size
    } else {
        // Start scanning just after an off-screen direction so runs never wrap mid-count.
        val origin = ok.indexOfFirst { !it }
        var runStart = -1
        var runLen = 0
        for (k in 1..ok.size) {
            val i = (origin + k) % ok.size
            if (ok[i]) {
                if (runStart < 0) runStart = i
                runLen++
                if (runLen > bestLen) {
                    bestLen = runLen
                    bestStart = runStart
                }
            } else {
                runStart = -1
                runLen = 0
            }
        }
    }
    val arcStart = bestStart * step.toFloat()
    val arcSpan = (bestLen - 1).coerceAtLeast(0) * step.toFloat()

    // Keep the clockwise order, starting opposite the free arc so the order reads naturally
    // across it.
    val mid = arcStart + arcSpan / 2f
    val cut = mid + 180f
    val order = preferred.indices.sortedBy { ((preferred[it] - cut) % 360f + 360f) % 360f }
    val n = order.size
    val angles = FloatArray(n)
    order.forEachIndexed { rank, i -> angles[i] = arcStart + arcSpan * (rank + 0.5f) / n }
    return angles.toList()
}

/**
 * Computes the on-screen positions for all 8 [MediaRingSlot] entries around [center],
 * matching [MediaRingLayout.Ring] placement and screen-edge spreading.
 */
fun mediaRingAllSlotPositions(
    center: Offset,
    pieRadius: Float,
    radius: Float,
    itemRadius: Float,
    bounds: Rect,
): Map<MediaRingSlot, Offset> {
    val allSlots = MediaRingSlot.entries.associateWith { MediaRingAction.OpenApp }
    val geometry = mediaRingGeometry(
        center = center,
        pieRadius = pieRadius,
        radius = radius,
        itemRadius = itemRadius,
        bounds = bounds,
        layout = MediaRingLayout.Ring,
        ring = allSlots,
        arc = emptyList(),
    )
    return MediaRingSlot.entries.mapIndexed { index, slot ->
        slot to geometry.position(index)
    }.toMap()
}

/**
 * Moves the active (non-[MediaRingAction.None]) item at [fromActiveIndex] to [toActiveIndex],
 * then pads trailing slots with [MediaRingAction.None] up to [MediaArcSize].
 */
fun reorderMediaArc(
    arc: List<MediaRingAction>,
    fromActiveIndex: Int,
    toActiveIndex: Int,
): List<MediaRingAction> {
    val active = arc.filter { it != MediaRingAction.None }.toMutableList()
    if (fromActiveIndex !in active.indices || toActiveIndex !in active.indices || fromActiveIndex == toActiveIndex) {
        return List(MediaArcSize) { i -> active.getOrElse(i) { MediaRingAction.None } }
    }
    val moved = active.removeAt(fromActiveIndex)
    active.add(toActiveIndex, moved)
    return List(MediaArcSize) { i -> active.getOrElse(i) { MediaRingAction.None } }
}

/**
 * Removes the active (non-[MediaRingAction.None]) item at [activeIndex],
 * then pads trailing slots with [MediaRingAction.None] up to [MediaArcSize].
 */
fun removeMediaArcAt(
    arc: List<MediaRingAction>,
    activeIndex: Int,
): List<MediaRingAction> {
    val active = arc.filter { it != MediaRingAction.None }.toMutableList()
    if (activeIndex in active.indices) {
        active.removeAt(activeIndex)
    }
    return List(MediaArcSize) { i -> active.getOrElse(i) { MediaRingAction.None } }
}

/**
 * Swaps the actions assigned to [from] and [to] in a Ring layout map.
 */
fun swapMediaRingSlots(
    slots: Map<MediaRingSlot, MediaRingAction>,
    from: MediaRingSlot,
    to: MediaRingSlot,
): Map<MediaRingSlot, MediaRingAction> {
    if (from == to) return slots
    val fromAction = slots[from] ?: MediaRingAction.None
    val toAction = slots[to] ?: MediaRingAction.None
    return slots.toMutableMap().apply {
        put(from, toAction)
        put(to, fromAction)
    }
}

/**
 * The extra ring of media controls shown outside the pie while something is playing. Only
 * slots with an action are drawn; [selected] is the slot under the finger.
 */
@Composable
fun MediaRing(
    geometry: MediaRingGeometry,
    selected: Int?,
    playing: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    accent: Color?,
    modifier: Modifier = Modifier,
    aodActive: Boolean = false,
) {
    val density = LocalDensity.current
    val highlight = accent ?: MaterialTheme.colorScheme.primary
    val onHighlight = if (highlight.luminance() > 0.5f) Color.Black else Color.White
    Box(modifier) {
        geometry.actions.forEachIndexed { index, action ->
            val isSelected = index == selected
            val sizeDp = if (isSelected) 66.dp else 52.dp
            val sizePx = with(density) { sizeDp.toPx() }
            val p = geometry.position(index)
            val container = if (isSelected) {
                highlight
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
            }
            val content = if (isSelected) onHighlight else MaterialTheme.colorScheme.onSurface
            MediaRingBubble(
                action = action,
                sizeDp = sizeDp,
                playing = playing,
                aodActive = aodActive,
                rewindSeconds = rewindSeconds,
                forwardSeconds = forwardSeconds,
                container = container,
                content = content,
                borderColor = highlight.copy(alpha = if (isSelected) 1f else 0.55f),
                modifier = Modifier.offset {
                    IntOffset(
                        (p.x - sizePx / 2).roundToInt(),
                        (p.y - sizePx / 2).roundToInt(),
                    )
                },
            )
        }
    }
}

@Composable
internal fun MediaRingBubble(
    action: MediaRingAction,
    sizeDp: Dp,
    playing: Boolean,
    aodActive: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    container: Color,
    content: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(container)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        when (action) {
            MediaRingAction.Rewind, MediaRingAction.Forward -> Text(
                text = if (action == MediaRingAction.Rewind) "−${rewindSeconds}s" else "+${forwardSeconds}s",
                color = content,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )

            else -> Icon(
                painter = painterResource(action.iconRes(playing, aodActive)),
                contentDescription = action.label,
                tint = content,
            )
        }
    }
}

internal fun MediaRingAction.iconRes(playing: Boolean, aod: Boolean = false): Int = when (this) {
    MediaRingAction.OpenApp -> R.drawable.open_in_new_24px
    MediaRingAction.PlayPause -> if (playing) R.drawable.pause_24px_filled else R.drawable.play_arrow_24px
    MediaRingAction.Previous -> R.drawable.skip_previous_24px
    MediaRingAction.Next -> R.drawable.skip_next_24px
    MediaRingAction.Aod -> if (aod) R.drawable.light_mode_24px else R.drawable.dark_mode_24px
    else -> R.drawable.music_note_24px
}

/** The label shown above the orb while a control is under the finger. */
fun MediaRingAction.hoverLabel(
    playing: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    aod: Boolean = false,
): String =
    when (this) {
        MediaRingAction.PlayPause -> if (playing) "Pause" else "Play"
        MediaRingAction.Rewind -> "Back ${rewindSeconds}s"
        MediaRingAction.Forward -> "Forward ${forwardSeconds}s"
        MediaRingAction.Aod -> if (aod) "Exit AOD" else "AOD"
        else -> label
    }

/**
 * The media slot under [pointer], or null. A slot is picked once the finger is past the midway
 * point between the pie and the ring, within half the gap to its neighbours of the slot's angle.
 */
fun mediaRingSelection(
    pointer: Offset,
    geometry: MediaRingGeometry,
): Int? {
    val dx = pointer.x - geometry.center.x
    val dy = pointer.y - geometry.center.y
    val threshold = geometry.pieRadius + (geometry.radius - geometry.pieRadius) * 0.5f
    if (hypot(dx, dy) < threshold) return null
    val deg = atan2(dy, dx) * 180f / PI.toFloat()
    var best: Int? = null
    var bestDiff = geometry.pickRangeDeg
    geometry.angles.forEachIndexed { index, at ->
        var diff = abs(deg - at) % 360f
        if (diff > 180f) diff = 360f - diff
        if (diff <= bestDiff) {
            bestDiff = diff
            best = index
        }
    }
    return best
}

/**
 * Shown under the orb while music plays but the launcher cannot see media sessions: asks for
 * notification access, which cover art, track info and the media ring all need.
 */
@Composable
fun MediaAccessPrompt(
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f))
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.music_note_24px),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Show cover art",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextButton(onClick = onAllow) { Text("Allow") }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.close_24px),
                contentDescription = "Not now",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
