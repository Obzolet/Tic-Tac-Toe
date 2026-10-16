package com.example.tic_tac_toe

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener
import kotlin.random.Random

/** Sala en la que está jugando este dispositivo: su código de 4 dígitos y la marca asignada (X = anfitrión, O = invitado). */
data class OnlineSession(val code: String, val mark: Int) {
    val isHost: Boolean get() = mark == TicTacToeGame.X
}

/** Copia local de `rooms/{code}` (ver docs/ONLINE_PROTOCOL.md). */
class RoomState(
    val code: String,
    val status: String,
    val board: IntArray,
    val turn: Int,
    val winner: String?,
    val round: Int,
    val hostUid: String?,
    val guestUid: String?,
    val hostOnline: Boolean,
    val guestOnline: Boolean,
    val scoreX: Int,
    val scoreO: Int
) {
    val hasOpponent: Boolean get() = hostUid != null && guestUid != null
    fun uidOf(mark: Int): String? = if (mark == TicTacToeGame.X) hostUid else guestUid
    fun isOnline(mark: Int): Boolean = if (mark == TicTacToeGame.X) hostOnline else guestOnline
    fun scoreOf(mark: Int): Int = if (mark == TicTacToeGame.X) scoreX else scoreO
}

enum class JoinResult { SUCCESS, INVALID_CODE, CONNECTION_ERROR }

sealed class CreateResult {
    data class Created(val code: String) : CreateResult()
    object Failed : CreateResult()
}

/**
 * Toda la comunicación con Firebase (Authentication anónima + Realtime Database).
 * Las jugadas, el reinicio de partida y la unión a la sala son transacciones: son atómicas,
 * así que dos jugadas simultáneas o dos jugadores uniéndose a la vez nunca se pisan.
 */
class OnlineRoom(context: Context) {

    interface Listener {
        /** La sala cambió (jugada, rival entra/sale, reinicio...). */
        fun onState(state: RoomState)

        /** La sala ya no existe o este usuario ya no pertenece a ella. */
        fun onClosed()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** false si el proyecto se compiló sin google-services.json (Firebase sin configurar). */
    val isAvailable: Boolean = FirebaseApp.getApps(context.applicationContext).isNotEmpty()

    private val database: FirebaseDatabase by lazy { FirebaseDatabase.getInstance() }
    private val rooms: DatabaseReference get() = database.getReference(ROOMS)
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val uid: String? get() = auth.currentUser?.uid

    // Observación activa
    private var generation = 0
    private var observedRef: DatabaseReference? = null
    private var valueListener: ValueEventListener? = null
    private var connectedRef: DatabaseReference? = null
    private var connectedListener: ValueEventListener? = null

    // ---------------------------------------------------------------- crear y unirse

    fun isValidCode(code: String): Boolean = code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    /** Crea una sala con un código libre de 4 dígitos. */
    fun createRoom(callback: (CreateResult) -> Unit) {
        val done = once<CreateResult>(CreateResult.Failed, callback)
        ensureSignedIn { ok ->
            val me = uid
            if (!ok || me == null) done(CreateResult.Failed) else tryCreate(me, MAX_CREATE_ATTEMPTS, done)
        }
    }

    private fun tryCreate(me: String, attemptsLeft: Int, done: (CreateResult) -> Unit) {
        if (attemptsLeft == 0) {
            done(CreateResult.Failed)
            return
        }
        val code = "%04d".format(Random.nextInt(10_000))
        rooms.child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value != null) {
                    // Código ocupado, salvo que sea una sala abandonada hace mucho.
                    val created = data.child("createdAt").getValue(Long::class.java) ?: 0L
                    if (System.currentTimeMillis() - created < STALE_MS) return Transaction.abort()
                }
                data.value = mapOf(
                    "schemaVersion" to SCHEMA_VERSION,
                    "createdAt" to ServerValue.TIMESTAMP,
                    "players" to mapOf("X" to me),
                    "presence" to mapOf("X" to true),
                    "status" to STATUS_WAITING,
                    "board" to EMPTY_BOARD,
                    "turn" to "X",
                    "round" to 1,
                    "score" to mapOf("X" to 0, "O" to 0)
                )
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                when {
                    error != null -> done(CreateResult.Failed)
                    committed -> done(CreateResult.Created(code))
                    else -> tryCreate(me, attemptsLeft - 1, done) // código ocupado: se prueba otro
                }
            }
        })
    }

    /** Intenta entrar como invitado (O) a la sala [code]. */
    fun joinRoom(code: String, callback: (JoinResult) -> Unit) {
        val done = once<JoinResult>(JoinResult.CONNECTION_ERROR, callback)
        if (!isValidCode(code)) {
            done(JoinResult.INVALID_CODE)
            return
        }
        ensureSignedIn { ok ->
            val me = uid
            if (!ok || me == null) {
                done(JoinResult.CONNECTION_ERROR)
                return@ensureSignedIn
            }
            rooms.child(code).runTransaction(object : Transaction.Handler {
                override fun doTransaction(data: MutableData): Transaction.Result {
                    // Primer intento con caché vacía: se devuelve sin cambios y el SDK reintenta con el valor real.
                    if (data.value == null) return Transaction.success(data)

                    val status = data.child("status").getValue(String::class.java)
                    val host = data.child("players").child("X").getValue(String::class.java)
                    val hostOnline = data.child("presence").child("X").getValue(Boolean::class.java) == true
                    val taken = data.child("players").child("O").value != null
                    if (status != STATUS_WAITING || taken || host == null || host == me || !hostOnline) {
                        return Transaction.abort()
                    }
                    data.child("players").child("O").value = me
                    data.child("presence").child("O").value = true
                    data.child("status").value = STATUS_PLAYING
                    data.child("board").value = EMPTY_BOARD
                    data.child("turn").value = "X"
                    data.child("round").value = 1
                    data.child("score").child("X").value = 0
                    data.child("score").child("O").value = 0
                    data.child("winner").value = null
                    return Transaction.success(data)
                }

                override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                    val joined = snapshot?.child("players")?.child("O")?.getValue(String::class.java) == me
                    when {
                        error != null -> done(
                            if (error.code == DatabaseError.PERMISSION_DENIED) JoinResult.INVALID_CODE
                            else JoinResult.CONNECTION_ERROR
                        )
                        committed && joined -> done(JoinResult.SUCCESS)
                        else -> done(JoinResult.INVALID_CODE) // no existe, está llena o ya empezó
                    }
                }
            })
        }
    }

    // ---------------------------------------------------------------- observar la sala

    /** Escucha la sala en tiempo real y mantiene la presencia del jugador (online / desconectado). */
    fun observe(session: OnlineSession, listener: Listener) {
        stopObserving()
        val gen = generation
        ensureSignedIn { ok ->
            if (gen != generation) return@ensureSignedIn
            val me = uid
            if (!ok || me == null) listener.onClosed() else attach(session, me, listener)
        }
    }

    private fun attach(session: OnlineSession, me: String, listener: Listener) {
        val ref = rooms.child(session.code)
        val presence = ref.child("presence").child(markChar(session.mark))
        var roomExists = false

        fun announcePresence() {
            presence.onDisconnect().setValue(false) // si se corta la conexión, el servidor lo marca desconectado
            presence.setValue(true)
        }

        val value = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = parse(session.code, snapshot)
                if (state == null || state.uidOf(session.mark) != me) {
                    roomExists = false
                    listener.onClosed()
                    return
                }
                val first = !roomExists
                roomExists = true
                if (first) announcePresence()
                listener.onState(state)
            }

            override fun onCancelled(error: DatabaseError) {
                listener.onClosed()
            }
        }
        val connected = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // Al reconectarse tras un corte se vuelve a anunciar la presencia.
                if (snapshot.getValue(Boolean::class.java) == true && roomExists) announcePresence()
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        observedRef = ref
        valueListener = value
        connectedRef = database.getReference(".info/connected")
        connectedListener = connected
        ref.addValueEventListener(value)
        database.getReference(".info/connected").addValueEventListener(connected)
    }

    fun stopObserving() {
        generation++
        valueListener?.let { observedRef?.removeEventListener(it) }
        connectedListener?.let { connectedRef?.removeEventListener(it) }
        observedRef = null
        valueListener = null
        connectedRef = null
        connectedListener = null
    }

    // ---------------------------------------------------------------- jugar

    /** Coloca [mark] en [index] si es su turno y la casilla está libre (el resultado llega por el listener). */
    fun makeMove(code: String, index: Int, mark: Int) {
        val me = uid ?: return
        if (index !in 0 until TicTacToeGame.SIZE) return
        rooms.child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value == null) return Transaction.success(data)

                val markKey = markChar(mark)
                val status = data.child("status").getValue(String::class.java)
                val turn = data.child("turn").getValue(String::class.java)
                val board = data.child("board").getValue(String::class.java)
                val owner = data.child("players").child(markKey).getValue(String::class.java)
                if (status != STATUS_PLAYING || turn != markKey || owner != me ||
                    board == null || board.length != TicTacToeGame.SIZE || board[index] != '0'
                ) {
                    return Transaction.abort()
                }

                val cells = parseBoard(board)
                cells[index] = mark
                data.child("board").value = cells.joinToString("")

                val winner = TicTacToeGame.winnerOf(cells)
                when {
                    winner != TicTacToeGame.EMPTY -> {
                        val key = markChar(winner)
                        data.child("status").value = STATUS_FINISHED
                        data.child("winner").value = key
                        val score = data.child("score").child(key)
                        score.value = (score.getValue(Long::class.java) ?: 0L) + 1
                    }
                    cells.none { it == TicTacToeGame.EMPTY } -> {
                        data.child("status").value = STATUS_FINISHED
                        data.child("winner").value = WINNER_DRAW
                    }
                    else -> data.child("turn").value = markChar(otherMark(mark))
                }
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
        })
    }

    /** Play again: solo si la partida terminó. Empieza la ronda siguiente alternando quién abre. */
    fun playAgain(code: String) {
        rooms.child(code).runTransaction(object : Transaction.Handler {
            override fun doTransaction(data: MutableData): Transaction.Result {
                if (data.value == null) return Transaction.success(data)
                if (data.child("status").getValue(String::class.java) != STATUS_FINISHED) return Transaction.abort()

                val round = (data.child("round").getValue(Long::class.java) ?: 1L) + 1
                data.child("round").value = round
                data.child("board").value = EMPTY_BOARD
                data.child("turn").value = if (round % 2L == 1L) "X" else "O"
                data.child("status").value = STATUS_PLAYING
                data.child("winner").value = null
                return Transaction.success(data)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) = Unit
        })
    }

    /** Sale de la sala: el anfitrión la borra; el invitado libera su sitio y la sala vuelve a "waiting". */
    fun leave(session: OnlineSession) {
        stopObserving()
        if (uid == null) return
        val ref = rooms.child(session.code)
        ref.child("presence").child(markChar(session.mark)).onDisconnect().cancel()
        if (session.isHost) {
            ref.removeValue()
        } else {
            ref.updateChildren(
                mapOf(
                    "players/O" to null,
                    "presence/O" to null,
                    "status" to STATUS_WAITING
                )
            )
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun ensureSignedIn(onDone: (Boolean) -> Unit) {
        if (auth.currentUser != null) {
            onDone(true)
        } else {
            auth.signInAnonymously().addOnCompleteListener { onDone(it.isSuccessful) }
        }
    }

    /** Envuelve [callback] para que se llame una sola vez y, si Firebase no responde, falle tras [TIMEOUT_MS]. */
    private fun <T> once(onTimeout: T, callback: (T) -> Unit): (T) -> Unit {
        var finished = false
        val timeout = Runnable {
            if (!finished) {
                finished = true
                callback(onTimeout)
            }
        }
        handler.postDelayed(timeout, TIMEOUT_MS)
        return { result ->
            if (!finished) {
                finished = true
                handler.removeCallbacks(timeout)
                callback(result)
            }
        }
    }

    private fun parse(code: String, s: DataSnapshot): RoomState? {
        if (!s.exists()) return null
        val boardText = s.child("board").getValue(String::class.java) ?: return null
        if (boardText.length != TicTacToeGame.SIZE) return null
        return RoomState(
            code = code,
            status = s.child("status").getValue(String::class.java) ?: STATUS_WAITING,
            board = parseBoard(boardText),
            turn = markOf(s.child("turn").getValue(String::class.java)),
            winner = s.child("winner").getValue(String::class.java),
            round = (s.child("round").getValue(Long::class.java) ?: 1L).toInt(),
            hostUid = s.child("players").child("X").getValue(String::class.java),
            guestUid = s.child("players").child("O").getValue(String::class.java),
            hostOnline = s.child("presence").child("X").getValue(Boolean::class.java) == true,
            guestOnline = s.child("presence").child("O").getValue(Boolean::class.java) == true,
            scoreX = (s.child("score").child("X").getValue(Long::class.java) ?: 0L).toInt(),
            scoreO = (s.child("score").child("O").getValue(Long::class.java) ?: 0L).toInt()
        )
    }

    private fun parseBoard(text: String): IntArray =
        IntArray(TicTacToeGame.SIZE) { (text[it] - '0').coerceIn(0, 2) }

    private fun markChar(mark: Int): String = if (mark == TicTacToeGame.X) "X" else "O"

    private fun markOf(text: String?): Int = when (text) {
        "X" -> TicTacToeGame.X
        "O" -> TicTacToeGame.O
        else -> TicTacToeGame.EMPTY
    }

    private fun otherMark(mark: Int): Int = if (mark == TicTacToeGame.X) TicTacToeGame.O else TicTacToeGame.X

    companion object {
        const val CODE_LENGTH = 4

        private const val ROOMS = "rooms"
        private const val SCHEMA_VERSION = 1
        private const val EMPTY_BOARD = "000000000"

        const val STATUS_WAITING = "waiting"
        const val STATUS_PLAYING = "playing"
        const val STATUS_FINISHED = "finished"
        private const val WINNER_DRAW = "draw"

        private const val MAX_CREATE_ATTEMPTS = 8
        private const val TIMEOUT_MS = 10_000L

        /** Una sala con más de 6 h se considera abandonada y su código puede reutilizarse (igual en las reglas). */
        private const val STALE_MS = 6 * 60 * 60 * 1000L
    }
}
