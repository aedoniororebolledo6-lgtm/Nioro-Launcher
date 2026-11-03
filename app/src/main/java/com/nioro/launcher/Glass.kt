package com.nioro.launcher

import android.content.res.Resources
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
 * Pintor de "cristal líquido": sombra suave, base translúcida, relleno degradado,
 * destello diagonal y borde especular. Reutiliza pinturas y shaders (sin asignar en draw).
 */
class Glass {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sh = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()

    private val fillShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0x55FFFFFF, 0x16FFFFFF, 0x2CFFFFFF),
        floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
    )
    private val rimShader = LinearGradient(
        0f, 0f, 0f, 1f,
        intArrayOf(0xE6FFFFFF.toInt(), 0x28FFFFFF, 0x7AFFFFFF),
        floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
    )
    private val sheenShader = LinearGradient(
        0f, 0f, 1f, 1f,
        intArrayOf(0x44FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x24FFFFFF),
        floatArrayOf(0f, 0.38f, 0.7f, 1f), Shader.TileMode.CLAMP
    )

    fun draw(c: Canvas, r: RectF, rad: Float, a: Float = 1f, shadow: Boolean = true) {
        val al = if (a > 1f) 1f else a
        if (al <= 0.01f) return
        var k = Prefs.glass / 60f
        if (k < 0.3f) k = 0.3f
        if (k > 1.6f) k = 1.6f

        if (shadow) {
            for (i in 1..3) {
                sh.color = Color.argb((24f * al / i).toInt(), 0, 0, 0)
                val g = dp(3f) * i
                c.drawRoundRect(r.left - g, r.top - g + dp(5f), r.right + g, r.bottom + g + dp(5f), rad + g, rad + g, sh)
            }
        }

        base.color = Color.argb(minOf(210f, 74f * al * k).toInt(), 10, 18, 38)
        c.drawRoundRect(r, rad, rad, base)

        val fa = minOf(1f, al * minOf(1.3f, k))
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        fillShader.setLocalMatrix(m)
        fill.shader = fillShader
        fill.alpha = (255f * fa).toInt()
        c.drawRoundRect(r, rad, rad, fill)

        m.setScale(r.width(), r.height())
        m.postTranslate(r.left, r.top)
        sheenShader.setLocalMatrix(m)
        sheen.shader = sheenShader
        sheen.alpha = (255f * fa).toInt()
        c.drawRoundRect(r, rad, rad, sheen)

        val sw = dp(1.2f)
        rim.strokeWidth = sw
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        rimShader.setLocalMatrix(m)
        rim.shader = rimShader
        rim.alpha = (255f * minOf(1f, al * minOf(1.2f, k))).toInt()
        val hw = sw / 2f
        c.drawRoundRect(r.left + hw, r.top + hw, r.right - hw, r.bottom - hw, rad - hw, rad - hw, rim)
    }
}
