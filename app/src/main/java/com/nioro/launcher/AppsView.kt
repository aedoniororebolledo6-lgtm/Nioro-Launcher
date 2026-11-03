package com.nioro.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale

/**
 * Base de las 5 vistas de aplicaciones. Cada una dibuja todo en un solo Canvas.
 * El "progress" (0 -> 1) lo controla DrawerHost con un resorte; las vistas solo
 * lo leen para sus efectos de entrada y salida.
 */
abstract class AppsView(ctx: Context) : View(ctx) {

    interface Listener {
        fun onLaunch(e: AppEntry, from: RectF)
        fun onMenu(e: AppEntry, anchor: RectF)
        fun onClose()
        fun onTouchStart()
    }

    var listener: Listener? = null
    var progress = 0f
    var insetTop = 0f
    var insetBottom = 0f
    var anchorX = 0f

    protected var apps: List<AppEntry> = emptyList()
    protected var shown: List<AppEntry> = emptyList()
    protected var query = ""
    protected val ticker = Ticker { dt -> onFrame(dt) }
    protected val handler = Handler(Looper.getMainLooper())
    protected val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    protected val glass = Glass()
    protected val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    protected val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    protected val dst = RectF()
    private val labelCache = HashMap<String, String>()
    private var labelWidth = 0f

    init {
        textPaint.color = Color.WHITE
        textPaint.textSize = dp(11.5f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
    }

    open fun setData(list: List<AppEntry>) {
        apps = list
        refilter()
    }

    fun setQuery(q: String) {
        query = q.trim().lowercase(Locale.getDefault())
        refilter()
    }

    private fun refilter() {
        shown = if (query.isEmpty()) apps else apps.filter { it.label.lowercase().contains(query) }
        labelCache.clear()
        onDataChanged()
        invalidate()
    }

    fun firstMatch(): AppEntry? = if (shown.isEmpty()) null else shown[0]

    abstract fun onFrame(dt: Float): Boolean
    open fun onDataChanged() {}
    open fun onOpen() {}
    open fun onClosed() {}

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        ticker.stop()
        super.onDetachedFromWindow()
    }

    protected fun labelOf(e: AppEntry, maxW: Float): String {
        if (maxW != labelWidth) {
            labelCache.clear()
            labelWidth = maxW
        }
        var s = labelCache[e.key]
        if (s == null) {
            s = TextUtils.ellipsize(e.label, textPaint, maxW, TextUtils.TruncateAt.END).toString()
            labelCache[e.key] = s
        }
        return s
    }

    protected fun drawIcon(c: Canvas, e: AppEntry, cx: Float, cy: Float, size: Float, a: Float) {
        val b = e.icon ?: return
        val al = (255f * a).toInt().coerceIn(0, 255)
        if (al == 0) return
        bmpPaint.alpha = al
        dst.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        c.drawBitmap(b, null, dst, bmpPaint)
    }

    protected fun drawLabel(c: Canvas, e: AppEntry, cx: Float, y: Float, maxW: Float, a: Float) {
        if (!Prefs.labels) return
        val al = (255f * a).toInt().coerceIn(0, 255)
        if (al == 0) return
        textPaint.alpha = al
        c.drawText(labelOf(e, maxW), cx, y, textPaint)
    }
}
