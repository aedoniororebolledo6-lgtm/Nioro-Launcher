package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vista 3 - Rejilla flotante: panel de cristal que sale del dock (pila "Rejilla" de macOS)
 * con una rejilla de iconos que se desplaza verticalmente.
 */
class GridPopupView(ctx: Context) : AppsView(ctx) {

    private val scroll = Scroll1D(ticker)
    private val panel = RectF()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nubPath = Path()
    private var cols = 4
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private var areaTop = 0f
    private var areaBottom = 0f

    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downIdx = -1
    private var longFired = false
    private var wasMoving = false
    private var vt: VelocityTracker? = null
    private val longRun = Runnable {
        if (!moved && downIdx in shown.indices) {
            longFired = true
            unpress()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val r = RectF()
            itemRect(downIdx, r)
            listener?.onMenu(shown[downIdx], r)
        }
    }

    init {
        title.color = Color.WHITE
        title.textSize = dp(19f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.setShadowLayer(dp(4f), 0f, dp(1f), 0x66000000)
        nub.color = 0x46FFFFFF
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = Prefs.columns
        iconSize = dp(Prefs.iconDp.toFloat() - 2f)
        val pw = w - dp(32f)
        cellW = (pw - dp(20f)) / cols
        cellH = iconSize + dp(40f)
        val bottom = h - insetBottom - dp(96f) - dp(14f)
        val maxH = min(h * 0.66f, h - insetTop - dp(160f))
        val rowsAll = ceil(shown.size / cols.toFloat()).toInt()
        val wanted = dp(66f) + rowsAll * cellH + dp(10f)
        val ph = min(maxH, max(dp(220f), wanted))
        panel.set(dp(16f), bottom - ph, dp(16f) + pw, bottom)
        areaTop = panel.top + dp(56f)
        areaBottom = panel.bottom - dp(8f)
        scroll.min = 0f
        scroll.max = max(0f, rowsAll * cellH - (areaBottom - areaTop) + dp(6f))
        scroll.dim = areaBottom - areaTop
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onDataChanged() {
        layout()
        scroll.jump(0f)
    }

    override fun onOpen() {
        layout()
        scroll.jump(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    private fun itemRect(idx: Int, out: RectF) {
        val col = idx % cols
        val row = idx / cols
        val cx = panel.left + dp(10f) + col * cellW + cellW / 2f
        val cy = areaTop + row * cellH + iconSize / 2f + dp(4f) - scroll.value
        out.set(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val s = lerp(0.12f, 1f, min(p, 1.06f))
        val a = clamp01(p * 1.7f)
        val ax = if (anchorX > 0f) anchorX else width / 2f
        val pivotY = panel.bottom + dp(14f)
        c.save()
        c.scale(s, s, ax, pivotY)
        glass.draw(c, panel, dp(30f), a)
        // pico que apunta al dock
        nubPath.reset()
        nubPath.moveTo(ax - dp(14f), panel.bottom - dp(1f))
        nubPath.lineTo(ax + dp(14f), panel.bottom - dp(1f))
        nubPath.lineTo(ax, panel.bottom + dp(12f))
        nubPath.close()
        nub.alpha = (70 * a).toInt()
        c.drawPath(nubPath, nub)
        title.alpha = (255 * a).toInt().coerceIn(0, 255)
        c.drawText("Aplicaciones", panel.left + dp(22f), panel.top + dp(36f), title)

        c.save()
        c.clipRect(panel.left + dp(4f), areaTop, panel.right - dp(4f), areaBottom)
        val first = max(0, ((scroll.value - dp(4f)) / cellH).toInt() * cols)
        val rect = RectF()
        var i = first
        while (i < shown.size) {
            itemRect(i, rect)
            if (rect.top > areaBottom) break
            if (rect.bottom > areaTop - cellH) {
                val e = shown[i]
                drawIcon(c, e, rect.centerX(), rect.centerY(), iconSize, a)
                drawLabel(c, e, rect.centerX(), rect.bottom + dp(16f), cellW - dp(6f), a)
            }
            i++
        }
        c.restore()
        c.restore()
    }

    private fun idxAt(x: Float, y: Float): Int {
        if (!panel.contains(x, y) || y < areaTop || y > areaBottom) return -1
        val col = ((x - panel.left - dp(10f)) / cellW).toInt()
        val row = ((y - areaTop + scroll.value) / cellH).toInt()
        if (col !in 0 until cols || row < 0) return -1
        val i = row * cols + col
        return if (i in shown.indices) i else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onTouchStart()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                wasMoving = scroll.isMoving
                scroll.stop()
                downX = x
                downY = y
                lastY = y
                moved = false
                longFired = false
                downIdx = idxAt(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downIdx >= 0) uiHandler.postDelayed(longRun, 380L)
                if (downIdx >= 0) press(shown[downIdx])
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (longFired) return true
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                unpress()
                }
                if (moved) {
                    scroll.drag(-(y - lastY))
                    invalidate()
                }
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                unpress()
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                if (moved) {
                    vt?.computeCurrentVelocity(1000)
                    scroll.release(-(vt?.yVelocity ?: 0f))
                    ticker.kick()
                } else if (wasMoving) {
                    // el toque solo frenó la inercia o el rebote: no abre nada y vuelve al borde
                    scroll.clampNow()
                    ticker.kick()
                } else if (!longFired && up) {
                    if (downIdx >= 0) {
                        val r = RectF()
                        itemRect(downIdx, r)
                        listener?.onLaunch(shown[downIdx], r)
                    } else if (!panel.contains(x, y)) {
                        listener?.onClose()
                    }
                }
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }
}
