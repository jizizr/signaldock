package com.jizizr.signaldock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Persistent preferences that only affect the app's own UI. */
object AppUiSettingsStore {
    private const val PREFS_NAME = "app_ui_settings"
    private const val KEY_SPLASH_ANIMATION_ENABLED = "splash_animation_enabled"

    private lateinit var preferences: SharedPreferences

    fun init(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var splashAnimationEnabled: Boolean
        get() = preferences.getBoolean(KEY_SPLASH_ANIMATION_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_SPLASH_ANIMATION_ENABLED, value) }
}
