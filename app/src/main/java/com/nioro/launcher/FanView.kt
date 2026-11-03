package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vista 2 - Abanico: igual que la pila "Abanico" del dock de macOS.
 * Los iconos salen del botón Launchpad del dock y suben en curva; arrastrar los desliza por el arco.
 */
class FanView(ctx: Context) : AppsView(ctx) {

    private val scroll = Scroll1D(ticker)
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var step = 0f
    private var iconSize = 0f
    private var ay = 0f
    private var ax = 0f
    private var k = 0f
    private var visibleCount = 8

    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downIdx = -1
    private var longFired = false
    private var vt: VelocityTracker? = null
    private val rectTmp = RectF()
    private val longRun = Runnable {
        if (!moved && downIdx in shown.indices) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val r = RectF()
            iconRect(downIdx, r)
            listener?.onMenu(shown[downIdx], r)
        }
    }

    init {
        labelPaint.color = Color.WHITE
        labelPaint.textSize = dp(15f)
        labelPaint.textAlign = Paint.Align.RIGHT
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        iconSize = dp(Prefs.iconDp.toFloat() - 4f)
        step = iconSize * 1.2f
        ax = if (anchorX > 0f) anchorX else w / 2f
        ay = h - insetBottom - dp(104f) - iconSize / 2f
        val usable = ay - (insetTop + dp(90f))
        visibleCount = max(3, (usable / step).toInt())
        val reach = h * 0.72f
        k = (w * 0.26f) / (reach * reach)
        scroll.min = 0f
        scroll.max = max(0, shown.size - visibleCount).toFloat() * step
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

    /** Posición de un icono: dist = distancia a lo largo del abanico desde el dock. */
    private fun posX(dist: Float): Float = ax + k * dist * dist
    private fun posY(dist: Float): Float = ay - dist

    private fun iconRect(idx: Int, out: RectF) {
        val dist = idx * step - scroll.value
        val cx = posX(dist)
        val cy = posY(dist)
        out.set(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
    }

    override fun onDraw(c: Canvas) {
        if (shown.isEmpty()) return
        val p = progress
        val topLimit = insetTop + dp(70f)
        // se dibuja de arriba (lejos) hacia abajo (cerca del dock)
        for (i in shown.size - 1 downTo 0) {
            val dist0 = i * step - scroll.value
            if (dist0 < -step * 1.4f) continue
            val cy0 = posY(dist0)
            if (cy0 < topLimit - step) continue
            val rank = max(0f, dist0 / step)
            val q = (p * 1.55f - min(rank, 12f) * 0.045f).coerceIn(0f, 1.25f)
            val dist = dist0 * q
            val cx = posX(dist)
            val cy = posY(dist)
            var al = clamp01(q * 2f)
            if (dist0 < 0f) al *= clamp01(1f + dist0 / (step * 1.2f))
            if (cy < topLimit + step) al *= clamp01((cy - topLimit) / step)
            if (al <= 0.01f) continue
            val sc = lerp(0.35f, 1f, min(1f, q))
            val e = shown[i]
            val size = iconSize * sc
            drawIcon(c, e, cx, cy, size, al)
            if (Prefs.labels && q > 0.6f) {
                val text = e.label
                val tw = labelPaint.measureText(text) + dp(26f)
                val right = cx - size / 2f - dp(8f)
                rectTmp.set(max(dp(10f), right - tw), cy - dp(15f), right, cy + dp(15f))
                glass.draw(c, rectTmp, dp(15f), al * 0.9f, false)
                labelPaint.alpha = (255 * al).toInt().coerceIn(0, 255)
                c.drawText(text, right - dp(13f), cy + dp(5.5f), labelPaint)
            }
        }
    }

    private fun idxAt(x: Float, y: Float): Int {
        for (i in 0 until shown.size) {
            val dist = i * step - scroll.value
            if (dist < -step * 0.6f) continue
            val cx = posX(dist)
            val cy = posY(dist)
            if (abs(y - cy) < step * 0.5f && x > cx - dp(220f) && x < cx + iconSize * 0.6f) return i
        }
        return -1
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
                scroll.sp.snap(scroll.sp.value)
                downX = x
                downY = y
                lastY = y
                moved = false
                longFired = false
                downIdx = idxAt(x, y)
                uiHandler.removeCallbacks(longRun)
                if (downIdx >= 0) uiHandler.postDelayed(longRun, 380L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (longFired) return true
                if (!moved && (abs(x - downX) > slop || abs(y - downY) > slop)) {
                    moved = true
                    uiHandler.removeCallbacks(longRun)
                }
                if (moved) {
                    scroll.drag(y - lastY)
                    invalidate()
                }
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                uiHandler.removeCallbacks(longRun)
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                if (moved) {
                    vt?.computeCurrentVelocity(1000)
                    scroll.release(vt?.yVelocity ?: 0f)
                    ticker.kick()
                } else if (!longFired && up) {
                    if (downIdx >= 0) {
                        val r = RectF()
                        iconRect(downIdx, r)
                        listener?.onLaunch(shown[downIdx], r)
                    } else {
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
