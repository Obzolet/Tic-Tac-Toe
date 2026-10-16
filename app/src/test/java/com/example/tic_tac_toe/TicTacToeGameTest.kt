package com.example.tic_tac_toe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TicTacToeGameTest {

    @Test
    fun detectsRowWin() {
        val game = TicTacToeGame()
        game.play(0, TicTacToeGame.X)
        game.play(1, TicTacToeGame.X)
        game.play(2, TicTacToeGame.X)

        assertEquals(TicTacToeGame.X, game.winner())
        assertEquals(TicTacToeGame.LineType.ROW, game.lineType(game.winningLineIndex()))
        assertTrue(game.isOver())
    }

    @Test
    fun detectsDiagonalWin() {
        val game = TicTacToeGame()
        game.play(0, TicTacToeGame.X)
        game.play(4, TicTacToeGame.X)
        game.play(8, TicTacToeGame.X)

        assertEquals(TicTacToeGame.LineType.DIAGONAL, game.lineType(game.winningLineIndex()))
    }

    @Test
    fun winnerOfWorksOnRawBoards() {
        // Formato de la sala online: "board" = 9 casillas por filas (0 vacía, 1 = X, 2 = O)
        assertEquals(TicTacToeGame.X, TicTacToeGame.winnerOf(intArrayOf(1, 1, 1, 2, 2, 0, 0, 0, 0)))
        assertEquals(TicTacToeGame.O, TicTacToeGame.winnerOf(intArrayOf(1, 0, 2, 0, 2, 1, 2, 1, 1)))
        assertEquals(TicTacToeGame.EMPTY, TicTacToeGame.winnerOf(IntArray(9)))
        assertEquals(6, TicTacToeGame.winningLineOf(intArrayOf(1, 2, 0, 2, 1, 0, 0, 0, 1)))
    }

    @Test
    fun rejectsMovesOnTakenCellsAndAfterGameOver() {
        val game = TicTacToeGame()
        assertTrue(game.play(4, TicTacToeGame.X))
        assertFalse(game.play(4, TicTacToeGame.O))

        game.play(0, TicTacToeGame.X)
        game.play(8, TicTacToeGame.X)
        assertTrue(game.isOver())
        assertFalse(game.play(1, TicTacToeGame.O))
    }

    @Test
    fun harderTakesTheWinningMove() {
        val game = TicTacToeGame(Random(1))
        game.play(0, TicTacToeGame.O)
        game.play(1, TicTacToeGame.O)
        game.play(3, TicTacToeGame.X)
        game.play(4, TicTacToeGame.X)

        assertEquals(2, game.chooseComputerMove(Difficulty.HARDER))
    }

    @Test
    fun harderBlocksTheHumanWin() {
        val game = TicTacToeGame(Random(1))
        game.play(0, TicTacToeGame.X)
        game.play(1, TicTacToeGame.X)
        game.play(4, TicTacToeGame.O)

        assertEquals(2, game.chooseComputerMove(Difficulty.HARDER))
    }

    @Test
    fun expertNeverLosesToARandomPlayer() {
        val human = Random(42)
        repeat(300) { seed ->
            val game = TicTacToeGame(Random(seed))
            while (!game.isOver()) {
                val free = (0 until TicTacToeGame.SIZE).filter { game.markAt(it) == TicTacToeGame.EMPTY }
                game.play(free.random(human), TicTacToeGame.X)
                if (game.isOver()) break
                game.play(game.chooseComputerMove(Difficulty.EXPERT), TicTacToeGame.O)
            }
            assertNotEquals(TicTacToeGame.X, game.winner())
        }
    }
}
