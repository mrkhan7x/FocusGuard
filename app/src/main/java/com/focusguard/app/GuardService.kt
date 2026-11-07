package com.focusguard.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * The enforcer. It:
 *  1. learns which app is in the foreground (window-state events),
 *  2. counts foreground time once per second for apps that have a rule,
 *  3. asks the Engine whether the app is allowed, needs the mindful gate, or is blocked,
 *  4. if not allowed: sends the user Home and shows BlockActivity,
 *  5. (blocked websites) reads the address bar of supported browsers,
 *  6. (strict mode) kicks the user out of the Settings / uninstall screens that mention FocusGuard.
 */
class GuardService : AccessibilityService() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_SITE = "site"

        private const val WARN_CHANNEL = "fg_warn"
        private const val WARN_NOTIF_ID = 1001

        /** System surfaces whose window events must not change the "current app". */
        private val IGNORED = setOf("com.android.systemui", "android")

        /** Screens where strict mode stops the user from disabling/uninstalling FocusGuard. */
        private val GUARDED = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
        )

        /** Browser package -> resource id of its address bar. Unlisted browsers are simply not covered. */
        private val URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private val power by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguard by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private var currentPkg: String? = null
    private var lastTick = 0L
    private var lastEnforce = 0L
    private var lastGuardCheck = 0L
    private var lastSiteCheck = 0L
    private var imePkgs: Set<String> = emptySet()
    private var imeStamp = -120_000L
    private var protectedPkgs: Set<String> = emptySet()
    private var protectedStamp = -120_000L
    private val warned = HashSet<String>()

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                // never let one bad tick kill the loop
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Repo.init(applicationContext)
        ensureWarnChannel()
        lastTick = SystemClock.elapsedRealtime()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg in IGNORED || isIme(pkg)) return
                currentPkg = pkg
                guardSettings(pkg)
                if (evaluate(pkg)) return
                if (URL_BARS.containsKey(pkg)) checkBrowser(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (pkg != currentPkg) return
                if (URL_BARS.containsKey(pkg)) checkBrowser(pkg) else guardSettings(pkg)
            }
        }
    }

    override fun onInterrupt() {
        Repo.flush()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        handler.removeCallbacks(ticker)
        Repo.flush()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ---- once per second ----------------------------------------------------------------------

    private fun tick() {
        val elapsed = SystemClock.elapsedRealtime()
        val delta = (elapsed - lastTick).coerceIn(0L, 5_000L)
        Repo.tick()

        lastTick = elapsed // always advance, so time while the screen is off is never counted later
        val pkg = currentPkg ?: return
        if (!power.isInteractive || keyguard.isKeyguardLocked) return
        if (Repo.rule(pkg) == null) return

        Repo.addUsage(pkg, delta)
        if (!evaluate(pkg)) warnIfClose(pkg)
    }

    /** Returns true if the app was not allowed and the user was sent away. */
    private fun evaluate(pkg: String): Boolean {
        if (isProtected(pkg)) return false
        val decision = Engine.decide(pkg)
        if (decision is Decision.Allow) return false
        enforce(pkg, null, countBlock = decision is Decision.Block)
        return true
    }

    private fun enforce(pkg: String, site: String?, countBlock: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastEnforce < 1_500L) return
        lastEnforce = now
        if (countBlock) Repo.recordBlock()

        // Home first, so the blocked app is gone even if the block screen cannot be shown.
        performGlobalAction(GLOBAL_ACTION_HOME)
        handler.postDelayed({
            val i = Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PKG, pkg)
            if (site != null) i.putExtra(EXTRA_SITE, site)
            try {
                startActivity(i)
            } catch (e: Exception) {
                // Without the overlay permission Android may refuse; the user is still on Home.
            }
        }, 250L)
    }

    private fun warnIfClose(pkg: String) {
        val rem = Engine.remainingSec(pkg)
        if (rem == Long.MAX_VALUE) return
        if (rem > 61L) {
            warned.remove("$pkg:60")
            warned.remove("$pkg:10")
            return
        }
        val label = Repo.rule(pkg)?.label ?: pkg
        if (rem <= 10L) {
            warned.add("$pkg:60")
            if (warned.add("$pkg:10")) warnNotification("$label closes in 10 seconds")
        } else if (warned.add("$pkg:60")) {
            warnNotification("1 minute left on $label")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Posts a heads-up notification for the "time almost up" warning.
     * Toasts from background services are silently suppressed on Android 13+,
     * so a notification is the only reliable channel.
     */
    private fun warnNotification(msg: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.areNotificationsEnabled()) {
                toast(msg) // permission not granted / notifications off: a plain toast still works
                return
            }
            val n = Notification.Builder(this, WARN_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(msg)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()
            nm.notify(WARN_NOTIF_ID, n)
        } catch (e: Exception) {
            toast(msg)
        }
    }

    private fun ensureWarnChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(WARN_CHANNEL) != null) return
        val ch = NotificationChannel(
            WARN_CHANNEL,
            "Time warnings",
            android.app.NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when an app is about to reach its daily or session limit."
        }
        nm.createNotificationChannel(ch)
    }

    // ---- websites -----------------------------------------------------------------------------

    private fun checkBrowser(pkg: String) {
        val sites = Repo.config.sites
        if (sites.isEmpty()) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastSiteCheck < 400L) return
        lastSiteCheck = nowMs

        val viewId = URL_BARS[pkg] ?: return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        if (nodes.isNullOrEmpty()) return
        val text = nodes[0].text?.toString() ?: return
        val blocked = Logic.matchSite(text, sites) ?: return
        enforce(pkg, blocked, countBlock = true)
    }

    // ---- strict mode: protect FocusGuard itself -------------------------------------------------

    private fun guardSettings(pkg: String) {
        if (pkg !in GUARDED || !Repo.strictActive()) return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastGuardCheck < 300L) return
        lastGuardCheck = nowMs

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        // Match by visible app name first; fall back to package name string for OEM Settings
        // variants (MIUI, One UI) that suppress the display label in the accessibility tree.
        val appName = getString(R.string.app_name)
        val hits = root.findAccessibilityNodeInfosByText(appName)
            .orEmpty()
            .ifEmpty { root.findAccessibilityNodeInfosByText(packageName).orEmpty() }
        if (hits.isEmpty()) return

        performGlobalAction(GLOBAL_ACTION_HOME)
        toast("Strict mode is on")
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Home launcher, dialer, Settings, System UI and FocusGuard itself are never blocked (no lock-outs). */
    private fun isProtected(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - protectedStamp > 60_000L) {
            protectedPkgs = protectedPackages(applicationContext)
            protectedStamp = now
        }
        return pkg in protectedPkgs
    }

    private fun isIme(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - imeStamp > 60_000L) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePkgs = imm.enabledInputMethodList.map { it.packageName }.toSet()
            imeStamp = now
        }
        return pkg in imePkgs
    }
}
