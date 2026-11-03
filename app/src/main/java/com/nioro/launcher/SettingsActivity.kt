package com.nioro.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Ajustes: 7 categorías con 5 estilos cada una (con vista previa animada) + apariencia y batería. */
class SettingsActivity : Activity() {

    companion object {
        val OPEN = arrayOf("Zoom", "Resorte", "Cristal", "Giro 3D", "Deslizar")
        val RETURN = arrayOf("Zoom", "Rebote", "Cascada", "Cristal", "Deslizar")
        val DOCK = arrayOf("macOS", "Resorte", "Ola", "Lupa", "Sutil")
        val BOUNCE = arrayOf("Salto", "Pulso", "Giro", "Ola", "Sacudir")
        val PAGE = arrayOf("Plana", "Cubo", "Cascada", "Zoom", "Carrusel")
        val RECENTS = arrayOf("Carrusel", "Pila 3D", "Cover Flow", "Rejilla", "Burbujas")
        val DRAWER = arrayOf("Launchpad", "Abanico", "Rejilla", "Lista", "Gravedad")
    }

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Compat.edgeToEdge(this)
        Compat.applyRefreshRate(this)
        val scroll = ScrollView(this)
        scroll.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1A3A8F.toInt(), 0xFF0B1638.toInt(), 0xFF2A1450.toInt())
        )
        col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val pad = dp(18f).toInt()
        col.setPadding(pad, pad, pad, pad)
        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val (top, bottom) = Compat.bars(ins)
            col.setPadding(pad, pad + top, pad, pad + bottom)
            ins
        }
        setContentView(scroll)
        build()
    }

    private fun build() {
        col.addView(text("Nioro Launcher", 30f, true).apply { setPadding(0, dp(8f).toInt(), 0, 0) })
        col.addView(text("Cristal líquido · dock macOS · One UI", 14f, false).apply { alpha = 0.75f })

        section("Inicio") {
            button(it, "Establecer como launcher predeterminado") {
                tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
            }
            button(it, "Acceso de uso (apps recientes y puntitos del dock)") {
                tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            button(it, "Fondo de pantalla") {
                tryStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
            }
        }

        choice("Vista de todas las apps", DRAWER, Prefs.drawerStyle, -1) { Prefs.drawerStyle = it }
        choice("Abrir apps", OPEN, Prefs.openAnim, PreviewView.OPEN) { Prefs.openAnim = it }
        choice("Volver al inicio", RETURN, Prefs.returnAnim, PreviewView.RETURN) { Prefs.returnAnim = it }
        choice("Apps en segundo plano", RECENTS, Prefs.recentsAnim, -1) { Prefs.recentsAnim = it }
        choice("Magnificación del dock", DOCK, Prefs.dockMode, PreviewView.DOCK) { Prefs.dockMode = it }
        choice("Rebote al tocar el dock", BOUNCE, Prefs.bounceAnim, PreviewView.BOUNCE) { Prefs.bounceAnim = it }
        choice("Cambio de página", PAGE, Prefs.pageAnim, PreviewView.PAGE) { Prefs.pageAnim = it }

        section("Apariencia") {
            slider(it, "Velocidad de animaciones", 50, 200, Prefs.speedPct, "%") { v -> Prefs.speedPct = v }
            slider(it, "Intensidad del cristal", 20, 100, Prefs.glass, "%") { v -> Prefs.glass = v }
            slider(it, "Tamaño de iconos", 46, 70, Prefs.iconDp, " dp") { v -> Prefs.iconDp = v }
            slider(it, "Tamaño del dock", 42, 64, Prefs.dockDp, " dp") { v -> Prefs.dockDp = v }
            slider(it, "Magnificación del dock", 0, 100, Prefs.magnify, "%") { v -> Prefs.magnify = v }
            slider(it, "Columnas", 4, 5, Prefs.columns, "") { v -> Prefs.columns = v }
            toggle(it, "Mostrar nombres", Prefs.labels) { v -> Prefs.labels = v }
            toggle(it, "Desenfoque detrás de menús (Android 12+)", Prefs.blur) { v -> Prefs.blur = v }
            toggle(it, "Gravedad: inclinar el móvil mueve los iconos", Prefs.tilt) { v -> Prefs.tilt = v }
        }

        section("Batería") {
            toggle(it, "Modo ahorro (pantalla a 60 Hz y sin desenfoque)", Prefs.saver) { v ->
                Prefs.saver = v
                Compat.applyRefreshRate(this)
            }
            it.addView(text("El launcher solo dibuja mientras algo se mueve. En reposo no usa CPU ni GPU.", 12.5f, false).apply {
                alpha = 0.7f
                setPadding(0, dp(6f).toInt(), 0, dp(4f).toInt())
            })
        }

        section("Organizar") {
            button(it, "Mostrar apps ocultas (" + Prefs.hidden().size + ")") {
                Prefs.setHidden(emptyList())
                Toast.makeText(this, "Apps visibles de nuevo", Toast.LENGTH_SHORT).show()
            }
            button(it, "Restablecer inicio y dock") {
                Prefs.resetLayout()
                Toast.makeText(this, "Se reorganizará al volver al inicio", Toast.LENGTH_SHORT).show()
            }
        }

        col.addView(text(
            "Gestos: desliza hacia arriba para ver todas las apps · hacia abajo para notificaciones · " +
                "mantén presionado un icono para su menú o para moverlo · mantén presionado el fondo para estos ajustes.",
            12.5f, false
        ).apply {
            alpha = 0.7f
            setPadding(0, dp(18f).toInt(), 0, dp(30f).toInt())
        })
    }

    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.stay, R.anim.fade_zoom_out)
    }

    private fun tryStart(i: Intent) {
        try {
            startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(this, "No disponible en este móvil", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Constructores de UI
    private fun text(s: String, size: Float, bold: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(Color.WHITE)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        return t
    }

    private fun section(title: String, body: (LinearLayout) -> Unit) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = GlassDrawable(26f)
        val p = dp(18f).toInt()
        card.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(16f).toInt()
        card.layoutParams = lp
        card.addView(text(title, 18f, true).apply { setPadding(0, 0, 0, dp(8f).toInt()) })
        body(card)
        col.addView(card)
    }

    private fun choice(title: String, names: Array<String>, cur: Int, preview: Int, set: (Int) -> Unit) {
        section(title) { card ->
            var pv: PreviewView? = null
            if (preview >= 0) {
                val v = PreviewView(this, preview)
                v.style = cur
                v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150f).toInt())
                card.addView(v)
                pv = v
            }
            val hs = HorizontalScrollView(this)
            hs.isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(10f).toInt(), 0, 0)
            val chips = ArrayList<TextView>()
            for (i in names.indices) {
                val c = text((i + 1).toString() + " · " + names[i], 14f, false)
                c.gravity = Gravity.CENTER
                val ph = dp(14f).toInt()
                c.setPadding(ph, dp(9f).toInt(), ph, dp(9f).toInt())
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.rightMargin = dp(8f).toInt()
                c.layoutParams = lp
                chips.add(c)
                row.addView(c)
            }
            fun paint(sel: Int) {
                for (i in chips.indices) {
                    val g = GradientDrawable()
                    g.cornerRadius = dp(18f)
                    if (i == sel) {
                        g.setColor(0xF2FFFFFF.toInt())
                        chips[i].setTextColor(0xFF0B1638.toInt())
                    } else {
                        g.setColor(0x26FFFFFF)
                        g.setStroke(dp(1f).toInt(), 0x55FFFFFF)
                        chips[i].setTextColor(Color.WHITE)
                    }
                    chips[i].background = g
                }
            }
            paint(cur)
            for (i in chips.indices) {
                chips[i].setOnClickListener {
                    set(i)
                    paint(i)
                    pv?.let { p -> p.style = i; p.play() }
                }
            }
            hs.addView(row)
            card.addView(hs)
        }
    }

    private fun slider(parent: LinearLayout, label: String, min: Int, max: Int, cur: Int, unit: String, set: (Int) -> Unit) {
        val t = text("$label: $cur$unit", 14f, false)
        t.setPadding(0, dp(8f).toInt(), 0, 0)
        parent.addView(t)
        val sb = SeekBar(this)
        sb.max = max - min
        sb.progress = cur - min
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + min
                t.text = "$label: $v$unit"
                if (fromUser) set(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        parent.addView(sb)
    }

    private fun toggle(parent: LinearLayout, label: String, cur: Boolean, set: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = label
        s.textSize = 14f
        s.setTextColor(Color.WHITE)
        s.isChecked = cur
        s.setPadding(0, dp(10f).toInt(), 0, dp(4f).toInt())
        s.setOnCheckedChangeListener { _, v -> set(v) }
        parent.addView(s)
    }

    private fun button(parent: LinearLayout, label: String, run: () -> Unit) {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.background = GlassDrawable(18f, 0.8f)
        b.stateListAnimator = null
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50f).toInt())
        lp.topMargin = dp(6f).toInt()
        b.layoutParams = lp
        b.setOnClickListener { run() }
        parent.addView(b)
    }
}

/**
 * Vista previa animada de un estilo usando las MISMAS funciones de Motion que el launcher.
 * Se reproduce al elegir un estilo o al tocarla.
 */
class PreviewView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val OPEN = 0
        const val RETURN = 1
        const val DOCK = 2
        const val BOUNCE = 3
        const val PAGE = 4
    }

    var style = 0
    private val ticker = Ticker { onFrame(it) }
    private val sp = ticker.add(SpringValue(0f, 200f, 25f, 0.002f))
    private val bsp = ticker.add(SpringValue(0f, 120f, 5f, 0.002f))
    private var t = 0f
    private var playing = false
    private val glass = Glass()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scr = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val r2 = RectF()
    private val of = Motion.OF()
    private val it0 = Motion.IT()
    private val pt = Motion.PT()
    private val colors = intArrayOf(
        0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(),
        0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt()
    )

    init {
        setOnClickListener { play() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scr.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF243B7A.toInt(), 0xFF4B2A7A.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed({ play() }, 300)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }

    fun play() {
        t = 0f
        playing = true
        when (kind) {
            OPEN -> { Motion.openTune(style, sp); sp.snap(0f); sp.to(1f) }
            RETURN -> { Motion.enterTune(style, sp); sp.snap(0f); sp.to(1f) }
            PAGE -> { sp.tune(220f, 0.9f); sp.snap(0f); sp.to(1f) }
            BOUNCE -> {
                bsp.snap(0f)
                when (style) {
                    0, 3 -> { bsp.stiffness = 120f; bsp.damping = 5f; bsp.velocity = 6.5f }
                    1 -> { bsp.stiffness = 200f; bsp.damping = 8f; bsp.velocity = 5f }
                    2 -> { bsp.stiffness = 90f; bsp.damping = 7f; bsp.velocity = 3.4f }
                    else -> { bsp.stiffness = 160f; bsp.damping = 6f; bsp.velocity = 5.5f }
                }
                bsp.to(0f)
            }
        }
        ticker.kick()
    }

    private fun onFrame(dt: Float): Boolean {
        t += dt * Prefs.speed
        invalidate()
        if (kind == DOCK) {
            if (t > 3.2f) { playing = false; return false }
            return true
        }
        if (kind == BOUNCE && style == 3 && t < 1.2f) return true
        return false
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, dp(20f), dp(20f), scr)
        c.save()
        c.clipRect(0f, 0f, w, h)
        when (kind) {
            OPEN -> drawOpen(c, w, h)
            RETURN -> drawReturn(c, w, h)
            DOCK -> drawDock(c, w, h)
            BOUNCE -> drawBounce(c, w, h)
            PAGE -> drawPage(c, w, h)
        }
        c.restore()
    }

    private fun sq(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, a: Float) {
        icon.color = color
        icon.alpha = (255 * clamp01(a)).toInt()
        r2.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        c.drawRoundRect(r2, s * 0.24f, s * 0.24f, icon)
    }

    private fun drawOpen(c: Canvas, w: Float, h: Float) {
        // "teléfono" en miniatura
        val ph = h - dp(16f)
        val pw = ph * 0.5f
        val px = w / 2f - pw / 2f
        val screen = RectF(px, dp(8f), px + pw, dp(8f) + ph)
        glass.draw(c, screen, dp(14f), 0.8f, false)
        val s = pw / 5f
        for (i in 0 until 6) {
            sq(c, px + pw * (0.2f + (i % 3) * 0.3f), screen.top + ph * (0.3f + (i / 3) * 0.22f), s, colors[i], 1f)
        }
        val from = RectF(px + pw * 0.5f - s / 2f, screen.top + ph * 0.52f - s / 2f, px + pw * 0.5f + s / 2f, screen.top + ph * 0.52f + s / 2f)
        Motion.openFrame(style, sp.value, from, screen, of)
        r.set(of.l, of.t, of.r, of.b)
        c.save()
        if (of.rotY != 0f) Rot3D.apply(c, of.rotY, r.centerX(), r.centerY())
        icon.color = colors[4]
        icon.alpha = (255 * clamp01(max(of.bg, of.iconA))).toInt()
        val rad = max(dp(2f), min(of.corner, r.width() / 2f) * (pw / w))
        c.drawRoundRect(r, rad + dp(4f) * (1f - sp.value), rad + dp(4f) * (1f - sp.value), icon)
        if (style == 2) glass.draw(c, r, dp(8f), 0.8f, false)
        c.restore()
    }

    private fun drawReturn(c: Canvas, w: Float, h: Float) {
        val s = dp(34f)
        val n = 8
        for (i in 0 until n) {
            Motion.enterItem(style, sp.value, i, n, it0)
            val cx = w * (0.2f + (i % 4) * 0.2f) + it0.dx * 0.5f
            val cy = h * (0.3f + (i / 4) * 0.4f) + it0.dy * 0.4f
            sq(c, cx, cy, s * it0.sc, colors[i % colors.size], it0.alpha)
        }
    }

    private fun drawDock(c: Canvas, w: Float, h: Float) {
        val n = 7
        val unit = dp(40f)
        val base = dp(34f)
        // un "dedo" que barre el dock de lado a lado
        val sweep = (t / 3f).coerceIn(0f, 1f)
        val finger = if (playing) w * 0.1f + w * 0.8f * (0.5f - 0.5f * kotlin.math.cos(sweep * Math.PI.toFloat() * 2f)) else -9999f
        val total = n * unit
        val x0 = (w - total) / 2f
        val amt = Prefs.magnify / 100f * 0.9f
        val scales = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val rest = x0 + unit * (i + 0.5f)
            scales[i] = if (finger > -999f) Motion.dockScale(style, (finger - rest) / unit, amt) else 1f
            sum += unit * scales[i]
        }
        var x = (w - sum) / 2f
        val bottom = h - dp(26f)
        r.set(x0 - dp(10f), bottom - base - dp(10f), x0 + total + dp(10f), bottom + dp(10f))
        glass.draw(c, r, dp(18f), 1f)
        val lf = Motion.liftFactor(style)
        for (i in 0 until n) {
            val sw = unit * scales[i]
            val cx = x + sw / 2f
            val sz = base * scales[i]
            val lift = (scales[i] - 1f) * base * lf
            sq(c, cx, bottom - sz / 2f - lift, sz, colors[i % colors.size], 1f)
            x += sw
        }
    }

    private fun drawBounce(c: Canvas, w: Float, h: Float) {
        val base = dp(40f)
        val bottom = h - dp(30f)
        r.set(w / 2f - dp(120f), bottom - base - dp(10f), w / 2f + dp(120f), bottom + dp(10f))
        glass.draw(c, r, dp(18f), 1f)
        for (i in 0 until 5) {
            val cx = w / 2f + (i - 2) * dp(46f)
            var dy = 0f
            var sc = 1f
            var rot = 0f
            var dx = 0f
            val v = bsp.value
            if (i == 2 || style == 3) {
                val k = if (style == 3) {
                    val d = abs(i - 2)
                    val lt = t - d * 0.07f
                    if (lt < 0f) 0f else max(0f, kotlin.math.sin(lt * 9f) * kotlin.math.exp(-lt * 3f)) * (1f - d * 0.2f)
                } else v
                when (style) {
                    0, 3 -> dy = max(0f, if (style == 3) k * 0.9f else k) * base
                    1 -> sc = 1f + v
                    2 -> rot = v * 360f
                    else -> dx = v * base * 0.5f
                }
            }
            c.save()
            c.rotate(rot, cx + dx, bottom - base / 2f - dy)
            sq(c, cx + dx, bottom - base / 2f - dy, base * sc, colors[i], 1f)
            c.restore()
        }
    }

    private fun drawPage(c: Canvas, w: Float, h: Float) {
        val pv = sp.value
        val pw = w * 0.6f
        val left = (w - pw) / 2f
        for (pg in 0..1) {
            val o = pg - pv
            Motion.pageT(style, o, pw, 0.5f, pt)
            c.save()
            c.clipRect(left - dp(4f), 0f, left + pw + dp(4f), h)
            if (style != 2) {
                c.translate(pt.tx, 0f)
                if (pt.sc != 1f) c.scale(pt.sc, pt.sc, w / 2f, h / 2f)
                if (pt.rotY != 0f) Rot3D.apply(c, pt.rotY, left + pt.pivot * pw, h / 2f)
            }
            for (i in 0 until 8) {
                val colI = i % 4
                var cx = left + pw * (0.14f + colI * 0.24f)
                var a = pt.alpha
                if (style == 2) {
                    Motion.pageT(2, o, pw, colI / 3f, pt)
                    cx += pt.tx
                    a = pt.alpha
                }
                val cy = h * (0.32f + (i / 4) * 0.36f)
                sq(c, cx, cy, dp(30f), colors[(i + pg * 3) % colors.size], a)
            }
            c.restore()
        }
    }
}
