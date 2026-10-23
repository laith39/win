package com.winlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast

/** شعار ويندوز (4 مربعات). */
class WinLogo(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF60CDFF.toInt() }
    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) * 0.42f
        val g = s * 0.07f
        val q = (s - g) / 2f
        val x0 = (width - s) / 2f
        val y0 = (height - s) / 2f
        for (i in 0..1) for (j in 0..1) {
            val l = x0 + i * (q + g)
            val t = y0 + j * (q + g)
            c.drawRect(l, t, l + q, t + q, p)
        }
    }
}

class OverlayService : Service() {

    companion object {
        @Volatile
        var instance: OverlayService? = null
    }

    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var taskbar: FrameLayout? = null
    private var tbRow: LinearLayout? = null
    private var menu: View? = null
    private val chromes = HashMap<Win, Chrome>()
    private var navH = 0
    private var statusH = 0
    private var scr = Point(1080, 2400)
    private val taskbarH: Int get() = dp(48)

    private val poll = object : Runnable {
        override fun run() {
            pollTasks()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startFg()
        WindowController.barH = dp(36)
        WindowController.minW = dp(220)
        WindowController.minH = dp(180)
        measure()
        buildTaskbar()
        WindowController.onChange = { refresh() }
        ui.postDelayed(poll, 3000)
    }

    override fun onDestroy() {
        ui.removeCallbacks(poll)
        hideMenu()
        chromes.values.forEach { it.remove() }
        chromes.clear()
        taskbar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        taskbar = null
        WindowController.onChange = null
        instance = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideMenu()
        measure()
        taskbar?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        buildTaskbar()
        for (w in WindowController.wins) {
            WindowController.clamp(w)
            WindowController.apply(w)
        }
        refresh()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("win", "Win Launcher", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "win")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Win Launcher")
            .build()
        startForeground(1, n)
    }

    @Suppress("DEPRECATION")
    private fun measure() {
        navH = sysDimen("navigation_bar_height")
        statusH = sysDimen("status_bar_height")
        val p = Point()
        wm.defaultDisplay.getRealSize(p)
        scr = p
        WindowController.area = Rect(0, statusH, p.x, p.y - navH - taskbarH)
    }

    private fun lp(w: Int, h: Int, g: Int, x: Int, y: Int, focus: Boolean = false): WindowManager.LayoutParams {
        var f = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        if (!focus) f = f or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, f, PixelFormat.TRANSLUCENT
        )
        p.gravity = g
        p.x = x
        p.y = y
        return p
    }

    // ---------------------------------------------------------------- شريط المهام

    private fun buildTaskbar() {
        val root = FrameLayout(this)
        root.setBackgroundColor(0xEB1C1C1C.toInt())

        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        scroll.addView(row, FrameLayout.LayoutParams(-2, -1))
        val slp = FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL)
        slp.marginStart = dp(76)
        slp.marginEnd = dp(76)
        root.addView(scroll, slp)

        val clock = TextClock(this)
        clock.format12Hour = "h:mm a\nd/M/yyyy"
        clock.format24Hour = "HH:mm\nd/M/yyyy"
        clock.setTextColor(Color.WHITE)
        clock.textSize = 11f
        clock.gravity = Gravity.CENTER
        clock.setPadding(dp(10), 0, dp(10), 0)
        root.addView(clock, FrameLayout.LayoutParams(-2, -1, Gravity.END))

        taskbar = root
        tbRow = row
        wm.addView(root, lp(-1, taskbarH, Gravity.BOTTOM or Gravity.START, 0, navH))
        refreshTaskbar()
    }

    private fun refreshTaskbar() {
        val row = tbRow ?: return
        row.removeAllViews()
        val logo = WinLogo(this)
        logo.setOnClickListener { toggleMenu() }
        row.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))

        val order = LinkedHashSet<String>(AppRepo.pinned(this))
        WindowController.wins.forEach { order.add(it.app.pkg) }
        for (pkg in order) {
            val app = AppRepo.find(this, pkg) ?: continue
            row.addView(taskbarCell(app), LinearLayout.LayoutParams(dp(44), dp(44)))
        }
    }

    private fun taskbarCell(app: AppInfo): View {
        val running = WindowController.wins.any { it.app.pkg == app.pkg }
        val f = FrameLayout(this)
        val iv = ImageView(this)
        iv.setImageDrawable(app.newIcon())
        f.addView(iv, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        if (running) {
            val ind = View(this)
            val d = GradientDrawable()
            d.setColor(0xFF60CDFF.toInt())
            d.cornerRadius = dp(2).toFloat()
            ind.background = d
            val ilp = FrameLayout.LayoutParams(dp(14), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            ilp.bottomMargin = dp(2)
            f.addView(ind, ilp)
        }
        f.setOnClickListener { WindowController.open(this, app) }
        return f
    }

    // ---------------------------------------------------------------- قائمة ابدأ

    fun hideMenu() {
        menu?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        menu = null
    }

    private fun toggleMenu() {
        if (menu != null) hideMenu() else showMenu()
    }

    private fun showMenu() {
        val w = minOf(scr.x - dp(16), dp(400))
        val h = minOf(scr.y - statusH - navH - taskbarH - dp(16), dp(560))

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        val pbg = GradientDrawable()
        pbg.setColor(0xF2202020.toInt())
        pbg.cornerRadius = dp(12).toFloat()
        pbg.setStroke(1, 0x33FFFFFF)
        panel.background = pbg
        panel.setPadding(dp(14), dp(14), dp(14), dp(10))

        val search = EditText(this)
        search.hint = "ابحث عن تطبيق"
        search.setHintTextColor(0xFF9A9A9A.toInt())
        search.setTextColor(Color.WHITE)
        search.textSize = 14f
        search.isSingleLine = true
        val sbg = GradientDrawable()
        sbg.setColor(0xFF2D2D2D.toInt())
        sbg.cornerRadius = dp(20).toFloat()
        search.background = sbg
        search.setPadding(dp(16), dp(8), dp(16), dp(8))
        panel.addView(search, LinearLayout.LayoutParams(-1, -2))

        val title = TextView(this)
        title.text = "التطبيقات"
        title.setTextColor(Color.WHITE)
        title.textSize = 13f
        title.setPadding(dp(4), dp(12), 0, dp(6))
        panel.addView(title)

        val grid = GridLayout(this)
        grid.columnCount = 4
        val sv = ScrollView(this)
        sv.addView(grid)
        panel.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))

        val cellW = (w - dp(28)) / 4
        fun fill(q: String) {
            grid.removeAllViews()
            AppRepo.all(this).filter { it.label.contains(q, true) }.forEach { grid.addView(menuCell(it, cellW)) }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { fill(s.toString().trim()) }
        })

        val foot = TextView(this)
        foot.text = "⚙  الإعدادات والخلفية"
        foot.setTextColor(0xFFCCCCCC.toInt())
        foot.textSize = 13f
        foot.setPadding(dp(4), dp(10), dp(4), dp(4))
        foot.setOnClickListener {
            hideMenu()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        panel.addView(foot)

        panel.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
            false
        }

        val p = lp(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, navH + taskbarH + dp(8), true)
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        wm.addView(panel, p)
        menu = panel
    }

    private fun menuCell(app: AppInfo, w: Int): View {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(dp(2), dp(8), dp(2), dp(8))
        val iv = ImageView(this)
        iv.setImageDrawable(app.newIcon())
        c.addView(iv, LinearLayout.LayoutParams(dp(40), dp(40)))
        val tv = TextView(this)
        tv.text = app.label
        tv.setTextColor(Color.WHITE)
        tv.textSize = 11f
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER
        c.addView(tv, LinearLayout.LayoutParams(-1, -2))
        val glp = GridLayout.LayoutParams()
        glp.width = w
        c.layoutParams = glp
        c.setOnClickListener {
            hideMenu()
            WindowController.open(this, app)
        }
        c.setOnLongClickListener {
            val on = AppRepo.togglePin(this, app.pkg)
            Toast.makeText(this, if (on) "تم التثبيت" else "أُزيل التثبيت", Toast.LENGTH_SHORT).show()
            refreshTaskbar()
            true
        }
        return c
    }

    // ---------------------------------------------------------------- مزامنة النوافذ

    fun refresh() {
        refreshTaskbar()
        val live = WindowController.wins.toSet()
        chromes.keys.filter { it !in live }.forEach { chromes.remove(it)?.remove() }
        for (w in WindowController.wins) {
            chromes.getOrPut(w) { Chrome(w) }.layout()
        }
    }

    private fun pollTasks() {
        if (WindowController.wins.isEmpty() || !Shell.ready()) return
        Shell.task {
            val ids = WindowController.tasks() ?: return@task
            ui.post {
                val gone = WindowController.wins.filter { it.taskId !in ids.keys }
                if (gone.isNotEmpty()) {
                    WindowController.wins.removeAll(gone.toSet())
                    refresh()
                }
            }
        }
    }

    private fun btn(t: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = t
        b.setTextColor(Color.WHITE)
        b.textSize = 14f
        b.gravity = Gravity.CENTER
        b.layoutParams = LinearLayout.LayoutParams(dp(44), -1)
        b.setOnClickListener { onClick() }
        return b
    }

    /** شريط العنوان + مقبض تغيير الحجم لكل نافذة. */
    private inner class Chrome(val win: Win) {
        private val ctx: Context = this@OverlayService
        private val grip = dp(30)
        private val bar = LinearLayout(ctx)
        private val gripV = View(ctx)
        private val barLp = lp(win.rect.width(), WindowController.barH, Gravity.TOP or Gravity.START, win.rect.left, win.rect.top)
        private val gripLp = lp(grip, grip, Gravity.TOP or Gravity.START, 0, 0)
        private var added = false
        private var lastApply = 0L
        private var dX = 0f
        private var dY = 0f
        private var sL = 0
        private var sT = 0
        private var sR = 0
        private var sB = 0

        init {
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER_VERTICAL
            val bg = GradientDrawable()
            bg.setColor(0xFF2B2B2B.toInt())
            val r = dp(8).toFloat()
            bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            bar.background = bg

            val icon = ImageView(ctx)
            icon.setImageDrawable(win.app.newIcon())
            val ilp = LinearLayout.LayoutParams(dp(18), dp(18))
            ilp.marginStart = dp(10)
            ilp.marginEnd = dp(8)
            bar.addView(icon, ilp)

            val title = TextView(ctx)
            title.text = win.app.label
            title.setTextColor(Color.WHITE)
            title.textSize = 12f
            title.maxLines = 1
            title.ellipsize = TextUtils.TruncateAt.END
            bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

            bar.addView(btn("▢") { WindowController.toggleMax(win) })
            bar.addView(btn("✕") { WindowController.close(win) })

            bar.setOnTouchListener { _, e ->
                if (!win.maximized) {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            dX = e.rawX; dY = e.rawY
                            sL = win.rect.left; sT = win.rect.top
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val a = WindowController.area
                            val l = (sL + (e.rawX - dX).toInt()).coerceIn(a.left, maxOf(a.left, a.right - win.rect.width()))
                            val t = (sT + (e.rawY - dY).toInt()).coerceIn(a.top, maxOf(a.top, a.bottom - win.rect.height()))
                            win.rect.offsetTo(l, t)
                            layout()
                            throttled()
                        }
                        MotionEvent.ACTION_UP -> WindowController.apply(win)
                    }
                }
                true
            }

            val gbg = GradientDrawable()
            gbg.setColor(0x30FFFFFF)
            gbg.cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(8).toFloat(), dp(8).toFloat(), 0f, 0f)
            gripV.background = gbg
            gripV.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = e.rawX; dY = e.rawY
                        sR = win.rect.right; sB = win.rect.bottom
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val a = WindowController.area
                        win.maximized = false
                        win.rect.right = (sR + (e.rawX - dX).toInt())
                            .coerceIn(win.rect.left + WindowController.minW, maxOf(win.rect.left + WindowController.minW, a.right))
                        win.rect.bottom = (sB + (e.rawY - dY).toInt())
                            .coerceIn(win.rect.top + WindowController.minH, maxOf(win.rect.top + WindowController.minH, a.bottom))
                        layout()
                        throttled()
                    }
                    MotionEvent.ACTION_UP -> WindowController.apply(win)
                }
                true
            }
        }

        private fun throttled() {
            val now = SystemClock.uptimeMillis()
            if (now - lastApply > 250) {
                lastApply = now
                WindowController.apply(win)
            }
        }

        fun layout() {
            val r = win.rect
            barLp.x = r.left
            barLp.y = r.top
            barLp.width = r.width()
            gripLp.x = r.right - grip
            gripLp.y = r.bottom - grip
            if (!added) {
                wm.addView(bar, barLp)
                wm.addView(gripV, gripLp)
                added = true
            } else {
                wm.updateViewLayout(bar, barLp)
                wm.updateViewLayout(gripV, gripLp)
            }
        }

        fun remove() {
            if (!added) return
            try { wm.removeView(bar) } catch (_: Exception) {}
            try { wm.removeView(gripV) } catch (_: Exception) {}
            added = false
        }
    }
}
