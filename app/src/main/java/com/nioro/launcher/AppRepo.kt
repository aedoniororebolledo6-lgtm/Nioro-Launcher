package com.nioro.launcher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.AdaptiveIconDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import java.util.concurrent.Executors

class AppEntry(val key: String, val pkg: String, val cls: String, val label: String) {
    var icon: Bitmap? = null
    var tint: Int = 0xFF3A5BA0.toInt()
}

/** Lista de apps instaladas + iconos "horneados" con forma squircle y brillo de cristal. */
object AppRepo {
    var apps: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<String, Bitmap>()
    private val tintCache = HashMap<String, Int>()
    var launchpadIcon: Bitmap? = null
        private set
    var recentsIcon: Bitmap? = null
        private set

    fun get(key: String): AppEntry? = byKey[key]

    fun visible(): List<AppEntry> {
        val h = Prefs.hidden()
        return apps.filter { it.key !in h }
    }

    fun firstOfPackage(pkg: String): AppEntry? {
        for (a in apps) if (a.pkg == pkg) return a
        return null
    }

    fun intentFor(e: AppEntry): Intent {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.component = ComponentName(e.pkg, e.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return i
    }

    private fun px(): Int = dp(72f).toInt()

    fun load(ctx: Context, done: () -> Unit) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                val pm = app.packageManager
                val q = Intent(Intent.ACTION_MAIN)
                q.addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(q, 0)
                val out = ArrayList<AppEntry>()
                for (ri in list) {
                    val ai = ri.activityInfo ?: continue
                    val key = ai.packageName + "/" + ai.name
                    val label = try { ri.loadLabel(pm).toString() } catch (t: Throwable) { ai.packageName }
                    val e = AppEntry(key, ai.packageName, ai.name, label)
                    val cached = cache[key]
                    if (cached != null) {
                        e.icon = cached
                        e.tint = tintCache[key] ?: e.tint
                    } else {
                        val d = try { ri.loadIcon(pm) } catch (t: Throwable) { null }
                        if (d != null) {
                            val b = bake(renderContent(d, px()))
                            e.icon = b
                            e.tint = average(b)
                            cache[key] = b
                            tintCache[key] = e.tint
                        }
                    }
                    out.add(e)
                }
                out.sortBy { it.label.lowercase() }
                if (launchpadIcon == null) buildSpecials()
                val map = HashMap<String, AppEntry>()
                for (e in out) map[e.key] = e
                main.post {
                    apps = out
                    byKey = map
                    done()
                }
            } catch (t: Throwable) {
                main.post { done() }
            }
        }
    }

    // ---------------- Iconos ----------------
    fun squirclePath(size: Float): Path {
        val p = Path()
        val r = size / 2.0
        val n = 5.0
        val steps = 128
        for (i in 0..steps) {
            val t = i * 2.0 * Math.PI / steps
            val ct = Math.cos(t)
            val st = Math.sin(t)
            val x = r + r * Math.signum(ct) * Math.pow(Math.abs(ct), 2.0 / n)
            val y = r + r * Math.signum(st) * Math.pow(Math.abs(st), 2.0 / n)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close()
        return p
    }

    private fun renderContent(d: Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (d is AdaptiveIconDrawable) {
            val ext = (px * 0.25f).toInt()
            val bg = d.background
            val fg = d.foreground
            if (bg != null) {
                bg.setBounds(-ext, -ext, px + ext, px + ext)
                bg.draw(c)
            }
            if (fg != null) {
                fg.setBounds(-ext, -ext, px + ext, px + ext)
                fg.draw(c)
            }
        } else {
            c.drawColor(0xFFF1F3F8.toInt())
            val inset = (px * 0.14f).toInt()
            d.setBounds(inset, inset, px - inset, px - inset)
            d.draw(c)
        }
        return b
    }

    /** Aplica la máscara squircle y hornea el borde y el brillo superior "liquid glass". */
    private fun bake(content: Bitmap): Bitmap {
        val size = content.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val path = squirclePath(size.toFloat())
        val mask = Paint(Paint.ANTI_ALIAS_FLAG)
        mask.color = Color.WHITE
        c.drawPath(path, mask)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(content, 0f, 0f, p)
        content.recycle()

        val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        gloss.shader = LinearGradient(
            0f, 0f, 0f, size * 0.62f,
            intArrayOf(0x4DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, gloss)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG)
        rim.style = Paint.Style.STROKE
        rim.strokeWidth = size * 0.022f
        rim.shader = LinearGradient(
            0f, 0f, 0f, size.toFloat(),
            intArrayOf(0xB3FFFFFF.toInt(), 0x14FFFFFF, 0x66FFFFFF), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawPath(path, rim)
        return out
    }

    private fun average(b: Bitmap): Int {
        return try {
            val s = Bitmap.createScaledBitmap(b, 1, 1, true)
            val col = s.getPixel(0, 0)
            if (s !== b) s.recycle()
            col or 0xFF000000.toInt()
        } catch (t: Throwable) {
            0xFF3A5BA0.toInt()
        }
    }

    private fun buildSpecials() {
        val size = px()
        // Launchpad: degradado + rejilla 3x3 de cuadritos de colores.
        val a = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ca = Canvas(a)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF6A4CFF.toInt(), 0xFF22B5FF.toInt(), Shader.TileMode.CLAMP
        )
        ca.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val cols = intArrayOf(
            0xFFFFD166.toInt(), 0xFFFF7A90.toInt(), 0xFFB9F27C.toInt(),
            0xFFFFFFFF.toInt(), 0xFF7CE0FF.toInt(), 0xFFFFA24C.toInt(),
            0xFFC7A6FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF8CFFC4.toInt()
        )
        val cell = size / 5.2f
        val gap = cell * 0.38f
        val start = (size - (cell * 3 + gap * 2)) / 2f
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (r in 0 until 3) for (cc in 0 until 3) {
            dot.color = cols[r * 3 + cc]
            val l = start + cc * (cell + gap)
            val t = start + r * (cell + gap)
            ca.drawRoundRect(RectF(l, t, l + cell, t + cell), cell * 0.3f, cell * 0.3f, dot)
        }
        launchpadIcon = bake(a)

        // Recientes: tres tarjetas apiladas.
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cb = Canvas(b)
        val bg2 = Paint(Paint.ANTI_ALIAS_FLAG)
        bg2.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            0xFF1B2440.toInt(), 0xFF4A5FA8.toInt(), Shader.TileMode.CLAMP
        )
        cb.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg2)
        val card = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = size * 0.5f
        val h = size * 0.34f
        for (i in 0 until 3) {
            card.color = Color.argb(70 + i * 80, 255, 255, 255)
            val l = (size - w) / 2f + (i - 1) * size * 0.04f
            val t = size * 0.2f + i * size * 0.16f
            cb.drawRoundRect(RectF(l, t, l + w, t + h), size * 0.07f, size * 0.07f, card)
        }
        recentsIcon = bake(b)
    }

    // ---------------- Apps por defecto ----------------
    fun defaultDockKeys(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val intents = ArrayList<Intent>()
        intents.add(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
        intents.add(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        intents.add(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        intents.add(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        intents.add(Intent(Settings.ACTION_SETTINGS))
        val keys = ArrayList<String>()
        for (i in intents) {
            try {
                val ri = pm.queryIntentActivities(i, PackageManager.MATCH_DEFAULT_ONLY)
                for (r in ri) {
                    val pkg = r.activityInfo?.packageName ?: continue
                    if (pkg == "android") continue
                    val e = firstOfPackage(pkg) ?: continue
                    if (e.key !in keys) {
                        keys.add(e.key)
                        break
                    }
                }
            } catch (t: Throwable) {
            }
        }
        return keys
    }

    fun seedHome(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val preferred = listOf(
            "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.sec.android.gallery3d",
            "com.google.android.apps.photos", "com.google.android.apps.maps", "com.android.vending",
            "com.google.android.youtube", "com.whatsapp", "com.android.chrome", "com.sec.android.app.sbrowser",
            "com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.samsung.android.calendar",
            "com.google.android.calendar", "com.spotify.music", "com.instagram.android",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.google.android.gm",
            "com.samsung.android.app.notes", "com.sec.android.app.myfiles", "com.netflix.mediaclient",
            "org.telegram.messenger", "com.anthropic.claude", "com.openai.chatgpt", "com.nioro.life"
        )
        val out = ArrayList<String>()
        for (p in preferred) {
            val e = firstOfPackage(p) ?: continue
            if (e.key !in out) out.add(e.key)
        }
        for (e in apps) {
            if (out.size >= 20) break
            if (e.key in out) continue
            try {
                val flags = pm.getApplicationInfo(e.pkg, 0).flags
                if (flags and ApplicationInfo.FLAG_SYSTEM == 0) out.add(e.key)
            } catch (t: Throwable) {
            }
        }
        return out
    }
}

/** Apps recientes: con "Acceso de uso" usa el historial real; si no, las que abrió este launcher. */
object Recents {
    private val session = ArrayList<String>()
    private var loaded = false

    private fun ensure() {
        if (!loaded) {
            loaded = true
            session.addAll(Prefs.recentKeys())
        }
    }

    fun note(key: String) {
        ensure()
        session.remove(key)
        session.add(0, key)
        while (session.size > 24) session.removeAt(session.size - 1)
        Prefs.setRecentKeys(session)
    }

    fun forget(key: String) {
        ensure()
        session.remove(key)
        Prefs.setRecentKeys(session)
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        return try {
            val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (t: Throwable) {
            false
        }
    }

    /** paquete -> última vez en primer plano (ms) durante las últimas 12 h. */
    private fun usagePackages(ctx: Context): LinkedHashMap<String, Long> {
        val res = LinkedHashMap<String, Long>()
        if (!hasUsageAccess(ctx)) return res
        try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val ev = usm.queryEvents(now - 12L * 3600_000L, now)
            val e = UsageEvents.Event()
            val last = HashMap<String, Long>()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last[e.packageName] = e.timeStamp
            }
            val sorted = last.entries.sortedByDescending { it.value }
            for (en in sorted) res[en.key] = en.value
        } catch (t: Throwable) {
        }
        return res
    }

    fun list(ctx: Context): List<AppEntry> {
        ensure()
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        val hidden = Prefs.hidden()
        for ((pkg, _) in usagePackages(ctx)) {
            if (pkg == ctx.packageName) continue
            val e = AppRepo.firstOfPackage(pkg) ?: continue
            if (e.key in hidden) continue
            if (seen.add(e.pkg)) out.add(e)
        }
        for (k in session) {
            val e = AppRepo.get(k) ?: continue
            if (seen.add(e.pkg)) out.add(e)
        }
        return if (out.size > 20) ArrayList(out.subList(0, 20)) else out
    }

    /** Apps usadas en los últimos 30 min: se marcan con un puntito bajo el icono del dock. */
    fun recentPackages(ctx: Context): Set<String> {
        ensure()
        val out = HashSet<String>()
        val now = System.currentTimeMillis()
        for ((pkg, t) in usagePackages(ctx)) if (now - t < 30L * 60_000L) out.add(pkg)
        if (out.isEmpty()) {
            for (i in 0 until minOf(4, session.size)) {
                val e = AppRepo.get(session[i]) ?: continue
                out.add(e.pkg)
            }
        }
        return out
    }
}
