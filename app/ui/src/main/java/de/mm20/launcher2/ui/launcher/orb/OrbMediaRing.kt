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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingSlot
import de.mm20.launcher2.ui.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the media ring sits: a circle of [radius] around the pie, with each active slot at
 * [angles] (degrees, clockwise from the right). Usually that is the slot's own direction; see
 * [mediaRingGeometry] for when the orb is too close to a screen edge.
 */
data class MediaRingGeometry(
    val center: Offset,
    val radius: Float,
    val pieRadius: Float,
    val angles: Map<MediaRingSlot, Float>,
) {
    fun position(slot: MediaRingSlot): Offset {
        val a = (angles[slot] ?: slot.angleDeg) * PI.toFloat() / 180f
        return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
    }

    /** Half the angle between neighbouring controls, the reach of each one when picking. */
    val pickRangeDeg: Float = run {
        val sorted = angles.values.sorted()
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
 * Lays the ring out around [center]. Controls keep the user's arrangement whenever every one
 * of them fits inside [bounds]. When the orb sits near an edge, the ones that would fall off
 * screen are not squeezed onto the pie; instead all controls are spread, in their arrangement
 * order, across the widest stretch of the ring that is on screen.
 */
fun mediaRingGeometry(
    center: Offset,
    pieRadius: Float,
    radius: Float,
    itemRadius: Float,
    bounds: Rect,
    slots: Map<MediaRingSlot, MediaRingAction>,
): MediaRingGeometry {
    val active = MediaRingSlot.entries.filter { (slots[it] ?: MediaRingAction.None) != MediaRingAction.None }
    fun fits(deg: Float): Boolean {
        val a = deg * PI.toFloat() / 180f
        val x = center.x + radius * cos(a)
        val y = center.y + radius * sin(a)
        return x - itemRadius >= bounds.left && x + itemRadius <= bounds.right &&
            y - itemRadius >= bounds.top && y + itemRadius <= bounds.bottom
    }
    val defaults = active.associateWith { it.angleDeg }
    if (active.isEmpty() || active.all { fits(it.angleDeg) }) {
        return MediaRingGeometry(center, radius, pieRadius, defaults)
    }

    // Widest run of on-screen directions, sampled every 2 degrees around the circle.
    val step = 2
    val ok = BooleanArray(360 / step) { fits(it * step.toFloat()) }
    if (ok.none { it }) return MediaRingGeometry(center, radius, pieRadius, defaults)
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

    // Keep the user's clockwise order, starting opposite the free arc so the order reads
    // naturally across it.
    val mid = arcStart + arcSpan / 2f
    val cut = mid + 180f
    val ordered = active.sortedBy { ((it.angleDeg - cut) % 360f + 360f) % 360f }
    val n = ordered.size
    val angles = ordered.mapIndexed { i, slot ->
        slot to (arcStart + arcSpan * (i + 0.5f) / n)
    }.toMap()
    return MediaRingGeometry(center, radius, pieRadius, angles)
}

/**
 * The extra ring of media controls shown outside the pie while something is playing. Only
 * slots with an action are drawn; [selected] is the slot under the finger.
 */
@Composable
fun MediaRing(
    slots: Map<MediaRingSlot, MediaRingAction>,
    geometry: MediaRingGeometry,
    selected: MediaRingSlot?,
    playing: Boolean,
    rewindSeconds: Int,
    forwardSeconds: Int,
    accent: Color?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val highlight = accent ?: MaterialTheme.colorScheme.primary
    val onHighlight = if (highlight.luminance() > 0.5f) Color.Black else Color.White
    Box(modifier) {
        for ((slot, action) in slots) {
            if (action == MediaRingAction.None) continue
            val isSelected = slot == selected
            val sizeDp = if (isSelected) 66.dp else 52.dp
            val sizePx = with(density) { sizeDp.toPx() }
            val p = geometry.position(slot)
            val container = if (isSelected) {
                highlight
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f)
            }
            val content = if (isSelected) onHighlight else MaterialTheme.colorScheme.onSurface
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (p.x - sizePx / 2).roundToInt(),
                            (p.y - sizePx / 2).roundToInt(),
                        )
                    }
                    .size(sizeDp)
                    .clip(CircleShape)
                    .background(container)
                    .border(1.5.dp, highlight.copy(alpha = if (isSelected) 1f else 0.55f), CircleShape),
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
                        painter = painterResource(action.iconRes(playing)),
                        contentDescription = action.label,
                        tint = content,
                    )
                }
            }
        }
    }
}

private fun MediaRingAction.iconRes(playing: Boolean): Int = when (this) {
    MediaRingAction.OpenApp -> R.drawable.open_in_new_24px
    MediaRingAction.PlayPause -> if (playing) R.drawable.pause_24px_filled else R.drawable.play_arrow_24px
    MediaRingAction.Previous -> R.drawable.skip_previous_24px
    MediaRingAction.Next -> R.drawable.skip_next_24px
    else -> R.drawable.music_note_24px
}

/** The label shown above the orb while a control is under the finger. */
fun MediaRingAction.hoverLabel(playing: Boolean, rewindSeconds: Int, forwardSeconds: Int): String =
    when (this) {
        MediaRingAction.PlayPause -> if (playing) "Pause" else "Play"
        MediaRingAction.Rewind -> "Back ${rewindSeconds}s"
        MediaRingAction.Forward -> "Forward ${forwardSeconds}s"
        else -> label
    }

/**
 * The media slot under [pointer], or null. A slot is picked once the finger is past the midway
 * point between the pie and the ring, within half the gap to its neighbours of the slot's angle.
 */
fun mediaRingSelection(
    pointer: Offset,
    geometry: MediaRingGeometry,
    slots: Map<MediaRingSlot, MediaRingAction>,
): MediaRingSlot? {
    val dx = pointer.x - geometry.center.x
    val dy = pointer.y - geometry.center.y
    val threshold = geometry.pieRadius + (geometry.radius - geometry.pieRadius) * 0.5f
    if (hypot(dx, dy) < threshold) return null
    val deg = atan2(dy, dx) * 180f / PI.toFloat()
    var best: MediaRingSlot? = null
    var bestDiff = geometry.pickRangeDeg
    for ((slot, action) in slots) {
        if (action == MediaRingAction.None) continue
        val at = geometry.angles[slot] ?: continue
        var diff = abs(deg - at) % 360f
        if (diff > 180f) diff = 360f - diff
        if (diff <= bestDiff) {
            bestDiff = diff
            best = slot
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
