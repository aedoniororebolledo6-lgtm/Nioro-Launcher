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
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Vista 1 - Launchpad: rejilla paginada a pantalla completa, como el Launchpad de macOS. */
class LaunchpadView(ctx: Context) : AppsView(ctx) {

    private val pageSp = ticker.add(SpringValue(0f, 240f, 27f))
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cols = 4
    private var rows = 5
    private var cap = 20
    private var cellW = 0f
    private var cellH = 0f
    private var top = 0f
    private var dotsY = 0f
    private var iconSize = 0f

    private var downX = 0f
    private var downY = 0f
    private var pageStart = 0f
    private var mode = 0 // 0 nada, 1 paginar, 2 cerrar/menú
    private var moved = false
    private var downIdx = -1
    private var vt: VelocityTracker? = null
    private var longFired = false
    private val longRun = Runnable {
        if (mode == 0 && !moved && downIdx in shown.indices) {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val r = RectF()
            itemRect(downIdx, r)
            listener?.onMenu(shown[downIdx], r)
        }
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = Prefs.columns
        iconSize = dp(Prefs.iconDp.toFloat() + 2f)
        top = insetTop + dp(84f)
        dotsY = h - insetBottom - dp(40f)
        val avail = dotsY - dp(20f) - top
        val minCell = iconSize + dp(36f) + dp(10f)
        rows = (avail / minCell).toInt().coerceIn(3, 8)
        cap = cols * rows
        cellW = w / cols
        cellH = avail / rows
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    override fun onDataChanged() {
        layout()
        pageSp.snap(0f)
    }

    override fun onOpen() {
        layout()
        pageSp.snap(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        invalidate()
        return false
    }

    private fun pageCount(): Int = max(1, ceil(shown.size / max(1, cap).toFloat()).toInt())

    private fun itemRect(idx: Int, out: RectF) {
        val local = idx % cap
        val col = local % cols
        val row = local / cols
        val cx = col * cellW + cellW / 2f
        val cy = top + row * cellH + cellH * 0.42f
        out.set(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
    }

    override fun onDraw(c: Canvas) {
        if (shown.isEmpty()) return
        val w = width.toFloat()
        val pv = pageSp.value
        val n = pageCount()
        val p = progress
        val first = max(0, floor(pv).toInt())
        val last = min(n - 1, ceil(pv).toInt())
        for (pg in first..last) {
            val ox = (pg - pv) * w
            val start = pg * cap
            val end = min(shown.size, start + cap)
            for (idx in start until end) {
                val local = idx - start
                val col = local % cols
                val row = local / cols
                val cx = col * cellW + cellW / 2f + ox
                val cy = top + row * cellH + cellH * 0.42f
                // entrada tipo Launchpad: los iconos del centro llegan primero
                val d = hypot(cx - w / 2f, cy - height / 2f) / w
                val q = (p - d * 0.3f) / (1f - d * 0.3f)
                val sc = lerp(1.38f, 1f, q)
                val al = clamp01(q * 1.7f)
                val e = shown[idx]
                drawIcon(c, e, cx, cy, iconSize * sc, al)
                drawLabel(c, e, cx, cy + iconSize / 2f + dp(17f) * sc, cellW - dp(8f), al)
            }
        }
        if (n > 1) {
            val gap = dp(15f)
            val x0 = w / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                val near = 1f - min(1f, abs(i - pv))
                dotPaint.color = Color.argb(((90 + 165 * near) * clamp01(p)).toInt(), 255, 255, 255)
                c.drawCircle(x0 + i * gap, dotsY, lerp(dp(3f), dp(4.4f), near), dotPaint)
            }
        }
    }

    private fun idxAt(x: Float, y: Float): Int {
        val pg = pageSp.value.roundToInt()
        val col = (x / cellW).toInt()
        val row = ((y - top) / cellH).toInt()
        if (y < top || col !in 0 until cols || row !in 0 until rows) return -1
        val i = pg * cap + row * cols + col
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
                downX = x
                downY = y
                mode = 0
                moved = false
                longFired = false
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
                downIdx = idxAt(x, y)
                handler.removeCallbacks(longRun)
                if (downIdx >= 0) handler.postDelayed(longRun, 380L)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (longFired) return true
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    handler.removeCallbacks(longRun)
                    mode = if (abs(dx) > abs(dy)) 1 else 2
                }
                if (mode == 1) {
                    val n = (pageCount() - 1).toFloat()
                    var t = pageStart - dx / width
                    if (t < 0f) t *= 0.3f
                    if (t > n) t = n + (t - n) * 0.3f
                    pageSp.snap(t)
                    invalidate()
                } else if (mode == 2 && dy > dp(110f)) {
                    mode = 3
                    listener?.onClose()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longRun)
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                vt?.addMovement(ev)
                if (mode == 1) {
                    vt?.computeCurrentVelocity(1000)
                    val v = -(vt?.xVelocity ?: 0f) / width
                    val target = (pageSp.value + v * 0.25f).roundToInt().coerceIn(0, pageCount() - 1)
                    pageSp.to(target.toFloat())
                    pageSp.velocity = v
                    ticker.kick()
                } else if (mode == 0 && !moved && !longFired && up) {
                    if (downIdx >= 0) {
                        val r = RectF()
                        itemRect(downIdx, r)
                        listener?.onLaunch(shown[downIdx], r)
                    } else {
                        listener?.onClose()
                    }
                } else if (pageSp.value != pageSp.value.roundToInt().toFloat()) {
                    pageSp.to(pageSp.value.roundToInt().toFloat())
                    ticker.kick()
                }
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }
}
