package com.shiftcal.widget

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import kotlin.math.pow

/**
 * Picks readable text colours using the WCAG contrast-ratio formula, taking into account
 * what is really behind the text: partly transparent widget layers over the wallpaper.
 */
object Contrast {

    /** WCAG relative luminance (0 = black, 1 = white). */
    fun luminance(color: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) + 0.7152 * channel(Color.green(color)) + 0.0722 * channel(Color.blue(color))
    }

    /** WCAG contrast ratio, 1 (none) – 21 (black on white). */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** White or black – whichever reads better on [background]. */
    fun bestText(background: Int): Int =
        if (ratio(Color.WHITE, background) >= ratio(Color.BLACK, background)) Color.WHITE else Color.BLACK

    /** [top] drawn at [alpha] (0–255) over an opaque [bottom]. */
    fun blend(top: Int, bottom: Int, alpha: Int): Int {
        val a = alpha / 255f
        fun mix(t: Int, b: Int) = (t * a + b * (1 - a)).toInt()
        return Color.rgb(
            mix(Color.red(top), Color.red(bottom)),
            mix(Color.green(top), Color.green(bottom)),
            mix(Color.blue(top), Color.blue(bottom)),
        )
    }

    /** Main colour of the home-screen wallpaper (no permission needed), or dark grey if unknown. */
    fun wallpaperColor(c: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                WallpaperManager.getInstance(c).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    ?.primaryColor?.toArgb()
            }.getOrNull()?.let { return it or 0xFF000000.toInt() }
        }
        return 0xFF303030.toInt()
    }

    /** The colours actually seen behind the widget body and its header bar. */
    class Backdrop(val body: Int, val header: Int)

    fun backdrop(c: Context): Backdrop {
        val wall = wallpaperColor(c)
        val body = blend(Prefs.color(c, StyleColor.BACKGROUND), wall, Prefs.bgOpacity(c) * 255 / 100)
        val header = blend(Prefs.color(c, StyleColor.HEADER), body, Prefs.headerOpacity(c) * 255 / 100)
        return Backdrop(body, header)
    }
}
