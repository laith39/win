package com.winlauncher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppInfo(val label: String, val pkg: String, val activity: String, val icon: Drawable) {
    val component: String get() = "$pkg/$activity"
}

fun AppInfo.newIcon(): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

object AppRepo {
    private var cache: List<AppInfo>? = null

    fun all(ctx: Context, refresh: Boolean = false): List<AppInfo> {
        if (!refresh) cache?.let { return it }
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name, it.loadIcon(pm)) }
            .filter { it.pkg != ctx.packageName }
            .sortedBy { it.label.lowercase() }
        cache = list
        return list
    }

    fun find(ctx: Context, pkg: String): AppInfo? = all(ctx).find { it.pkg == pkg }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("win", Context.MODE_PRIVATE)

    fun pinned(ctx: Context): List<String> {
        val p = prefs(ctx)
        if (!p.contains("pinned")) {
            val d = all(ctx).take(8).map { it.pkg }
            p.edit().putString("pinned", d.joinToString(",")).apply()
            return d
        }
        return p.getString("pinned", "")!!.split(",").filter { it.isNotEmpty() }
    }

    /** يرجع true إذا أصبح مثبتاً. */
    fun togglePin(ctx: Context, pkg: String): Boolean {
        val l = pinned(ctx).toMutableList()
        val on = if (l.contains(pkg)) { l.remove(pkg); false } else { l.add(pkg); true }
        prefs(ctx).edit().putString("pinned", l.joinToString(",")).apply()
        return on
    }
}
