package com.nioro.launcher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

fun dp(v: Float): Float = v * Resources.getSystem().displayMetrics.density
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v
fun smooth(a: Float, b: Float, x: Float): Float {
    val t = clamp01((x - a) / (b - a))
    return t * t * (3f - 2f * t)
}

/** Rotación 3D alrededor del eje Y con perspectiva suave. */
object Rot3D {
    private val cam = Camera()
    private val m = Matrix()

    fun apply(c: Canvas, rotY: Float, px: Float, py: Float) {
        val dist = Resources.getSystem().displayMetrics.widthPixels * 3.2f
        cam.save()
        cam.setLocation(0f, 0f, -dist / 72f)
        cam.rotateY(rotY)
        cam.getMatrix(m)
        cam.restore()
        m.preTranslate(-px, -py)
        m.postTranslate(px, py)
        c.concat(m)
    }
}

/**
 * Pintor de "cristal líquido" (inspirado en Liquid Glass de Apple):
 *  - sombra suave en dos capas
 *  - cuerpo translúcido teñido
 *  - "lente": brillo curvo en la mitad superior, como luz refractada
 *  - cáustica: resplandor fino en el borde inferior
 *  - borde especular doble (brillante arriba, más tenue abajo) que da grosor al cristal
 * Todos los shaders se crean una vez y solo se reubican con una matriz (cero asignaciones al dibujar).
 */
class Glass {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caustic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sh = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val tmp = RectF()

    private val fillShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x3DFFFFFF, 0x12FFFFFF, 0x0AFFFFFF, 0x1CFFFFFF),
        floatArrayOf(0f, 0.45f, 0.75f, 1f), Shader.TileMode.CLAMP
    )
    private val lensShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x52FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val causticShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x30FFFFFF),
        floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
    )
    private val rimShader = LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(0xF0FFFFFF.toInt(), 0x40FFFFFF, 0x18FFFFFF, 0x50FFFFFF, 0x99FFFFFF.toInt()),
        floatArrayOf(0f, 0.25f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP
    )

    fun draw(c: Canvas, r: RectF, rad: Float, a: Float = 1f, shadow: Boolean = true) {
        val al = if (a > 1f) 1f else a
        if (al <= 0.01f || r.width() < 1f || r.height() < 1f) return
        var k = Prefs.glass / 60f
        if (k < 0.3f) k = 0.3f
        if (k > 1.6f) k = 1.6f
        val rr = minOf(rad, r.height() / 2f, r.width() / 2f)

        if (shadow && !Prefs.saver) {
            sh.color = Color.argb((26f * al).toInt(), 0, 0, 0)
            val g1 = dp(6f)
            c.drawRoundRect(r.left - g1 * 0.5f, r.top + g1 * 0.6f, r.right + g1 * 0.5f, r.bottom + g1 * 1.6f, rr + g1, rr + g1, sh)
            sh.color = Color.argb((22f * al).toInt(), 0, 0, 0)
            val g2 = dp(2f)
            c.drawRoundRect(r.left, r.top + g2, r.right, r.bottom + g2 * 1.5f, rr + g2, rr + g2, sh)
        }

        // cuerpo teñido
        base.color = Color.argb(minOf(200f, 58f * al * k).toInt(), 12, 20, 44)
        c.drawRoundRect(r, rr, rr, base)

        val fa = minOf(1f, al * minOf(1.35f, k))
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        fillShader.setLocalMatrix(m)
        fill.shader = fillShader
        fill.alpha = (255f * fa).toInt()
        c.drawRoundRect(r, rr, rr, fill)

        // lente superior: rectángulo redondeado más pequeño en la mitad de arriba
        val inset = minOf(dp(3f), r.height() * 0.08f)
        tmp.set(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * 0.58f)
        m.setScale(1f, tmp.height())
        m.postTranslate(0f, tmp.top)
        lensShader.setLocalMatrix(m)
        lens.shader = lensShader
        lens.alpha = (255f * fa).toInt()
        val lr = maxOf(0f, rr - inset)
        c.drawRoundRect(tmp, lr, lr, lens)

        // cáustica inferior
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        causticShader.setLocalMatrix(m)
        caustic.shader = causticShader
        caustic.alpha = (255f * fa).toInt()
        c.drawRoundRect(r, rr, rr, caustic)

        // borde especular
        val sw = dp(1.1f)
        rim.strokeWidth = sw
        m.setScale(r.width(), r.height())
        m.postTranslate(r.left, r.top)
        rimShader.setLocalMatrix(m)
        rim.shader = rimShader
        rim.alpha = (255f * minOf(1f, al * minOf(1.2f, k))).toInt()
        val hw = sw / 2f
        c.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, rr - hw, rr - hw, rim)
        // borde interior oscuro: da grosor al cristal
        inner.strokeWidth = sw
        inner.color = Color.argb((34f * al).toInt(), 0, 0, 0)
        val i2 = sw * 1.5f
        c.drawRoundRect(r.left + i2, r.top + i2, r.right - i2, r.bottom - i2, maxOf(0f, rr - i2), maxOf(0f, rr - i2), inner)
    }
}

/** Desenfoque rápido en CPU (3 pasadas de caja ≈ gaussiano). Se usa solo una vez por apertura. */
object Blur {
    fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            pass(px, tmp, w, h, radius, true)
            pass(tmp, px, w, h, radius, false)
        }
        val out = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(i: IntArray, o: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val len = if (horiz) w else h
        val lines = if (horiz) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val p = i[idx(line, k.coerceIn(0, len - 1), w, horiz)]
                sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (x in 0 until len) {
                o[idx(line, x, w, horiz)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = i[idx(line, (x + r + 1).coerceAtMost(len - 1), w, horiz)]
                val rem = i[idx(line, (x - r).coerceAtLeast(0), w, horiz)]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }

    private fun idx(line: Int, x: Int, w: Int, horiz: Boolean): Int = if (horiz) line * w + x else x * w + line
}

/** Sombra suave de icono: un único bitmap difuminado que se reutiliza bajo todos los iconos. */
object IconShadow {
    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private fun get(): Bitmap {
        var b = bmp
        if (b == null) {
            val s = 96
            val pad = 24
            b = Bitmap.createBitmap(s + pad * 2, s + pad * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.translate(pad.toFloat(), pad.toFloat())
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.BLACK
            c.drawPath(AppRepo.squirclePath(s.toFloat()), p)
            b = Blur.boxBlur(b, 7)
            bmp = b
        }
        return b
    }

    /** Dibuja la sombra para un icono centrado en (cx, cy) de tamaño size. */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, a: Float) {
        if (Prefs.saver || a <= 0.02f) return
        val b = get()
        val full = size * (144f / 96f)
        val oy = size * 0.07f
        dst.set(cx - full / 2f, cy - full / 2f + oy, cx + full / 2f, cy + full / 2f + oy)
        paint.alpha = (110f * clamp01(a)).toInt()
        c.drawBitmap(b, null, dst, paint)
    }
}
