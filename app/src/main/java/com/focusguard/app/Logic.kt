package com.focusguard.app

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Pure Kotlin: no Android imports, so it is unit-testable on the JVM.
 * All timestamps are epoch milliseconds from the trusted Clock.
 */

enum class Reason { FOCUS, SCHEDULE, ALWAYS, DAILY_LIMIT, COOLDOWN, OPENS, SITE }

sealed class Decision {
    /** App may be used right now. */
    object Allow : Decision()

    /** App needs a mindful "gate": the user must pick a session length (at most [maxMinutes]). */
    data class Gate(val maxMinutes: Int) : Decision()

    /** App is blocked until [untilMs] (0 = no end time, e.g. permanently blocked). */
    data class Block(val reason: Reason, val untilMs: Long, val detail: String = "") : Decision()
}

data class AppRule(
    val pkg: String,
    val label: String,
    val dailyLimitMin: Int = 0,        // 0 = no daily limit
    val sessionMin: Int = 0,           // 0 = no mindful gate; >0 = longest allowed session per opening
    val cooldownMin: Int = 30,         // lock time after a session ends
    val maxOpens: Int = 0,             // 0 = unlimited; counts started sessions (needs sessionMin > 0)
    val alwaysBlocked: Boolean = false,
    val followSchedules: Boolean = true,
)

data class Schedule(
    val id: String,
    val name: String,
    val days: Int,                     // bit 0 = Monday ... bit 6 = Sunday
    val startMin: Int,                 // minutes after midnight
    val endMin: Int,                   // may be < startMin for overnight windows
    val enabled: Boolean = true,
)

data class Config(
    val rules: List<AppRule> = emptyList(),
    val schedules: List<Schedule> = emptyList(),
    val sites: List<String> = emptyList(),
    val focusUntil: Long = 0L,
    val strictUntil: Long = 0L,
    val gateDelaySec: Int = 10,
)

object Logic {
    const val ALL_DAYS = 127
    const val WEEKDAYS = 31 // Mon-Fri

    fun zonedAt(now: Long, zone: ZoneId): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)

    fun dayKey(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        zonedAt(now, zone).toLocalDate().toString()

    fun nextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        zonedAt(now, zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** If [s] is active at [now], returns the epoch ms at which this window ends; otherwise null. */
    fun scheduleEnd(s: Schedule, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!s.enabled || s.days == 0 || s.startMin == s.endMin) return null
        val z = zonedAt(now, zone)
        val m = z.hour * 60 + z.minute
        val dow = z.dayOfWeek.value // Mon = 1 .. Sun = 7
        val todayOn = (s.days and (1 shl (dow - 1))) != 0
        val yesterdayOn = (s.days and (1 shl ((dow + 5) % 7))) != 0
        val active = if (s.startMin < s.endMin) {
            todayOn && m >= s.startMin && m < s.endMin
        } else {
            (todayOn && m >= s.startMin) || (yesterdayOn && m < s.endMin)
        }
        if (!active) return null
        val minutesLeft = if (m < s.endMin) s.endMin - m else 24 * 60 - m + s.endMin
        val startOfMinute = z.withSecond(0).withNano(0).toInstant().toEpochMilli()
        return startOfMinute + minutesLeft * 60_000L
    }

    /** Latest end among all active schedules, with the schedule that owns it. */
    fun activeSchedule(cfg: Config, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Schedule, Long>? {
        var best: Pair<Schedule, Long>? = null
        for (s in cfg.schedules) {
            val end = scheduleEnd(s, now, zone) ?: continue
            if (best == null || end > best.second) best = Pair(s, end)
        }
        return best
    }

    fun decide(
        rule: AppRule,
        cfg: Config,
        now: Long,
        usageMs: Long,
        opens: Int,
        sessionEnd: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Decision {
        if (cfg.focusUntil > now) return Decision.Block(Reason.FOCUS, cfg.focusUntil)
        if (rule.alwaysBlocked) return Decision.Block(Reason.ALWAYS, 0L)
        if (rule.followSchedules) {
            val sched = activeSchedule(cfg, now, zone)
            if (sched != null) return Decision.Block(Reason.SCHEDULE, sched.second, sched.first.name)
        }
        val limitMs = rule.dailyLimitMin * 60_000L
        if (rule.dailyLimitMin > 0 && usageMs >= limitMs) {
            return Decision.Block(Reason.DAILY_LIMIT, nextMidnight(now, zone))
        }
        if (rule.sessionMin > 0) {
            if (sessionEnd > now) return Decision.Allow
            if (sessionEnd > 0L) {
                val coolEnd = sessionEnd + rule.cooldownMin * 60_000L
                if (coolEnd > now) return Decision.Block(Reason.COOLDOWN, coolEnd)
            }
            if (rule.maxOpens > 0 && opens >= rule.maxOpens) {
                return Decision.Block(Reason.OPENS, nextMidnight(now, zone))
            }
            var maxMin = rule.sessionMin
            if (rule.dailyLimitMin > 0) {
                val remainingMs = limitMs - usageMs
                val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt() // round up
                maxMin = minOf(maxMin, maxOf(1, remainingMin))
            }
            return Decision.Gate(maxMin)
        }
        return Decision.Allow
    }

    /** Seconds of use left before this app gets blocked (Long.MAX_VALUE if nothing is counting down). */
    fun remainingSec(rule: AppRule, now: Long, usageMs: Long, sessionEnd: Long): Long {
        var rem = Long.MAX_VALUE
        if (rule.sessionMin > 0 && sessionEnd > now) rem = minOf(rem, (sessionEnd - now) / 1000L)
        if (rule.dailyLimitMin > 0) {
            rem = minOf(rem, maxOf(0L, rule.dailyLimitMin * 60_000L - usageMs) / 1000L)
        }
        return rem
    }

    // ---- website helpers -------------------------------------------------------------------

    /** Extracts "example.com" from text such as "https://www.example.com/a?b=1" or "m.example.com/x". */
    fun hostOf(text: String): String? {
        var t = text.trim().lowercase()
        if (t.isEmpty()) return null
        t = t.removePrefix("https://").removePrefix("http://")
        val end = t.indexOfFirst { it == '/' || it == '?' || it == '#' || it == ' ' }
        var host = if (end >= 0) t.substring(0, end) else t
        host = host.substringBefore(':').removePrefix("www.")
        if (!host.contains('.') || host.startsWith('.') || host.endsWith('.')) return null
        return host
    }

    /** Returns the blocked domain that [urlText] belongs to, or null. */
    fun matchSite(urlText: String, sites: List<String>): String? {
        val host = hostOf(urlText) ?: return null
        return sites.firstOrNull { host == it || host.endsWith(".$it") }
    }
}
