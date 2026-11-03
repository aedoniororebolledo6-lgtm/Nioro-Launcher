package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Drawable de cristal para vistas normales (barra de búsqueda, tarjetas de Ajustes). */
class GlassDrawable(private val radiusDp: Float, private val alphaK: Float = 1f) : Drawable() {
    private val g = Glass()
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds
        r.set(b.left + dp(2f), b.top + dp(2f), b.right - dp(2f), b.bottom - dp(2f))
        g.draw(c, r, dp(radiusDp), alphaK, false)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Capa que dibuja la animación de apertura (la "ventana" de cristal que crece desde el icono).
 * Cuando el resorte llega al punto de lanzamiento se abre la app de verdad.
 */
class OpenOverlay(ctx: Context) : View(ctx) {
    private val sp = SpringValue(0f, 300f, 34f, 0.002f)
    private val ticker = Ticker { onFrame() }
    private val glass = Glass()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val of = Motion.OF()
    private val from = RectF()
    private val to = RectF()
    private val cur = RectF()
    private val tmp = RectF()
    private var entry: AppEntry? = null
    private var style = 0
    private var fired = false
    private var onLaunch: (() -> Unit)? = null
    var active = false
        private set

    init {
        ticker.add(sp)
        visibility = GONE
    }

    fun start(e: AppEntry, src: RectF, styleIdx: Int, launch: () -> Unit) {
        entry = e
        style = styleIdx
        from.set(src)
        to.set(0f, 0f, width.toFloat(), height.toFloat())
        fired = false
        onLaunch = launch
        active = true
        Motion.openTune(style, sp)
        sp.snap(0f)
        sp.to(1f)
        visibility = VISIBLE
        ticker.kick()
        invalidate()
    }

    fun hide() {
        active = false
        ticker.stop()
        visibility = GONE
        entry = null
        onLaunch = null
    }

    private fun onFrame(): Boolean {
        val p = sp.value
        // Se lanza cuando la ventana ya cubre casi toda la pantalla.
        val fireAt = if (style == 1) 0.78f else 0.86f
        if (!fired && (p >= fireAt || !sp.active)) {
            fired = true
            onLaunch?.invoke()
            onLaunch = null
        }
        invalidate()
        return false
    }

    override fun onDraw(c: Canvas) {
        val e = entry ?: return
        val p = sp.value
        Motion.openFrame(style, p, from, to, of)
        cur.set(of.l, of.t, of.r, of.b)
        c.save()
        if (of.rotY != 0f) Rot3D.apply(c, of.rotY, cur.centerX(), cur.centerY())
        val rad = max(0f, of.corner)
        fill.color = e.tint
        fill.alpha = (255 * clamp01(of.bg)).toInt()
        c.drawRoundRect(cur, rad, rad, fill)
        if (style == 2) {
            glass.draw(c, cur, max(rad, dp(4f)), 1f, false)
            if (of.glow > 0f) {
                glow.color = Color.WHITE
                glow.alpha = (60 * of.glow).toInt()
                c.drawRoundRect(cur, rad, rad, glow)
            }
        }
        val b = e.icon
        if (b != null && of.iconA > 0.01f) {
            val s = min(from.width(), from.height()) * of.iconS
            tmp.set(cur.centerX() - s / 2f, cur.centerY() - s / 2f, cur.centerX() + s / 2f, cur.centerY() + s / 2f)
            bmp.alpha = (255 * clamp01(of.iconA)).toInt()
            c.drawBitmap(b, null, tmp, bmp)
        }
        c.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = active

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Menú contextual de cristal (mantener presionado), con aparición de resorte. */
class MenuView(ctx: Context) : View(ctx) {
    class Item(val label: String, val action: () -> Unit)

    private val sp = SpringValue(0f, 340f, 22f, 0.002f)
    private val ticker = Ticker { invalidate(); false }
    private val glass = Glass()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sep = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint()
    private val box = RectF()
    private val anchor = RectF()
    private var items: List<Item> = emptyList()
    private var title = ""
    private var pressed = -1
    private var above = true
    private val rowH get() = dp(50f)
    private val headH get() = dp(46f)
    var showing = false
        private set

    init {
        ticker.add(sp)
        text.color = Color.WHITE
        text.textSize = dp(16f)
        sep.color = 0x30FFFFFF
        hi.color = 0x30FFFFFF
        scrim.color = Color.BLACK
        visibility = GONE
    }

    fun show(t: String, a: RectF, list: List<Item>) {
        title = t
        items = list
        anchor.set(a)
        val w = dp(240f)
        val h = headH + rowH * list.size
        var left = a.centerX() - w / 2f
        left = left.coerceIn(dp(12f), width - w - dp(12f))
        above = a.top - h - dp(12f) > dp(40f)
        val top = if (above) a.top - h - dp(10f) else min(a.bottom + dp(10f), height - h - dp(12f))
        box.set(left, top, left + w, top + h)
        pressed = -1
        showing = true
        visibility = VISIBLE
        sp.snap(0f)
        sp.to(1f)
        ticker.kick()
    }

    fun dismiss() {
        if (!showing) return
        showing = false
        sp.to(0f)
        ticker.kick()
        postDelayed({ if (!showing) visibility = GONE }, 260)
    }

    override fun onDraw(c: Canvas) {
        val p = sp.value
        val a = clamp01(p)
        scrim.alpha = (70 * a).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        val s = lerp(0.6f, 1f, p)
        val py = if (above) box.bottom else box.top
        c.save()
        c.scale(s, s, anchor.centerX().coerceIn(box.left, box.right), py)
        glass.draw(c, box, dp(24f), a)
        text.alpha = (170 * a).toInt()
        text.textSize = dp(13.5f)
        c.drawText(title, box.left + dp(20f), box.top + dp(29f), text)
        text.textSize = dp(16f)
        for (i in items.indices) {
            val top = box.top + headH + i * rowH
            if (i == pressed) c.drawRect(box.left + dp(6f), top, box.right - dp(6f), top + rowH, hi)
            sep.alpha = (48 * a).toInt()
            c.drawLine(box.left + dp(16f), top, box.right - dp(16f), top, sep)
            text.alpha = (255 * a).toInt()
            c.drawText(items[i].label, box.left + dp(20f), top + rowH / 2f + dp(6f), text)
        }
        c.restore()
    }

    private fun idxAt(x: Float, y: Float): Int {
        if (!box.contains(x, y)) return -1
        val i = ((y - box.top - headH) / rowH).toInt()
        return if (y >= box.top + headH && i in items.indices) i else -1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!showing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val i = idxAt(ev.x, ev.y)
                if (i != pressed) {
                    pressed = i
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val i = idxAt(ev.x, ev.y)
                dismiss()
                if (i >= 0) {
                    val act = items[i].action
                    post { act() }
                }
            }
            MotionEvent.ACTION_CANCEL -> dismiss()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}
