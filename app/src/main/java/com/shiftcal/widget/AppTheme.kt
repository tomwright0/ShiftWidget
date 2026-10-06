package com.shiftcal.widget

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Light / dark theme for the app's screens (not the widgets), per the setting. */
object AppTheme {
    val NAMES = arrayOf("Follow phone", "Light", "Dark")

    fun isDark(c: Context): Boolean = when (Prefs.appTheme(c)) {
        1 -> false
        2 -> true
        else -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Call before super.onCreate(). Returns whether dark was applied. */
    fun apply(a: Activity): Boolean {
        val dark = isDark(a)
        a.setTheme(if (dark) R.style.AppTheme_Dark else R.style.AppTheme_Light)
        return dark
    }
}
