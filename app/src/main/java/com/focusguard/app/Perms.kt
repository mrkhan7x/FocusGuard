package com.focusguard.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

data class PermStatus(
    val accessibility: Boolean,
    val overlay: Boolean,
    val battery: Boolean,
    val admin: Boolean,
) {
    val coreReady: Boolean get() = accessibility && overlay
}

object Perms {
    fun status(ctx: Context): PermStatus = PermStatus(
        accessibility = accessibilityOn(ctx),
        overlay = Settings.canDrawOverlays(ctx),
        battery = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName),
        admin = (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isAdminActive(ComponentName(ctx, GuardAdminReceiver::class.java)),
    )

    private fun accessibilityOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(ctx, GuardService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    fun openAccessibility(ctx: Context) = launch(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun openOverlay(ctx: Context) = launch(
        ctx,
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")),
    )

    fun openBattery(ctx: Context) {
        // Try the direct per-app exemption screen first (better UX).
        // Falls back to the general list if the direct intent is not handled (some OEMs).
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}"),
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try {
            ctx.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            launch(ctx, fallback)
        }
    }

    fun openAppInfo(ctx: Context) = launch(
        ctx,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    fun requestAdmin(ctx: Context) = launch(
        ctx,
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(ctx, GuardAdminReceiver::class.java))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents FocusGuard from being uninstalled on impulse.",
            ),
    )

    private fun launch(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not open that settings screen on this phone.", Toast.LENGTH_LONG).show()
        }
    }
}
