package com.winlauncher

import android.content.Context
import android.graphics.Rect
import android.widget.Toast

/** rect = النافذة كاملة بما فيها شريط العنوان (الذي نرسمه نحن). */
class Win(
    val app: AppInfo,
    var taskId: Int,
    val rect: Rect,
    var maximized: Boolean = false,
    var restore: Rect? = null
)

object WindowController {
    val wins = mutableListOf<Win>()
    var onChange: (() -> Unit)? = null
    var area = Rect(0, 0, 1000, 1000)
    var barH = 0
    var minW = 300
    var minH = 300

    private val taskRe = Regex("""Task(?:Record)?\{[0-9a-f]+ #(\d+)[^}]*?A=(?:\d+:)?([\w.]+)""")

    fun ensureFreeform() {
        Shell.async(
            "settings put global enable_freeform_support 1; " +
                "settings put global force_resizable_activities 1; " +
                "settings put global freeform_window_management 1"
        )
    }

    /** خريطة taskId -> package. من خيط خلفي. */
    fun tasks(): Map<Int, String>? {
        val out = Shell.run("dumpsys activity activities")
        if (out.startsWith("ERR")) return null
        val m = HashMap<Int, String>()
        for (r in taskRe.findAll(out)) m[r.groupValues[1].toInt()] = r.groupValues[2]
        return m
    }

    private fun findTask(pkg: String): Int {
        val t = tasks() ?: return -1
        return t.filter { it.value == pkg }.keys.maxOrNull() ?: -1
    }

    fun open(ctx: Context, app: AppInfo) {
        if (!Shell.ready()) {
            Toast.makeText(ctx, "Shizuku غير جاهز", Toast.LENGTH_SHORT).show()
            return
        }
        val existing = wins.find { it.app.pkg == app.pkg }
        if (existing != null) {
            focus(existing)
            return
        }
        val n = wins.size % 5
        val w = (area.width() * 0.72f).toInt().coerceAtLeast(minW)
        val h = (area.height() * 0.62f).toInt().coerceAtLeast(minH)
        val l = (area.left + 30 + n * 60).coerceAtMost(area.right - w).coerceAtLeast(area.left)
        val t = (area.top + 30 + n * 60).coerceAtMost(area.bottom - h).coerceAtLeast(area.top)
        val rect = Rect(l, t, l + w, t + h)

        Shell.task {
            Shell.run("am start -n ${app.component} --windowingMode 5")
            var id = -1
            for (i in 0 until 8) {
                Thread.sleep(400)
                id = findTask(app.pkg)
                if (id >= 0) break
            }
            Shell.main.post {
                if (id < 0) {
                    Toast.makeText(ctx, "تعذر فتح ${app.label} كنافذة", Toast.LENGTH_SHORT).show()
                } else {
                    val win = Win(app, id, rect)
                    clamp(win)
                    wins.add(win)
                    apply(win)
                    onChange?.invoke()
                }
            }
        }
    }

    fun focus(w: Win) {
        Shell.async("am start -n ${w.app.component} -f 0x00020000 --windowingMode 5")
    }

    fun apply(w: Win) {
        val r = w.rect
        Shell.async("am task resize ${w.taskId} ${r.left} ${r.top + barH} ${r.right} ${r.bottom}")
    }

    fun close(w: Win) {
        Shell.async("am force-stop ${w.app.pkg}")
        wins.remove(w)
        onChange?.invoke()
    }

    fun toggleMax(w: Win) {
        if (!w.maximized) {
            w.restore = Rect(w.rect)
            w.rect.set(area)
            w.maximized = true
        } else {
            w.restore?.let { w.rect.set(it) }
            w.maximized = false
        }
        apply(w)
        onChange?.invoke()
    }

    fun clamp(w: Win) {
        val r = w.rect
        val ww = r.width().coerceIn(minW, maxOf(minW, area.width()))
        val hh = r.height().coerceIn(minH, maxOf(minH, area.height()))
        val l = r.left.coerceIn(area.left, maxOf(area.left, area.right - ww))
        val t = r.top.coerceIn(area.top, maxOf(area.top, area.bottom - hh))
        r.set(l, t, l + ww, t + hh)
    }
}
