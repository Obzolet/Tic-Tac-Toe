package com.example.tic_tac_toe

import kotlin.random.Random

/** Niveles de dificultad de la computadora (los mismos del diálogo "Difficulty"). */
enum class Difficulty { EASY, HARDER, EXPERT }

/**
 * Lógica pura del juego (sin dependencias de Android): el humano juega con X y la computadora con O.
 * Las casillas se numeran de 0 a 8, por filas, de izquierda a derecha y de arriba abajo.
 */
class TicTacToeGame(private val random: Random = Random.Default) {

    enum class LineType { ROW, COLUMN, DIAGONAL }

    private val cells = IntArray(SIZE)

    fun snapshot(): IntArray = cells.copyOf()

    fun restore(saved: IntArray) {
        if (saved.size == SIZE) saved.copyInto(cells)
    }

    fun reset() = cells.fill(EMPTY)

    fun markAt(index: Int): Int = cells[index]

    fun filledCount(): Int = cells.count { it != EMPTY }

    fun isFull(): Boolean = filledCount() == SIZE

    fun isOver(): Boolean = winningLineIndex() >= 0 || isFull()

    /** Coloca [mark] en [index]; devuelve false si la jugada no es válida. */
    fun play(index: Int, mark: Int): Boolean {
        if (index !in 0 until SIZE || cells[index] != EMPTY || isOver()) return false
        cells[index] = mark
        return true
    }

    /** Índice (en [LINES]) de la línea ganadora, o -1 si todavía no hay ganador. */
    fun winningLineIndex(): Int = winningLineOf(cells)

    fun winner(): Int {
        val line = winningLineIndex()
        return if (line < 0) EMPTY else cells[LINES[line][0]]
    }

    fun lineType(lineIndex: Int): LineType = when {
        lineIndex < 3 -> LineType.ROW
        lineIndex < 6 -> LineType.COLUMN
        else -> LineType.DIAGONAL
    }

    /** Elige la jugada de la computadora (O) según la dificultad. Requiere al menos una casilla libre. */
    fun chooseComputerMove(difficulty: Difficulty): Int {
        val empty = emptyCells()
        require(empty.isNotEmpty()) { "No hay casillas libres" }
        return when (difficulty) {
            Difficulty.EASY -> empty.random(random)
            Difficulty.HARDER -> findWinningMove(O) ?: findWinningMove(X) ?: empty.random(random)
            Difficulty.EXPERT -> bestMove(empty)
        }
    }

    private fun emptyCells(): List<Int> = (0 until SIZE).filter { cells[it] == EMPTY }

    /** Casilla que hace ganar a [mark] en un solo movimiento, si existe. */
    private fun findWinningMove(mark: Int): Int? {
        for (i in emptyCells()) {
            cells[i] = mark
            val wins = winner() == mark
            cells[i] = EMPTY
            if (wins) return i
        }
        return null
    }

    /** Minimax completo: nunca pierde. Entre jugadas igual de buenas elige al azar. */
    private fun bestMove(empty: List<Int>): Int {
        var bestScore = Int.MIN_VALUE
        val best = mutableListOf<Int>()
        for (i in empty) {
            cells[i] = O
            val score = minimax(computerTurn = false, depth = 1)
            cells[i] = EMPTY
            if (score > bestScore) {
                bestScore = score
                best.clear()
                best.add(i)
            } else if (score == bestScore) {
                best.add(i)
            }
        }
        return best.random(random)
    }

    private fun minimax(computerTurn: Boolean, depth: Int): Int {
        when (winner()) {
            O -> return 10 - depth
            X -> return depth - 10
        }
        if (isFull()) return 0

        var best = if (computerTurn) Int.MIN_VALUE else Int.MAX_VALUE
        for (i in 0 until SIZE) {
            if (cells[i] != EMPTY) continue
            cells[i] = if (computerTurn) O else X
            val score = minimax(!computerTurn, depth + 1)
            cells[i] = EMPTY
            best = if (computerTurn) maxOf(best, score) else minOf(best, score)
        }
        return best
    }

    companion object {
        const val SIZE = 9
        const val EMPTY = 0
        const val X = 1
        const val O = 2

        /** Índice (en [LINES]) de la línea ganadora de [board], o -1. Se usa también en el modo online. */
        fun winningLineOf(board: IntArray): Int {
            for ((i, line) in LINES.withIndex()) {
                val mark = board[line[0]]
                if (mark != EMPTY && mark == board[line[1]] && mark == board[line[2]]) return i
            }
            return -1
        }

        /** Marca ganadora de [board] ([X] u [O]) o [EMPTY] si no hay ganador. */
        fun winnerOf(board: IntArray): Int {
            val line = winningLineOf(board)
            return if (line < 0) EMPTY else board[LINES[line][0]]
        }

        /** 3 filas, 3 columnas y 2 diagonales (en ese orden: lo usa [lineType]). */
        val LINES: Array<IntArray> = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
            intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
            intArrayOf(0, 4, 8), intArrayOf(2, 4, 6)
        )
    }
}
