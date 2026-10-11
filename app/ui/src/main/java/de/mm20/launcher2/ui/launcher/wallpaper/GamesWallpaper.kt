package de.mm20.launcher2.ui.launcher.wallpaper

import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import de.mm20.launcher2.ui.R
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

internal enum class MiniGame { TicTacToe, Game2048, Sudoku, ConnectFour, Checkers, Minesweeper }
internal enum class Direction { Left, Right, Up, Down }

internal data class GamesState(
    val game: MiniGame = MiniGame.TicTacToe,
    val optionsOpen: Boolean = false,
    val optionIndex: Int = 0,
    val ticSize: Int = 3,
    val marks: List<Int> = List(9) { 0 },
    val winner: Int = 0,
    val tileSize: Int = 4,
    val tiles: List<Int> = List(16) { 0 }.withSpawnedTile().withSpawnedTile(),
    val score: Int = 0,
    val gameOver: Boolean = false,
    val sudokuSolution: List<Int> = solvedSudoku(),
    val sudokuPuzzle: List<Int> = makeSudokuPuzzle(sudokuSolution, 0),
    val sudokuValues: List<Int> = sudokuPuzzle,
    val sudokuSelection: Int = -1,
    val connectBoard: List<Int> = List(42) { 0 },
    val checkerBoard: List<Int> = initialCheckersBoard(),
    val checkerSelection: Int = -1,
    val mines: List<Boolean> = makeMines(10),
    val mineCounts: List<Int> = mineCounts(mines),
    val revealed: List<Boolean> = List(64) { false },
    val flags: List<Boolean> = List(64) { false },
)

private data class GamesLayout(
    val card: Rect,
    val previous: Rect,
    val title: Rect,
    val next: Rect,
    val optionPrevious: Rect,
    val optionValue: Rect,
    val optionNext: Rect,
    val board: Rect,
    val numberRow: Rect,
    val restart: Rect,
)

private data class CheckersMove(val from: Int, val to: Int, val capture: Boolean)

@Composable
fun GamesWallpaper(modifier: Modifier = Modifier) {
    var state by remember { mutableStateOf(GamesState()) }
    val viewportSize = remember { mutableStateOf(Size.Zero) }
    val boardPulse = remember { Animatable(1f) }
    val titleFade = remember { Animatable(1f) }
    LaunchedEffect(state) {
        boardPulse.snapTo(.965f)
        boardPulse.animateTo(1.018f, tween(105, easing = FastOutSlowInEasing))
        boardPulse.animateTo(1f, tween(145, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(state.game) {
        titleFade.snapTo(.55f)
        titleFade.animateTo(1f, tween(240, easing = FastOutSlowInEasing))
    }
    val gameNames = listOf(
        stringResource(R.string.wallpaper_games_tic_tac_toe),
        stringResource(R.string.wallpaper_games_2048),
        stringResource(R.string.wallpaper_games_sudoku),
        stringResource(R.string.wallpaper_games_connect_four),
        stringResource(R.string.wallpaper_games_checkers),
        stringResource(R.string.wallpaper_games_minesweeper),
    )
    val newGameLabel = stringResource(R.string.wallpaper_games_new_game)
    val optionsLabel = stringResource(R.string.wallpaper_games_options)
    val optionValue = optionText(state)
    val instruction = when (state.game) {
        MiniGame.TicTacToe -> stringResource(R.string.wallpaper_games_tap_square)
        MiniGame.Game2048 -> stringResource(R.string.wallpaper_games_swipe)
        MiniGame.Sudoku -> stringResource(R.string.wallpaper_games_sudoku_instruction)
        MiniGame.ConnectFour -> stringResource(R.string.wallpaper_games_connect_instruction)
        MiniGame.Checkers -> stringResource(R.string.wallpaper_games_checkers_instruction)
        MiniGame.Minesweeper -> stringResource(R.string.wallpaper_games_mines_instruction)
    }
    val status = statusText(state)
    var lastTitleTap by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        WallpaperPointer.gestures.collect { gesture ->
            val size = viewportSize.value
            if (gesture.consumed || size.width <= 0f || size.height <= 0f) return@collect
            val layout = gamesLayout(size.width, size.height, state.optionsOpen)
            val tap = abs(gesture.end.x - gesture.start.x) < 24f && abs(gesture.end.y - gesture.start.y) < 24f
            val titleDoubleTap = if (tap && layout.title.contains(gesture.end)) {
                val now = SystemClock.uptimeMillis()
                val isDouble = now - lastTitleTap in 1..350
                lastTitleTap = now
                isDouble
            } else false
            state = handleGameInput(state, layout, gesture.start, gesture.end, titleDoubleTap)
        }
    }

    Canvas(modifier = modifier.onSizeChanged { viewportSize.value = Size(it.width.toFloat(), it.height.toFloat()) }) {
        val layout = gamesLayout(size.width, size.height, state.optionsOpen)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF090D1B), Color(0xFF171A35), Color(0xFF090D1B))))
        drawCircle(Color(0x263E85FF), size.minDimension * .42f, Offset(size.width * .15f, size.height * .28f))
        drawCircle(Color(0x243A1F9A), size.minDimension * .48f, Offset(size.width * .86f, size.height * .76f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xF51B2340), Color(0xF20F172B))), layout.card.topLeft, Size(layout.card.width, layout.card.height), androidx.compose.ui.geometry.CornerRadius(34f))
        drawRoundRect(Color(0x554D6299), layout.card.topLeft, Size(layout.card.width, layout.card.height), androidx.compose.ui.geometry.CornerRadius(34f), style = Stroke(1.4f))
        drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF5EC8FF), Color(0xFF8B7BFF), Color(0xFFFFC46B))), Offset(layout.card.left + layout.card.width * .34f, layout.card.top + layout.card.height * .035f), Size(layout.card.width * .32f, 4f), androidx.compose.ui.geometry.CornerRadius(4f))

        drawArrowButton(layout.previous, "‹")
        drawText(gameNames[state.game.ordinal], layout.title.center, layout.card.width * .052f, Color(0xFFF7F8FF).copy(alpha = titleFade.value), true)
        drawArrowButton(layout.next, "›")
        if (state.optionsOpen) {
            drawText(optionsLabel, Offset(layout.card.center.x, layout.optionValue.center.y - layout.optionValue.height * .8f), layout.card.width * .027f, Color(0xFFAAB5DC), true)
            drawArrowButton(layout.optionPrevious, "‹")
            drawButton(layout.optionValue, optionValue, selected = true)
            drawArrowButton(layout.optionNext, "›")
        }

        withTransform({ scale(boardPulse.value, boardPulse.value, layout.board.center) }) {
            when (state.game) {
                MiniGame.TicTacToe -> drawTicTacToe(layout.board, state)
                MiniGame.Game2048 -> draw2048(layout.board, state)
                MiniGame.Sudoku -> {
                    drawSudoku(layout.board, state)
                    drawNumberRow(layout.numberRow)
                }
                MiniGame.ConnectFour -> drawConnectFour(layout.board, state)
                MiniGame.Checkers -> drawCheckers(layout.board, state)
                MiniGame.Minesweeper -> drawMinesweeper(layout.board, state)
            }
        }
        val statusRect = Rect(layout.card.center.x - layout.card.width * .34f, layout.card.top + layout.card.height * .78f, layout.card.center.x + layout.card.width * .34f, layout.card.top + layout.card.height * .835f)
        drawRoundedRect(Color(0x55263150), statusRect, statusRect.height / 2)
        drawText(status, statusRect.center, layout.card.width * .032f, Color(0xFFE3E9FF), true)
        drawButton(layout.restart, newGameLabel, selected = true)
        drawText(instruction, Offset(layout.card.center.x, layout.card.bottom - layout.card.height * .035f), layout.card.width * .027f, Color(0xFFB3BDD9), false)
    }
}

private fun optionText(state: GamesState): String = when (state.game) {
    MiniGame.TicTacToe -> "${state.ticSize} × ${state.ticSize}"
    MiniGame.Game2048 -> "${state.tileSize} × ${state.tileSize}"
    MiniGame.Sudoku -> listOf("Easy", "Medium", "Hard")[state.optionIndex % 3]
    MiniGame.ConnectFour -> listOf("Easy bot", "Normal bot", "Strong bot")[state.optionIndex % 3]
    MiniGame.Checkers -> listOf("Easy bot", "Normal bot", "Strong bot")[state.optionIndex % 3]
    MiniGame.Minesweeper -> listOf("Easy · 8", "Medium · 12", "Hard · 16")[state.optionIndex % 3]
}

private fun statusText(state: GamesState): String = when (state.game) {
    MiniGame.TicTacToe -> when (state.winner) {
        1 -> "YOU WIN"
        2 -> "BOT WINS"
        3 -> "DRAW"
        else -> "YOUR TURN"
    }
    MiniGame.Game2048 -> if (state.gameOver) "GAME OVER · ${state.score}" else "SCORE  ${state.score}"
    MiniGame.Sudoku -> if (state.sudokuValues == state.sudokuSolution) "PUZZLE COMPLETE" else if (state.sudokuSelection < 0) "SELECT A CELL" else "SELECT A NUMBER"
    MiniGame.ConnectFour -> when (state.winner) { 1 -> "YOU WIN"; 2 -> "BOT WINS"; 3 -> "DRAW"; else -> "YOUR TURN" }
    MiniGame.Checkers -> when (state.winner) { 1 -> "YOU WIN"; 2 -> "BOT WINS"; 3 -> "DRAW"; else -> "YOUR TURN" }
    MiniGame.Minesweeper -> if (state.gameOver) "BOOM! START AGAIN" else if (state.revealed.count { it } >= 64 - state.mines.count { it }) "CLEARED!" else "MINES LEFT  ${state.mines.count { it } - state.flags.count { it }}"
}

private fun gamesLayout(w: Float, h: Float, optionsOpen: Boolean): GamesLayout {
    val cardHeight = min(h - 24f, (h * .68f).coerceIn(580f, 1300f))
    val cardWidth = min(min(w - 32f, 900f), cardHeight * .82f).coerceAtLeast(min(w - 32f, 260f))
    val top = ((h - cardHeight) / 2f).coerceAtLeast(12f)
    val left = (w - cardWidth) / 2f
    val card = Rect(left, top, left + cardWidth, top + cardHeight)
    val arrowSize = cardWidth * .10f
    val titleWidth = cardWidth * .57f
    val titleY = top + cardHeight * .095f
    val titleHeight = cardHeight * .09f
    val title = Rect(card.center.x - titleWidth / 2, titleY, card.center.x + titleWidth / 2, titleY + titleHeight)
    val optY = top + cardHeight * .205f
    val optionHeight = cardHeight * .065f
    val optionWidth = cardWidth * .55f
    val optionValue = Rect(card.center.x - optionWidth / 2, optY, card.center.x + optionWidth / 2, optY + optionHeight)
    val optionGap = cardWidth * .025f
    val boardTop = top + cardHeight * if (optionsOpen) .31f else .235f
    val boardSize = min(cardWidth * .76f, cardHeight * if (optionsOpen) .405f else .48f)
    val board = Rect(card.center.x - boardSize / 2, boardTop, card.center.x + boardSize / 2, boardTop + boardSize)
    val restartHeight = cardHeight * .065f
    val restartWidth = cardWidth * .48f
    val restart = Rect(card.center.x - restartWidth / 2, top + cardHeight * .875f, card.center.x + restartWidth / 2, top + cardHeight * .875f + restartHeight)
    val rowWidth = cardWidth * .82f
    val numberRow = Rect(card.center.x - rowWidth / 2, board.bottom + cardHeight * .02f, card.center.x + rowWidth / 2, board.bottom + cardHeight * .075f)
    return GamesLayout(
        card = card,
        previous = Rect(title.left - arrowSize - cardWidth * .035f, title.center.y - arrowSize / 2, title.left - cardWidth * .035f, title.center.y + arrowSize / 2),
        title = title,
        next = Rect(title.right + cardWidth * .035f, title.center.y - arrowSize / 2, title.right + arrowSize + cardWidth * .035f, title.center.y + arrowSize / 2),
        optionPrevious = Rect(optionValue.left - optionHeight - optionGap, optionValue.center.y - optionHeight / 2, optionValue.left - optionGap, optionValue.center.y + optionHeight / 2),
        optionValue = optionValue,
        optionNext = Rect(optionValue.right + optionGap, optionValue.center.y - optionHeight / 2, optionValue.right + optionHeight + optionGap, optionValue.center.y + optionHeight / 2),
        board = board,
        numberRow = numberRow,
        restart = restart,
    )
}

private fun handleGameInput(state: GamesState, layout: GamesLayout, start: Offset, end: Offset, titleDoubleTap: Boolean): GamesState {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val tap = abs(dx) < 24f && abs(dy) < 24f
    if (tap) {
        if (layout.previous.contains(end)) return switchGame(state, -1)
        if (layout.next.contains(end)) return switchGame(state, 1)
        if (layout.title.contains(end)) return if (titleDoubleTap) state.copy(optionsOpen = !state.optionsOpen) else state
        if (state.optionsOpen && layout.optionPrevious.contains(end)) return cycleOption(state, -1)
        if (state.optionsOpen && layout.optionNext.contains(end)) return cycleOption(state, 1)
        if (layout.restart.contains(end)) return newRound(state)
        if (state.optionsOpen) return state
        return when (state.game) {
            MiniGame.TicTacToe -> if (state.winner == 0 && layout.board.contains(end)) playTicTacToe(state, ticCell(layout.board, end, state.ticSize)) else state
            MiniGame.Game2048 -> state
            MiniGame.Sudoku -> sudokuTap(state, layout, end)
            MiniGame.ConnectFour -> if (state.winner == 0 && layout.board.contains(end)) playConnectFour(state, ((end.x - layout.board.left) / layout.board.width * 7).toInt().coerceIn(0, 6)) else state
            MiniGame.Checkers -> if (state.winner == 0 && layout.board.contains(end)) tapCheckers(state, layout.board, end) else state
            MiniGame.Minesweeper -> if (layout.board.contains(end) && !state.gameOver) revealMineCell(state, mineCell(layout.board, end)) else state
        }
    }
    return when (state.game) {
        MiniGame.Game2048 -> move2048(state, state.tileSize, when {
            abs(dx) > abs(dy) && dx > 0 -> Direction.Right
            abs(dx) > abs(dy) -> Direction.Left
            dy > 0 -> Direction.Down
            else -> Direction.Up
        })
        else -> state
    }
}

private fun switchGame(state: GamesState, amount: Int): GamesState {
    val next = MiniGame.entries[(state.game.ordinal + amount + MiniGame.entries.size) % MiniGame.entries.size]
    return newRound(state.copy(game = next, optionsOpen = false, optionIndex = 0))
}

private fun cycleOption(state: GamesState, amount: Int): GamesState {
    val count = if (state.game == MiniGame.TicTacToe || state.game == MiniGame.Game2048) 2 else 3
    val index = (state.optionIndex + amount + count) % count
    val changed = when (state.game) {
        MiniGame.TicTacToe -> state.copy(optionIndex = index, ticSize = 3 + index)
        MiniGame.Game2048 -> state.copy(optionIndex = index, tileSize = 4 + index)
        else -> state.copy(optionIndex = index)
    }
    return newRound(changed)
}

private fun newRound(state: GamesState): GamesState = when (state.game) {
    MiniGame.TicTacToe -> state.copy(marks = List(state.ticSize * state.ticSize) { 0 }, winner = 0)
    MiniGame.Game2048 -> state.copy(tiles = List(state.tileSize * state.tileSize) { 0 }.withSpawnedTile().withSpawnedTile(), score = 0, gameOver = false)
    MiniGame.Sudoku -> {
        val puzzle = makeSudokuPuzzle(state.sudokuSolution, state.optionIndex)
        state.copy(sudokuPuzzle = puzzle, sudokuValues = puzzle, sudokuSelection = -1)
    }
    MiniGame.ConnectFour -> state.copy(connectBoard = List(42) { 0 }, winner = 0)
    MiniGame.Checkers -> state.copy(checkerBoard = initialCheckersBoard(), checkerSelection = -1, winner = 0)
    MiniGame.Minesweeper -> {
        val count = listOf(8, 12, 16)[state.optionIndex % 3]
        val mines = makeMines(count)
        state.copy(mines = mines, mineCounts = mineCounts(mines), revealed = List(64) { false }, flags = List(64) { false }, gameOver = false)
    }
}

private fun ticCell(board: Rect, point: Offset, size: Int): Int {
    val col = (((point.x - board.left) / board.width) * size).toInt().coerceIn(0, size - 1)
    val row = (((point.y - board.top) / board.height) * size).toInt().coerceIn(0, size - 1)
    return row * size + col
}

internal fun playTicTacToe(state: GamesState, index: Int): GamesState {
    if (state.marks[index] != 0) return state
    val board = state.marks.toMutableList().also { it[index] = 1 }
    val humanWin = ticWinner(board, state.ticSize)
    if (humanWin != 0) return state.copy(marks = board, winner = humanWin)
    if (board.all { it != 0 }) return state.copy(marks = board, winner = 3)
    val botMove = chooseTicMove(board, state.ticSize)
    if (botMove < 0) return state.copy(marks = board, winner = 3)
    board[botMove] = 2
    val botWin = ticWinner(board, state.ticSize)
    return state.copy(marks = board, winner = if (botWin != 0) botWin else if (board.all { it != 0 }) 3 else 0)
}

internal fun ticWinner(board: List<Int>, size: Int): Int {
    for (mark in 1..2) {
        for (row in 0 until size) if ((0 until size).all { board[row * size + it] == mark }) return mark
        for (col in 0 until size) if ((0 until size).all { board[it * size + col] == mark }) return mark
        if ((0 until size).all { board[it * size + it] == mark }) return mark
        if ((0 until size).all { board[it * size + size - it - 1] == mark }) return mark
    }
    return 0
}

private fun chooseTicMove(board: List<Int>, size: Int): Int {
    if (size == 3) return bestTicBotMove(board)
    fun winningMove(mark: Int): Int = board.indices.firstOrNull { i ->
        board[i] == 0 && board.toMutableList().also { it[i] = mark }.let { ticWinner(it, size) == mark }
    } ?: -1
    val win = winningMove(2)
    if (win >= 0) return win
    val block = winningMove(1)
    if (block >= 0) return block
    val center = (size / 2) * size + size / 2
    if (board[center] == 0) return center
    return board.indices.firstOrNull { board[it] == 0 } ?: -1
}

private fun bestTicBotMove(board: List<Int>): Int {
    var bestMove = -1
    var bestScore = Int.MIN_VALUE
    for (i in listOf(4, 0, 2, 6, 8, 1, 3, 5, 7)) {
        if (board[i] != 0) continue
        val score = ticMinimax(board.toMutableList().also { it[i] = 2 }, true, 1)
        if (score > bestScore) { bestScore = score; bestMove = i }
    }
    return bestMove
}

private fun ticMinimax(board: List<Int>, humanTurn: Boolean, depth: Int): Int {
    when (ticWinner(board, 3)) { 2 -> return 10 - depth; 1 -> return depth - 10 }
    if (board.all { it != 0 }) return 0
    var best = if (humanTurn) Int.MAX_VALUE else Int.MIN_VALUE
    board.indices.filter { board[it] == 0 }.forEach { i ->
        val next = board.toMutableList().also { it[i] = if (humanTurn) 1 else 2 }
        val score = ticMinimax(next, !humanTurn, depth + 1)
        best = if (humanTurn) min(best, score) else max(best, score)
    }
    return best
}

internal fun move2048(state: GamesState, size: Int, direction: Direction): GamesState {
    val result = state.tiles.toMutableList()
    var score = 0
    for (line in 0 until size) {
        val indices = when (direction) {
            Direction.Left -> (0 until size).map { line * size + it }
            Direction.Right -> (size - 1 downTo 0).map { line * size + it }
            Direction.Up -> (0 until size).map { it * size + line }
            Direction.Down -> (size - 1 downTo 0).map { it * size + line }
        }
        val values = indices.map { result[it] }.filter { it != 0 }
        val merged = mutableListOf<Int>()
        var i = 0
        while (i < values.size) {
            if (i + 1 < values.size && values[i] == values[i + 1]) {
                merged += values[i] * 2
                score += values[i] * 2
                i += 2
            } else { merged += values[i]; i++ }
        }
        indices.forEachIndexed { j, index -> result[index] = merged.getOrElse(j) { 0 } }
    }
    if (result == state.tiles) return state
    val spawned = result.withSpawnedTile()
    return state.copy(tiles = spawned, score = state.score + score, gameOver = !canMove2048(spawned, size))
}

private fun List<Int>.withSpawnedTile(): List<Int> {
    val empty = indices.filter { this[it] == 0 }
    if (empty.isEmpty()) return this
    return toMutableList().also { it[empty.random()] = if (Random.nextFloat() < .9f) 2 else 4 }
}

private fun canMove2048(tiles: List<Int>, size: Int): Boolean {
    if (tiles.any { it == 0 }) return true
    for (r in 0 until size) for (c in 0 until size) {
        val value = tiles[r * size + c]
        if (c + 1 < size && tiles[r * size + c + 1] == value) return true
        if (r + 1 < size && tiles[(r + 1) * size + c] == value) return true
    }
    return false
}

private fun solvedSudoku(): List<Int> = List(81) { i -> ((i / 9 * 3 + i / 27 + i % 9) % 9) + 1 }

private fun makeSudokuPuzzle(solution: List<Int>, difficulty: Int): List<Int> {
    val holes = listOf(30, 40, 50)[difficulty.coerceIn(0, 2)]
    val blank = (0 until 81).shuffled(Random(9000 + difficulty)).take(holes).toSet()
    return solution.mapIndexed { index, value -> if (index in blank) 0 else value }
}

private fun sudokuTap(state: GamesState, layout: GamesLayout, point: Offset): GamesState {
    if (layout.board.contains(point)) {
        val col = (((point.x - layout.board.left) / layout.board.width) * 9).toInt().coerceIn(0, 8)
        val row = (((point.y - layout.board.top) / layout.board.height) * 9).toInt().coerceIn(0, 8)
        return state.copy(sudokuSelection = row * 9 + col)
    }
    if (layout.numberRow.contains(point) && state.sudokuSelection >= 0 && state.sudokuPuzzle[state.sudokuSelection] == 0) {
        val number = (((point.x - layout.numberRow.left) / layout.numberRow.width) * 9).toInt().coerceIn(0, 8) + 1
        return state.copy(sudokuValues = state.sudokuValues.toMutableList().also { it[state.sudokuSelection] = number })
    }
    return state
}

private fun connectDrop(board: List<Int>, column: Int, player: Int): List<Int>? {
    val row = (5 downTo 0).firstOrNull { board[it * 7 + column] == 0 } ?: return null
    return board.toMutableList().also { it[row * 7 + column] = player }
}

private fun connectWinner(board: List<Int>): Int {
    for (r in 0 until 6) for (c in 0 until 7) {
        val player = board[r * 7 + c]
        if (player == 0) continue
        for ((dr, dc) in listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1)) {
            if ((0..3).all { k ->
                    val nr = r + dr * k
                    val nc = c + dc * k
                    nr in 0..5 && nc in 0..6 && board[nr * 7 + nc] == player
                }) return player
        }
    }
    return 0
}

private fun playConnectFour(state: GamesState, column: Int): GamesState {
    val human = connectDrop(state.connectBoard, column, 1) ?: return state
    val win = connectWinner(human)
    if (win != 0) return state.copy(connectBoard = human, winner = win)
    if (human.all { it != 0 }) return state.copy(connectBoard = human, winner = 3)
    val botColumn = chooseConnectColumn(human, state.optionIndex)
    val bot = connectDrop(human, botColumn, 2) ?: return state.copy(connectBoard = human)
    val botWin = connectWinner(bot)
    return state.copy(connectBoard = bot, winner = if (botWin != 0) botWin else if (bot.all { it != 0 }) 3 else 0)
}

private fun chooseConnectColumn(board: List<Int>, difficulty: Int): Int {
    val legal = (0..6).filter { board[it] == 0 }
    if (legal.isEmpty()) return 3
    if (difficulty == 0) return legal.random()
    legal.firstOrNull { connectDrop(board, it, 2)?.let { b -> connectWinner(b) == 2 } == true }?.let { return it }
    if (difficulty >= 2) legal.firstOrNull { connectDrop(board, it, 1)?.let { b -> connectWinner(b) == 1 } == true }?.let { return it }
    return legal.minBy { abs(it - 3) }
}

private fun initialCheckersBoard(): List<Int> = List(64) { i ->
    val row = i / 8
    val col = i % 8
    if ((row + col) % 2 == 1 && row < 3) 2 else if ((row + col) % 2 == 1 && row > 4) 1 else 0
}

private fun checkerMoves(board: List<Int>, player: Int): List<CheckersMove> {
    val captures = mutableListOf<CheckersMove>()
    val regular = mutableListOf<CheckersMove>()
    for (from in board.indices) {
        val piece = board[from]
        if (piece != player && piece != player + 2) continue
        val row = from / 8
        val col = from % 8
        val drs = if (piece >= 3) listOf(-1, 1) else if (player == 1) listOf(-1) else listOf(1)
        for (dr in drs) for (dc in listOf(-1, 1)) {
            val nr = row + dr
            val nc = col + dc
            if (nr !in 0..7 || nc !in 0..7) continue
            val to = nr * 8 + nc
            if (board[to] == 0) regular += CheckersMove(from, to, false)
            else if (board[to] != player && board[to] != player + 2) {
                val jr = row + dr * 2
                val jc = col + dc * 2
                if (jr in 0..7 && jc in 0..7 && board[jr * 8 + jc] == 0) captures += CheckersMove(from, jr * 8 + jc, true)
            }
        }
    }
    return if (captures.isNotEmpty()) captures else regular
}

private fun applyCheckerMove(board: List<Int>, move: CheckersMove): List<Int> {
    val next = board.toMutableList()
    var piece = next[move.from]
    next[move.from] = 0
    if (move.capture) next[(move.from / 8 + move.to / 8) / 2 * 8 + (move.from % 8 + move.to % 8) / 2] = 0
    if (piece == 1 && move.to / 8 == 0) piece = 3
    if (piece == 2 && move.to / 8 == 7) piece = 4
    next[move.to] = piece
    return next
}

private fun tapCheckers(state: GamesState, board: Rect, point: Offset): GamesState {
    val cellW = board.width / 8f
    val cellH = board.height / 8f
    val col = ((point.x - board.left) / cellW).toInt().coerceIn(0, 7)
    val row = ((point.y - board.top) / cellH).toInt().coerceIn(0, 7)
    val index = row * 8 + col
    if (state.checkerSelection >= 0) {
        val move = checkerMoves(state.checkerBoard, 1).firstOrNull { it.from == state.checkerSelection && it.to == index }
        if (move != null) {
            val human = applyCheckerMove(state.checkerBoard, move)
            if (checkerMoves(human, 2).isEmpty()) return state.copy(checkerBoard = human, checkerSelection = -1, winner = 1)
            val botMove = checkerMoves(human, 2).let { moves ->
                when (state.optionIndex) {
                    0 -> moves.randomOrNull()
                    else -> moves.maxByOrNull { scoreCheckerMove(it, human) }
                }
            }
            val bot = botMove?.let { applyCheckerMove(human, it) } ?: human
            val winner = if (checkerMoves(bot, 1).isEmpty()) 2 else 0
            return state.copy(checkerBoard = bot, checkerSelection = -1, winner = winner)
        }
    }
    return if (state.checkerBoard[index] == 1 || state.checkerBoard[index] == 3) state.copy(checkerSelection = index) else state.copy(checkerSelection = -1)
}

private fun scoreCheckerMove(move: CheckersMove, board: List<Int>): Int =
    (if (move.capture) 10 else 0) + (if (board[move.from] == 2 && move.to / 8 == 7) 5 else 0) + Random.nextInt(3)

private fun makeMines(count: Int): List<Boolean> {
    val selected = (0 until 64).shuffled().take(count).toSet()
    return List(64) { it in selected }
}

private fun mineCounts(mines: List<Boolean>): List<Int> = List(64) { index ->
    if (mines[index]) -1 else neighbors(index).count { mines[it] }
}

private fun neighbors(index: Int): List<Int> {
    val row = index / 8
    val col = index % 8
    return buildList {
        for (dr in -1..1) for (dc in -1..1) {
            if (dr == 0 && dc == 0) continue
            val r = row + dr
            val c = col + dc
            if (r in 0..7 && c in 0..7) add(r * 8 + c)
        }
    }
}

private fun mineCell(board: Rect, point: Offset): Int {
    val col = (((point.x - board.left) / board.width) * 8).toInt().coerceIn(0, 7)
    val row = (((point.y - board.top) / board.height) * 8).toInt().coerceIn(0, 7)
    return row * 8 + col
}

private fun revealMineCell(state: GamesState, index: Int): GamesState {
    var mines = state.mines
    var counts = state.mineCounts
    val revealedCount = state.revealed.count { it }
    if (revealedCount == 0 && mines[index]) {
        val safe = mines.indices.first { !mines[it] }
        mines = mines.toMutableList().also { it[index] = false; it[safe] = true }
        counts = mineCounts(mines)
    }
    if (mines[index]) return state.copy(mines = mines, mineCounts = counts, gameOver = true)
    val revealed = state.revealed.toMutableList()
    val queue = ArrayDeque<Int>()
    queue.add(index)
    while (queue.isNotEmpty()) {
        val cell = queue.removeFirst()
        if (revealed[cell] || mines[cell] || state.flags[cell]) continue
        revealed[cell] = true
        if (counts[cell] == 0) neighbors(cell).forEach(queue::add)
    }
    return state.copy(mines = mines, mineCounts = counts, revealed = revealed)
}

private fun DrawScope.drawTicTacToe(board: Rect, state: GamesState) {
    drawRoundedRect(Color(0xFF202846), board, 18f)
    val cell = board.width / state.ticSize
    for (i in 1 until state.ticSize) {
        drawLine(Color(0xFF59658F), Offset(board.left + i * cell, board.top + 8f), Offset(board.left + i * cell, board.bottom - 8f), 3f)
        drawLine(Color(0xFF59658F), Offset(board.left + 8f, board.top + i * cell), Offset(board.right - 8f, board.top + i * cell), 3f)
    }
    state.marks.forEachIndexed { index, mark ->
        if (mark == 0) return@forEachIndexed
        val center = Offset(board.left + (index % state.ticSize + .5f) * cell, board.top + (index / state.ticSize + .5f) * cell)
        val radius = cell * .24f
        if (mark == 1) {
            drawLine(Color(0xFF7AD7FF), Offset(center.x - radius, center.y - radius), Offset(center.x + radius, center.y + radius), max(3f, cell * .06f))
            drawLine(Color(0xFF7AD7FF), Offset(center.x + radius, center.y - radius), Offset(center.x - radius, center.y + radius), max(3f, cell * .06f))
        } else drawCircle(Color(0xFFFFC46B), radius, center, style = Stroke(max(3f, cell * .06f)))
    }
}

private fun DrawScope.draw2048(board: Rect, state: GamesState) {
    drawRoundedRect(Color(0xFF38312B), board, 16f)
    val n = state.tileSize
    val gap = board.width * .025f
    val cell = (board.width - gap * (n + 1)) / n
    state.tiles.forEachIndexed { index, value ->
        val col = index % n
        val row = index / n
        val rect = Rect(board.left + gap + col * (cell + gap), board.top + gap + row * (cell + gap), board.left + gap + col * (cell + gap) + cell, board.top + gap + row * (cell + gap) + cell)
        val color = when (value) {
            0 -> Color(0xFF51483F); 2 -> Color(0xFFECE4D8); 4 -> Color(0xFFE5D8BB); 8 -> Color(0xFFF2B179)
            16 -> Color(0xFFF59563); 32 -> Color(0xFFF67C5F); 64 -> Color(0xFFF65E3B); 128 -> Color(0xFFEDCF72)
            256 -> Color(0xFFEDCC61); 512 -> Color(0xFFEDC850); else -> Color(0xFFEDC53F)
        }
        drawRoundedRect(color, rect, 8f)
        if (value != 0) drawText(value.toString(), rect.center, cell * if (value < 100) .35f else .27f, if (value <= 4) Color(0xFF51483F) else Color.White, true)
    }
}

private fun DrawScope.drawSudoku(board: Rect, state: GamesState) {
    drawRoundedRect(Color(0xFF202846), board, 12f)
    val cell = board.width / 9f
    for (i in 0..9) {
        val weight = if (i % 3 == 0) 3f else 1f
        drawLine(Color(0xFF657299), Offset(board.left + i * cell, board.top), Offset(board.left + i * cell, board.bottom), weight)
        drawLine(Color(0xFF657299), Offset(board.left, board.top + i * cell), Offset(board.right, board.top + i * cell), weight)
    }
    for (i in 0 until 81) {
        val value = state.sudokuValues[i]
        if (value == 0) continue
        val center = Offset(board.left + (i % 9 + .5f) * cell, board.top + (i / 9 + .5f) * cell)
        val color = if (state.sudokuPuzzle[i] != 0) Color.White else Color(0xFF7AD7FF)
        drawText(value.toString(), center, cell * .55f, color, state.sudokuPuzzle[i] != 0)
    }
    if (state.sudokuSelection >= 0) {
        val i = state.sudokuSelection
        val rect = Rect(board.left + i % 9 * cell, board.top + i / 9 * cell, board.left + (i % 9 + 1) * cell, board.top + (i / 9 + 1) * cell)
        drawRect(Color(0x554F78FF), Offset(rect.left, rect.top), Size(rect.width, rect.height))
    }
}

private fun DrawScope.drawNumberRow(rect: Rect) {
    val w = rect.width / 9f
    for (i in 0..8) {
        val cell = Rect(rect.left + i * w + 2f, rect.top, rect.left + (i + 1) * w - 2f, rect.bottom)
        drawRoundedRect(Color(0xFF2B3555), cell, 7f)
        drawText((i + 1).toString(), cell.center, cell.height * .58f, Color.White, true)
    }
}

private fun DrawScope.drawConnectFour(board: Rect, state: GamesState) {
    val width = board.width * 1.15f
    val height = width * 6f / 7f
    val field = Rect(board.center.x - width / 2, board.center.y - height / 2, board.center.x + width / 2, board.center.y + height / 2)
    drawRoundedRect(Color(0xFF2C55B9), field, 18f)
    val cell = min(field.width / 7f, field.height / 6f)
    for (row in 0 until 6) for (col in 0 until 7) {
        val player = state.connectBoard[row * 7 + col]
        val color = when (player) { 1 -> Color(0xFFFF6A70); 2 -> Color(0xFFFFD36A); else -> Color(0xFF11182B) }
        drawCircle(color, cell * .39f, Offset(field.left + (col + .5f) * cell, field.top + (row + .5f) * cell))
    }
}

private fun DrawScope.drawCheckers(board: Rect, state: GamesState) {
    drawRoundedRect(Color(0xFF29263A), board, 12f)
    val cell = board.width / 8f
    for (row in 0 until 8) for (col in 0 until 8) {
        val index = row * 8 + col
        val rect = Rect(board.left + col * cell, board.top + row * cell, board.left + (col + 1) * cell, board.top + (row + 1) * cell)
        drawRect(if ((row + col) % 2 == 0) Color(0xFFE4D4B8) else Color(0xFF76614D), Offset(rect.left, rect.top), Size(cell, cell))
        val piece = state.checkerBoard[index]
        if (piece != 0) {
            val color = if (piece == 1 || piece == 3) Color(0xFFD94857) else Color(0xFF252B39)
            drawCircle(color, cell * .38f, rect.center)
            if (piece >= 3) drawCircle(Color(0xFFFFD36A), cell * .2f, rect.center, style = Stroke(3f))
        }
        if (state.checkerSelection == index) drawRect(Color(0x663E85FF), Offset(rect.left, rect.top), Size(cell, cell))
    }
}

private fun DrawScope.drawMinesweeper(board: Rect, state: GamesState) {
    drawRoundedRect(Color(0xFF151D34), board, 12f)
    val gap = 2f
    val cell = (board.width - gap * 9) / 8f
    val colors = listOf(Color.Transparent, Color(0xFF8BD6FF), Color(0xFF91D58A), Color(0xFFFF9A85), Color(0xFFD59CFF), Color(0xFFFFD36A), Color.White, Color.White, Color.White)
    for (i in 0 until 64) {
        val row = i / 8
        val col = i % 8
        val rect = Rect(board.left + gap + col * (cell + gap), board.top + gap + row * (cell + gap), board.left + gap + col * (cell + gap) + cell, board.top + gap + row * (cell + gap) + cell)
        when {
            state.revealed[i] -> {
                drawRoundedRect(Color(0xFF424A60), rect, 4f)
                if (state.mineCounts[i] == -1) drawText("✹", rect.center, cell * .55f, Color(0xFFFF6A70), true)
                else if (state.mineCounts[i] > 0) drawText(state.mineCounts[i].toString(), rect.center, cell * .55f, colors[state.mineCounts[i].coerceAtMost(8)], true)
            }
            state.flags[i] -> { drawRoundedRect(Color(0xFF384666), rect, 4f); drawText("⚑", rect.center, cell * .5f, Color(0xFFFFD36A), true) }
            else -> drawRoundedRect(Color(0xFF68728E), rect, 4f)
        }
    }
}

private fun DrawScope.drawArrowButton(rect: Rect, label: String) {
    drawRoundedRect(Color(0xAA34466F), rect, rect.height / 2)
    drawRoundRect(Color(0x665F7BB9), rect.topLeft, Size(rect.width, rect.height), androidx.compose.ui.geometry.CornerRadius(rect.height / 2), style = Stroke(1.2f))
    drawText(label, rect.center, rect.height * .7f, Color(0xFFF1F4FF), true)
}

private fun DrawScope.drawButton(rect: Rect, label: String, selected: Boolean) {
    val radius = rect.height / 2
    if (selected) drawRoundRect(Brush.horizontalGradient(listOf(Color(0xFF4E7BEE), Color(0xFF765BE8))), rect.topLeft, Size(rect.width, rect.height), androidx.compose.ui.geometry.CornerRadius(radius))
    else drawRoundedRect(Color(0xFF272E49), rect, radius)
    drawRoundRect(Color(0x556F89CB), rect.topLeft, Size(rect.width, rect.height), androidx.compose.ui.geometry.CornerRadius(radius), style = Stroke(1f))
    drawText(label, rect.center, rect.height * .34f, Color.White, true)
}

private fun DrawScope.drawRoundedRect(color: Color, rect: Rect, radius: Float) {
    drawRoundRect(color = color, topLeft = Offset(rect.left, rect.top), size = Size(rect.width, rect.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius))
}

private fun DrawScope.drawText(text: String, center: Offset, size: Float, color: Color, bold: Boolean) {
    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.nativeCanvas.drawText(text, center.x, center.y - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
