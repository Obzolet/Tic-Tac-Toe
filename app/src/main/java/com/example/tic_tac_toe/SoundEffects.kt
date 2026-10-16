package com.example.tic_tac_toe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.annotation.RawRes

/**
 * Efectos de sonido reproducidos con [MediaPlayer]:
 *  - [playMove]: al colocar una ficha (res/raw/move.wav);
 *  - [playConnected]: cuando se establece la conexión online (res/raw/connected.wav).
 * Cada efecto se prepara una sola vez y se reutiliza.
 */
class SoundEffects(context: Context) {

    private var movePlayer: MediaPlayer? = create(context, R.raw.move)
    private var connectedPlayer: MediaPlayer? = create(context, R.raw.connected)

    fun playMove() = play(movePlayer) { movePlayer = null }

    fun playConnected() = play(connectedPlayer) { connectedPlayer = null }

    /** Reproduce desde el principio (reinicia si todavía estaba sonando). */
    private fun play(player: MediaPlayer?, onBroken: () -> Unit) {
        val mp = player ?: return
        try {
            if (mp.isPlaying) mp.pause()
            mp.seekTo(0)
            mp.start()
        } catch (e: IllegalStateException) {
            // El reproductor quedó en un estado inválido: se descarta para no volver a fallar.
            mp.release()
            onBroken()
        }
    }

    fun release() {
        movePlayer?.release()
        movePlayer = null
        connectedPlayer?.release()
        connectedPlayer = null
    }

    private fun create(context: Context, @RawRes resId: Int): MediaPlayer? = MediaPlayer.create(
        context,
        resId,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        AudioManager.AUDIO_SESSION_ID_GENERATE
    )
}
