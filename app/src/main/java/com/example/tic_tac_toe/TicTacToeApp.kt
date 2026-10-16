package com.example.tic_tac_toe

import android.app.Application

class TicTacToeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Se aplica antes de crear cualquier pantalla para que no haya parpadeo de tema.
        ThemeSettings.apply(this)
    }
}
