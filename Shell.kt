package com.winlauncher

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/** تنفيذ أوامر shell بصلاحية Shizuku. */
object Shell {
    private val pool = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** يُستدعى من خيط خلفي فقط. */
    fun run(cmd: String): String {
        return try {
            // newProcess أصبحت private في Shizuku 13 فنستدعيها بالـ reflection
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (e: Throwable) {
            "ERR: $e"
        }
    }

    fun async(cmd: String, cb: ((String) -> Unit)? = null) {
        pool.execute {
            val r = run(cmd)
            if (cb != null) main.post { cb(r) }
        }
    }

    fun task(block: () -> Unit) {
        pool.execute(block)
    }
}
