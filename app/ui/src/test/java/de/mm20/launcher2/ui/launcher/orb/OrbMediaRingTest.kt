package de.mm20.launcher2.ui.launcher.orb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.mm20.launcher2.orb.MediaArcSize
import de.mm20.launcher2.orb.MediaRingAction
import de.mm20.launcher2.orb.MediaRingLayout
import de.mm20.launcher2.orb.MediaRingSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbMediaRingTest {

    @Test
    fun reorderMediaArc_movesActiveItemAndPadsWithNone() {
        val initial = listOf(
            MediaRingAction.PlayPause,
            MediaRingAction.Rewind,
            MediaRingAction.None,
            MediaRingAction.Next,
            MediaRingAction.Aod,
            MediaRingAction.None,
            MediaRingAction.None,
        )

        val reordered = reorderMediaArc(initial, fromActiveIndex = 3, toActiveIndex = 0)

        assertEquals(MediaArcSize, reordered.size)
        assertEquals(
            listOf(
                MediaRingAction.Aod,
                MediaRingAction.PlayPause,
                MediaRingAction.Rewind,
                MediaRingAction.Next,
                MediaRingAction.None,
                MediaRingAction.None,
                MediaRingAction.None,
            ),
            reordered,
        )
    }

    @Test
    fun removeMediaArcAt_removesActiveItemAndCompacts() {
        val initial = listOf(
            MediaRingAction.PlayPause,
            MediaRingAction.Rewind,
            MediaRingAction.Previous,
            MediaRingAction.OpenApp,
            MediaRingAction.Next,
            MediaRingAction.Forward,
            MediaRingAction.Aod,
        )

        val updated = removeMediaArcAt(initial, activeIndex = 2)

        assertEquals(MediaArcSize, updated.size)
        assertEquals(
            listOf(
                MediaRingAction.PlayPause,
                MediaRingAction.Rewind,
                MediaRingAction.OpenApp,
                MediaRingAction.Next,
                MediaRingAction.Forward,
                MediaRingAction.Aod,
                MediaRingAction.None,
            ),
            updated,
        )
    }

    @Test
    fun swapMediaRingSlots_swapsOccupiedAndEmptySlots() {
        val initial = MediaRingSlot.entries.associateWith { MediaRingAction.None } + mapOf(
            MediaRingSlot.Top to MediaRingAction.OpenApp,
            MediaRingSlot.TopRight to MediaRingAction.Aod,
        )

        val swappedOccupied = swapMediaRingSlots(initial, MediaRingSlot.Top, MediaRingSlot.TopRight)
        assertEquals(MediaRingAction.Aod, swappedOccupied[MediaRingSlot.Top])
        assertEquals(MediaRingAction.OpenApp, swappedOccupied[MediaRingSlot.TopRight])

        val movedToEmpty = swapMediaRingSlots(swappedOccupied, MediaRingSlot.Top, MediaRingSlot.BottomLeft)
        assertEquals(MediaRingAction.None, movedToEmpty[MediaRingSlot.Top])
        assertEquals(MediaRingAction.Aod, movedToEmpty[MediaRingSlot.BottomLeft])
    }

    @Test
    fun mediaRingGeometry_supportsSevenArcActionsIncludingAod() {
        val arc = listOf(
            MediaRingAction.PlayPause,
            MediaRingAction.Rewind,
            MediaRingAction.Previous,
            MediaRingAction.OpenApp,
            MediaRingAction.Next,
            MediaRingAction.Forward,
            MediaRingAction.Aod,
        )
        val geometry = mediaRingGeometry(
            center = Offset(540f, 1600f),
            pieRadius = 320f,
            radius = 520f,
            itemRadius = 80f,
            bounds = Rect(20f, 100f, 1060f, 2200f),
            layout = MediaRingLayout.Arc,
            ring = emptyMap(),
            arc = arc,
        )

        assertEquals(7, geometry.actions.size)
        assertEquals(MediaRingAction.Aod, geometry.actions.last())
        assertEquals(7, geometry.angles.size)
    }

    @Test
    fun mediaRingAllSlotPositions_returnsAllEightCompassSlots() {
        val positions = mediaRingAllSlotPositions(
            center = Offset(540f, 1200f),
            pieRadius = 300f,
            radius = 480f,
            itemRadius = 70f,
            bounds = Rect(16f, 80f, 1064f, 2200f),
        )

        assertEquals(MediaRingSlot.entries.toSet(), positions.keys)
        assertTrue(positions.getValue(MediaRingSlot.Top).y < positions.getValue(MediaRingSlot.Bottom).y)
        assertTrue(positions.getValue(MediaRingSlot.Left).x < positions.getValue(MediaRingSlot.Right).x)
    }

    @Test
    fun aodHoverLabel_togglesWithState() {
        assertEquals("AOD", MediaRingAction.Aod.hoverLabel(playing = true, rewindSeconds = 10, forwardSeconds = 10, aod = false))
        assertEquals("Exit AOD", MediaRingAction.Aod.hoverLabel(playing = true, rewindSeconds = 10, forwardSeconds = 10, aod = true))
    }
}
