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

    private val rr2 = RectF()
    private val filters = HashMap<Int, android.graphics.ColorFilter>()
    private var gradShader: LinearGradient? = null
    private var gradA = 0
    private var gradB = 0

    private fun filter(color: Int): android.graphics.ColorFilter =
        filters.getOrPut(color) { android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.MULTIPLY) }

    /**
     * Dibuja el cristal. [surf] es la superficie (S.SF_*): con ella se aplican sus 19 ajustes
     * (tinte, borde, lente, sombra, tamaño...). Con -1 se usa el estilo base.
     */
    fun draw(c: Canvas, r0: RectF, rad0: Float, a: Float = 1f, shadow: Boolean = true, surf: Int = -1) {
        var r = r0
        var rad = rad0
        var al = if (a > 1f) 1f else a
        var tint = Color.rgb(12, 20, 44)
        var tintOp = 1f
        var kMul = 1f
        var rimW = 1f
        var rimB = 1f
        var rimColor = 0
        var lensM = 1f
        var lensH = 0.58f
        var causM = 1f
        var innerM = 1f
        var shOn = shadow
        var shS = 1f
        var shY = 1f
        var grad = false
        var tint2 = 0
        if (surf >= 0) {
            if (!S.b(S.gp(surf, S.GP_VISIBLE))) return
            val size = S.p(S.gp(surf, S.GP_SIZE))
            if (size != 1f) {
                val dw = r0.width() * (size - 1f) / 2f
                val dh = r0.height() * (size - 1f) / 2f
                rr2.set(r0.left - dw, r0.top - dh, r0.right + dw, r0.bottom + dh)
                r = rr2
            }
            tint = S.col(S.gp(surf, S.GP_TINT), tint)
            tintOp = S.p(S.gp(surf, S.GP_TINT_OP))
            kMul = S.p(S.gp(surf, S.GP_INTENSITY))
            rad *= S.p(S.gp(surf, S.GP_RADIUS))
            rimW = S.p(S.gp(surf, S.GP_RIM_W))
            rimB = S.p(S.gp(surf, S.GP_RIM_B))
            rimColor = S.col(S.gp(surf, S.GP_RIM_COLOR), 0)
            lensM = S.p(S.gp(surf, S.GP_LENS))
            lensH = S.p(S.gp(surf, S.GP_LENS_H))
            causM = S.p(S.gp(surf, S.GP_CAUSTIC))
            innerM = S.p(S.gp(surf, S.GP_INNER))
            shOn = shadow && S.b(S.gp(surf, S.GP_SHADOW))
            shS = S.p(S.gp(surf, S.GP_SHADOW_S))
            shY = S.p(S.gp(surf, S.GP_SHADOW_Y))
            al *= S.p(S.gp(surf, S.GP_ALPHA))
            grad = S.b(S.gp(surf, S.GP_GRAD))
            if (grad) tint2 = S.col(S.gp(surf, S.GP_TINT2), 0xFF8B5CF6.toInt())
            if (al > 1f) al = 1f
        }
        if (al <= 0.01f || r.width() < 1f || r.height() < 1f) return
        var k = Prefs.glass / 60f * kMul
        if (k < 0.2f) k = 0.2f
        if (k > 2f) k = 2f
        val rr = maxOf(0f, minOf(rad, r.height() / 2f, r.width() / 2f))

        val shadowQ = S.i(S.P_SHADOWS)
        if (shOn && !Prefs.saver && shadowQ > 0) {
            val g1 = dp(6f)
            if (shadowQ == 2) {
                sh.color = Color.argb((26f * al * shS).toInt().coerceIn(0, 255), 0, 0, 0)
                c.drawRoundRect(r.left - g1 * 0.5f, r.top + g1 * 0.6f * shY, r.right + g1 * 0.5f, r.bottom + g1 * 1.6f * shY, rr + g1, rr + g1, sh)
            }
            sh.color = Color.argb((22f * al * shS).toInt().coerceIn(0, 255), 0, 0, 0)
            val g2 = dp(2f)
            c.drawRoundRect(r.left, r.top + g2 * shY, r.right, r.bottom + g2 * 1.5f * shY, rr + g2, rr + g2, sh)
        }

        // cuerpo teñido (color sólido o degradado)
        val baseA = minOf(230f, 58f * al * k * tintOp).toInt().coerceIn(0, 255)
        if (grad) {
            if (gradShader == null || gradA != tint || gradB != tint2) {
                gradShader = LinearGradient(0f, 0f, 1f, 1f, tint, tint2, Shader.TileMode.CLAMP)
                gradA = tint
                gradB = tint2
            }
            m.setScale(r.width(), r.height())
            m.postTranslate(r.left, r.top)
            gradShader!!.setLocalMatrix(m)
            base.shader = gradShader
            base.alpha = baseA
        } else {
            base.shader = null
            base.color = (tint and 0x00FFFFFF) or (baseA shl 24)
        }
        c.drawRoundRect(r, rr, rr, base)

        val fa = minOf(1f, al * minOf(1.35f, k))
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        fillShader.setLocalMatrix(m)
        fill.shader = fillShader
        fill.alpha = (255f * fa).toInt()
        c.drawRoundRect(r, rr, rr, fill)

        // lente superior
        if (lensM > 0.01f) {
            val inset = minOf(dp(3f), r.height() * 0.08f)
            tmp.set(r.left + inset, r.top + inset, r.right - inset, r.top + r.height() * lensH)
            m.setScale(1f, tmp.height())
            m.postTranslate(0f, tmp.top)
            lensShader.setLocalMatrix(m)
            lens.shader = lensShader
            lens.alpha = (255f * fa * lensM).toInt().coerceIn(0, 255)
            val lr = maxOf(0f, rr - inset)
            c.drawRoundRect(tmp, lr, lr, lens)
        }

        // cáustica inferior
        if (causM > 0.01f) {
            m.setScale(1f, r.height())
            m.postTranslate(0f, r.top)
            causticShader.setLocalMatrix(m)
            caustic.shader = causticShader
            caustic.alpha = (255f * fa * causM).toInt().coerceIn(0, 255)
            c.drawRoundRect(r, rr, rr, caustic)
        }

        // borde especular
        val sw = dp(1.1f) * rimW
        if (sw > 0.05f) {
            rim.strokeWidth = sw
            m.setScale(r.width(), r.height())
            m.postTranslate(r.left, r.top)
            rimShader.setLocalMatrix(m)
            rim.shader = rimShader
            rim.colorFilter = if (rimColor != 0) filter(rimColor) else null
            rim.alpha = (255f * minOf(1f, al * minOf(1.2f, k) * rimB)).toInt().coerceIn(0, 255)
            val hw = sw / 2f
            c.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, maxOf(0f, rr - hw), maxOf(0f, rr - hw), rim)
        }
        // borde interior oscuro
        if (innerM > 0.01f) {
            val iw = dp(1.1f)
            inner.strokeWidth = iw
            inner.color = Color.argb((34f * al * innerM).toInt().coerceIn(0, 255), 0, 0, 0)
            val i2 = iw * 1.5f + (sw - dp(1.1f)).coerceAtLeast(0f)
            c.drawRoundRect(r.left + i2, r.top + i2, r.right - i2, r.bottom - i2, maxOf(0f, rr - i2), maxOf(0f, rr - i2), inner)
        }
    }
}

/** Desenfoque rápido en CPU (3 pasadas de caja ≈ gaussiano). Se usa solo una vez por apertura. */
object Blur {
    fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        // premultiplicar: así lo transparente no "ensucia" de negro los colores al promediar
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 255) continue
            px[i] = (a shl 24) or ((((p shr 16) and 255) * a / 255) shl 16) or ((((p shr 8) and 255) * a / 255) shl 8) or ((p and 255) * a / 255)
        }
        val tmp = IntArray(w * h)
        repeat(3) {
            pass(px, tmp, w, h, radius, true)
            pass(tmp, px, w, h, radius, false)
        }
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 255 || a == 0) {
                if (a == 0) px[i] = 0
                continue
            }
            val r = (((p shr 16) and 255) * 255 / a).coerceAtMost(255)
            val g = (((p shr 8) and 255) * 255 / a).coerceAtMost(255)
            val b = ((p and 255) * 255 / a).coerceAtMost(255)
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
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
    private var bmpShape = -1
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    private fun get(): Bitmap {
        var b = bmp
        if (b != null && bmpShape != AppRepo.bakedShape) {
            bmp = null
            b = null
        }
        if (b == null) {
            bmpShape = AppRepo.bakedShape
            val s = 96
            val pad = 24
            b = Bitmap.createBitmap(s + pad * 2, s + pad * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.translate(pad.toFloat(), pad.toFloat())
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = Color.BLACK
            c.drawPath(AppRepo.shapePath(maxOf(0, AppRepo.bakedShape), s.toFloat()), p)
            b = Blur.boxBlur(b, 7)
            bmp = b
        }
        return b
    }

    /** Dibuja la sombra para un icono centrado en (cx, cy) de tamaño size. */
    fun draw(c: Canvas, cx: Float, cy: Float, size: Float, a: Float) {
        if (Prefs.saver || a <= 0.02f || S.i(S.P_SHADOWS) == 0) return
        val b = get()
        val full = size * (144f / 96f)
        val oy = size * 0.07f
        dst.set(cx - full / 2f, cy - full / 2f + oy, cx + full / 2f, cy + full / 2f + oy)
        paint.alpha = (110f * clamp01(a)).toInt()
        c.drawBitmap(b, null, dst, paint)
    }
}
