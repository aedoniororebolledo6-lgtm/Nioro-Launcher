package com.nioro.launcher

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Resorte amortiguado (masa = 1). Es la base de todas las animaciones:
 * en vez de duraciones fijas usa física, así que se siente "líquido" y
 * reacciona bien si el usuario interrumpe un gesto (conserva la velocidad).
 */
class SpringValue(
    v: Float = 0f,
    var stiffness: Float = 280f,
    var damping: Float = 24f,
    private val rest: Float = 0.001f
) {
    var value = v
    var target = v
    var velocity = 0f
    var active = false

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
        active = false
    }

    fun to(t: Float) {
        target = t
        active = true
    }

    /** ratio < 1 rebota, = 1 crítico, > 1 sobreamortiguado. */
    fun tune(stiff: Float, ratio: Float) {
        stiffness = stiff
        damping = 2f * ratio * sqrt(stiff)
    }

    fun step(dt: Float): Boolean {
        if (!active) return false
        var left = dt
        while (left > 0f) {
            val h = if (left > 0.008f) 0.008f else left
            val a = -stiffness * (value - target) - damping * velocity
            velocity += a * h
            value += velocity * h
            left -= h
        }
        if (abs(value - target) < rest && abs(velocity) < rest * 20f) {
            value = target
            velocity = 0f
            active = false
        }
        return active
    }
}

/**
 * Bucle de animación bajo demanda: solo pide fotogramas mientras algún resorte
 * (o la física) siga en movimiento. En reposo no consume nada de batería.
 */
class Ticker(private val frame: (Float) -> Boolean) : Choreographer.FrameCallback {
    val springs = ArrayList<SpringValue>()
    private var running = false
    private var last = 0L
    private var skip = false

    fun <T : SpringValue> add(s: T): T {
        springs.add(s)
        return s
    }

    val isRunning: Boolean get() = running

    fun kick() {
        if (!running) {
            running = true
            last = 0L
            skip = false
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (Prefs.saver) {
            // Modo ahorro: a la mitad de fotogramas (p. ej. 120 Hz -> 60 Hz).
            skip = !skip
            if (skip) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
        }
        val dt = if (last == 0L) 0.0166f else ((frameTimeNanos - last) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        last = frameTimeNanos
        var any = false
        val sdt = dt * Prefs.speed
        for (i in 0 until springs.size) {
            if (springs[i].step(sdt)) any = true
        }
        val more = frame(dt)
        if (any || more) {
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            running = false
        }
    }
}

/** Desplazamiento 1D con inercia y rebote elástico en los bordes, hecho con un resorte. */
class Scroll1D(ticker: Ticker) {
    val sp = ticker.add(SpringValue(0f, 90f, 19f, 0.3f))
    var min = 0f
    var max = 0f

    val value: Float get() = sp.value

    fun jump(v: Float) = sp.snap(v)

    fun drag(delta: Float) {
        var v = sp.value + delta
        if (v < min) v = sp.value + delta * 0.35f
        if (v > max) v = sp.value + delta * 0.35f
        sp.snap(v)
    }

    fun release(vel: Float) {
        var tgt = sp.value + vel * 0.4f
        if (tgt < min) tgt = min
        if (tgt > max) tgt = max
        sp.to(tgt)
        sp.velocity = vel
    }

    fun clampNow() {
        if (sp.value < min) sp.to(min)
        if (sp.value > max) sp.to(max)
    }
}
