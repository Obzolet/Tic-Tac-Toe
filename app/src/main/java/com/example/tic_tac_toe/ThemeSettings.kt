package com.example.tic_tac_toe

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Guarda la elección claro/oscuro del usuario. Mientras no elija nada, se sigue al sistema. */
object ThemeSettings {

    private const val PREFS = "settings"
    private const val KEY_NIGHT_MODE = "night_mode"

    /** Lee la preferencia guardada y la aplica a toda la app. */
    fun apply(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(saved)
    }

    /** Fuerza modo oscuro o claro, lo guarda y lo aplica (la Activity se recrea sola). */
    fun setDarkMode(context: Context, dark: Boolean) {
        val mode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NIGHT_MODE, mode)
            .apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
