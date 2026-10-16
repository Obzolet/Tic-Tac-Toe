package com.example.tic_tac_toe

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Pantalla única del juego. El mismo código sirve para vertical y horizontal: cada orientación
 * tiene su propio layout (res/layout y res/layout-land) y el estado se conserva al rotar.
 *
 * Tiene dos modos: contra la computadora (por defecto) y online contra otra persona (opción "Online"
 * del menú). En modo online el tablero es un reflejo de la sala de Firebase; la partida local queda
 * guardada y se retoma al salir de la sala.
 */
class MainActivity : AppCompatActivity() {

    private val game = TicTacToeGame()
    private lateinit var store: GameStore
    private lateinit var room: OnlineRoom
    private var computerThinking = false

    // Modo online
    private var session: OnlineSession? = null
    private var roomState: RoomState? = null
    private var awaitingOpponent = false
    private var onlineDialog: OnlineDialog? = null

    private val handler = Handler(Looper.getMainLooper())
    private val computerMoveTask = Runnable { playComputerMove() }

    private var sounds: SoundEffects? = null
    private var menuPopup: PopupWindow? = null
    private var activeDialog: Dialog? = null

    private lateinit var boardView: BoardView
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var scorePlayer: TextView
    private lateinit var scoreComputer: TextView
    private lateinit var playAgainButton: View
    private lateinit var playerYouMark: TextView
    private lateinit var playerYouName: TextView
    private lateinit var playerOtherMark: TextView
    private lateinit var playerOtherName: TextView
    private lateinit var scoreYouLabel: TextView
    private lateinit var scoreOtherLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        setUpSystemBars(root)

        boardView = findViewById(R.id.board)
        statusTitle = findViewById(R.id.status_title)
        statusDetail = findViewById(R.id.status_detail)
        scorePlayer = findViewById(R.id.score_player)
        scoreComputer = findViewById(R.id.score_computer)
        playAgainButton = findViewById(R.id.play_again)
        playerYouMark = findViewById(R.id.player_you_mark)
        playerYouName = findViewById(R.id.player_you_name)
        playerOtherMark = findViewById(R.id.player_other_mark)
        playerOtherName = findViewById(R.id.player_other_name)
        scoreYouLabel = findViewById(R.id.score_you_label)
        scoreOtherLabel = findViewById(R.id.score_other_label)

        sounds = SoundEffects(this)
        room = OnlineRoom(this)

        // Todo el estado (tablero, turno, dificultad y marcador) se recupera del almacenamiento:
        // sirve igual para rotar la pantalla que para cerrar la app y volver a abrirla.
        store = GameStore(this)
        game.restore(store.loadBoard())
        computerThinking = store.wasComputerThinking() && !game.isOver()

        boardView.onCellClickListener = ::onCellTapped
        findViewById<View>(R.id.overflow_button).setOnClickListener { showOptionsMenu(it) }
        findViewById<View>(R.id.theme_button).setOnClickListener { toggleDarkMode() }
        playAgainButton.setOnClickListener { onPlayAgain() }

        val savedSession = store.loadOnlineSession()
        if (savedSession != null && room.isAvailable) {
            // Estaba en una sala online (rotación o app reabierta): se vuelve a conectar a ella.
            beginSession(savedSession)
        } else {
            if (savedSession != null) store.clearOnlineSession()
            render()
            // Si se cerró o rotó mientras la computadora "pensaba", se retoma su jugada.
            if (computerThinking) handler.postDelayed(computerMoveTask, COMPUTER_DELAY_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(computerMoveTask)
        room.stopObserving()
        onlineDialog?.dismissQuietly()
        onlineDialog = null
        menuPopup?.dismiss()
        activeDialog?.dismiss()
        sounds?.release()
        sounds = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- sistema

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Sol -> luna: guarda la elección y la aplica; Android recrea la pantalla y el estado se conserva. */
    private fun toggleDarkMode() {
        ThemeSettings.setDarkMode(this, dark = !isNightMode())
    }

    /** Iconos de las barras del sistema acordes al modo, y espacio para ellas también en pantalla completa. */
    private fun setUpSystemBars(root: View) {
        val lightBars = !isNightMode()
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    // ---------------------------------------------------------------- jugadas (contra la computadora)

    private fun onCellTapped(index: Int) {
        val online = session
        if (online != null) {
            onlineCellTapped(online, index)
            return
        }
        if (computerThinking || game.isOver()) return
        if (!game.play(index, TicTacToeGame.X)) return

        sounds?.playMove()
        computerThinking = !game.isOver()
        recordResultIfOver()
        render(animateIndex = index)
        if (computerThinking) handler.postDelayed(computerMoveTask, COMPUTER_DELAY_MS)
    }

    private fun playComputerMove() {
        computerThinking = false
        if (game.isOver()) {
            render()
            return
        }
        val move = game.chooseComputerMove(store.difficulty)
        game.play(move, TicTacToeGame.O)
        sounds?.playMove()
        recordResultIfOver()
        render(animateIndex = move)
    }

    /** Suma la victoria al marcador justo cuando la jugada termina la partida (una sola vez por partida). */
    private fun recordResultIfOver() {
        when (game.winner()) {
            TicTacToeGame.X -> store.recordPlayerWin()
            TicTacToeGame.O -> store.recordComputerWin()
        }
    }

    /** New Game del menú: tablero vacío y marcador de nuevo en 0 - 0. */
    private fun startFromScratch() {
        store.resetScore()
        startNewGame()
    }

    /** Play again contra la computadora: tablero vacío conservando el marcador. */
    private fun startNewGame() {
        handler.removeCallbacks(computerMoveTask)
        computerThinking = false
        game.reset()
        render()
    }

    private fun onPlayAgain() {
        if (!game.isOver()) return
        val online = session
        if (online != null) room.playAgain(online.code) else startNewGame()
    }

    // ---------------------------------------------------------------- modo online

    private val roomListener = object : OnlineRoom.Listener {
        override fun onState(state: RoomState) = handleRoomState(state)

        override fun onClosed() {
            if (session != null) endOnlineSession(R.string.online_room_closed)
        }
    }

    private val dialogListener = object : OnlineDialog.Listener {
        override fun onRoomCreated(code: String) {
            // El anfitrión ya tiene sala: se guarda y se observa; el diálogo sigue mostrando el código.
            beginSession(OnlineSession(code, TicTacToeGame.X))
            awaitingOpponent = true
        }

        override fun onRoomJoined(code: String) {
            // Conexión exitosa del invitado: sonido y acceso directo al tablero.
            onlineDialog?.dismissQuietly()
            onlineDialog = null
            beginSession(OnlineSession(code, TicTacToeGame.O))
            sounds?.playConnected()
        }

        override fun onWaitingCancelled() {
            onlineDialog = null
            awaitingOpponent = false
            leaveOnline(messageRes = null)
        }
    }

    private fun openOnlineDialog() {
        if (!room.isAvailable) {
            Toast.makeText(this, R.string.online_not_available, Toast.LENGTH_LONG).show()
            return
        }
        onlineDialog = OnlineDialog(this, room, dialogListener).also { it.show() }
    }

    /** Entra en modo online con [newSession]: se guarda (para rotar o reabrir) y se escucha la sala. */
    private fun beginSession(newSession: OnlineSession) {
        handler.removeCallbacks(computerMoveTask)
        computerThinking = false
        session = newSession
        roomState = null
        awaitingOpponent = false
        store.saveOnlineSession(newSession)
        game.reset()
        room.observe(newSession, roomListener)
        render()
    }

    private fun handleRoomState(state: RoomState) {
        val current = session ?: return
        val hadState = roomState != null
        roomState = state

        if (!state.hasOpponent) {
            // Sala sin rival (recién creada o el invitado salió): el anfitrión vuelve a esperar con el código a la vista.
            game.reset()
            if (current.isHost) showWaitingDialog(state.code)
            render()
            return
        }

        if (awaitingOpponent) {
            // El rival entró: sonido de conexión y acceso al tablero.
            awaitingOpponent = false
            onlineDialog?.dismissQuietly()
            onlineDialog = null
            sounds?.playConnected()
        }

        val before = game.snapshot()
        game.restore(state.board)
        val added = (0 until TicTacToeGame.SIZE).filter { before[it] == TicTacToeGame.EMPTY && state.board[it] != TicTacToeGame.EMPTY }
        val removed = (0 until TicTacToeGame.SIZE).any { before[it] != TicTacToeGame.EMPTY && state.board[it] == TicTacToeGame.EMPTY }
        val newMove = if (hadState && !removed && added.size == 1) added[0] else -1
        if (newMove >= 0) sounds?.playMove()
        render(animateIndex = newMove)
    }

    private fun showWaitingDialog(code: String) {
        if (onlineDialog?.isShowing == true) return
        awaitingOpponent = true
        onlineDialog = OnlineDialog(this, room, dialogListener).also { it.showWaiting(code) }
    }

    private fun onlineCellTapped(current: OnlineSession, index: Int) {
        val state = roomState ?: return
        val myTurn = state.status == OnlineRoom.STATUS_PLAYING && state.turn == current.mark
        if (myTurn && state.isOnline(opposite(current.mark)) && !game.isOver() &&
            game.markAt(index) == TicTacToeGame.EMPTY
        ) {
            room.makeMove(current.code, index, current.mark)
        }
    }

    /** Sale de la sala (la cierra o libera su sitio) y vuelve a la partida contra la computadora. */
    private fun leaveOnline(messageRes: Int?) {
        session?.let { room.leave(it) }
        endOnlineSession(messageRes)
    }

    private fun endOnlineSession(messageRes: Int?) {
        session = null
        roomState = null
        awaitingOpponent = false
        onlineDialog?.dismissQuietly()
        onlineDialog = null
        store.clearOnlineSession()

        // Se retoma la partida contra la computadora tal como estaba guardada.
        game.restore(store.loadBoard())
        computerThinking = store.wasComputerThinking() && !game.isOver()
        render()
        if (computerThinking) handler.postDelayed(computerMoveTask, COMPUTER_DELAY_MS)
        messageRes?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun opposite(mark: Int): Int = if (mark == TicTacToeGame.X) TicTacToeGame.O else TicTacToeGame.X

    // ---------------------------------------------------------------- pantalla

    /** Refleja el estado del juego en el tablero, el contador y la tarjeta de estado. */
    private fun render(animateIndex: Int = -1) {
        boardView.setMarks(game.snapshot(), animateIndex)
        boardView.countText = getString(R.string.squares_filled, game.filledCount())
        // "Play again" solo se habilita cuando alguien ganó o hubo empate.
        playAgainButton.isEnabled = game.isOver()

        val current = session
        if (current == null) renderLocal() else renderOnline(current)
    }

    private fun renderLocal() {
        updateLabels(TicTacToeGame.X, TicTacToeGame.O, R.string.player_computer)
        boardView.isEnabled = !computerThinking && !game.isOver()

        // Marcador: las derrotas de un lado son las victorias del otro.
        scorePlayer.text = getString(R.string.score_wins_losses, store.playerWins, store.computerWins)
        scoreComputer.text = getString(R.string.score_wins_losses, store.computerWins, store.playerWins)

        // Se guarda en cada cambio: si la app se cierra en cualquier momento, nada se pierde.
        store.save(game.snapshot(), computerThinking)

        val winnerMark = game.winner()
        when {
            winnerMark == TicTacToeGame.X -> showStatus(R.string.status_you_win, winningDetail(winnerMark))
            winnerMark == TicTacToeGame.O -> showStatus(R.string.status_computer_wins, winningDetail(winnerMark))
            game.isFull() -> showStatus(R.string.status_tie, getString(R.string.status_tie_detail))
            computerThinking -> showStatus(
                R.string.status_computer_turn,
                getString(R.string.status_computer_turn_detail)
            )
            else -> showStatus(R.string.status_your_turn, yourTurnDetail(TicTacToeGame.X))
        }
    }

    /** En modo online no se guarda nada de la partida local: sigue intacta en el almacenamiento. */
    private fun renderOnline(current: OnlineSession) {
        val me = current.mark
        val other = opposite(me)
        updateLabels(me, other, R.string.player_opponent)

        val state = roomState
        if (state == null || !state.hasOpponent) {
            boardView.isEnabled = false
            scorePlayer.text = getString(R.string.score_wins_losses, 0, 0)
            scoreComputer.text = getString(R.string.score_wins_losses, 0, 0)
            if (state == null) {
                showStatus(R.string.status_connecting, getString(R.string.status_connecting_detail))
            } else {
                showStatus(R.string.online_waiting, getString(R.string.online_share_code))
            }
            return
        }

        val myWins = state.scoreOf(me)
        val theirWins = state.scoreOf(other)
        scorePlayer.text = getString(R.string.score_wins_losses, myWins, theirWins)
        scoreComputer.text = getString(R.string.score_wins_losses, theirWins, myWins)

        val opponentOnline = state.isOnline(other)
        val over = game.isOver()
        boardView.isEnabled = !over && opponentOnline &&
            state.status == OnlineRoom.STATUS_PLAYING && state.turn == me

        val winnerMark = game.winner()
        when {
            winnerMark == me -> showStatus(R.string.status_you_win, winningDetail(winnerMark))
            winnerMark == other -> showStatus(R.string.status_opponent_wins, winningDetail(winnerMark))
            game.isFull() -> showStatus(R.string.status_tie, getString(R.string.status_tie_detail))
            !opponentOnline -> showStatus(
                R.string.status_opponent_offline,
                getString(R.string.status_opponent_offline_detail)
            )
            state.turn == me -> showStatus(R.string.status_your_turn, yourTurnDetail(me))
            else -> showStatus(
                R.string.status_opponent_turn,
                getString(R.string.status_opponent_turn_detail)
            )
        }
    }

    /** Etiquetas de jugadores y del marcador: "tú" con tu marca y el rival con la suya. */
    private fun updateLabels(youMark: Int, otherMark: Int, otherNameRes: Int) {
        playerYouMark.setText(markLetterRes(youMark))
        playerYouMark.setTextColor(markColor(youMark))
        playerYouName.setText(R.string.player_you)
        playerOtherMark.setText(markLetterRes(otherMark))
        playerOtherMark.setTextColor(markColor(otherMark))
        playerOtherName.setText(otherNameRes)
        scoreYouLabel.setTextColor(markColor(youMark))
        scoreOtherLabel.setText(otherNameRes)
        scoreOtherLabel.setTextColor(markColor(otherMark))
    }

    private fun markLetterRes(mark: Int): Int = if (mark == TicTacToeGame.X) R.string.mark_x else R.string.mark_o

    private fun markColor(mark: Int): Int =
        ContextCompat.getColor(this, if (mark == TicTacToeGame.X) R.color.mark_x else R.color.mark_o)

    private fun yourTurnDetail(mark: Int): String =
        getString(R.string.status_your_turn_detail, getString(markLetterRes(mark)))

    private fun showStatus(titleRes: Int, detail: String) {
        statusTitle.setText(titleRes)
        statusDetail.text = detail
    }

    private fun winningDetail(mark: Int): String {
        val letter = getString(markLetterRes(mark))
        val res = when (game.lineType(game.winningLineIndex())) {
            TicTacToeGame.LineType.ROW -> R.string.status_three_row
            TicTacToeGame.LineType.COLUMN -> R.string.status_three_column
            TicTacToeGame.LineType.DIAGONAL -> R.string.status_three_diagonal
        }
        return getString(res, letter)
    }

    // ---------------------------------------------------------------- menú y diálogos

    /** Menú de opciones (New Game / Difficulty / Online / Quit) bajo el botón de tres puntos. */
    private fun showOptionsMenu(anchor: View) {
        menuPopup?.dismiss()
        val content = layoutInflater.inflate(R.layout.popup_options, null)
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener { menuPopup = null }
        }
        menuPopup = popup

        val online = session != null
        val newGameItem = content.findViewById<View>(R.id.menu_new_game)
        val difficultyItem = content.findViewById<View>(R.id.menu_difficulty)
        val onlineItem = content.findViewById<TextView>(R.id.menu_online)

        // En una sala online no tiene sentido reiniciar la partida local ni cambiar la dificultad.
        newGameItem.visibility = if (online) View.GONE else View.VISIBLE
        difficultyItem.visibility = if (online) View.GONE else View.VISIBLE
        onlineItem.setText(if (online) R.string.menu_leave_online else R.string.menu_online)

        newGameItem.setOnClickListener {
            popup.dismiss()
            onNewGameSelected()
        }
        difficultyItem.setOnClickListener {
            popup.dismiss()
            showDifficultyDialog()
        }
        onlineItem.setOnClickListener {
            popup.dismiss()
            if (online) {
                showConfirmDialog(
                    R.string.dialog_leave_online_title,
                    R.string.dialog_leave_online_message,
                    R.string.leave
                ) { leaveOnline(messageRes = null) }
            } else {
                openOnlineDialog()
            }
        }
        content.findViewById<View>(R.id.menu_quit).setOnClickListener {
            popup.dismiss()
            showConfirmDialog(R.string.dialog_quit_title, R.string.dialog_quit_message, R.string.menu_quit) {
                session?.let { room.leave(it) }
                finish()
            }
        }

        // La tarjeta (180dp) queda justo debajo del botón; el padding de 16dp es el margen de la sombra.
        popup.showAsDropDown(anchor, dp(12), -dp(16), Gravity.END)
    }

    private fun onNewGameSelected() {
        // Se pregunta siempre que haya algo que perder: una partida a medias o un marcador distinto de 0 - 0.
        val nothingToLose = game.filledCount() == 0 && store.playerWins == 0 && store.computerWins == 0
        if (nothingToLose) {
            startFromScratch()
        } else {
            showConfirmDialog(
                R.string.dialog_new_game_title,
                R.string.dialog_new_game_message,
                R.string.menu_new_game
            ) { startFromScratch() }
        }
    }

    private fun showDifficultyDialog() {
        val labels = arrayOf(
            getString(R.string.difficulty_easy),
            getString(R.string.difficulty_harder),
            getString(R.string.difficulty_expert)
        )
        var selected = store.difficulty.ordinal
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_difficulty)
                .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ -> store.difficulty = Difficulty.values()[selected] }
        )
    }

    private fun showConfirmDialog(titleRes: Int, messageRes: Int, positiveRes: Int, onConfirm: () -> Unit) {
        showDialog(
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(positiveRes) { _, _ -> onConfirm() }
        )
    }

    private fun showDialog(builder: MaterialAlertDialogBuilder) {
        activeDialog?.dismiss()
        activeDialog = builder.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** Pausa antes de que la computadora juegue (se ve el estado "Computer’s turn"). */
        const val COMPUTER_DELAY_MS = 700L
    }
}
