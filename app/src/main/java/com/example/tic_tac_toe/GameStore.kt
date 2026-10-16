package com.example.tic_tac_toe

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistencia del juego en [SharedPreferences]: tablero, si la computadora estaba por jugar,
 * dificultad y marcador. Así, al cerrar y volver a abrir la app todo sigue donde estaba.
 *
 * Solo se guardan las victorias de cada lado: las derrotas del jugador son las victorias de la
 * computadora y viceversa, así que los dos marcadores nunca pueden contradecirse.
 */
class GameStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var playerWins: Int = prefs.getInt(KEY_PLAYER_WINS, 0)
        private set

    var computerWins: Int = prefs.getInt(KEY_COMPUTER_WINS, 0)
        private set

    var difficulty: Difficulty =
        Difficulty.values().getOrElse(prefs.getInt(KEY_DIFFICULTY, Difficulty.HARDER.ordinal)) {
            Difficulty.HARDER
        }
        set(value) {
            field = value
            prefs.edit().putInt(KEY_DIFFICULTY, value.ordinal).apply()
        }

    fun loadBoard(): IntArray {
        val text = prefs.getString(KEY_BOARD, null)
        if (text == null || text.length != TicTacToeGame.SIZE) return IntArray(TicTacToeGame.SIZE)
        return IntArray(TicTacToeGame.SIZE) { (text[it] - '0').coerceIn(0, 2) }
    }

    fun wasComputerThinking(): Boolean = prefs.getBoolean(KEY_THINKING, false)

    /** Suma una victoria al jugador (en memoria; se escribe en el siguiente [save]). */
    fun recordPlayerWin() {
        playerWins++
    }

    /** Suma una victoria a la computadora (en memoria; se escribe en el siguiente [save]). */
    fun recordComputerWin() {
        computerWins++
    }

    /** Sala online en la que estaba el jugador (para retomarla al rotar o reabrir la app). */
    fun loadOnlineSession(): OnlineSession? {
        val code = prefs.getString(KEY_ONLINE_CODE, null) ?: return null
        val mark = prefs.getInt(KEY_ONLINE_MARK, 0)
        return if (mark == TicTacToeGame.X || mark == TicTacToeGame.O) OnlineSession(code, mark) else null
    }

    fun saveOnlineSession(session: OnlineSession) {
        prefs.edit().putString(KEY_ONLINE_CODE, session.code).putInt(KEY_ONLINE_MARK, session.mark).apply()
    }

    fun clearOnlineSession() {
        prefs.edit().remove(KEY_ONLINE_CODE).remove(KEY_ONLINE_MARK).apply()
    }

    /** Pone el marcador en 0 - 0 (en memoria; se escribe en el siguiente [save]). */
    fun resetScore() {
        playerWins = 0
        computerWins = 0
    }

    /** Guarda tablero, turno y marcador en una sola operación para que siempre sean coherentes. */
    fun save(board: IntArray, computerThinking: Boolean) {
        prefs.edit()
            .putString(KEY_BOARD, board.joinToString(""))
            .putBoolean(KEY_THINKING, computerThinking)
            .putInt(KEY_PLAYER_WINS, playerWins)
            .putInt(KEY_COMPUTER_WINS, computerWins)
            .apply()
    }

    private companion object {
        const val PREFS = "game_state"
        const val KEY_BOARD = "board"
        const val KEY_THINKING = "computer_thinking"
        const val KEY_PLAYER_WINS = "player_wins"
        const val KEY_COMPUTER_WINS = "computer_wins"
        const val KEY_DIFFICULTY = "difficulty"
        const val KEY_ONLINE_CODE = "online_code"
        const val KEY_ONLINE_MARK = "online_mark"
    }
}
