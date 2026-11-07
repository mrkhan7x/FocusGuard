package com.focusguard.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast

const val STRICT_MSG = "Strict mode is on, so rules can only get stricter."

/**
 * Packages that must never be blocked, so FocusGuard can't lock you out of your own phone:
 * this app, Settings, System UI, every home launcher and the dialer.
 */
fun protectedPackages(ctx: Context): Set<String> {
    val pm = ctx.packageManager
    val out = hashSetOf(ctx.packageName, "com.android.settings", "com.android.systemui", "android")
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val dial = Intent(Intent.ACTION_DIAL)
    try {
        pm.queryIntentActivities(home, 0).forEach { out.add(it.activityInfo.packageName) }
        pm.queryIntentActivities(dial, 0).forEach { out.add(it.activityInfo.packageName) }
        pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { out.add(it) }
        pm.resolveActivity(dial, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { out.add(it) }
    } catch (e: Exception) {
        // keep the static set
    }
    return out
}

fun Context.toast(msg: String) {
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
