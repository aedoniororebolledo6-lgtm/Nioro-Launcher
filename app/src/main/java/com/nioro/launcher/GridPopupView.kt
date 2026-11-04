package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Vista 3 - Rejilla flotante: panel de cristal que sale del dock (pila "Rejilla" de macOS).
 * Diseño estilo Apple: esquinas continuas, cabecera con título y contador en cápsula, filo
 * separador fino, iconos que entran en cascada fila a fila, pico unido al panel y una barra
 * de desplazamiento fina que aparece solo al moverse.
 */
class GridPopupView(ctx: Context) : AppsView(ctx) {
    override val iconArea = S.IA_GRID

    private val scroll = Scroll1D(ticker)
    /** barra de desplazamiento: aparece al moverse y se desvanece al parar */
    private val indSp = ticker.add(SpringValue(0f, 260f, 30f, 0.003f))
    private val panel = RectF()
    private val panelPath = Path()
    private val title = Paint(Paint.ANTI_ALIAS_FLAG)
    private val count = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nubRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nubPath = Path()
    private val pill = RectF()
    private val rect = RectF()
    private var cols = 4
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private var areaTop = 0f
    private var areaBottom = 0f
    private var pad = 0f
    private var touching = false

    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var downIdx = -1
    private var longFired = false
    private var wasMoving = false
    private var atTop = false
    private var closing = false
    private var vt: VelocityTracker? = null
    private val longRun = Runnable {
        if (!moved && downIdx in shown.indices) {
            longFired = true
            lift()
            Hx.fire(this, S.HE_LONG)
            val r = RectF()
            itemRect(downIdx, r)
            listener?.onMenu(shown[downIdx], r)
        }
    }

    init {
        title.color = Color.WHITE
        title.textSize = dp(20f)
        title.typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        title.setShadowLayer(dp(4f), 0f, dp(1f), 0x55000000)
        count.color = Color.WHITE
        count.textSize = dp(13f)
        count.textAlign = Paint.Align.CENTER
        count.typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        nub.color = Color.WHITE
        nubRim.color = Color.WHITE
        line.color = Color.WHITE
        bar.color = Color.WHITE
    }

    private fun layout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = Prefs.drawerCols.coerceIn(3, 6)
        val pw = w - dp(28f)
        pad = dp(14f)
        cellW = (pw - pad * 2f) / cols
        iconSize = min(dp(Prefs.iconDp.toFloat() - 2f), cellW - dp(18f)).coerceAtLeast(dp(30f))
        cellH = iconSize + dp(36f)
        val bottom = h - insetBottom - dp(112f)
        val maxH = min(h * S.p(S.GRID_H), h - insetTop - dp(160f))
        val rowsAll = ceil(shown.size / cols.toFloat()).toInt()
        val header = if (S.b(S.GRID_TITLE)) dp(66f) else dp(14f)
        val wanted = header + rowsAll * cellH + dp(16f)
        val ph = min(maxH, max(dp(230f), wanted))
        panel.set(dp(14f), bottom - ph, dp(14f) + pw, bottom)
        areaTop = panel.top + header
        areaBottom = panel.bottom - dp(10f)
        scroll.min = 0f
        scroll.max = max(0f, rowsAll * cellH + dp(12f) - (areaBottom - areaTop))
        scroll.dim = areaBottom - areaTop
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        layout()
    }

    /** "Aplicaciones" o "Resultados" mientras se busca; el número va en su cápsula */
    private var titleText = "Aplicaciones"
    private var countText = ""

    override fun onDataChanged() {
        titleText = if (query.isEmpty()) "Aplicaciones" else "Resultados"
        countText = shown.size.toString()
        layout()
        scroll.jump(0f)
    }

    override fun onOpen() {
        layout()
        scroll.jump(0f)
        indSp.snap(0f)
    }

    override fun onFrame(dt: Float): Boolean {
        val want = if (touching && moved || scroll.isMoving) 1f else 0f
        if (indSp.target != want) indSp.to(want)
        invalidate()
        return false
    }

    private fun itemRect(idx: Int, out: RectF) {
        val col = idx % cols
        val row = idx / cols
        val cx = panel.left + pad + col * cellW + cellW / 2f
        val cy = areaTop + dp(6f) + row * cellH + iconSize / 2f - scroll.value
        out.set(cx - iconSize / 2f, cy - iconSize / 2f, cx + iconSize / 2f, cy + iconSize / 2f)
    }

    /** curva de globo: pasa un poco de su tamaño y vuelve */
    private fun pop(q: Float): Float {
        val t = clamp01(q) - 1f
        return 1f + 2.7f * t * t * t + 1.7f * t * t
    }

    override fun onDraw(c: Canvas) {
        val p = progress
        val a = clamp01(p * 1.7f)
        val ax = (if (anchorX > 0f) anchorX else width / 2f).coerceIn(panel.left + dp(44f), panel.right - dp(44f))
        val pivotY = panel.bottom + dp(14f)
        // se abre desde el pico como una gota: primero a lo ancho y luego a lo alto
        val sx = lerp(0.16f, 1f, min(p * 1.08f, 1.05f))
        val sy = lerp(0.06f, 1f, min(p, 1.05f))
        c.save()
        c.scale(sx, sy, ax, pivotY)
        // pico unido al panel (redondeado), con el mismo cristal
        if (S.b(S.GRID_NUB)) {
            nubPath.reset()
            nubPath.moveTo(ax - dp(16f), panel.bottom - dp(2f))
            nubPath.cubicTo(ax - dp(7f), panel.bottom - dp(2f), ax - dp(4f), panel.bottom + dp(11f), ax, panel.bottom + dp(12f))
            nubPath.cubicTo(ax + dp(4f), panel.bottom + dp(11f), ax + dp(7f), panel.bottom - dp(2f), ax + dp(16f), panel.bottom - dp(2f))
            nubPath.close()
            nub.alpha = (52 * a).toInt()
            c.drawPath(nubPath, nub)
            nubRim.strokeWidth = dp(0.9f)
            nubRim.alpha = (90 * a).toInt()
            c.drawPath(nubPath, nubRim)
        }
        glass.draw(c, panel, dp(36f), a, true, S.SF_GRIDPANEL)
        // velo oscuro muy suave para que iconos y nombres se lean sobre cualquier fondo
        Squircle.path(panelPath, panel, dp(36f))
        tint.color = Color.BLACK
        tint.alpha = (34 * a).toInt()
        c.drawPath(panelPath, tint)
        // cabecera: título que entra deslizándose y contador en cápsula
        if (S.b(S.GRID_TITLE)) {
            val tq = clamp01(p * 1.8f - 0.35f)
            val ty = panel.top + dp(41f) + (1f - tq) * dp(8f)
            title.alpha = (255 * a * tq).toInt().coerceIn(0, 255)
            c.drawText(titleText, panel.left + dp(22f), ty, title)
            val cw = max(dp(34f), count.measureText(countText) + dp(20f))
            pill.set(panel.right - dp(18f) - cw, ty - dp(20f), panel.right - dp(18f), ty + dp(6f))
            glass.draw(c, pill, dp(13f), a * tq, false, S.SF_SEARCH)
            count.alpha = (235 * a * tq).toInt().coerceIn(0, 255)
            c.drawText(countText, pill.centerX(), pill.centerY() + dp(4.5f), count)
            // filo separador de un pelo
            line.alpha = (34 * a * tq).toInt()
            c.drawRect(panel.left + dp(18f), areaTop - dp(5f), panel.right - dp(18f), areaTop - dp(5f) + max(1f, dp(0.6f)), line)
        }

        c.save()
        c.clipPath(panelPath)
        c.clipRect(panel.left, areaTop, panel.right, areaBottom)
        val first = max(0, ((scroll.value - dp(4f)) / cellH).toInt() * cols)
        var i = first
        while (i < shown.size) {
            itemRect(i, rect)
            if (rect.top > areaBottom) break
            if (rect.bottom > areaTop - cellH) {
                // cascada: fila a fila (y un poco por columna) los iconos llegan inflándose
                val row = i / cols - first / cols
                val col = i % cols
                val q = clamp01(p * 1.7f - row * 0.07f - col * 0.025f - 0.1f)
                val sc = lerp(0.55f, 1f, pop(q))
                val e = shown[i]
                val ia = a * clamp01(q * 1.6f)
                val oy = (1f - q) * dp(12f)
                drawIcon(c, e, rect.centerX(), rect.centerY() + oy, iconSize * sc, ia)
                drawLabel(c, e, rect.centerX(), rect.bottom + dp(17f) + oy, cellW - dp(6f), ia * clamp01((q - 0.3f) / 0.7f))
            }
            i++
        }
        c.restore()
        // barra de desplazamiento fina (solo mientras se mueve)
        val ia = indSp.value
        if (ia > 0.01f && scroll.max > 0f) {
            val len = areaBottom - areaTop - dp(12f)
            val th = max(dp(36f), len * len / (len + scroll.max))
            val f = clamp01(scroll.value / scroll.max)
            val top = areaTop + dp(6f) + (len - th) * f
            bar.alpha = (140 * ia * a).toInt()
            c.drawRoundRect(panel.right - dp(8f), top, panel.right - dp(4.5f), top + th, dp(2f), dp(2f), bar)
        }
        c.restore()
    }

    private fun idxAt(x: Float, y: Float): Int {
        if (!panel.contains(x, y) || y < areaTop || y > areaBottom) return -1
        val col = ((x - panel.left - pad) / cellW).toInt()
        val row = ((y - areaTop - dp(6f) + scroll.value) / cellH).toInt()
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
                touching = true
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                wasMoving = scroll.isMoving
                scroll.stop()
                atTop = scroll.value <= scroll.min + 1f
                closing = false
                downX = x
                downY = y
                lastY = y
                moved = false
                longFired = false
                downIdx = idxAt(x, y)
                uiHandler.removeCallbacks(longRun)
                unpress()
                if (downIdx >= 0) uiHandler.postDelayed(longRun, longMs)
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
                    // desde arriba del todo, tirar hacia abajo cierra la vista
                    if (atTop && !closing && S.b(S.DR_SWIPE_CLOSE) && y - downY > dp(120f) && abs(y - downY) > abs(x - downX) * 1.4f) {
                        closing = true
                        listener?.onClose()
                    }
                    scroll.drag(-(y - lastY))
                    invalidate()
                }
                lastY = y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
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
