package com.nioro.launcher

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs

/** Utilidades para que funcione igual en Android 10 a 14 (Samsung, Xiaomi, Pixel...). */
object Compat {

    /**
     * Pide a la pantalla su modo de mayor frecuencia (p. ej. 120 Hz en el S21) mientras
     * el launcher está visible. En modo ahorro pide 60 Hz, que sí ahorra batería de verdad.
     */
    fun applyRefreshRate(act: Activity) {
        try {
            @Suppress("DEPRECATION")
            val disp = if (Build.VERSION.SDK_INT >= 30) act.display else act.windowManager.defaultDisplay
            disp ?: return
            val cur = disp.mode
            val same = disp.supportedModes.filter {
                it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
            }
            if (same.isEmpty()) return
            val want = if (Prefs.saver) {
                same.minByOrNull { abs(it.refreshRate - 60f) } ?: cur
            } else {
                same.maxByOrNull { it.refreshRate } ?: cur
            }
            val lp = act.window.attributes
            if (lp.preferredDisplayModeId != want.modeId) {
                lp.preferredDisplayModeId = want.modeId
                act.window.attributes = lp
            }
            Screen.hz = want.refreshRate.coerceIn(30f, 165f)
        } catch (t: Throwable) {
        }
    }

    /** Dibujar detrás de las barras del sistema (pantalla completa) en cualquier versión. */
    fun edgeToEdge(act: Activity) {
        if (Build.VERSION.SDK_INT >= 30) {
            act.window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            act.window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /** (arriba, abajo) de las barras del sistema. */
    fun bars(ins: WindowInsets): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Pair(b.top, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            Pair(ins.systemWindowInsetTop, ins.systemWindowInsetBottom)
        }
    }
}
