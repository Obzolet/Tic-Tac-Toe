package com.example.tic_tac_toe

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cuadro de diálogo del modo online con tres pasos:
 *  1. elegir: "Create room" o "Join room";
 *  2. sala creada: muestra el código de 4 dígitos y espera al rival;
 *  3. unirse: pide el código de 4 dígitos.
 *
 * Si no se logra conectar (código inválido, sala llena, sin red...), el cuadro se sacude, se tiñe de rojo
 * transparente durante 0.5 s y aparece un aviso en la parte inferior de la pantalla.
 */
class OnlineDialog(
    private val activity: AppCompatActivity,
    private val room: OnlineRoom,
    private val listener: Listener
) {

    interface Listener {
        /** La sala se creó: el código ya se puede compartir y el anfitrión empieza a esperar. */
        fun onRoomCreated(code: String)

        /** El invitado entró correctamente a la sala. */
        fun onRoomJoined(code: String)

        /** El anfitrión canceló mientras esperaba al rival (hay que borrar la sala). */
        fun onWaitingCancelled()
    }

    private enum class Step { CHOICE, WAITING, JOIN }

    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null
    private var quiet = false
    private var busy = false
    private var waiting = false
    private var shakeAnimator: ValueAnimator? = null

    private lateinit var title: TextView
    private lateinit var stepChoice: View
    private lateinit var stepCreate: View
    private lateinit var stepJoin: View
    private lateinit var roomCode: TextView
    private lateinit var waitingLabel: TextView
    private lateinit var codeInput: TextInputEditText
    private lateinit var overlay: View
    private lateinit var createButton: View
    private lateinit var joinButton: View
    private lateinit var joinConfirmButton: View

    val isShowing: Boolean get() = dialog?.isShowing == true

    /** Abre el diálogo en el paso de elegir. */
    fun show() {
        open()
        showStep(Step.CHOICE)
    }

    /** Abre el diálogo directamente esperando al rival (p. ej. tras rotar la pantalla). */
    fun showWaiting(code: String) {
        open()
        showWaitingStep(code)
    }

    /** Cierra el diálogo sin cancelar nada: el rival entró o la pantalla se está destruyendo. */
    fun dismissQuietly() {
        quiet = true
        shakeAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
    }

    // ---------------------------------------------------------------- construcción

    private fun open() {
        dismissQuietly()
        quiet = false
        busy = false
        waiting = false

        val view = activity.layoutInflater.inflate(R.layout.dialog_online, null)
        title = view.findViewById(R.id.online_title)
        stepChoice = view.findViewById(R.id.step_choice)
        stepCreate = view.findViewById(R.id.step_create)
        stepJoin = view.findViewById(R.id.step_join)
        roomCode = view.findViewById(R.id.room_code)
        waitingLabel = view.findViewById(R.id.waiting_label)
        codeInput = view.findViewById(R.id.code_input)
        overlay = view.findViewById(R.id.error_overlay)
        createButton = view.findViewById(R.id.button_create)
        joinButton = view.findViewById(R.id.button_join)
        joinConfirmButton = view.findViewById(R.id.button_join_confirm)

        createButton.setOnClickListener { createRoom() }
        joinButton.setOnClickListener { showStep(Step.JOIN) }
        joinConfirmButton.setOnClickListener { joinRoom() }
        view.findViewById<View>(R.id.button_choice_cancel).setOnClickListener { dialog?.dismiss() }
        view.findViewById<View>(R.id.button_waiting_cancel).setOnClickListener { dialog?.dismiss() }
        view.findViewById<View>(R.id.button_join_back).setOnClickListener { showStep(Step.CHOICE) }
        codeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                joinRoom()
                true
            } else {
                false
            }
        }

        val d = MaterialAlertDialogBuilder(activity).setView(view).create()
        d.setCanceledOnTouchOutside(false)
        d.setOnDismissListener {
            val cancelledWhileWaiting = !quiet && waiting
            waiting = false
            dialog = null
            if (cancelledWhileWaiting) listener.onWaitingCancelled()
        }
        dialog = d
        d.show()
    }

    private fun showStep(step: Step) {
        stepChoice.visibility = if (step == Step.CHOICE) View.VISIBLE else View.GONE
        stepCreate.visibility = if (step == Step.WAITING) View.VISIBLE else View.GONE
        stepJoin.visibility = if (step == Step.JOIN) View.VISIBLE else View.GONE
        if (step == Step.JOIN) {
            dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            codeInput.requestFocus()
        }
    }

    private fun showWaitingStep(code: String) {
        waiting = true
        roomCode.text = code
        waitingLabel.setText(R.string.online_waiting)
        showStep(Step.WAITING)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        createButton.isEnabled = !value
        joinButton.isEnabled = !value
        joinConfirmButton.isEnabled = !value
    }

    // ---------------------------------------------------------------- acciones

    private fun createRoom() {
        if (busy) return
        setBusy(true)
        room.createRoom { result ->
            if (dialog == null) return@createRoom // se cerró mientras se creaba
            setBusy(false)
            when (result) {
                is CreateResult.Created -> {
                    showWaitingStep(result.code)
                    listener.onRoomCreated(result.code)
                }
                CreateResult.Failed -> showError(R.string.online_error_connection)
            }
        }
    }

    private fun joinRoom() {
        if (busy) return
        val code = codeInput.text?.toString()?.trim().orEmpty()
        if (!room.isValidCode(code)) {
            showError(R.string.online_invalid_code)
            return
        }
        setBusy(true)
        room.joinRoom(code) { result ->
            if (dialog == null) return@joinRoom
            setBusy(false)
            when (result) {
                JoinResult.SUCCESS -> listener.onRoomJoined(code)
                JoinResult.INVALID_CODE -> showError(R.string.online_invalid_code)
                JoinResult.CONNECTION_ERROR -> showError(R.string.online_error_connection)
            }
        }
    }

    // ---------------------------------------------------------------- error visual

    /** Sacudida + rojo transparente durante 0.5 s + aviso inferior. */
    private fun showError(messageRes: Int) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show() // aparece en la parte inferior
        codeInput.selectAll()

        overlay.visibility = View.VISIBLE
        handler.postDelayed({ overlay.visibility = View.GONE }, ERROR_MS)
        shake()
    }

    /** Mueve la ventana del diálogo de lado a lado (oscilación amortiguada de 0.5 s). */
    private fun shake() {
        val window = dialog?.window ?: return
        shakeAnimator?.cancel()
        val amplitude = 16 * activity.resources.displayMetrics.density
        shakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ERROR_MS
            addUpdateListener {
                val t = it.animatedValue as Float
                val params = window.attributes
                params.x = (sin(t * 6 * PI) * (1 - t) * amplitude).toInt()
                window.attributes = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val params = window.attributes
                    params.x = 0
                    window.attributes = params
                }
            })
            start()
        }
    }

    private companion object {
        const val ERROR_MS = 500L
    }
}
