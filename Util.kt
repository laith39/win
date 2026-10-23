package com.winlauncher

import android.content.Context

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.sysDimen(name: String): Int {
    val id = resources.getIdentifier(name, "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}
