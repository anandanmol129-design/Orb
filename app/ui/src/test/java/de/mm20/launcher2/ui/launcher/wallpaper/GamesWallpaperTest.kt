package de.mm20.launcher2.ui.launcher.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GamesWallpaperTest {
    @Test
    fun ticTacToeDetectsHumanWin() {
        assertEquals(1, ticWinner(listOf(
            1, 1, 1,
            2, 2, 0,
            0, 0, 0,
        ), 3))
    }

    @Test
    fun ticTacToeBotBlocksImmediateWin() {
        val state = GamesState(marks = listOf(
            1, 1, 0,
            0, 2, 0,
            0, 0, 0,
        ))
        val next = playTicTacToe(state, 8)
        assertEquals(2, next.marks[2])
    }

    @Test
    fun twentyFortyEightMergesEachTileOnceAndAddsScore() {
        val state = GamesState(
            game = MiniGame.Game2048,
            tiles = listOf(
                2, 2, 2, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
            ),
        )

        val moved = move2048(state, 4, Direction.Left)

        assertEquals(listOf(4, 2), moved.tiles.take(2))
        assertEquals(4, moved.score)
        assertNotEquals(state.tiles, moved.tiles)
    }

    @Test
    fun twentyFortyEightDoesNotSpawnTileWhenMoveChangesNothing() {
        val state = GamesState(
            game = MiniGame.Game2048,
            tiles = listOf(
                2, 4, 8, 16,
                32, 64, 128, 256,
                512, 1024, 2, 4,
                8, 16, 32, 64,
            ),
        )

        assertEquals(state, move2048(state, 4, Direction.Left))
    }

    @Test
    fun minesweeperPlacesRequestedMineCount() {
        val state = GamesState()
        assertEquals(10, state.mines.count { it })
    }
}
