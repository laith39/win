package com.winlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var bg: ImageView
    private lateinit var desk: GridLayout
    private lateinit var card: LinearLayout

    private val permL = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { checkSetup() } }
    private val binderL = Shizuku.OnBinderReceivedListener { runOnUiThread { checkSetup() } }

    private val wallFile: File get() = File(filesDir, "wallpaper.jpg")
    private val prefs get() = getSharedPreferences("win", MODE_PRIVATE)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        root = FrameLayout(this)
        bg = ImageView(this)
        bg.scaleType = ImageView.ScaleType.CENTER_CROP
        root.addView(bg, FrameLayout.LayoutParams(-1, -1))

        desk = GridLayout(this)
        desk.columnCount = 4
        desk.setPadding(dp(8), sysDimen("status_bar_height") + dp(12), dp(8), 0)
        root.addView(desk, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        val cbg = GradientDrawable()
        cbg.setColor(0xEE202020.toInt())
        cbg.cornerRadius = dp(14).toFloat()
        card.background = cbg
        card.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.addView(card, FrameLayout.LayoutParams(dp(300), -2, Gravity.CENTER))

        root.setOnLongClickListener { showBgMenu(); true }
        setContentView(root)

        Shizuku.addRequestPermissionResultListener(permL)
        Shizuku.addBinderReceivedListenerSticky(binderL)
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permL)
        Shizuku.removeBinderReceivedListener(binderL)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        AppRepo.all(this, true)
        checkSetup()
        loadBg()
        loadDesk()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        OverlayService.instance?.hideMenu()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // لانشر: لا نغلق شيئاً
    }

    // ---------------------------------------------------------------- الإعداد

    private fun row(msg: String, btn: String? = null, act: (() -> Unit)? = null) {
        val tv = TextView(this)
        tv.text = msg
        tv.setTextColor(Color.WHITE)
        tv.textSize = 13f
        tv.setPadding(0, dp(6), 0, dp(4))
        card.addView(tv)
        if (btn != null && act != null) {
            val b = Button(this)
            b.text = btn
            b.setOnClickListener { act() }
            card.addView(b)
        }
    }

    private fun checkSetup() {
        card.removeAllViews()
        var ok = true

        if (!Settings.canDrawOverlays(this)) {
            ok = false
            row("1) اسمح بالعرض فوق التطبيقات (لشريط المهام وأزرار النوافذ)", "فتح الإعدادات") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        } else if (OverlayService.instance == null) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }

        val binder = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!binder) {
            ok = false
            row("2) شغّل تطبيق Shizuku أولاً", "فتح Shizuku") {
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
            }
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            ok = false
            row("2) امنح هذا التطبيق صلاحية Shizuku", "منح الصلاحية") { Shizuku.requestPermission(1) }
        } else if (!prefs.getBoolean("ffDone", false)) {
            if (!prefs.getBoolean("ff", false)) {
                WindowController.ensureFreeform()
                prefs.edit().putBoolean("ff", true).apply()
            }
            ok = false
            row("تم تفعيل النوافذ الحرة. إذا لم تعمل النوافذ أعد تشغيل الهاتف مرة واحدة.", "حسناً") {
                prefs.edit().putBoolean("ffDone", true).apply()
                checkSetup()
            }
        }
        card.visibility = if (ok) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- الخلفية

    private fun loadBg() {
        if (wallFile.exists()) {
            val bmp = BitmapFactory.decodeFile(wallFile.absolutePath)
            if (bmp != null) {
                bg.setImageBitmap(bmp)
                return
            }
        }
        bg.setImageDrawable(
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF0A2A5E.toInt(), 0xFF1565C0.toInt(), 0xFF42A5F5.toInt())
            )
        )
    }

    private fun showBgMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("تغيير الخلفية", "الخلفية الافتراضية")) { _, i ->
                if (i == 0) {
                    val it = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                    @Suppress("DEPRECATION")
                    startActivityForResult(it, 7)
                } else {
                    wallFile.delete()
                    loadBg()
                }
            }
            .show()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == 7 && resultCode == RESULT_OK && uri != null) saveWallpaper(uri)
    }

    private fun saveWallpaper(uri: Uri) {
        try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val max = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            var s = 1
            while (opts.outWidth / s > max * 2) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) }
            if (bmp != null) {
                FileOutputStream(wallFile).use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                loadBg()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر تحميل الصورة", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- أيقونات سطح المكتب

    private fun loadDesk() {
        desk.removeAllViews()
        for (pkg in AppRepo.pinned(this)) {
            val app = AppRepo.find(this, pkg) ?: continue
            val c = LinearLayout(this)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setPadding(dp(4), dp(8), dp(4), dp(8))
            val iv = ImageView(this)
            iv.setImageDrawable(app.newIcon())
            c.addView(iv, LinearLayout.LayoutParams(dp(48), dp(48)))
            val tv = TextView(this)
            tv.text = app.label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 11f
            tv.maxLines = 2
            tv.gravity = Gravity.CENTER
            tv.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            c.addView(tv, LinearLayout.LayoutParams(-1, -2))
            val glp = GridLayout.LayoutParams()
            glp.width = dp(80)
            c.layoutParams = glp
            c.setOnClickListener { WindowController.open(this, app) }
            c.setOnLongClickListener {
                AppRepo.togglePin(this, app.pkg)
                loadDesk()
                OverlayService.instance?.refresh()
                true
            }
            desk.addView(c)
        }
    }
}
