package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pantalla de inicio: páginas de iconos + dock de cristal estilo macOS.
 * Todo se dibuja en un único Canvas (una sola pasada) y solo se redibuja mientras algo se mueve.
 */
class HomeView(ctx: Context) : View(ctx) {

    interface Host {
        fun launch(e: AppEntry, from: RectF, fromDock: Boolean)
        fun openDrawer()
        fun openRecents()
        fun showMenu(e: AppEntry, anchor: RectF, where: Int) // 0 inicio, 1 dock
        fun dismissMenu()
        fun expandShade()
        fun showHomeMenu(x: Float, y: Float)
        /** Botones del modo edición: 0 Fondo, 1 Vista de apps, 2 Animaciones, 3 Ajustes. */
        fun editAction(i: Int)
        /** gesto configurable (S.G_*) */
        fun gesture(g: Int)
        /** 0 = abrir alarmas (tocar reloj), 1 = abrir calendario (tocar fecha) */
        fun special(i: Int)
    }

    class Slot(val kind: Int, val key: String?) { // 0 app, 1 launchpad, 2 recientes, 3 separador
        val sc = SpringValue(1f, 520f, 40f, 0.002f)
        val by = SpringValue(0f, 120f, 5f, 0.002f)
        val bs = SpringValue(0f, 200f, 8f, 0.002f)
        val br = SpringValue(0f, 90f, 7f, 0.002f)
        val bx = SpringValue(0f, 160f, 6f, 0.002f)
        var rest = 0f
        var cx = 0f
        var scale = 1f
        var running = false
    }

    var host: Host? = null

    private val ticker = Ticker { dt -> onFrame(dt) }
    private val handler = Handler(Looper.getMainLooper())
    private val glass = Glass()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()

    // Estado
    private var homeKeys = ArrayList<String>()
    private var dockKeys = ArrayList<String>()
    private val slots = ArrayList<Slot>()
    private val pageSp = SpringValue(0f, 220f, 25f)
    private val enterSp = SpringValue(1f, 190f, 25f)
    private val pressSp = SpringValue(1f, 700f, 53f, 0.0005f)
    private var pressKey: String? = null
    private var pressSlot: Slot? = null
    private var tickSlot: Slot? = null
    private var datePillW = 0f
    private val editSp = SpringValue(0f, 260f, 26f, 0.0008f)
    private var hourStr = ""
    private var minStr = ""
    private val stackPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val minPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val greetPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fxPaint = Paint()
    private var topGrad: android.graphics.LinearGradient? = null
    private var botGrad: android.graphics.LinearGradient? = null
    private var greetStr = ""
    private var battery = -1
    private var gridLeft = 0f
    private val clockRect = RectF()
    private val dateRect = RectF()
    private var pressArea = S.IA_HOME
    // multitáctil y toques múltiples
    private val M_MULTI = 5
    private var multiDone = false
    private var y0avg = 0f
    private var d0 = 0f
    private var yAvg = 0f
    private var dNow = 0f
    private var multiT0 = 0L
    private var tapCount = 0
    private var lastTapT = 0L
    // contador de FPS
    private var fpsLast = 0L
    private var fpsVal = 0f
    private val editLabel = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val editNames = arrayOf("Fondo", "Vista de apps", "Animaciones", "Ajustes")
    private val editGlyphs = intArrayOf(Glyphs.WALLPAPER, Glyphs.GRID, Glyphs.MOTION, Glyphs.GEAR)
    private var drawerP = 0f
    private var running: Set<String> = emptySet()

    // Medidas
    private var insetTop = 0f
    private var insetBottom = 0f
    private var iconPx = 0f
    private var dockIconPx = 0f
    private var dockUnit = 0f
    private var cols = 4
    private var rows = 5
    private var cap = 20
    private var cellW = 0f
    private var cellH = 0f
    private var gridTop = 0f
    private var dotsY = 0f
    private var dockCy = 0f
    private var dockH = 0f
    private var restTotal = 0f

    // Dibujo
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val clockPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val capRect = RectF()
    private val tmpRect = RectF()
    private val pt = Motion.PT()
    private val itx = Motion.IT()
    private val labelCache = HashMap<String, String>()
    private var timeStr = ""
    private var dateStr = ""
    private var lastMinute = -1L

    // Gestos
    private val M_NONE = 0
    private val M_PAGE = 1
    private val M_DRAG = 2
    private val M_VSWIPE = 3
    private val M_MENU = 4
    private var touchMode = M_NONE
    private var downX = 0f
    private var downY = 0f
    private var pageStart = 0f
    private var moved = false
    private var inDock = false
    private var downKey: String? = null
    private var vt: VelocityTracker? = null
    private var scrubbing = false
    private var scrubX = 0f
    private var vTriggered = false
    private var dragKey: String? = null
    private var dragFromDock = false
    private var dragX = 0f
    private var dragY = 0f
    private var edgeDir = 0

    private val longRun = Runnable {
        val k = downKey
        if (touchMode == M_NONE && !moved && k == null && !inDock) {
            touchMode = M_MENU
            unpress()
            host?.gesture(S.G_LONG)
        } else if (touchMode == M_NONE && !moved && k != null) {
            val e = AppRepo.get(k)
            if (e != null) {
                touchMode = M_MENU
                scrubbing = false
                unpress()
                Hx.fire(this, S.HE_LONG)
                val r = RectF()
                if (inDock) {
                    val s = slotOfKey(k)
                    if (s != null) {
                        val sz = dockIconPx * s.scale
                        r.set(s.cx - sz / 2f, dockCy - dockH / 2f - sz * 0.3f, s.cx + sz / 2f, dockCy + dockH / 2f)
                    }
                } else {
                    homeIconRect(homeKeys.indexOf(k), r)
                }
                host?.showMenu(e, r, if (inDock) 1 else 0)
                updateDockTargets()
                ticker.kick()
            }
        }
    }

    private val edgeRun = object : Runnable {
        override fun run() {
            if (edgeDir == 0 || touchMode != M_DRAG) return
            val n = pageCount()
            val cur = pageSp.value.roundToInt()
            val t = (cur + edgeDir).coerceIn(0, n - 1)
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(t.toFloat())
            ticker.kick()
            handler.postDelayed(this, 750)
        }
    }

    private val clockRun = object : Runnable {
        override fun run() {
            if (updateClock()) invalidate()
            handler.postDelayed(this, 20000)
        }
    }

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(11.5f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
        clockPaint.color = Color.WHITE
        clockPaint.textSize = dp(76f)
        clockPaint.typeface = Typeface.create(Typeface.DEFAULT, 200, false)
        clockPaint.letterSpacing = -0.02f
        clockPaint.textAlign = Paint.Align.CENTER
        clockPaint.setShadowLayer(dp(10f), 0f, dp(2f), 0x66000000)
        datePaint.color = 0xF2FFFFFF.toInt()
        datePaint.textSize = dp(15f)
        datePaint.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        datePaint.textAlign = Paint.Align.CENTER
        datePaint.setShadowLayer(dp(6f), 0f, dp(1f), 0x66000000)
        hintPaint.color = Color.WHITE
        ticker.add(pageSp)
        ticker.add(enterSp)
        ticker.add(pressSp)
        ticker.add(editSp)
        stackPaint.color = Color.WHITE
        stackPaint.textSize = dp(96f)
        stackPaint.typeface = Typeface.create(Typeface.DEFAULT, 300, false)
        stackPaint.letterSpacing = -0.03f
        stackPaint.setShadowLayer(dp(10f), 0f, dp(2f), 0x55000000)
        editLabel.color = Color.WHITE
        editLabel.textSize = dp(12.5f)
        editLabel.textAlign = Paint.Align.CENTER
        editLabel.typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        editLabel.setShadowLayer(dp(3f), 0f, dp(1f), 0x88000000.toInt())
        updateClock()
    }

    // ---------------------------------------------------------------- API
    fun setInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        relayout()
        invalidate()
    }

    fun setDrawerProgress(p: Float) {
        if (abs(p - drawerP) > 0.0005f) {
            drawerP = p
            invalidate()
        }
    }

    fun setRunning(set: Set<String>) {
        running = set
        for (s in slots) {
            val k = s.key
            s.running = k != null && AppRepo.get(k)?.pkg in set
        }
        invalidate()
    }

    /** Vuelve a leer inicio y dock desde los ajustes guardados. */
    fun reload() {
        homeKeys = ArrayList()
        for (k in Prefs.homeKeys()) if (AppRepo.get(k) != null && k !in homeKeys) homeKeys.add(k)
        dockKeys = ArrayList()
        for (k in Prefs.dockKeys()) if (AppRepo.get(k) != null && k !in dockKeys) dockKeys.add(k)
        relayout()
        val maxPage = (pageCount() - 1).toFloat()
        if (pageSp.value > maxPage) pageSp.snap(maxPage)
        invalidate()
    }

    val isEditing: Boolean get() = editSp.target > 0.5f

    /** Modo edición estilo One UI: las páginas se encogen y aparece la barra inferior. */
    fun enterEdit() {
        if (isEditing) return
        editGesture = false
        Mo.tune(editSp, S.MO_EDIT_IN, 260f, 0.78f)
        editSp.to(1f)
        Hx.fire(this, S.HE_LONG)
        ticker.kick()
    }

    fun exitEdit(now: Boolean = false) {
        if (!isEditing && editSp.value <= 0.001f) return
        if (now) {
            editSp.snap(0f)
            invalidate()
            return
        }
        Mo.tune(editSp, S.MO_EDIT_OUT, 300f, 1f)
        editSp.to(0f)
        ticker.kick()
    }

    fun goToFirstPage() {
        val t = (S.i(S.H_DEFAULT_PAGE) - 1).coerceIn(0, pageCount() - 1).toFloat()
        if (abs(pageSp.value - t) > 0.01f) {
            Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
            pageSp.to(t)
            ticker.kick()
        }
    }

    fun playEnter() {
        Motion.enterTune(Prefs.returnAnim, enterSp)
        enterSp.snap(0f)
        enterSp.to(1f)
        ticker.kick()
        invalidate()
    }

    fun refreshStyle() {
        relayout()
        invalidate()
    }

    // ---------------------------------------------------------------- Layout
    private fun pageCount(): Int {
        val c = if (cap < 1) 1 else cap
        return max(1, ceil(homeKeys.size / c.toFloat()).toInt())
    }

    private fun relayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        iconPx = dp(Prefs.iconDp.toFloat())
        cols = Prefs.columns.coerceIn(3, 6)
        applyStyles()
        val dockOn = S.b(S.K_SHOW)
        val dockBottom = h - insetBottom - dp(S.f(S.K_BOTTOM))
        dockH = if (dockOn) dp(Prefs.dockDp.toFloat()) + dp(S.f(S.K_EXTRA_H)) else 0f
        dockCy = dockBottom - dockH / 2f
        dotsY = if (dockOn) dockBottom - dockH - dp(22f) else h - insetBottom - dp(28f)
        gridTop = insetTop + when (Prefs.clockStyle) {
            1 -> dp(232f)
            2 -> dp(28f)
            else -> dp(146f)
        } + dp(S.f(S.H_TOP)) + (if (S.b(S.DT_GREETING) && Prefs.clockStyle != 2) dp(22f) else 0f)
        val avail = dotsY - dp(16f) - gridTop - dp(S.f(S.H_BOTTOM))
        val labelH = if (Prefs.labels) dp(Prefs.labelSize + 8f) else 0f
        val minCell = iconPx + labelH + dp(24f)
        // filas: automáticas según el espacio, o las que elija el usuario
        rows = if (Prefs.rows > 0) Prefs.rows.coerceIn(3, 8) else (avail / minCell).toInt().coerceIn(2, 7)
        // si las filas elegidas no caben, se reducen en vez de encimar iconos y nombres
        while (rows > 2 && avail / rows < dp(28f) + labelH + dp(8f)) rows--
        cap = cols * rows
        gridLeft = dp(S.f(S.H_SIDE))
        cellW = (w - gridLeft * 2f) / cols
        cellH = avail / rows
        // el icono nunca se sale de su celda aunque la cuadrícula sea muy densa
        iconPx = min(iconPx, min(cellW - dp(12f), cellH - labelH - dp(8f))).coerceAtLeast(dp(28f))
        labelCache.clear()
        rebuildDock()
    }

    private fun applyStyles() {
        Tx.apply(textPaint, S.TX_LABEL_HOME, dp(Prefs.labelSize.toFloat()), 400, dp(3f), 0x99)
        Tx.apply(clockPaint, S.TX_CLOCK, dp(76f), 200, dp(10f), 0x66, -0.02f)
        Tx.apply(datePaint, S.TX_DATE, dp(15f), 500, dp(6f), 0x66)
        Tx.apply(stackPaint, S.TX_HOURS, dp(96f), 300, dp(10f), 0x55, -0.03f)
        Tx.apply(minPaint, S.TX_MINUTES, dp(96f), 300, dp(10f), 0x55, -0.03f)
        Tx.apply(editLabel, S.TX_EDIT, dp(12.5f), 500, dp(3f), 0x88)
        Tx.apply(greetPaint, S.TX_GREETING, dp(17f), 500, dp(6f), 0x66)
        datePillW = 0f
        lastMinute = -1L
        labelCache.clear()
    }

    fun setBattery(pct: Int) {
        if (pct != battery) {
            battery = pct
            lastMinute = -1L
            invalidate()
        }
    }

    private fun rebuildDock() {
        slots.clear()
        val lp = S.b(S.K_LAUNCHPAD)
        val rc = S.b(S.K_RECENTS)
        val lpRight = S.b(S.K_LP_RIGHT)
        if (lp && !lpRight) slots.add(Slot(1, null))
        for (k in dockKeys) if (AppRepo.get(k) != null) slots.add(Slot(0, k))
        if ((rc || (lp && lpRight)) && S.b(S.K_SEP)) slots.add(Slot(3, null))
        if (lp && lpRight) slots.add(Slot(1, null))
        if (rc) slots.add(Slot(2, null))
        ticker.springs.clear()
        ticker.springs.add(pageSp)
        ticker.springs.add(enterSp)
        ticker.springs.add(pressSp)
        ticker.springs.add(editSp)
        pressSlot = null
        tickSlot = null
        for (s in slots) {
            ticker.springs.add(s.sc)
            ticker.springs.add(s.by)
            ticker.springs.add(s.bs)
            ticker.springs.add(s.br)
            ticker.springs.add(s.bx)
            if (Prefs.dockMode == 1) Mo.tune(s.sc, S.MO_DOCK_MAG, 210f, 0.42f) else Mo.tune(s.sc, S.MO_DOCK_MAG, 520f, 0.95f)
        }
        layoutDock()
    }

    private fun layoutDock() {
        val w = width.toFloat()
        var icons = 0
        for (s in slots) if (s.kind != 3) icons++
        val sepW = dp(14f)
        val maxUnit = (w - dp(36f) - sepW) / max(1, icons)
        dockUnit = min(dp(Prefs.dockDp.toFloat()) + dp(8f), maxUnit)
        dockIconPx = min(dp(Prefs.dockDp.toFloat()), dockUnit - dp(6f))
        var total = 0f
        for (s in slots) total += if (s.kind == 3) sepW else dockUnit
        restTotal = total
        var x = (w - total) / 2f
        for (s in slots) {
            val sw = if (s.kind == 3) sepW else dockUnit
            s.rest = x + sw / 2f
            s.cx = s.rest
            x += sw
        }
        for (s in slots) {
            val k = s.key
            s.running = k != null && AppRepo.get(k)?.pkg in running
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        relayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        resumeClock()
    }

    fun resumeClock() {
        handler.removeCallbacks(clockRun)
        if (updateClock()) invalidate()
        handler.postDelayed(clockRun, 20000)
    }

    fun pauseClock() {
        handler.removeCallbacks(clockRun)
    }

    fun launchpadX(): Float {
        for (s in slots) if (s.kind == 1) return s.rest
        return width / 2f
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun updateClock(): Boolean {
        val now = System.currentTimeMillis()
        val minute = now / 60000L
        if (minute == lastMinute) return false
        lastMinute = minute
        val loc = Locale.getDefault()
        val d = Date(now)
        val is24 = when (S.i(S.CK_FORMAT)) {
            1 -> false
            2 -> true
            else -> DateFormat.is24HourFormat(context)
        }
        val tp = if (is24) "HH:mm" else if (S.b(S.CK_AMPM)) "h:mm a" else "h:mm"
        timeStr = SimpleDateFormat(tp, loc).format(d)
        if (!S.b(S.CK_BLINK)) timeStr = timeStr.replace(':', ' ')
        timeStr = Tx.str(S.TX_CLOCK, timeStr)
        hourStr = SimpleDateFormat(if (is24) "HH" else "hh", loc).format(d)
        minStr = SimpleDateFormat("mm", loc).format(d)
        val dp0 = when (S.i(S.DT_FORMAT)) {
            1 -> DateFormat.getBestDateTimePattern(loc, "EEEdMMM")
            2 -> "dd/MM"
            3 -> DateFormat.getBestDateTimePattern(loc, "dMMMM")
            4 -> "EEEE"
            5 -> "EEEE dd/MM/yyyy"
            else -> DateFormat.getBestDateTimePattern(loc, "EEEEdMMMM")
        }
        dateStr = SimpleDateFormat(dp0, loc).format(d)
        dateStr = dateStr.replaceFirstChar { if (it.isLowerCase()) it.titlecase(loc) else it.toString() }
        if (S.b(S.DT_BATTERY) && battery >= 0) dateStr += "  ·  $battery %"
        dateStr = Tx.str(S.TX_DATE, dateStr)
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        greetStr = Tx.str(S.TX_GREETING, if (hr in 5..11) "Buenos días" else if (hr in 12..19) "Buenas tardes" else "Buenas noches")
        datePillW = 0f
        return true
    }

    private fun iconCenterY(row: Int): Float =
        gridTop + row * cellH + cellH / 2f - (if (Prefs.labels) dp(Prefs.labelSize * 0.75f) else 0f) + dp(S.f(S.H_ICON_DY))

    private fun homeIconRect(idx: Int, out: RectF) {
        if (idx < 0) {
            out.set(0f, 0f, 0f, 0f)
            return
        }
        val local = idx % cap
        val col = local % cols
        val row = local / cols
        val cx = gridLeft + col * cellW + cellW / 2f
        val cy = iconCenterY(row)
        out.set(cx - iconPx / 2f, cy - iconPx / 2f, cx + iconPx / 2f, cy + iconPx / 2f)
    }

    private fun dockTopY(): Float = dockCy - dockH / 2f - dp(8f)

    // ---------------------------------------------------------------- Iconos que se "hunden" al tocar
    private fun press(key: String?, slot: Slot?) {
        pressKey = key
        pressSlot = slot
        pressArea = if (slot != null) S.IA_DOCK else S.IA_HOME
        Mo.tune(pressSp, S.MO_PRESS, 700f, 1f)
        pressSp.to(1f - IconFx.pressDepth(pressArea))
        if (key != null || slot != null) Hx.fire(this, S.HE_ICON)
        ticker.kick()
    }

    private fun unpress() {
        if (pressKey == null && pressSlot == null && pressSp.target >= 1f) return
        Mo.tune(pressSp, S.MO_RELEASE, 420f, IconFx.releaseRatio(pressArea))
        pressSp.to(1f)
        ticker.kick()
        if (!pressSp.active) {
            pressKey = null
            pressSlot = null
        }
    }

    private fun pressScaleFor(key: String?): Float =
        if (key != null && key == pressKey) pressSp.value else 1f

    // ---------------------------------------------------------------- Física del dock
    private fun updateDockTargets() {
        val amt = Prefs.magnify / 100f * 0.9f
        val mode = Prefs.dockMode
        for (s in slots) {
            if (s.kind == 3) continue
            val rad = S.p(S.DOCKS_BASE + mode * 2).coerceAtLeast(0.1f)
            val t = if (scrubbing) Motion.dockScale(mode, (scrubX - s.rest) / (dockUnit * rad), amt).coerceAtMost(S.p(S.K_MAX_SCALE)) else 1f
            if (abs(t - s.sc.target) > 0.002f) s.sc.to(t)
        }
    }

    private fun onFrame(dt: Float): Boolean {
        if (scrubbing) updateDockTargets()
        if (!pressSp.active && pressSp.target >= 1f) {
            pressKey = null
            pressSlot = null
        }
        invalidate()
        return scrubbing
    }

    private fun slotOfKey(k: String): Slot? {
        for (s in slots) if (s.key == k) return s
        return null
    }

    private fun slotAt(x: Float): Slot? {
        var best: Slot? = null
        var bd = Float.MAX_VALUE
        for (s in slots) {
            if (s.kind == 3) continue
            val d = abs(x - s.cx)
            if (d < bd) {
                bd = d
                best = s
            }
        }
        return if (best != null && bd < dockUnit * best.scale * 0.62f) best else null
    }

    private fun bounce(i: Int) {
        val s = slots[i]
        val mode = Prefs.bounceAnim
        val f = S.p(S.BOUNCES_BASE + mode)
        when (mode) {
            0 -> { Mo.tune(s.by, S.MO_B_JUMP, 120f, 0.23f); s.by.velocity = 6.5f * f; s.by.to(0f) }
            1 -> { Mo.tune(s.bs, S.MO_B_PULSE, 200f, 0.28f); s.bs.velocity = 5f * f; s.bs.to(0f) }
            2 -> { Mo.tune(s.br, S.MO_B_SPIN, 90f, 0.37f); s.br.velocity = 3.4f * f; s.br.to(0f) }
            3 -> {
                for (j in slots.indices) {
                    if (slots[j].kind == 3) continue
                    val d = abs(j - i)
                    val v = max(1.8f, 6.5f - d * 1.3f) * f
                    handler.postDelayed({
                        if (j < slots.size) {
                            Mo.tune(slots[j].by, S.MO_B_WAVE, 120f, 0.23f)
                            slots[j].by.velocity = v
                            slots[j].by.to(0f)
                            ticker.kick()
                        }
                    }, d * 70L)
                }
            }
            else -> { Mo.tune(s.bx, S.MO_B_SHAKE, 160f, 0.24f); s.bx.velocity = 5.5f * f; s.bx.to(0f) }
        }
        ticker.kick()
    }

    // ---------------------------------------------------------------- Dibujo
    override fun onDraw(c: Canvas) {
        drawBackdrop(c)
        c.save()
        if (drawerP > 0.001f) {
            val s = 1f - S.p(S.H_SCALE_PANEL) * drawerP
            c.scale(s, s, width / 2f, height / 2f)
        }
        val enterOn = enterSp.value < 0.9995f || enterSp.active
        val ed = editSp.value
        if (ed > 0.001f) {
            val s = 1f - (1f - S.p(S.E_SCALE)) * ed
            c.save()
            c.scale(s, s, width / 2f, editPivotY())
        }
        drawClock(c, enterOn)
        drawPages(c, enterOn)
        drawDots(c)
        if (ed > 0.001f) c.restore()
        if (ed < 0.999f) {
            c.save()
            c.translate(0f, ed * dp(70f))
            dockFade = 1f - clamp01(ed * 1.4f)
            drawDock(c, enterOn)
            dockFade = 1f
            c.restore()
        }
        if (ed > 0.001f) drawEditBar(c, ed)
        drawDrag(c)
        c.restore()
        if (S.b(S.P_FPS)) drawFps(c)
    }

    /** oscurecer el fondo de pantalla y degradados superior/inferior (estilo One UI) */
    private fun drawBackdrop(c: Canvas) {
        val dim = S.f(S.L_WALL_DIM)
        if (dim > 0.5f) c.drawColor(Color.argb((dim * 2.55f).toInt(), 0, 0, 0))
        if (S.b(S.L_TOP_GRAD)) {
            val h = insetTop + dp(150f)
            if (topGrad == null) topGrad = android.graphics.LinearGradient(0f, 0f, 0f, h, 0xFF000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
            fxPaint.shader = topGrad
            fxPaint.alpha = (255 * S.p(S.L_TOP_GRAD_S)).toInt()
            c.drawRect(0f, 0f, width.toFloat(), h, fxPaint)
        }
        if (S.b(S.L_BOT_GRAD)) {
            val h = dp(260f)
            val top = height - h
            if (botGrad == null) botGrad = android.graphics.LinearGradient(0f, 0f, 0f, h, 0x00000000, 0xFF000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
            fxPaint.shader = botGrad
            fxPaint.alpha = (255 * S.p(S.L_BOT_GRAD_S)).toInt()
            c.save()
            c.translate(0f, top)
            c.drawRect(0f, 0f, width.toFloat(), h, fxPaint)
            c.restore()
        }
    }

    private fun drawFps(c: Canvas) {
        val now = System.nanoTime()
        if (fpsLast != 0L) {
            val dt = (now - fpsLast) / 1e9f
            if (dt in 0.001f..0.1f) fpsVal = if (fpsVal == 0f) 1f / dt else fpsVal * 0.9f + (1f / dt) * 0.1f
        }
        fpsLast = now
        hintPaint.textSize = dp(13f)
        hintPaint.textAlign = Paint.Align.LEFT
        tmpRect.set(dp(12f), insetTop + dp(6f), dp(112f), insetTop + dp(32f))
        glass.draw(c, tmpRect, dp(13f), 0.9f, false)
        c.drawText("FPS " + fpsVal.toInt() + " · " + Screen.hz.toInt() + " Hz", tmpRect.left + dp(10f), tmpRect.centerY() + dp(4.5f), hintPaint)
        hintPaint.textAlign = Paint.Align.CENTER
    }

    private var dockFade = 1f

    private fun editPivotY(): Float = (insetTop + dotsY) / 2f

    private val editVisible = IntArray(4)
    private var editCount = 4

    private fun refreshEditButtons() {
        editCount = 0
        val on = booleanArrayOf(S.b(S.E_BTN_WALL), S.b(S.E_BTN_VIEW), S.b(S.E_BTN_MOTION), S.b(S.E_BTN_SETTINGS))
        for (i in 0 until 4) if (on[i]) editVisible[editCount++] = i
    }

    private fun editBtnCenter(slot: Int, out: FloatArray) {
        val n = max(1, editCount)
        val gap = width / n.toFloat()
        out[0] = gap * slot + gap / 2f
        out[1] = (if (S.b(S.K_SHOW)) dockCy else height - insetBottom - dp(50f)) - dp(8f)
    }

    private val btnTmp = FloatArray(2)

    private fun drawEditBar(c: Canvas, ed: Float) {
        val a = clamp01(ed * 1.3f)
        val dy = (1f - clamp01(ed)) * dp(60f)
        val r = dp(27f) * S.p(S.E_BTN_SIZE)
        refreshEditButtons()
        for (slot in 0 until editCount) {
            val i = editVisible[slot]
            editBtnCenter(slot, btnTmp)
            // aparición escalonada, como One UI
            val q = clamp01(ed * 1.6f - i * 0.12f)
            val cx = btnTmp[0]
            val cy = btnTmp[1] + dy + (1f - q) * dp(24f)
            val rr = r * lerp(0.7f, 1f, q)
            tmpRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            glass.draw(c, tmpRect, rr, a * q, true, S.SF_EDIT_BTN)
            Glyphs.draw(c, editGlyphs[i], cx, cy, rr * 0.95f, Color.WHITE, a * q)
            editLabel.alpha = Tx.alpha(S.TX_EDIT, a * q)
            c.drawText(Tx.str(S.TX_EDIT, editNames[i]), cx, cy + rr + dp(19f) + Tx.dy(S.TX_EDIT), editLabel)
        }
    }

    private fun editBtnAt(x: Float, y: Float): Int {
        refreshEditButtons()
        for (slot in 0 until editCount) {
            editBtnCenter(slot, btnTmp)
            if (hypot(x - btnTmp[0], y - btnTmp[1]) < dp(40f) * S.p(S.E_BTN_SIZE)) return editVisible[slot]
        }
        return -1
    }

    private fun drawClock(c: Canvas, enterOn: Boolean) {
        var a = 1f
        var dy = 0f
        if (enterOn) {
            Motion.enterItem(Prefs.returnAnim, enterSp.value, 0, 1, itx)
            a = itx.alpha
            dy = itx.dy * 0.5f
        }
        updateClock()
        clockRect.setEmpty()
        dateRect.setEmpty()
        if (Prefs.clockStyle == 2) return
        dy += dp(S.f(S.CK_DY))
        val align = S.i(S.CK_ALIGN)
        val w = width.toFloat()
        val greet = S.b(S.DT_GREETING)
        val gOff = if (greet) dp(22f) else 0f
        val al = when (align) { 1 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
        if (Prefs.clockStyle == 1) {
            // Reloj apilado de One UI: horas sobre minutos
            val x = when (align) { 0 -> dp(26f); 2 -> w - dp(26f); else -> dp(26f) }
            val sal = if (align == 2) Paint.Align.RIGHT else Paint.Align.LEFT
            if (greet) {
                greetPaint.textAlign = sal
                greetPaint.alpha = Tx.alpha(S.TX_GREETING, a)
                c.drawText(greetStr, x, insetTop + dp(26f) + dy + Tx.dy(S.TX_GREETING), greetPaint)
            }
            stackPaint.textAlign = sal
            stackPaint.alpha = Tx.alpha(S.TX_HOURS, a)
            c.drawText(hourStr, x, insetTop + dp(100f) + gOff + dy + Tx.dy(S.TX_HOURS), stackPaint)
            minPaint.textAlign = sal
            minPaint.alpha = Tx.alpha(S.TX_MINUTES, a * 0.82f)
            c.drawText(minStr, x, insetTop + dp(184f) + gOff + dy + Tx.dy(S.TX_MINUTES), minPaint)
            clockRect.set(if (sal == Paint.Align.LEFT) x else x - dp(150f), insetTop + dp(20f) + gOff + dy, if (sal == Paint.Align.LEFT) x + dp(150f) else x, insetTop + dp(190f) + gOff + dy)
            if (S.b(S.DT_SHOW)) {
                datePaint.textAlign = sal
                datePaint.alpha = Tx.alpha(S.TX_DATE, a * 0.92f)
                val ty = insetTop + dp(214f) + gOff + dy + Tx.dy(S.TX_DATE)
                c.drawText(dateStr, x + (if (sal == Paint.Align.LEFT) dp(4f) else -dp(4f)), ty, datePaint)
                val tw = datePaint.measureText(dateStr)
                dateRect.set(if (sal == Paint.Align.LEFT) x else x - tw, ty - dp(20f), if (sal == Paint.Align.LEFT) x + tw else x, ty + dp(8f))
                datePaint.textAlign = Paint.Align.CENTER
            }
            return
        }
        val cx = when (align) { 1 -> dp(28f); 2 -> w - dp(28f); else -> w / 2f }
        if (greet) {
            greetPaint.textAlign = al
            greetPaint.alpha = Tx.alpha(S.TX_GREETING, a)
            c.drawText(greetStr, cx, insetTop + dp(26f) + dy + Tx.dy(S.TX_GREETING), greetPaint)
        }
        clockPaint.textAlign = al
        clockPaint.alpha = Tx.alpha(S.TX_CLOCK, a)
        val ty = insetTop + dp(88f) + gOff + dy + Tx.dy(S.TX_CLOCK)
        c.drawText(timeStr, cx, ty, clockPaint)
        val cw = clockPaint.measureText(timeStr)
        val cl = when (al) { Paint.Align.LEFT -> cx; Paint.Align.RIGHT -> cx - cw; else -> cx - cw / 2f }
        clockRect.set(cl, ty - clockPaint.textSize * 0.8f, cl + cw, ty + dp(4f))
        if (!S.b(S.DT_SHOW)) return
        datePaint.textAlign = al
        datePaint.alpha = Tx.alpha(S.TX_DATE, a)
        if (datePillW <= 0f) datePillW = datePaint.measureText(dateStr) + dp(30f)
        val py = insetTop + dp(100f) + gOff + dy
        val pl = when (al) { Paint.Align.LEFT -> cx - dp(4f); Paint.Align.RIGHT -> cx - datePillW + dp(4f); else -> cx - datePillW / 2f }
        tmpRect.set(pl, py, pl + datePillW, py + dp(30f))
        dateRect.set(tmpRect)
        if (S.b(S.DT_PILL)) glass.draw(c, tmpRect, dp(15f), a * 0.85f, false, S.SF_DATE)
        val tx = when (al) { Paint.Align.LEFT -> pl + dp(15f); Paint.Align.RIGHT -> pl + datePillW - dp(15f); else -> cx }
        c.drawText(dateStr, tx, py + dp(20.5f) + Tx.dy(S.TX_DATE), datePaint)
        datePaint.textAlign = Paint.Align.CENTER
    }

    private fun labelOf(e: AppEntry): String {
        var s = labelCache[e.key]
        if (s == null) {
            s = TextUtils.ellipsize(Tx.str(S.TX_LABEL_HOME, e.label), textPaint, cellW - dp(8f), TextUtils.TruncateAt.END).toString()
            labelCache[e.key] = s
        }
        return s
    }

    private fun drawIconAt(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float, label: Boolean) {
        val b = e.icon ?: return
        val al = (255f * a).toInt().coerceIn(0, 255)
        if (al == 0) return
        val sz = size * pressScaleFor(e.key) * IconFx.scale(S.IA_HOME)
        IconFx.draw(c, b, e.tint, cx, cy, sz, a, S.IA_HOME)
        if (label && Prefs.labels) {
            textPaint.alpha = Tx.alpha(S.TX_LABEL_HOME, a)
            c.drawText(labelOf(e), cx, cy + size / 2f + dp(Prefs.labelSize + S.f(S.H_LABEL_GAP)) + Tx.dy(S.TX_LABEL_HOME), textPaint)
        }
    }

    private fun drawPages(c: Canvas, enterOn: Boolean) {
        val n = pageCount()
        val pv = pageSp.value
        val mode = Prefs.pageAnim
        val w = width.toFloat()
        val first = max(0, floor(pv).toInt())
        val last = min(n - 1, ceil(pv).toInt())
        val styleEnter = Prefs.returnAnim
        for (pg in first..last) {
            val o = pg - pv
            Motion.pageT(mode, o, w, 0.5f, pt)
            val pageAlpha = pt.alpha
            c.save()
            if (mode != 2) {
                c.translate(pt.tx, 0f)
                if (pt.sc != 1f) c.scale(pt.sc, pt.sc, w / 2f, (gridTop + dotsY) / 2f)
                if (pt.rotY != 0f) Rot3D.apply(c, pt.rotY, pt.pivot * w, height / 2f)
            }
            val ed = editSp.value
            if (ed > 0.001f) {
                tmpRect.set(dp(10f), insetTop + dp(10f), w - dp(10f), dotsY + dp(18f))
                glass.draw(c, tmpRect, dp(34f), clamp01(ed) * 0.75f * pageAlpha, false, S.SF_EDIT_FRAME)
            }
            val start = pg * cap
            val end = min(homeKeys.size, start + cap)
            for (idx in start until end) {
                val key = homeKeys[idx]
                if (touchMode == M_DRAG && !dragFromDock && key == dragKey) continue
                val e = AppRepo.get(key) ?: continue
                val local = idx - start
                val col = local % cols
                val row = local / cols
                var cx = gridLeft + col * cellW + cellW / 2f
                var cy = iconCenterY(row)
                var sc = 1f
                var al = pageAlpha
                if (mode == 2) {
                    val colFrac = if (cols > 1) col / (cols - 1f) else 0.5f
                    Motion.pageT(2, o, w, colFrac, pt)
                    cx += pt.tx
                    al = pt.alpha
                }
                if (enterOn) {
                    Motion.enterItem(styleEnter, enterSp.value, idx - start, min(cap, end - start), itx)
                    cx += itx.dx
                    cy += itx.dy
                    sc = itx.sc
                    al *= itx.alpha
                }
                drawIconAt(c, e, cx, cy, iconPx * sc, al, true)
            }
            c.restore()
        }
        // marcador de destino mientras se arrastra un icono
        if (touchMode == M_DRAG && !dragFromDock) {
            val idx = dropIndex(dragX, dragY)
            val pg = pageSp.value.roundToInt()
            if (idx >= 0 && dragY > insetTop + dp(80f) && dragY < dockTopY()) {
                val local = idx - pg * cap
                if (local in 0 until cap) {
                    val col = local % cols
                    val row = local / cols
                    tmpRect.set(
                        gridLeft + col * cellW + dp(6f), gridTop + row * cellH + dp(4f),
                        gridLeft + (col + 1) * cellW - dp(6f), gridTop + (row + 1) * cellH - dp(4f)
                    )
                    glass.draw(c, tmpRect, dp(20f), 0.45f, false, S.SF_DROP)
                }
            }
        }
    }

    private fun drawDots(c: Canvas) {
        if (!S.b(S.D_SHOW)) return
        val n = pageCount()
        if (n <= 1) return
        val pv = pageSp.value
        val k = S.p(S.D_SIZE)
        val gap = dp(15f) * S.p(S.D_GAP) * max(1f, k)
        val y = dotsY + dp(S.f(S.D_DY))
        val x0 = width / 2f - (n - 1) * gap / 2f
        val style = S.i(S.D_STYLE)
        val act = S.col(S.D_COLOR, Color.WHITE)
        val idle = S.p(S.D_IDLE)
        if (style == 4) {
            tmpRect.set(x0 - dp(14f) * k, y - dp(11f) * k, x0 + (n - 1) * gap + dp(14f) * k, y + dp(11f) * k)
            glass.draw(c, tmpRect, dp(11f) * k, 0.7f, false, S.SF_DOTS)
        }
        if (style == 3) {
            hintPaint.textSize = dp(13f) * k
            hintPaint.textAlign = Paint.Align.CENTER
            hintPaint.color = act
            c.drawText((pv.roundToInt().coerceIn(0, n - 1) + 1).toString() + " / " + n, width / 2f, y + dp(4.5f) * k, hintPaint)
            hintPaint.color = Color.WHITE
            return
        }
        for (i in 0 until n) {
            val near = 1f - min(1f, abs(i - pv))
            val col = blend((act and 0x00FFFFFF) or ((255 * idle).toInt() shl 24), act, near)
            val x = x0 + i * gap
            if (i == 0 && S.b(S.D_HOME)) {
                Glyphs.draw(c, Glyphs.HOME, x, y, dp(11f) * k, col)
                continue
            }
            dotPaint.color = col
            when (style) {
                1 -> {
                    val lw = dp(9f) * k
                    tmpRect.set(x - lw / 2f, y - dp(1.5f) * k, x + lw / 2f, y + dp(1.5f) * k)
                    c.drawRoundRect(tmpRect, dp(1.5f) * k, dp(1.5f) * k, dotPaint)
                }
                2 -> {
                    val r = dp(3.2f) * k
                    val ww = r + near * dp(6f) * k
                    tmpRect.set(x - ww, y - r, x + ww, y + r)
                    c.drawRoundRect(tmpRect, r, r, dotPaint)
                }
                else -> c.drawCircle(x, y, lerp(dp(3f), dp(4.4f), near) * k, dotPaint)
            }
        }
    }

    private fun drawDock(c: Canvas, enterOn: Boolean) {
        if (slots.isEmpty() || !S.b(S.K_SHOW)) return
        val w = width.toFloat()
        val sepW = dp(14f)
        var total = 0f
        for (s in slots) {
            s.scale = s.sc.value
            total += if (s.kind == 3) sepW else dockUnit * s.scale
        }
        var fit = 1f
        if (total > w - dp(8f)) fit = (w - dp(8f)) / total
        total *= fit
        var x = (w - total) / 2f
        for (s in slots) {
            val sw = (if (s.kind == 3) sepW else dockUnit * s.scale) * fit
            s.cx = x + sw / 2f
            x += sw
        }
        var a = dockFade
        var dy = 0f
        if (enterOn) {
            Motion.enterItem(Prefs.returnAnim, enterSp.value, 0, 1, itx)
            a = itx.alpha * dockFade
            dy = (1f - clamp01(enterSp.value)) * dp(90f)
        }
        if (a <= 0.01f) return
        val capW = max(restTotal, total) + dp(S.f(S.K_EXTRA_W))
        when (S.i(S.K_SHAPE)) {
            1 -> {
                capRect.set(dp(8f), dockCy - dockH / 2f + dy, w - dp(8f), dockCy + dockH / 2f + dy)
                glass.draw(c, capRect, dockH * 0.32f, a, true, S.SF_DOCK)
            }
            2 -> capRect.set((w - capW) / 2f, dockCy - dockH / 2f + dy, (w + capW) / 2f, dockCy + dockH / 2f + dy)
            else -> {
                capRect.set((w - capW) / 2f, dockCy - dockH / 2f + dy, (w + capW) / 2f, dockCy + dockH / 2f + dy)
                glass.draw(c, capRect, dockH * 0.44f, a, true, S.SF_DOCK)
            }
        }

        val baseBottom = dockCy + dockIconPx / 2f + dy
        val lf = Motion.liftFactor(Prefs.dockMode) * S.p(S.DOCKS_BASE + Prefs.dockMode * 2 + 1)
        val dkScale = IconFx.scale(S.IA_DOCK)
        for (s in slots) {
            if (s.kind == 3) {
                dotPaint.color = Color.argb((90 * a).toInt(), 255, 255, 255)
                tmpRect.set(s.cx - dp(0.8f), dockCy - dockIconPx * 0.4f + dy, s.cx + dp(0.8f), dockCy + dockIconPx * 0.4f + dy)
                c.drawRoundRect(tmpRect, dp(1f), dp(1f), dotPaint)
                continue
            }
            val bmp: android.graphics.Bitmap? = when (s.kind) {
                1 -> AppRepo.launchpadIcon
                2 -> AppRepo.recentsIcon
                else -> AppRepo.get(s.key ?: "")?.icon
            }
            if (bmp == null) continue
            if (touchMode == M_DRAG && dragFromDock && s.key == dragKey) continue
            val scale = s.scale * (1f + s.bs.value) * (if (s === pressSlot) pressSp.value else 1f)
            val size = dockIconPx * scale * dkScale
            val lift = (s.scale - 1f) * dockIconPx * lf
            val bounceUp = max(0f, s.by.value) * dockIconPx
            val cx = s.cx + s.bx.value * dockIconPx * 0.5f
            val cy = baseBottom - size / 2f - lift - bounceUp
            val tint = if (s.kind == 0) AppRepo.get(s.key ?: "")?.tint ?: 0xFF3A5BA0.toInt() else 0xFF3A5BA0.toInt()
            IconFx.draw(c, bmp, tint, cx, cy, size, a, S.IA_DOCK, s.br.value * 360f)
            if (s.running && S.b(S.K_DOTS)) {
                dotPaint.color = S.col(S.K_DOT_COLOR, Color.WHITE)
                dotPaint.alpha = (220 * a).toInt().coerceIn(0, 255)
                c.drawCircle(s.cx, capRect.bottom - dp(5f), dp(2f) * S.p(S.K_DOT_SIZE), dotPaint)
            }
        }
    }

    private fun drawDrag(c: Canvas) {
        val k = dragKey ?: return
        if (touchMode != M_DRAG) return
        val e = AppRepo.get(k) ?: return
        if (dragY < insetTop + dp(80f) && !dragFromDock && S.b(S.H_REMOVE_DRAG)) {
            tmpRect.set(width / 2f - dp(70f), insetTop + dp(16f), width / 2f + dp(70f), insetTop + dp(58f))
            glass.draw(c, tmpRect, dp(21f), 1f, true, S.SF_REMOVE)
            hintPaint.textSize = dp(14f)
            hintPaint.textAlign = Paint.Align.CENTER
            c.drawText("Quitar", tmpRect.centerX(), tmpRect.centerY() + dp(5f), hintPaint)
        }
        val size = (if (dragFromDock) dockIconPx else iconPx) * 1.22f
        tmpRect.set(dragX - size * 0.62f, dragY - size * 0.62f - dp(14f), dragX + size * 0.62f, dragY + size * 0.62f - dp(14f))
        glass.draw(c, tmpRect, size * 0.5f, 0.35f, false, S.SF_DRAG)
        drawIconAt(c, e, dragX, dragY - dp(14f), size, 1f, false)
    }

    // ---------------------------------------------------------------- Tacto
    private fun homeKeyAt(x: Float, y: Float): String? {
        val pg = pageSp.value.roundToInt()
        val col = floor((x - gridLeft) / cellW).toInt()
        val row = ((y - gridTop) / cellH).toInt()
        if (col !in 0 until cols || row !in 0 until rows || y < gridTop) return null
        val idx = pg * cap + row * cols + col
        return if (idx in 0 until homeKeys.size) homeKeys[idx] else null
    }

    private fun dockKeyAt(x: Float): String? {
        val s = slotAt(x) ?: return null
        return s.key
    }

    private fun dropIndex(x: Float, y: Float): Int {
        if (homeKeys.isEmpty()) return -1
        val pg = pageSp.value.roundToInt()
        val col = ((x - gridLeft) / cellW).toInt().coerceIn(0, cols - 1)
        val row = ((y - gridTop) / cellH).toInt().coerceIn(0, rows - 1)
        return min(pg * cap + row * cols + col, homeKeys.size - 1)
    }

    private fun cancelLong() {
        handler.removeCallbacks(longRun)
        unpress()
    }

    private fun setPageDrag(v: Float) {
        val n = (pageCount() - 1).toFloat()
        var t = v
        val rb = S.p(S.PAGE_RUBBER).coerceAtLeast(0.01f)
        if (t < 0f) t = rubber(t, rb)
        if (t > n) t = n + rubber(t - n, rb)
        pageSp.snap(t)
    }

    private fun settle(v: Float) {
        val n = pageCount() - 1
        val raw = (pageSp.value + v * 0.25f).roundToInt()
        var target = raw.coerceIn(0, n)
        if (S.b(S.PAGE_LOOP) && n > 0) {
            if (raw > n && pageStart.roundToInt() == n) target = 0
            if (raw < 0 && pageStart.roundToInt() == 0) target = n
        }
        if (target != pageStart.roundToInt()) Hx.fire(this, S.HE_PAGE)
        Mo.tune(pageSp, S.MO_PAGE, 220f, 0.84f)
        pageSp.to(target.toFloat())
        pageSp.velocity = v
        ticker.kick()
    }

    private fun endScrub() {
        scrubbing = false
        updateDockTargets()
        ticker.kick()
    }

    private var editPaging = false
    private var editGesture = false

    private fun editTouch(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        val s = S.p(S.E_SCALE)
        // el dedo que abrió el modo edición se ignora hasta que se levante
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) editGesture = true
        if (!editGesture) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                moved = false
                editPaging = false
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                if (!moved && (abs(dx) > slop || abs(y - downY) > slop)) {
                    moved = true
                    editPaging = abs(dx) > abs(y - downY)
                }
                if (editPaging) {
                    setPageDrag(pageStart - dx / (width * s))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                if (editPaging) {
                    vt?.computeCurrentVelocity(1000)
                    settle(-(vt?.xVelocity ?: 0f) / (width * s))
                } else if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) {
                    if (pageSp.value != pageSp.value.roundToInt().toFloat()) settle(0f)
                    val b = editBtnAt(x, y)
                    exitEdit()
                    if (b >= 0) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        host?.editAction(b)
                    }
                }
                vt?.recycle()
                vt = null
            }
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isEditing) return editTouch(ev)
        // mientras se cierra el modo edición las páginas aún están encogidas: se ignoran toques
        if (editSp.value > 0.02f) return true
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host?.dismissMenu()
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt?.addMovement(ev)
                downX = x
                downY = y
                touchMode = M_NONE
                moved = false
                vTriggered = false
                inDock = S.b(S.K_SHOW) && y > dockTopY()
                pageStart = pageSp.value
                pageSp.snap(pageSp.value)
                downKey = if (inDock) dockKeyAt(x) else homeKeyAt(x, y)
                handler.removeCallbacks(longRun)
                if (downKey != null || !inDock) handler.postDelayed(longRun, if (downKey != null) S.f(S.G_ICON_LONG_MS).toLong() else S.f(S.G_LONG_MS).toLong())
                if (inDock) {
                    scrubbing = true
                    scrubX = x
                    tickSlot = slotAt(x)
                    press(null, tickSlot)
                    updateDockTargets()
                    ticker.kick()
                } else if (downKey != null) {
                    press(downKey, null)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount == 2 && !inDock && (touchMode == M_NONE || touchMode == M_PAGE || touchMode == M_VSWIPE)) {
                    cancelLong()
                    if (touchMode == M_PAGE) settle(0f)
                    touchMode = M_MULTI
                    multiDone = false
                    multiT0 = System.currentTimeMillis()
                    y0avg = (ev.getY(0) + ev.getY(1)) / 2f
                    d0 = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1)).coerceAtLeast(1f)
                    yAvg = y0avg
                    dNow = d0
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (touchMode == M_MULTI) finishMulti()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = x - downX
                val dy = y - downY
                if (touchMode == M_MULTI) {
                    if (ev.pointerCount >= 2) {
                        yAvg = (ev.getY(0) + ev.getY(1)) / 2f
                        dNow = hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))
                    }
                    return true
                }
                when (touchMode) {
                    M_DRAG -> {
                        dragX = x
                        dragY = y
                        val dir = if (!dragFromDock && x < dp(30f)) -1 else if (!dragFromDock && x > width - dp(30f)) 1 else 0
                        if (dir != edgeDir) {
                            edgeDir = dir
                            handler.removeCallbacks(edgeRun)
                            if (dir != 0) handler.postDelayed(edgeRun, 600L)
                        }
                        invalidate()
                        return true
                    }
                    M_MENU -> {
                        if (downKey != null && !S.b(S.H_LOCK) && hypot(dx, dy) > slop * 2.2f) {
                            host?.dismissMenu()
                            startDrag(x, y)
                        }
                        return true
                    }
                }
                if (inDock) {
                    scrubX = x
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        cancelLong()
                    }
                    if (touchMode == M_NONE && dy < -dp(46f) * S.p(S.G_SWIPE_DIST) && abs(dy) > abs(dx) * 1.5f) {
                        touchMode = M_VSWIPE
                        endScrub()
                        host?.gesture(S.G_DOCK_UP)
                    }
                    if (scrubbing) {
                        updateDockTargets()
                        ticker.kick()
                        // vibración suave al pasar de un icono a otro (como el trackpad de un Mac)
                        val now = slotAt(scrubX)
                        if (now != null && now !== tickSlot && moved) {
                            tickSlot = now
                            Hx.fire(this, S.HE_DOCK)
                        }
                    }
                } else {
                    if (touchMode == M_NONE && (abs(dx) > slop || abs(dy) > slop)) {
                        cancelLong()
                        moved = true
                        touchMode = if (abs(dx) > abs(dy)) M_PAGE else M_VSWIPE
                    }
                    if (touchMode == M_PAGE) {
                        setPageDrag(pageStart - dx / width)
                        invalidate()
                    } else if (touchMode == M_VSWIPE && !vTriggered) {
                        val gd = S.p(S.G_SWIPE_DIST)
                        if (dy < -dp(70f) * gd) {
                            vTriggered = true
                            host?.gesture(S.G_UP)
                        } else if (dy > dp(90f) * gd) {
                            vTriggered = true
                            host?.gesture(S.G_DOWN)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLong()
                handler.removeCallbacks(edgeRun)
                edgeDir = 0
                val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                vt?.addMovement(ev)
                when (touchMode) {
                    M_MULTI -> finishMulti()
                    M_DRAG -> finishDrag(x, y)
                    M_PAGE -> {
                        vt?.computeCurrentVelocity(1000)
                        val v = -(vt?.xVelocity ?: 0f) / width
                        val n = (pageCount() - 1).toFloat()
                        // gestos de borde: tirar más allá de la primera o la última página
                        if (pageStart.roundToInt() == 0 && pageSp.value < -0.12f && S.gestAction(S.G_LEFT_EDGE) != S.A_NONE && !S.b(S.PAGE_LOOP)) host?.gesture(S.G_LEFT_EDGE)
                        else if (pageStart.roundToInt().toFloat() == n && pageSp.value > n + 0.12f && S.gestAction(S.G_RIGHT_EDGE) != S.A_NONE && !S.b(S.PAGE_LOOP)) host?.gesture(S.G_RIGHT_EDGE)
                        settle(v)
                    }
                    M_NONE -> if (!moved && !cancelled) tap(x, y) else if (pageSp.value != pageSp.value.roundToInt().toFloat()) settle(0f)
                    else -> {}
                }
                touchMode = M_NONE
                endScrub()
                vt?.recycle()
                vt = null
                return true
            }
        }
        return true
    }

    private fun tap(x: Float, y: Float) {
        if (inDock) {
            val s = slotAt(x) ?: return
            when (s.kind) {
                1 -> host?.openDrawer()
                2 -> host?.openRecents()
                0 -> {
                    val e = AppRepo.get(s.key ?: return) ?: return
                    val i = slots.indexOf(s)
                    bounce(i)
                    val sz = dockIconPx * s.scale
                    val r = RectF(s.cx - sz / 2f, dockCy - sz / 2f, s.cx + sz / 2f, dockCy + sz / 2f)
                    host?.launch(e, r, true)
                }
            }
        } else {
            val k = homeKeyAt(x, y)
            if (k == null) {
                emptyTap(x, y)
                return
            }
            val e = AppRepo.get(k) ?: return
            val r = RectF()
            homeIconRect(homeKeys.indexOf(k), r)
            host?.launch(e, r, false)
        }
    }

    private val tapRun = Runnable {
        val n = tapCount
        tapCount = 0
        if (n == 2) host?.gesture(S.G_DTAP)
    }

    /** toque en un hueco: reloj, fecha, doble toque o triple toque */
    private fun emptyTap(x: Float, y: Float) {
        if (S.b(S.CK_TAP) && clockRect.contains(x, y)) {
            host?.special(0)
            return
        }
        if (S.b(S.DT_TAP) && dateRect.contains(x, y)) {
            host?.special(1)
            return
        }
        val now = System.currentTimeMillis()
        val win = S.f(S.G_DTAP_MS).toLong()
        tapCount = if (now - lastTapT <= win) tapCount + 1 else 1
        lastTapT = now
        handler.removeCallbacks(tapRun)
        val triple = S.gestAction(S.G_TRIPLE) != S.A_NONE
        if (tapCount >= 3 && triple) {
            tapCount = 0
            host?.gesture(S.G_TRIPLE)
        } else if (tapCount == 2) {
            if (triple) handler.postDelayed(tapRun, win) else {
                tapCount = 0
                host?.gesture(S.G_DTAP)
            }
        }
    }

    /** evalúa el gesto de dos dedos al levantar uno */
    private fun finishMulti() {
        if (multiDone) return
        multiDone = true
        val sens = S.p(S.G_PINCH).coerceAtLeast(0.2f)
        val ratio = dNow / d0
        val dy = yAvg - y0avg
        val dist = dp(60f) * S.p(S.G_SWIPE_DIST)
        when {
            ratio < 1f - 0.28f / sens -> host?.gesture(S.G_PINCH_IN)
            ratio > 1f + 0.35f / sens -> host?.gesture(S.G_PINCH_OUT)
            dy < -dist -> host?.gesture(S.G_TWO_UP)
            dy > dist -> host?.gesture(S.G_TWO_DOWN)
            System.currentTimeMillis() - multiT0 < 300 -> host?.gesture(S.G_TWO_TAP)
        }
    }

    private fun startDrag(x: Float, y: Float) {
        if (downKey == null) {
            touchMode = M_NONE
            return
        }
        dragKey = downKey
        dragFromDock = inDock
        dragX = x
        dragY = y
        touchMode = M_DRAG
        scrubbing = false
        Hx.fire(this, S.HE_LONG)
        invalidate()
    }

    private fun finishDrag(x: Float, y: Float) {
        val key = dragKey ?: return
        if (!dragFromDock) {
            if (y < insetTop + dp(80f) && S.b(S.H_REMOVE_DRAG)) {
                homeKeys.remove(key)
            } else if (S.b(S.K_SHOW) && y > dockTopY() - dp(4f)) {
                if (key !in dockKeys && dockKeys.size < S.i(S.K_MAX)) {
                    dockKeys.add(key)
                    homeKeys.remove(key)
                }
            } else {
                val to = dropIndex(x, y)
                val from = homeKeys.indexOf(key)
                if (from >= 0 && to >= 0) {
                    homeKeys.removeAt(from)
                    homeKeys.add(min(to, homeKeys.size), key)
                }
            }
        } else {
            if (y < dockTopY() - dp(70f)) {
                dockKeys.remove(key)
                // al sacarla del dock vuelve al inicio (no se pierde)
                if (key !in homeKeys) homeKeys.add(key)
            } else {
                val from = dockKeys.indexOf(key)
                var best = from
                var bd = Float.MAX_VALUE
                var count = 0
                for (s in slots) {
                    if (s.kind != 0) continue
                    val d = abs(x - s.rest)
                    if (d < bd) {
                        bd = d
                        best = count
                    }
                    count++
                }
                if (from >= 0 && best >= 0) {
                    dockKeys.removeAt(from)
                    dockKeys.add(min(best, dockKeys.size), key)
                }
            }
        }
        dragKey = null
        Prefs.setHomeKeys(homeKeys)
        Prefs.setDockKeys(dockKeys)
        relayout()
        val maxPage = (pageCount() - 1).toFloat()
        if (pageSp.value > maxPage) pageSp.to(maxPage)
        ticker.kick()
        invalidate()
    }
}
