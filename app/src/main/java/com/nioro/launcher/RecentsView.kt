package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Apps en segundo plano. 5 estilos:
 * 0 Carrusel (One UI), 1 Pila 3D (iOS), 2 Cover Flow, 3 Rejilla (Exposé de macOS), 4 Burbujas.
 * Deslizar una tarjeta hacia arriba la quita de la lista.
 */
class RecentsView(ctx: Context) : View0(ctx) {

    interface Listener {
        fun onLaunch(e: AppEntry, from: RectF)
        fun onClose()
        fun onOpenAll()
    }

    var listener: Listener? = null
    var progress = 0f
    var insetTop = 0f
    var insetBottom = 0f

    private var items = ArrayList<AppEntry>()
    private val ticker = Ticker { invalidate(); false }
    private val pos = ticker.add(SpringValue(0f, 200f, 26f, 0.0005f))
    private val gridScroll = Scroll1D(ticker)
    private val dismissY = ticker.add(SpringValue(0f, 260f, 30f, 0.5f))
    private val closeAllSp = ticker.add(SpringValue(0f, 180f, 22f, 0.002f))
    private var closingAll = false
    private var dismissIdx = -1
    private val glass = Glass()
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val card = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG)
    private val big = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val r2 = RectF()
    private val btn = RectF()
    private val hdr = RectF()
    private var style = 0

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startPos = 0f
    private var mode = 0 // 0 nada, 1 desplazar, 2 descartar
    private var hitIdx = -1
    private var wasMoving = false
    private var vt: VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    init {
        title.color = Color.WHITE
        title.textSize = dp(14f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        big.color = Color.WHITE
        big.textSize = dp(22f)
        big.textAlign = Paint.Align.CENTER
        hint.color = 0xCCFFFFFF.toInt()
        hint.textSize = dp(14f)
        hint.textAlign = Paint.Align.CENTER
    }

    fun setItems(list: List<AppEntry>) {
        items = ArrayList(list)
        style = Prefs.recentsAnim
        closingAll = false
        closeAllSp.snap(0f)
        pos.snap(0f)
        gridScroll.jump(0f)
        dismissIdx = -1
        layoutGrid()
        invalidate()
    }

    // ---------------- Geometría ----------------
    private fun cardW(): Float = when (style) {
        0 -> width * 0.66f
        1 -> width * 0.78f
        2 -> width * 0.56f
        else -> width * 0.42f
    }

    private fun cardH(): Float = cardW() * (if (style == 4) 1f else 1.72f)
    private fun spacing(): Float = when (style) {
        0 -> cardW() + dp(18f)
        1 -> cardW() * 0.55f
        2 -> cardW() * 0.6f
        else -> cardW() + dp(30f)
    }

    private fun gridCols(): Int = 2
    private fun gridCellH(): Float = cardW() * 1.5f + dp(14f)

    private fun layoutGrid() {
        if (style != 3) return
        val rows = (items.size + 1) / 2
        val top = insetTop + dp(80f)
        val vis = height - insetBottom - dp(110f) - top
        gridScroll.min = 0f
        gridScroll.max = max(0f, rows * gridCellH() - vis)
        gridScroll.dim = vis
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layoutGrid()
    }

    /** Rectángulo de la tarjeta i en el estado actual. Devuelve escala/alpha/rotación vía campos. */
    private var outA = 1f
    private var outRot = 0f
    private fun cardRect(i: Int, out: RectF): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        val cw = cardW()
        val ch = cardH()
        val cy = (insetTop + h - insetBottom) / 2f - dp(10f)
        val o = i - pos.value
        val p = progress
        outA = 1f
        outRot = 0f
        var sc = 1f
        var cx = w / 2f
        var y = cy
        when (style) {
            0 -> {
                cx = w / 2f + o * spacing()
                sc = 1f - min(1f, abs(o)) * 0.08f
            }
            1 -> {
                // pila: las tarjetas anteriores se apilan a la izquierda
                val sp = spacing()
                cx = if (o >= 0) w / 2f + o * w * 0.9f else w / 2f + o * sp * 0.35f
                sc = if (o >= 0) 1f else 1f - min(4f, -o) * 0.05f
                outA = if (o < -4f) 0f else 1f
                outRot = 0f
            }
            2 -> {
                cx = w / 2f + o * spacing()
                outRot = (-o * 55f).coerceIn(-60f, 60f)
                sc = 1f - min(1f, abs(o)) * 0.18f
            }
            3 -> {
                val col = i % gridCols()
                val row = i / gridCols()
                val gw = w / gridCols()
                cx = col * gw + gw / 2f
                y = insetTop + dp(80f) + row * gridCellH() + cw * 0.75f - gridScroll.value
                val ch2 = cw * 1.5f
                val q = clamp01(p * 1.6f - (i % 6) * 0.06f)
                sc = lerp(0.7f, 1f, q)
                outA = clamp01(q * 1.5f)
                out.set(cx - cw / 2f * sc, y - ch2 / 2f * sc, cx + cw / 2f * sc, y + ch2 / 2f * sc)
                if (i == dismissIdx) out.offset(0f, dismissY.value)
                if (closingAll) {
                    out.offset(0f, -closeAllSp.value * h * 0.7f)
                    outA *= 1f - clamp01(closeAllSp.value * 1.3f)
                }
                return out.bottom > 0 && out.top < h
            }
            else -> {
                // burbujas: círculos que flotan en espiral
                val ang = i * 2.39996f + pos.value * 0.6f
                val rad = dp(46f) + sqrtI(i) * dp(58f)
                cx = w / 2f + kotlin.math.cos(ang) * rad
                y = cy + sin(ang) * rad * 1.1f
                val s = dp(84f) * (1f - min(0.45f, i * 0.03f))
                val q = clamp01(p * 1.5f - i * 0.04f)
                val ss = s * lerp(0.2f, 1f, q)
                outA = clamp01(q * 2f)
                out.set(cx - ss / 2f, y - ss / 2f, cx + ss / 2f, y + ss / 2f)
                if (i == dismissIdx) out.offset(0f, dismissY.value)
                if (closingAll) {
                    out.offset(0f, -closeAllSp.value * h * 0.7f)
                    outA *= 1f - clamp01(closeAllSp.value * 1.3f)
                }
                return true
            }
        }
        // entrada: crece desde el centro con un pequeño retraso por distancia
        val q = clamp01(p * 1.4f - abs(o) * 0.08f)
        val enter = lerp(0.82f, 1f, q)
        sc *= enter
        outA *= clamp01(q * 1.6f)
        out.set(cx - cw / 2f * sc, y - ch / 2f * sc, cx + cw / 2f * sc, y + ch / 2f * sc)
        if (i == dismissIdx) out.offset(0f, dismissY.value)
        if (closingAll) {
            out.offset(0f, -closeAllSp.value * h * (0.6f + abs(o) * 0.1f))
            outA *= 1f - clamp01(closeAllSp.value * 1.3f)
        }
        return out.right > -w * 0.2f && out.left < w * 1.2f
    }

    private fun sqrtI(i: Int): Float = kotlin.math.sqrt(i.toFloat())

    override fun onDraw(c: Canvas) {
        val a = clamp01(progress * 1.6f)
        if (items.isEmpty()) {
            big.alpha = (255 * a).toInt()
            c.drawText("No hay apps recientes", width / 2f, height / 2f, big)
            hint.alpha = (200 * a).toInt()
            c.drawText("Toca para volver", width / 2f, height / 2f + dp(30f), hint)
            return
        }
        // orden de dibujo: en estilos 3D, la tarjeta central encima
        val order = ArrayList<Int>(items.size)
        for (i in items.indices) order.add(i)
        if (style == 2 || style == 0) order.sortByDescending { abs(it - pos.value) }
        for (i in order) {
            if (!cardRect(i, r)) continue
            val al = outA * a
            if (al <= 0.01f) continue
            val e = items[i]
            c.save()
            if (outRot != 0f) Rot3D.apply(c, outRot, r.centerX(), r.centerY())
            if (style == 4) {
                val rad = r.width() / 2f
                glass.draw(c, r, rad, al)
                drawIcon(c, e, r.centerX(), r.centerY(), r.width() * 0.62f, al)
            } else {
                drawCard(c, e, r, al)
            }
            c.restore()
        }
        // botón "Ver todo" abajo
        btn.set(width / 2f - dp(80f), height - insetBottom - dp(78f), width / 2f + dp(80f), height - insetBottom - dp(34f))
        glass.draw(c, btn, dp(22f), a)
        hint.alpha = (255 * a).toInt()
        c.drawText("Cerrar todo", btn.centerX(), btn.centerY() + dp(5f), hint)
    }

    private fun drawCard(c: Canvas, e: AppEntry, rr: RectF, al: Float) {
        val k = rr.width() / max(1f, cardW())
        val rad = dp(28f) * k
        // cuerpo: color del icono + cristal encima (se ve como una ventana esmerilada de la app)
        card.color = e.tint
        card.alpha = (190 * al).toInt()
        c.drawRoundRect(rr, rad, rad, card)
        glass.draw(c, rr, rad, al)
        // "contenido" sugerido: barras de cristal suaves
        hdr.set(rr.left + dp(16f) * k, rr.top + dp(18f) * k, rr.right - dp(16f) * k, rr.top + dp(30f) * k)
        card.color = 0x33FFFFFF
        card.alpha = (70 * al).toInt()
        c.drawRoundRect(hdr, dp(6f), dp(6f), card)
        hdr.set(rr.left + dp(16f) * k, rr.bottom - dp(46f) * k, rr.right - dp(60f) * k, rr.bottom - dp(34f) * k)
        c.drawRoundRect(hdr, dp(6f), dp(6f), card)
        val isz = min(rr.width() * 0.4f, dp(80f))
        IconShadow.draw(c, rr.centerX(), rr.centerY(), isz, al)
        drawIcon(c, e, rr.centerX(), rr.centerY(), isz, al)
        // cabecera estilo iOS: icono pequeño + nombre encima de la tarjeta
        val hs = dp(24f) * min(1f, k + 0.2f)
        val hy = rr.top - dp(10f) - hs / 2f
        drawIcon(c, e, rr.left + dp(4f) + hs / 2f, hy, hs, al)
        title.alpha = (255 * al).toInt()
        title.textAlign = Paint.Align.LEFT
        c.drawText(e.label, rr.left + dp(12f) + hs, hy + dp(5f), title)
    }

    private fun drawIcon(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float) {
        val b = e.icon ?: return
        bmp.alpha = (255 * a).toInt().coerceIn(0, 255)
        r2.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        c.drawBitmap(b, null, r2, bmp)
    }

    private fun hit(x: Float, y: Float): Int {
        // de arriba (dibujado último) hacia abajo
        var best = -1
        var bd = Float.MAX_VALUE
        for (i in items.indices) {
            if (!cardRect(i, r)) continue
            if (outA < 0.2f) continue
            if (r.contains(x, y)) {
                val d = abs(i - pos.value)
                if (d < bd) {
                    bd = d
                    best = i
                }
            }
        }
        return best
    }

    private fun maxPos(): Float = max(0, items.size - 1).toFloat()

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (closingAll) return true
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                lastX = x
                lastY = y
                mode = 0
                startPos = pos.value
                pos.snap(pos.value)
                wasMoving = (style == 3 && gridScroll.isMoving) || pos.active
                gridScroll.stop()
                hitIdx = hit(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (mode == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                    mode = if (hitIdx >= 0 && dy < 0 && abs(dy) > abs(dx) * 1.2f) 2 else 1
                    if (mode == 2) {
                        dismissIdx = hitIdx
                        dismissY.snap(0f)
                    }
                }
                if (mode == 1) {
                    if (style == 3) {
                        gridScroll.drag(-(y - lastY))
                    } else {
                        val dist = if (style == 4) dp(120f) else spacing()
                        val d = if (style == 4) (dx + dy) else dx
                        var t = startPos - d / dist
                        val m = maxPos()
                        if (t < 0f) t = rubber(t, 1f)
                        if (t > m) t = m + rubber(t - m, 1f)
                        pos.snap(t)
                    }
                    invalidate()
                } else if (mode == 2) {
                    dismissY.snap(min(0f, dy))
                    invalidate()
                }
                lastX = x
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    1 -> {
                        if (style == 3) {
                            gridScroll.release(-vy)
                        } else {
                            val dist = if (style == 4) dp(120f) else spacing()
                            val v = -vx / dist
                            val target = (pos.value + v * 0.3f).roundToInt().coerceIn(0, max(0, items.size - 1))
                            pos.to(target.toFloat())
                            pos.velocity = v
                        }
                        ticker.kick()
                    }
                    2 -> {
                        val idx = dismissIdx
                        if (idx >= 0 && (dismissY.value < -dp(140f) || vy < -1800f)) {
                            removeAt(idx)
                        } else {
                            dismissY.to(0f)
                            ticker.kick()
                        }
                    }
                    else -> if (wasMoving) {
                        gridScroll.clampNow()
                        if (pos.value != pos.value.roundToInt().toFloat()) pos.to(pos.value.roundToInt().toFloat().coerceIn(0f, maxPos()))
                        ticker.kick()
                    } else if (up) {
                        if (btn.contains(x, y)) {
                            // "Cerrar todo" de One UI: las tarjetas salen volando hacia arriba
                            Recents.forgetAll(items.map { it.key })
                            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            closingAll = true
                            closeAllSp.snap(0f)
                            closeAllSp.to(1f)
                            ticker.kick()
                            postDelayed({ listener?.onClose() }, 260)
                        } else if (hitIdx >= 0 && hitIdx < items.size) {
                            cardRect(hitIdx, r)
                            listener?.onLaunch(items[hitIdx], RectF(r))
                        } else {
                            listener?.onClose()
                        }
                    }
                }
                mode = 0
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }

    private fun removeAt(idx: Int) {
        val e = items.removeAt(idx)
        Recents.forget(e.key)
        dismissIdx = -1
        dismissY.snap(0f)
        if (pos.value > maxPos()) pos.to(maxPos())
        layoutGrid()
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        ticker.kick()
        invalidate()
        if (items.isEmpty()) postDelayed({ listener?.onClose() }, 250)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Alias corto para android.view.View (evita choques de nombres). */
typealias View0 = android.view.View
