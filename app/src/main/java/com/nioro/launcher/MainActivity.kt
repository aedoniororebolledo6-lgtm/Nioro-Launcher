package com.nioro.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private lateinit var drawer: DrawerHost
    private lateinit var recents: RecentsHost
    private lateinit var overlay: OpenOverlay
    private lateinit var menu: MenuView
    private lateinit var backdrop: BackdropView
    private val bg = Executors.newSingleThreadExecutor()
    private var launchedApp = false
    private var lastBlur = -1f
    private var drawerP = 0f
    private var recentsP = 0f
    private var resumed = false
    private var wasStopped = false
    private val overlayGuard = Runnable { if (overlay.active) overlay.hide() }
    private val calmDown = Runnable { Compat.applyRefreshRate(this, false) }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.data?.schemeSpecificPart?.let { AppRepo.evict(it) }
            reloadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Compat.edgeToEdge(this)
        Compat.applyRefreshRate(this)

        root = FrameLayout(this)
        home = HomeView(this)
        drawer = DrawerHost(this)
        recents = RecentsHost(this)
        overlay = OpenOverlay(this)
        menu = MenuView(this)
        backdrop = BackdropView(this)
        val mp = FrameLayout.LayoutParams.MATCH_PARENT
        root.addView(home, FrameLayout.LayoutParams(mp, mp))
        root.addView(backdrop, FrameLayout.LayoutParams(mp, mp))
        root.addView(drawer, FrameLayout.LayoutParams(mp, mp))
        root.addView(recents, FrameLayout.LayoutParams(mp, mp))
        root.addView(overlay, FrameLayout.LayoutParams(mp, mp))
        root.addView(menu, FrameLayout.LayoutParams(mp, mp))
        setContentView(root)
        Screen.onAnimating = { on ->
            if (on) {
                root.removeCallbacks(calmDown)
                Compat.applyRefreshRate(this, true)
            } else {
                // se espera un poco: así gestos seguidos no cambian de frecuencia una y otra vez
                root.removeCallbacks(calmDown)
                root.postDelayed(calmDown, 1200)
            }
        }

        root.setOnApplyWindowInsetsListener { _, ins ->
            val (top, bottom) = Compat.bars(ins)
            home.setInsets(top.toFloat(), bottom.toFloat())
            drawer.setInsets(top.toFloat(), bottom.toFloat())
            recents.view.insetTop = top.toFloat()
            recents.view.insetBottom = bottom.toFloat()
            ins
        }

        home.host = homeHost
        drawer.callback = drawerCb
        recents.view.listener = recentsCb
        recents.onProgress = { p ->
            recentsP = p
            updateBackdrop()
        }

        val f = IntentFilter()
        f.addAction(Intent.ACTION_PACKAGE_ADDED)
        f.addAction(Intent.ACTION_PACKAGE_REMOVED)
        f.addAction(Intent.ACTION_PACKAGE_CHANGED)
        f.addAction(Intent.ACTION_PACKAGE_REPLACED)
        f.addDataScheme("package")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        reloadApps()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (t: Throwable) {}
        super.onDestroy()
    }

    private fun reloadApps() {
        AppRepo.load(this) {
            if (isDestroyed) return@load
            if (!Prefs.seeded && AppRepo.apps.isNotEmpty()) {
                val dock = AppRepo.defaultDockKeys(this)
                Prefs.setDockKeys(dock)
                Prefs.setHomeKeys(AppRepo.seedHome(this).filter { it !in dock })
                Prefs.seeded = true
            }
            // limpiar apps desinstaladas (solo si la lista cargó bien, para no borrar el inicio por error)
            if (AppRepo.apps.isNotEmpty()) {
                Prefs.setHomeKeys(Prefs.homeKeys().filter { AppRepo.get(it) != null })
                Prefs.setDockKeys(Prefs.dockKeys().filter { AppRepo.get(it) != null })
            }
            home.reload()
            drawer.refreshData()
            refreshRunning()
        }
    }

    private fun refreshRunning() {
        val ctx = applicationContext
        bg.execute {
            val set = try { Recents.recentPackages(ctx) } catch (t: Throwable) { emptySet() }
            runOnUiThread { if (!isDestroyed) home.setRunning(set) }
        }
    }

    // ---------------------------------------------------------------- Ciclo de vida
    override fun onResume() {
        super.onResume()
        resumed = true
        overlay.hide()
        Compat.applyRefreshRate(this)
        home.resumeClock()
        drawer.styleChanged()
        if (wasStopped) {
            // Al volver (de una app, de Ajustes o al desbloquear) se releen los ajustes.
            if (!Prefs.seeded) reloadApps() else {
                home.reload()
                drawer.refreshData()
            }
            home.refreshStyle()
        }
        if (launchedApp) {
            if (drawer.isOpen) drawer.closeNow()
            if (recents.isOpen) recents.closeNow()
        }
        if (launchedApp || wasStopped) home.playEnter()
        launchedApp = false
        wasStopped = false
        refreshRunning()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        wasStopped = true
        home.pauseClock()
        menu.dismiss()
        overlay.hide()
        // nada de animaciones ni sensores mientras el launcher no se ve
        if (drawer.isOpen) drawer.closeNow()
        if (recents.isOpen) recents.closeNow()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Botón/gesto de inicio estando ya en el launcher.
        if (intent?.action == Intent.ACTION_MAIN && resumed) {
            when {
                menu.showing -> menu.dismiss()
                recents.isOpen -> recents.close()
                drawer.isOpen -> drawer.close()
                else -> home.goToFirstPage()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        when {
            menu.showing -> menu.dismiss()
            recents.isOpen -> recents.close()
            drawer.isOpen -> drawer.close()
            else -> home.goToFirstPage()
        }
    }

    // ---------------------------------------------------------------- Fondo (desenfoque)
    private fun updateBackdrop() {
        val p = maxOf(drawerP, recentsP)
        home.setDrawerProgress(p)
        val blurOn = Prefs.blur && !Prefs.saver
        if (p > 0.001f) {
            if (blurOn && !backdrop.tried) backdrop.capture(home)
            backdrop.progress = p
            home.alpha = if (backdrop.hasSnapshot) 1f - p else 1f - 0.35f * p
        } else {
            backdrop.release()
            home.alpha = 1f
        }
        wallpaperBlur(if (blurOn) p else 0f)
    }

    /** Android 12+: el sistema difumina el fondo de pantalla detrás del launcher (lo hace la GPU del compositor). */
    private fun wallpaperBlur(p: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            if (!windowManager.isCrossWindowBlurEnabled) return
            val r = (p * dp(34f)).toInt() / 3 * 3
            if (r != lastBlur.toInt()) {
                lastBlur = r.toFloat()
                window.setBackgroundBlurRadius(r)
            }
        } catch (t: Throwable) {
        }
    }

    // ---------------------------------------------------------------- Abrir apps
    private fun launch(e: AppEntry, from: RectF) {
        menu.dismiss()
        val style = Prefs.openAnim
        val src = RectF(from)
        if (src.width() < 2f) src.set(root.width / 2f - 40f, root.height / 2f - 40f, root.width / 2f + 40f, root.height / 2f + 40f)
        overlay.start(e, src, style) { doStart(e) }
    }

    private fun doStart(e: AppEntry) {
        try {
            val opts = ActivityOptions.makeCustomAnimation(this, R.anim.zoom_in_soft, R.anim.stay)
            startActivity(AppRepo.intentFor(e), opts.toBundle())
            Recents.note(e.key)
            launchedApp = true
            // por si la app no llega a cubrir el launcher (p. ej. una ventana flotante)
            root.removeCallbacks(overlayGuard)
            root.postDelayed(overlayGuard, 2500)
        } catch (t: Throwable) {
            overlay.hide()
            Toast.makeText(this, "No se pudo abrir ${e.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- Menús
    private fun appMenu(e: AppEntry, anchor: RectF, where: Int) {
        val items = ArrayList<MenuView.Item>()
        val homeKeys = Prefs.homeKeys()
        val dockKeys = Prefs.dockKeys()
        if (e.key in homeKeys) {
            items.add(MenuView.Item("Quitar del inicio") {
                Prefs.setHomeKeys(Prefs.homeKeys().filter { it != e.key })
                home.reload()
            })
        } else if (e.key !in dockKeys) {
            items.add(MenuView.Item("Añadir al inicio") {
                val l = Prefs.homeKeys()
                l.add(e.key)
                Prefs.setHomeKeys(l)
                home.reload()
                Toast.makeText(this, "Añadida al inicio", Toast.LENGTH_SHORT).show()
            })
        }
        if (e.key in dockKeys) {
            items.add(MenuView.Item("Quitar del dock") {
                Prefs.setDockKeys(Prefs.dockKeys().filter { it != e.key })
                home.reload()
            })
        } else if (dockKeys.size < 5) {
            items.add(MenuView.Item("Añadir al dock") {
                val l = Prefs.dockKeys()
                l.add(e.key)
                Prefs.setDockKeys(l)
                Prefs.setHomeKeys(Prefs.homeKeys().filter { it != e.key })
                home.reload()
            })
        }
        items.add(MenuView.Item("Información de la app") {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
        })
        items.add(MenuView.Item("Ocultar") {
            val h = Prefs.hidden()
            if (e.key !in h) h.add(e.key)
            Prefs.setHidden(h)
            Prefs.setHomeKeys(Prefs.homeKeys().filter { it != e.key })
            Prefs.setDockKeys(Prefs.dockKeys().filter { it != e.key })
            home.reload()
            drawer.refreshData()
            Toast.makeText(this, "Oculta. Recupérala en Ajustes del launcher.", Toast.LENGTH_SHORT).show()
        })
        items.add(MenuView.Item("Desinstalar") {
            try {
                @Suppress("DEPRECATION")
                startActivity(Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:" + e.pkg)))
            } catch (t: Throwable) {
            }
        })
        menu.show(e.label, anchor, items)
    }

    private fun homeMenu(x: Float, y: Float) {
        val items = ArrayList<MenuView.Item>()
        items.add(MenuView.Item("Ajustes del launcher") {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_up_in, R.anim.stay)
        })
        items.add(MenuView.Item("Fondo de pantalla") {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"))
            } catch (t: Throwable) {
            }
        })
        items.add(MenuView.Item("Vista de apps: siguiente estilo") {
            Prefs.drawerStyle = (Prefs.drawerStyle + 1) % 5
            drawer.styleChanged()
            Toast.makeText(this, "Vista: " + SettingsActivity.DRAWER[Prefs.drawerStyle], Toast.LENGTH_SHORT).show()
        })
        val a = RectF(x - 10f, y - 10f, x + 10f, y + 10f)
        menu.show("Inicio", a, items)
    }

    // ---------------------------------------------------------------- Puentes
    private val homeHost = object : HomeView.Host {
        override fun launch(e: AppEntry, from: RectF, fromDock: Boolean) = this@MainActivity.launch(e, from)
        override fun openDrawer() {
            menu.dismiss()
            drawer.open(home.launchpadX())
        }
        override fun openRecents() {
            menu.dismiss()
            val ctx = applicationContext
            bg.execute {
                val list = try { Recents.list(ctx) } catch (t: Throwable) { emptyList() }
                runOnUiThread {
                    if (!isDestroyed) {
                        recents.open(list)
                        if (list.isEmpty() && !Recents.hasUsageAccess(ctx)) {
                            Toast.makeText(ctx, "Activa \"Acceso de uso\" en Ajustes del launcher para ver todas tus apps recientes", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        override fun showMenu(e: AppEntry, anchor: RectF, where: Int) = appMenu(e, anchor, where)
        override fun dismissMenu() = menu.dismiss()
        override fun expandShade() {
            try {
                @SuppressLint("WrongConstant")
                val sbm = getSystemService("statusbar")
                val c = Class.forName("android.app.StatusBarManager")
                c.getMethod("expandNotificationsPanel").invoke(sbm)
            } catch (t: Throwable) {
            }
        }
        override fun showHomeMenu(x: Float, y: Float) = homeMenu(x, y)
    }

    private val drawerCb = object : DrawerHost.Callback {
        override fun onProgress(p: Float) {
            drawerP = p
            updateBackdrop()
        }
        override fun onLaunch(e: AppEntry, from: RectF) = launch(e, from)
        override fun onMenu(e: AppEntry, anchor: RectF) = appMenu(e, anchor, 2)
        override fun onClosed() {
            drawerP = 0f
            updateBackdrop()
        }
        override fun dismissMenu() = menu.dismiss()
    }

    private val recentsCb = object : RecentsView.Listener {
        override fun onLaunch(e: AppEntry, from: RectF) = launch(e, from)
        override fun onClose() = recents.close()
        override fun onOpenAll() {
            recents.close()
            drawer.open(home.launchpadX())
        }
    }
}
