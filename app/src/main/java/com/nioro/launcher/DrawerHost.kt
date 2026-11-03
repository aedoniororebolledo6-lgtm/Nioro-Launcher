package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * Contenedor de la vista de aplicaciones: oscurece el fondo, muestra la barra de búsqueda
 * de cristal y aloja una de las 5 vistas. Su apertura/cierre es un resorte.
 */
class DrawerHost(ctx: Context) : FrameLayout(ctx) {

    interface Callback {
        fun onProgress(p: Float)
        fun onLaunch(e: AppEntry, from: RectF)
        fun onMenu(e: AppEntry, anchor: RectF)
        fun onClosed()
        fun dismissMenu()
    }

    var callback: Callback? = null
    private val sp = SpringValue(0f, 230f, 26f, 0.001f)
    private val ticker = Ticker { onFrame() }
    private var view: AppsView? = null
    private var viewStyle = -1
    private val search = EditText(ctx)
    private val scrim = Paint()
    private var scrimShader: LinearGradient? = null
    private var insetTop = 0f
    private var insetBottom = 0f
    var isOpen = false
        private set
    private var anchorX = 0f

    init {
        setWillNotDraw(false)
        ticker.add(sp)
        visibility = GONE
        search.hint = "Buscar"
        search.setHintTextColor(0xB3FFFFFF.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 16f
        search.isSingleLine = true
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.background = GlassDrawable(22f)
        search.setPadding(dp(22f).toInt(), 0, dp(22f).toInt(), 0)
        search.gravity = Gravity.CENTER_VERTICAL
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                view?.setQuery(s?.toString() ?: "")
            }
        })
        search.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val e = view?.firstMatch()
                if (e != null) {
                    val r = RectF(width / 2f - dp(30f), height / 2f - dp(30f), width / 2f + dp(30f), height / 2f + dp(30f))
                    callback?.onLaunch(e, r)
                }
                true
            } else false
        }
    }

    fun setInsets(top: Float, bottom: Float) {
        insetTop = top
        insetBottom = bottom
        view?.insetTop = top
        view?.insetBottom = bottom
        placeSearch()
    }

    private fun placeSearch() {
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, dp(48f).toInt())
        lp.topMargin = (insetTop + dp(16f)).toInt()
        lp.leftMargin = dp(20f).toInt()
        lp.rightMargin = dp(20f).toInt()
        search.layoutParams = lp
    }

    private fun ensureView() {
        val st = Prefs.drawerStyle
        if (view != null && st == viewStyle) return
        view?.let { removeView(it) }
        val v: AppsView = when (st) {
            1 -> FanView(context)
            2 -> GridPopupView(context)
            3 -> ListAppsView(context)
            4 -> GravityView(context)
            else -> LaunchpadView(context)
        }
        viewStyle = st
        v.insetTop = insetTop
        v.insetBottom = insetBottom
        v.listener = object : AppsView.Listener {
            override fun onLaunch(e: AppEntry, from: RectF) { callback?.onLaunch(e, from) }
            override fun onMenu(e: AppEntry, anchor: RectF) { callback?.onMenu(e, anchor) }
            override fun onClose() { close() }
            override fun onTouchStart() {
                callback?.dismissMenu()
                hideKeyboard()
            }
        }
        addView(v, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (search.parent == null) {
            placeSearch()
            addView(search)
        }
        view = v
        v.setData(AppRepo.visible())
    }

    fun refreshData() {
        view?.setData(AppRepo.visible())
    }

    /** Llamar cuando cambian los ajustes (estilo de vista, tamaños...). */
    fun styleChanged() {
        if (!isOpen && Prefs.drawerStyle != viewStyle) {
            view?.let { removeView(it) }
            view = null
            viewStyle = -1
        }
    }

    fun open(anchor: Float) {
        anchorX = anchor
        ensureView()
        val v = view ?: return
        v.anchorX = anchor
        search.setText("")
        if (!isOpen) {
            isOpen = true
            visibility = VISIBLE
            v.progress = 0f
            sp.snap(0f)
            v.post { v.onOpen() }
            if (Prefs.drawerStyle == 4) sp.tune(140f, 1f) else sp.tune(230f, 0.82f)
        }
        sp.to(1f)
        ticker.kick()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        hideKeyboard()
        search.clearFocus()
        sp.tune(260f, 1f)
        sp.to(0f)
        ticker.kick()
    }

    fun closeNow() {
        isOpen = false
        hideKeyboard()
        sp.snap(0f)
        finishClose()
    }

    private fun finishClose() {
        view?.onClosed()
        view?.progress = 0f
        visibility = GONE
        callback?.onProgress(0f)
        callback?.onClosed()
        styleChanged()
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
    }

    private fun onFrame(): Boolean {
        val p = sp.value
        val v = view
        if (v != null) {
            v.progress = p
            v.invalidate()
        }
        val a = clamp01(p)
        search.alpha = a
        search.translationY = (1f - a) * -dp(30f)
        callback?.onProgress(a)
        invalidate()
        if (!sp.active && !isOpen && p <= 0.001f) {
            finishClose()
            return false
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        scrimShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xB3060A18.toInt(), 0x8C0A1230.toInt(), 0xCC050814.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(c: Canvas) {
        val a = clamp01(sp.value)
        scrim.shader = scrimShader
        scrim.alpha = (255 * a * (if (Prefs.drawerStyle == 1 || Prefs.drawerStyle == 2) 0.75f else 1f)).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Contenedor de Recientes con su propio resorte de entrada/salida. */
class RecentsHost(ctx: Context) : FrameLayout(ctx) {
    val view = RecentsView(ctx)
    private val sp = SpringValue(0f, 240f, 27f, 0.001f)
    private val ticker = Ticker { onFrame() }
    private val scrim = Paint()
    var isOpen = false
        private set
    var onProgress: ((Float) -> Unit)? = null

    init {
        setWillNotDraw(false)
        ticker.add(sp)
        visibility = GONE
        scrim.color = 0xFF050814.toInt()
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun open(items: List<AppEntry>) {
        view.setItems(items)
        isOpen = true
        visibility = VISIBLE
        if (Prefs.recentsAnim == 0) sp.tune(240f, 0.85f) else sp.tune(200f, 0.75f)
        sp.to(1f)
        ticker.kick()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        sp.tune(280f, 1f)
        sp.to(0f)
        ticker.kick()
    }

    fun closeNow() {
        isOpen = false
        sp.snap(0f)
        visibility = GONE
        onProgress?.invoke(0f)
    }

    private fun onFrame(): Boolean {
        view.progress = sp.value
        view.invalidate()
        onProgress?.invoke(clamp01(sp.value))
        invalidate()
        if (!isOpen && !sp.active) {
            visibility = GONE
            onProgress?.invoke(0f)
        }
        return false
    }

    override fun onDraw(c: Canvas) {
        scrim.alpha = (170 * clamp01(sp.value)).toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
    }

    override fun onDetachedFromWindow() {
        ticker.stop()
        super.onDetachedFromWindow()
    }
}

/** Utilidad para View.visibility legible. */
fun View.shown(): Boolean = visibility == View.VISIBLE
