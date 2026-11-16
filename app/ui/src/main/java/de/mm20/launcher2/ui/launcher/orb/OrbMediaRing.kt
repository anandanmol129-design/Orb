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
 * Where the media ring sits: around the pie, [rx] out to each side, [ryUp] above and [ryDown]
 * below, so it can squeeze in wherever the screen edge is close (an orb low on the screen keeps
 * a full top half and a flatter bottom half).
 */
data class MediaRingGeometry(
    val center: Offset,
    val rx: Float,
    val ryUp: Float,
    val ryDown: Float,
    val pieRadius: Float,
) {
    fun position(slot: MediaRingSlot): Offset {
        val a = slot.angleDeg * PI.toFloat() / 180f
        val s = sin(a)
        return Offset(center.x + rx * cos(a), center.y + (if (s < 0f) ryUp else ryDown) * s)
    }

    /** Distance from the centre to the ring along the direction [angle] (radians). */
    fun radiusAt(angle: Float): Float {
        val c = cos(angle) / rx
        val s = sin(angle) / (if (sin(angle) < 0f) ryUp else ryDown)
        return 1f / hypot(c, s)
    }
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
 * point between the pie and the ring, within half a slot (22.5°) of the slot's direction.
 */
fun mediaRingSelection(
    pointer: Offset,
    geometry: MediaRingGeometry,
    slots: Map<MediaRingSlot, MediaRingAction>,
): MediaRingSlot? {
    val dx = pointer.x - geometry.center.x
    val dy = pointer.y - geometry.center.y
    val angle = atan2(dy, dx)
    val dist = hypot(dx, dy)
    val ringR = geometry.radiusAt(angle)
    val threshold = geometry.pieRadius + (ringR - geometry.pieRadius) * 0.5f
    if (dist < threshold) return null
    val deg = angle * 180f / PI.toFloat()
    var best: MediaRingSlot? = null
    var bestDiff = 22.5f
    for ((slot, action) in slots) {
        if (action == MediaRingAction.None) continue
        var diff = abs(deg - slot.angleDeg) % 360f
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
