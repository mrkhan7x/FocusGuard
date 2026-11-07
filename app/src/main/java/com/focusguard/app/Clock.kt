package com.focusguard.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.abs

/**
 * A clock that ignores manual changes to the phone's date/time.
 *
 * Wall-clock time is anchored to SystemClock.elapsedRealtime(), which the user cannot change.
 * Small differences (NTP corrections, < 2 min) are followed. A bigger jump is treated as tampering:
 * the trusted time keeps ticking from the anchor until the wall clock agrees with it again,
 * or the phone reboots (elapsedRealtime restarts, so the wall clock is trusted afresh).
 */
object Clock {
    private const val TOLERANCE_MS = 2 * 60_000L

    private lateinit var prefs: SharedPreferences
    private var anchorWall = 0L
    private var anchorElapsed = 0L
    private var bootCount = -1

    @Volatile
    var tamperDetected: Boolean = false
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("focusguard_clock", Context.MODE_PRIVATE)
        bootCount = Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
        val savedBoot = prefs.getInt("boot", -2)
        val savedWall = prefs.getLong("wall", 0L)
        val savedElapsed = prefs.getLong("elapsed", 0L)
        val nowElapsed = SystemClock.elapsedRealtime()
        if (savedWall == 0L || savedBoot != bootCount || savedElapsed > nowElapsed) {
            // First run or the phone rebooted: trust the wall clock.
            anchorWall = System.currentTimeMillis()
            anchorElapsed = nowElapsed
        } else {
            anchorWall = savedWall
            anchorElapsed = savedElapsed
        }
        persist()
    }

    /** Trusted "now" in epoch milliseconds. */
    @Synchronized
    fun now(): Long {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        if (anchorWall == 0L) { // init() not called yet; fall back safely.
            return wall
        }
        val trusted = anchorWall + (elapsed - anchorElapsed)
        val diff = wall - trusted
        if (abs(diff) <= TOLERANCE_MS) {
            tamperDetected = false
            if (abs(diff) > 1_000L) {
                // Small NTP-style correction: follow the wall clock and return it.
                anchorWall = wall
                anchorElapsed = elapsed
                return wall
            }
            return trusted
        }
        tamperDetected = true
        return trusted
    }

    @Synchronized
    fun persist() {
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putLong("wall", anchorWall)
            .putLong("elapsed", anchorElapsed)
            .putInt("boot", bootCount)
            .apply()
    }
}
