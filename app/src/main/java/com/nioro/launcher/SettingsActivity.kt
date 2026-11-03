package com.nioro.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * Ajustes con estética One UI + Smart Launcher:
 *  - cabecera grande que ocupa la parte alta (zona de "alcance" de One UI) y se colapsa al desplazar
 *  - tarjeta principal con vista previa en vivo y botón para hacerlo predeterminado
 *  - categorías con iconos de color en tarjetas de cristal agrupadas
 *  - subpáginas que entran deslizando con la curva de animación de One UI
 */
class SettingsActivity : Activity() {

    companion object {
        val OPEN = arrayOf("Zoom", "Resorte", "Cristal", "Giro 3D", "Deslizar")
        val RETURN = arrayOf("Zoom", "Rebote", "Cascada", "Cristal", "Deslizar")
        val DOCK = arrayOf("macOS", "Resorte", "Ola", "Lupa", "Sutil")
        val BOUNCE = arrayOf("Salto", "Pulso", "Giro", "Ola", "Sacudir")
        val PAGE = arrayOf("Plana", "Cubo", "Cascada", "Zoom", "Carrusel")
        val RECENTS = arrayOf("Carrusel", "Pila 3D", "Cover Flow", "Rejilla", "Burbujas")
        val DRAWER = arrayOf("Launchpad", "Abanico", "Rejilla", "Lista", "Gravedad")
        val DRAWER_DESC = arrayOf(
            "Páginas a pantalla completa, como el Launchpad del Mac",
            "Los iconos salen del dock en curva",
            "Panel de cristal flotante sobre el dock",
            "Lista alfabética con índice A‑Z",
            "Los iconos caen como esferas con física real"
        )
        val CLOCK = arrayOf("Nioro", "One UI apilado", "Sin reloj")

        const val PAGE_HOME = 0
        const val PAGE_DOCK = 1
        const val PAGE_APPS = 2
        const val PAGE_MOTION = 3
        const val PAGE_RECENTS = 4
        const val PAGE_LOOK = 5
        const val PAGE_BATTERY = 6
        const val PAGE_ABOUT = 7
        private const val REQ_HOME = 7

        val CAT_TITLES = arrayOf(
            "Pantalla de inicio", "Dock", "Vista de apps", "Animaciones",
            "Apps en segundo plano", "Apariencia", "Batería y rendimiento", "Acerca de"
        )
        val CAT_SUBS = arrayOf(
            "Reloj, cuadrícula, iconos y nombres",
            "Magnificación, rebote y tamaño",
            "5 formas de ver todas tus apps",
            "Abrir, volver y cambiar de página",
            "Estilo de recientes y acceso de uso",
            "Cristal líquido y desenfoque",
            "Ahorro, 120 Hz y consumo",
            "Versión y actualizaciones"
        )
        val CAT_GLYPHS = intArrayOf(
            Glyphs.HOME, Glyphs.DOCK, Glyphs.GRID, Glyphs.MOTION,
            Glyphs.RECENTS, Glyphs.PALETTE, Glyphs.BATTERY, Glyphs.INFO
        )
    }

    private lateinit var stack: FrameLayout
    private val pages = ArrayList<View>()
    private var insetTop = 0
    private var insetBottom = 0
    private val ease = PathInterpolator(0.22f, 0.25f, 0f, 1f) // curva de One UI
    private var heroStatus: (() -> Unit)? = null
    private var triedHomeSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Compat.edgeToEdge(this)
        Compat.applyRefreshRate(this)
        stack = FrameLayout(this)
        stack.addView(BackgroundView(this), FrameLayout.LayoutParams(-1, -1))
        setContentView(stack)
        stack.setOnApplyWindowInsetsListener { _, ins ->
            val (t, b) = Compat.bars(ins)
            if (t != insetTop || b != insetBottom) {
                insetTop = t
                insetBottom = b
                for (p in pages) (p.tag as? (() -> Unit))?.invoke()
            }
            ins
        }
        push(buildMain(), false)
        val start = intent.getIntExtra("page", -1)
        if (start in 0..7) stack.post { push(buildCategory(start), false) }
    }

    override fun onResume() {
        super.onResume()
        heroStatus?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        // algunas capas (MIUI) cancelan el diálogo al instante: se abre el ajuste del sistema
        if (requestCode == REQ_HOME && !isDefaultHome() && resultCode != RESULT_OK && !triedHomeSettings) {
            triedHomeSettings = true
            tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        heroStatus?.invoke()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pages.size > 1) {
            pop()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.stay, R.anim.fade_zoom_out)
    }

    // ---------------------------------------------------------------- Navegación
    private var navBusy = false

    private fun push(v: View, animate: Boolean = true) {
        if (navBusy) return
        val prev = pages.lastOrNull()
        pages.add(v)
        stack.addView(v, FrameLayout.LayoutParams(-1, -1))
        if (animate && prev != null) {
            navBusy = true
            val w = stack.width.toFloat().coerceAtLeast(1f)
            v.translationX = w
            v.animate().translationX(0f).setDuration(420).setInterpolator(ease).withEndAction { navBusy = false }.start()
            prev.animate().translationX(-w * 0.28f).alpha(0f).setDuration(420).setInterpolator(ease)
                .withEndAction { prev.visibility = View.INVISIBLE }.start()
        } else if (prev != null) {
            prev.translationX = -stack.width * 0.28f
            prev.alpha = 0f
            prev.visibility = View.INVISIBLE
        }
    }

    private fun pop() {
        if (pages.size < 2 || navBusy) return
        navBusy = true
        val top = pages.removeAt(pages.size - 1)
        val prev = pages.last()
        val w = stack.width.toFloat()
        // la página que sale ya no responde a toques
        (top as? FrameLayout)?.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(-1, -1))
        prev.visibility = View.VISIBLE
        top.animate().translationX(w).setDuration(380).setInterpolator(ease)
            .withEndAction {
                stack.removeView(top)
                navBusy = false
            }.start()
        prev.animate().translationX(0f).alpha(1f).setDuration(380).setInterpolator(ease).start()
        heroStatus?.invoke()
    }

    // ---------------------------------------------------------------- Página con cabecera One UI
    /**
     * Crea una página: la parte alta muestra un título grande; al desplazar, el título se
     * desvanece y aparece una barra compacta con el título pequeño (como en los Ajustes de Samsung).
     */
    private fun page(title: String, subtitle: String?, root: Boolean, body: (LinearLayout) -> Unit): View {
        val frame = FrameLayout(this)
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        scroll.addView(col)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // Cabecera grande
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER
        val big = text(title, 34f, 400)
        big.gravity = Gravity.CENTER
        header.addView(big)
        if (subtitle != null) {
            val sub = text(subtitle, 14f, 400, OneColors.SUB)
            sub.gravity = Gravity.CENTER
            sub.setPadding(0, dp(6f).toInt(), 0, 0)
            header.addView(sub)
        }
        col.addView(header, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.3f).toInt()))

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        val ph = dp(16f).toInt()
        content.setPadding(ph, 0, ph, dp(28f).toInt())
        col.addView(content)
        body(content)

        // Barra compacta
        val bar = FrameLayout(this)
        bar.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF2070B1A.toInt(), 0x00070B1A))
        val small = text(title, 19f, 500)
        small.alpha = 0f
        val sp = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL)
        sp.leftMargin = dp(if (root) 22f else 60f).toInt()
        bar.addView(small, sp)
        if (!root) {
            val back = View(this)
            back.background = GlyphDrawable(Glyphs.BACK, 26f)
            back.foreground = ripple(dp(22f))
            back.setOnClickListener { pop() }
            val bp = FrameLayout.LayoutParams(dp(44f).toInt(), dp(44f).toInt(), Gravity.CENTER_VERTICAL)
            bp.leftMargin = dp(10f).toInt()
            bar.addView(back, bp)
        }
        frame.addView(bar, FrameLayout.LayoutParams(-1, dp(64f).toInt()))

        val relayout = {
            (bar.layoutParams as FrameLayout.LayoutParams).height = insetTop + dp(60f).toInt()
            bar.setPadding(0, insetTop, 0, 0)
            bar.requestLayout()
            content.setPadding(ph, 0, ph, dp(28f).toInt() + insetBottom)
        }
        frame.tag = relayout
        relayout()

        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val h = header.height.toFloat().coerceAtLeast(1f)
            val t = clamp01(y / (h * 0.55f))
            big.alpha = 1f - t
            big.translationY = y * 0.35f
            big.scaleX = 1f - 0.08f * t
            big.scaleY = 1f - 0.08f * t
            small.alpha = smooth(0.6f, 1f, t)
        }
        return frame
    }

    // ---------------------------------------------------------------- Página principal
    private fun buildMain(): View = page("Nioro Launcher", "Cristal líquido · dock macOS · One UI", true) { c ->
        // Tarjeta principal (vista previa + predeterminado)
        val hero = card(c)
        val pv = HeroView(this)
        hero.addView(pv, LinearLayout.LayoutParams(-1, dp(210f).toInt()))
        val status = text("", 15f, 500)
        status.gravity = Gravity.CENTER
        status.setPadding(0, dp(14f).toInt(), 0, dp(4f).toInt())
        hero.addView(status)
        val btn = pill("Establecer como pantalla de inicio", true) { makeDefault() }
        hero.addView(btn, LinearLayout.LayoutParams(-1, dp(52f).toInt()).apply { topMargin = dp(10f).toInt() })
        heroStatus = {
            if (isDefaultHome()) {
                status.text = "✓  Nioro Launcher es tu pantalla de inicio"
                status.setTextColor(0xFF7CE0A3.toInt())
                btn.visibility = View.GONE
            } else {
                status.text = "Nioro Launcher aún no es tu pantalla de inicio"
                status.setTextColor(OneColors.SUB)
                btn.visibility = View.VISIBLE
            }
        }
        heroStatus?.invoke()

        // Categorías agrupadas como en los Ajustes de One UI
        val groups = arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4), intArrayOf(5, 6), intArrayOf(7))
        for (g in groups) {
            val cc = card(c, dp(6f).toInt())
            for ((n, i) in g.withIndex()) {
                if (n > 0) divider(cc, dp(72f))
                categoryRow(cc, i) { push(buildCategory(i)) }
            }
        }
        val tip = text(
            "Mantén presionado el fondo del inicio para el modo edición · desliza hacia arriba para ver todas las apps",
            12.5f, 400, OneColors.SUB
        )
        tip.gravity = Gravity.CENTER
        tip.setPadding(dp(20f).toInt(), dp(20f).toInt(), dp(20f).toInt(), 0)
        c.addView(tip)
    }

    private fun buildCategory(i: Int): View = page(CAT_TITLES[i], null, false) { c ->
        when (i) {
            PAGE_HOME -> {
                val k = card(c)
                choice(k, "Reloj", CLOCK, Prefs.clockStyle, -1, null) { Prefs.clockStyle = it }
                divider(k, 0f)
                slider(k, "Columnas", 4, 5, Prefs.columns, "") { Prefs.columns = it }
                divider(k, 0f)
                slider(k, "Tamaño de iconos", 46, 70, Prefs.iconDp, " dp") { Prefs.iconDp = it }
                divider(k, 0f)
                toggle(k, "Mostrar nombres", "Texto bajo cada icono", Prefs.labels) { Prefs.labels = it }
                val k2 = card(c, dp(6f).toInt())
                button(k2, "Fondo de pantalla", null, Glyphs.WALLPAPER) {
                    tryStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
                }
                divider(k2, dp(60f))
                button(k2, "Mostrar apps ocultas", Prefs.hidden().size.toString() + " ocultas", Glyphs.GRID) {
                    Prefs.setHidden(emptyList())
                    toast("Apps visibles de nuevo")
                }
                divider(k2, dp(60f))
                button(k2, "Restablecer inicio y dock", "Vuelve a la organización inicial", Glyphs.HOME) {
                    Prefs.resetLayout()
                    toast("Se reorganizará al volver al inicio")
                }
            }
            PAGE_DOCK -> {
                val k = card(c)
                choice(k, "Magnificación", DOCK, Prefs.dockMode, PreviewView.DOCK, null) { Prefs.dockMode = it }
                divider(k, 0f)
                slider(k, "Intensidad de magnificación", 0, 100, Prefs.magnify, "%") { Prefs.magnify = it }
                divider(k, 0f)
                slider(k, "Tamaño del dock", 42, 64, Prefs.dockDp, " dp") { Prefs.dockDp = it }
                val k2 = card(c)
                choice(k2, "Rebote al tocar", BOUNCE, Prefs.bounceAnim, PreviewView.BOUNCE, null) { Prefs.bounceAnim = it }
            }
            PAGE_APPS -> {
                val k = card(c)
                choice(k, "Forma de ver todas las apps", DRAWER, Prefs.drawerStyle, -1, DRAWER_DESC) { Prefs.drawerStyle = it }
                val k2 = card(c, dp(6f).toInt())
                toggle(k2, "Gravedad con inclinación", "Al inclinar el móvil, los iconos ruedan (solo en la vista Gravedad)", Prefs.tilt) { Prefs.tilt = it }
            }
            PAGE_MOTION -> {
                val k = card(c)
                choice(k, "Abrir apps", OPEN, Prefs.openAnim, PreviewView.OPEN, null) { Prefs.openAnim = it }
                val k2 = card(c)
                choice(k2, "Volver al inicio", RETURN, Prefs.returnAnim, PreviewView.RETURN, null) { Prefs.returnAnim = it }
                val k3 = card(c)
                choice(k3, "Cambio de página", PAGE, Prefs.pageAnim, PreviewView.PAGE, null) { Prefs.pageAnim = it }
                val k4 = card(c)
                slider(k4, "Velocidad de animaciones", 50, 200, Prefs.speedPct, "%") { Prefs.speedPct = it }
            }
            PAGE_RECENTS -> {
                val k = card(c)
                choice(k, "Estilo", RECENTS, Prefs.recentsAnim, -1, null) { Prefs.recentsAnim = it }
                val k2 = card(c, dp(6f).toInt())
                val has = Recents.hasUsageAccess(this)
                button(k2, "Acceso de uso", if (has) "Activado · ves todas tus apps recientes" else "Desactivado · tócalo para activarlo", Glyphs.RECENTS) {
                    tryStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            PAGE_LOOK -> {
                val k = card(c)
                slider(k, "Intensidad del cristal", 20, 100, Prefs.glass, "%") { Prefs.glass = it }
                divider(k, 0f)
                toggle(k, "Desenfoque", "Esmerila el fondo al abrir paneles (el fondo de pantalla, desde Android 12)", Prefs.blur) { Prefs.blur = it }
            }
            PAGE_BATTERY -> {
                val k = card(c)
                toggle(k, "Modo ahorro", "Pantalla a 60 Hz, sin sombras ni desenfoque", Prefs.saver) {
                    Prefs.saver = it
                    Compat.applyRefreshRate(this)
                }
                val info = text(
                    "Fuera del modo ahorro, la pantalla sube a su frecuencia máxima (" + Screen.hz.toInt() +
                        " Hz ahora) solo mientras algo se mueve. En reposo el launcher no dibuja nada ni usa la CPU.",
                    13f, 400, OneColors.SUB
                )
                info.setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(20f).toInt(), 0)
                c.addView(info)
            }
            PAGE_ABOUT -> {
                val k = card(c)
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (t: Throwable) { "?" }
                button(k, "Versión", ver, Glyphs.INFO) {}
                divider(k, dp(60f))
                button(k, "Descargar la última versión", "Abre GitHub para instalar la actualización", Glyphs.CHECK) {
                    tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aedoniororebolledo6-lgtm/Nioro-Launcher/releases/latest")))
                }
                val t = text("Hecho a medida: Kotlin nativo, sin HTML. Física de resortes exacta, cristal líquido dibujado a mano y cero animaciones en reposo.", 13f, 400, OneColors.SUB)
                t.setPadding(dp(20f).toInt(), dp(14f).toInt(), dp(20f).toInt(), 0)
                c.addView(t)
            }
        }
    }

    // ---------------------------------------------------------------- Predeterminado
    private fun isDefaultHome(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
            }
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
        } catch (t: Throwable) {
            false
        }
    }

    private fun makeDefault() {
        triedHomeSettings = false
        try {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
                return
            }
        } catch (t: Throwable) {
        }
        tryStart(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ---------------------------------------------------------------- Componentes
    private fun text(s: String, size: Float, weight: Int, color: Int = OneColors.TEXT): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        return t
    }

    private fun ripple(radius: Float): RippleDrawable {
        val mask = GradientDrawable()
        mask.cornerRadius = radius
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(0x26FFFFFF), null, mask)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun tryStart(i: Intent) {
        try {
            startActivity(i)
        } catch (t: Throwable) {
            toast("No disponible en este móvil")
        }
    }

    private fun card(parent: LinearLayout, padV: Int = dp(18f).toInt()): LinearLayout {
        val k = LinearLayout(this)
        k.orientation = LinearLayout.VERTICAL
        k.background = GlassDrawable(28f)
        k.setPadding(dp(4f).toInt(), padV, dp(4f).toInt(), padV)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(14f).toInt()
        parent.addView(k, lp)
        return k
    }

    private fun divider(parent: LinearLayout, inset: Float) {
        val d = View(this)
        d.setBackgroundColor(0x1FFFFFFF)
        val lp = LinearLayout.LayoutParams(-1, dp(1f).toInt().coerceAtLeast(1))
        lp.leftMargin = (dp(18f) + inset).toInt()
        lp.rightMargin = dp(18f).toInt()
        lp.topMargin = dp(4f).toInt()
        lp.bottomMargin = dp(4f).toInt()
        parent.addView(d, lp)
    }

    private fun pill(label: String, accent: Boolean, run: () -> Unit): TextView {
        val b = text(label, 16f, 600)
        b.gravity = Gravity.CENTER
        val g = GradientDrawable()
        g.cornerRadius = dp(26f)
        if (accent) {
            g.colors = intArrayOf(0xFF4FA0FF.toInt(), 0xFF2F7BFF.toInt())
            g.orientation = GradientDrawable.Orientation.TOP_BOTTOM
        } else {
            g.setColor(0x26FFFFFF)
        }
        b.background = g
        b.foreground = ripple(dp(26f))
        b.setOnClickListener { run() }
        return b
    }

    private fun categoryRow(parent: LinearLayout, i: Int, run: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14f).toInt(), dp(10f).toInt(), dp(10f).toInt(), dp(10f).toInt())
        row.background = ripple(dp(22f))
        row.setOnClickListener { run() }
        val icon = CategoryIconView(this, CAT_GLYPHS[i], OneColors.CATS[i])
        row.addView(icon, LinearLayout.LayoutParams(dp(42f).toInt(), dp(42f).toInt()))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(16f).toInt(), 0, dp(8f).toInt(), 0)
        texts.addView(text(CAT_TITLES[i], 17f, 500))
        val sub = text(CAT_SUBS[i], 13.5f, 400, OneColors.SUB)
        sub.setPadding(0, dp(2f).toInt(), 0, 0)
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val chev = View(this)
        chev.background = GlyphDrawable(Glyphs.CHEVRON, 18f, 0x80FFFFFF.toInt())
        row.addView(chev, LinearLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt()))
        parent.addView(row)
    }

    private fun button(parent: LinearLayout, title: String, sub: String?, glyph: Int, run: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16f).toInt(), dp(12f).toInt(), dp(12f).toInt(), dp(12f).toInt())
        row.background = ripple(dp(22f))
        row.setOnClickListener { run() }
        val g = View(this)
        g.background = GlyphDrawable(glyph, 24f, 0xE6FFFFFF.toInt())
        row.addView(g, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.setPadding(dp(20f).toInt(), 0, dp(8f).toInt(), 0)
        texts.addView(text(title, 16.5f, 500))
        if (sub != null) {
            val s = text(sub, 13.5f, 400, OneColors.SUB)
            s.setPadding(0, dp(2f).toInt(), 0, 0)
            texts.addView(s)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row)
    }

    private fun toggle(parent: LinearLayout, title: String, sub: String?, cur: Boolean, set: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(20f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
        row.background = ripple(dp(22f))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(text(title, 16.5f, 500))
        if (sub != null) {
            val s = text(sub, 13.5f, 400, OneColors.SUB)
            s.setPadding(0, dp(2f).toInt(), dp(10f).toInt(), 0)
            texts.addView(s)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = OneSwitch(this)
        sw.set(cur)
        sw.onChange = set
        row.addView(sw)
        row.setOnClickListener { sw.set(!sw.checked, true) }
        parent.addView(row)
    }

    private fun slider(parent: LinearLayout, title: String, min: Int, max: Int, cur: Int, unit: String, set: (Int) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6f).toInt(), dp(10f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.setPadding(dp(14f).toInt(), 0, dp(14f).toInt(), 0)
        head.addView(text(title, 16.5f, 500), LinearLayout.LayoutParams(0, -2, 1f))
        val v = text("$cur$unit", 15f, 600, OneColors.ACCENT)
        head.addView(v)
        box.addView(head)
        val s = OneSlider(this, min, max, cur)
        s.onChange = { x ->
            v.text = "$x$unit"
            set(x)
        }
        box.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6f).toInt() })
        parent.addView(box)
    }

    /** Selector de 5 estilos (fichas) con vista previa animada y descripción opcional. */
    private fun choice(parent: LinearLayout, title: String, names: Array<String>, cur: Int, preview: Int, desc: Array<String>?, set: (Int) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
        val t = text(title, 16.5f, 500)
        t.setPadding(dp(6f).toInt(), 0, 0, dp(10f).toInt())
        box.addView(t)
        var pv: PreviewView? = null
        if (preview >= 0) {
            val v = PreviewView(this, preview)
            v.style = cur
            box.addView(v, LinearLayout.LayoutParams(-1, dp(150f).toInt()).apply { bottomMargin = dp(12f).toInt() })
            pv = v
        }
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.overScrollMode = View.OVER_SCROLL_NEVER
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val chips = ArrayList<TextView>()
        for (i in names.indices) {
            val c = text(names[i], 14.5f, 500)
            c.gravity = Gravity.CENTER
            c.setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(16f).toInt(), dp(10f).toInt())
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(8f).toInt()
            chips.add(c)
            row.addView(c, lp)
        }
        var descView: TextView? = null
        if (desc != null) {
            val d = text(desc[cur.coerceIn(0, desc.size - 1)], 13.5f, 400, OneColors.SUB)
            d.setPadding(dp(6f).toInt(), dp(12f).toInt(), 0, dp(2f).toInt())
            descView = d
        }
        fun paint(sel: Int) {
            for (i in chips.indices) {
                val g = GradientDrawable()
                g.cornerRadius = dp(20f)
                if (i == sel) {
                    g.setColor(OneColors.ACCENT)
                    chips[i].setTextColor(Color.WHITE)
                } else {
                    g.setColor(0x1FFFFFFF)
                    g.setStroke(dp(1f).toInt(), 0x33FFFFFF)
                    chips[i].setTextColor(0xD9FFFFFF.toInt())
                }
                chips[i].background = g
            }
            if (desc != null) descView?.text = desc[sel.coerceIn(0, desc.size - 1)]
        }
        paint(cur)
        for (i in chips.indices) {
            chips[i].foreground = ripple(dp(20f))
            chips[i].setOnClickListener {
                set(i)
                paint(i)
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                pv?.let { p -> p.style = i; p.play() }
            }
        }
        hs.addView(row)
        box.addView(hs)
        descView?.let { box.addView(it) }
        parent.addView(box)
    }
}

/** Fondo de los ajustes: degradado profundo con dos "luces" de color, dibujado una sola vez. */
class BackgroundView(ctx: Context) : View(ctx) {
    private val base = Paint()
    private val blob1 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blob2 = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        base.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0A1230.toInt(), 0xFF050816.toInt(), Shader.TileMode.CLAMP)
        blob1.shader = RadialGradient(w * 0.15f, h * 0.12f, w * 0.9f, 0x663E91FF, 0x003E91FF, Shader.TileMode.CLAMP)
        blob2.shader = RadialGradient(w * 0.95f, h * 0.75f, w * 0.85f, 0x557C5CFF, 0x007C5CFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), base)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blob1)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blob2)
    }
}

/**
 * Vista previa del launcher en la tarjeta principal (estilo Smart Launcher):
 * un "móvil" en miniatura con tus iconos reales y el dock haciendo la magnificación una vez.
 */
class HeroView(ctx: Context) : View(ctx) {
    private val ticker = Ticker { onFrame(it) }
    private var t = 0f
    private val glass = Glass()
    private val screen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clock = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = RectF()
    private val colors = intArrayOf(
        0xFF5AC8FA.toInt(), 0xFFFF9F0A.toInt(), 0xFF34C759.toInt(), 0xFFFF375F.toInt(),
        0xFFBF5AF2.toInt(), 0xFFFFD60A.toInt(), 0xFF64D2FF.toInt(), 0xFFFF6482.toInt()
    )

    init {
        clock.color = Color.WHITE
        clock.textAlign = Paint.Align.CENTER
        clock.typeface = Typeface.create(Typeface.DEFAULT, 200, false)
    }

    private val startRun = Runnable { ticker.kick() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        t = 0f
        postDelayed(startRun, 350)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(startRun)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    private fun onFrame(dt: Float): Boolean {
        t += dt
        invalidate()
        return t < 3.4f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        screen.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF2B4FC4.toInt(), 0xFF5B2FA8.toInt(), 0xFF101A44.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun drawApp(c: Canvas, i: Int, cx: Float, cy: Float, s: Float) {
        val apps = AppRepo.apps
        val b = if (apps.size > 8) apps[(i * 7 + 3) % apps.size].icon else null
        d.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        if (b != null) {
            c.drawBitmap(b, null, d, icon)
        } else {
            icon.color = colors[i % colors.size]
            c.drawRoundRect(d, s * 0.24f, s * 0.24f, icon)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // "pantalla" en horizontal para que quepa en la tarjeta
        r.set(dp(8f), dp(4f), w - dp(8f), h - dp(4f))
        c.drawRoundRect(r, dp(24f), dp(24f), screen)
        clock.textSize = dp(40f)
        clock.alpha = 255
        c.drawText("12:30", w / 2f, r.top + dp(52f), clock)
        // fila de apps
        val s = dp(34f)
        val n = 6
        val gap = (r.width() - dp(32f)) / n
        for (i in 0 until n) drawApp(c, i, r.left + dp(16f) + gap * (i + 0.5f), r.top + dp(96f), s)
        // dock con magnificación
        val m = 6
        val unit = dp(38f)
        val base = dp(30f)
        val sweep = clamp01((t - 0.2f) / 3f)
        val finger = if (t > 0.2f && t < 3.2f) w * 0.15f + w * 0.7f * (0.5f - 0.5f * kotlin.math.cos(sweep * Math.PI.toFloat() * 2f)) else -9999f
        val x0 = (w - m * unit) / 2f
        val sc = FloatArray(m)
        var sum = 0f
        for (i in 0 until m) {
            val rest = x0 + unit * (i + 0.5f)
            sc[i] = if (finger > -999f) Motion.dockScale(Prefs.dockMode, (finger - rest) / unit, 0.6f) else 1f
            sum += unit * sc[i]
        }
        val bottom = r.bottom - dp(18f)
        d.set(x0 - dp(10f), bottom - base - dp(10f), x0 + m * unit + dp(10f), bottom + dp(10f))
        glass.draw(c, d, dp(18f), 1f)
        var x = (w - sum) / 2f
        val lf = Motion.liftFactor(Prefs.dockMode)
        for (i in 0 until m) {
            val sw = unit * sc[i]
            val sz = base * sc[i]
            drawApp(c, i + 11, x + sw / 2f, bottom - sz / 2f - (sc[i] - 1f) * base * lf, sz)
            x += sw
        }
    }
}
