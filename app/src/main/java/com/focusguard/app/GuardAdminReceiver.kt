package com.focusguard.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Registering FocusGuard as a device admin makes Android refuse to uninstall it
 * until the admin is deactivated first (and strict mode blocks the screens where that happens).
 */
class GuardAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off removes FocusGuard's uninstall protection."
}
