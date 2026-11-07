package com.focusguard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class LogicTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    // 2026-11-05 is a Thursday.
    private fun at(day: Int, hour: Int, minute: Int = 0, second: Int = 0): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, second, 0, utc).toInstant().toEpochMilli()

    private val min = 60_000L

    private fun decide(
        rule: AppRule,
        now: Long,
        cfg: Config = Config(rules = listOf(rule)),
        usage: Long = 0L,
        opens: Int = 0,
        sessionEnd: Long = 0L,
    ) = Logic.decide(rule, cfg, now, usage, opens, sessionEnd, utc)

    @Test
    fun appWithoutLimitsIsAllowed() {
        val rule = AppRule("a", "A")
        assertEquals(Decision.Allow, decide(rule, at(5, 12)))
    }

    @Test
    fun dailyLimitBlocksUntilMidnight() {
        val rule = AppRule("a", "A", dailyLimitMin = 30)
        val now = at(5, 12)
        assertEquals(Decision.Allow, decide(rule, now, usage = 29 * min))
        val d = decide(rule, now, usage = 30 * min)
        assertTrue(d is Decision.Block)
        d as Decision.Block
        assertEquals(Reason.DAILY_LIMIT, d.reason)
        assertEquals(at(6, 0), d.untilMs)
    }

    @Test
    fun sessionGateThenAllowThenCooldownThenGateAgain() {
        val rule = AppRule("a", "A", sessionMin = 10, cooldownMin = 30)
        val t0 = at(5, 12)
        // No session yet -> gate.
        assertEquals(Decision.Gate(10), decide(rule, t0))
        // Session started at t0 for 10 min.
        val end = t0 + 10 * min
        assertEquals(Decision.Allow, decide(rule, t0 + 5 * min, sessionEnd = end))
        // Right after it ends -> cooldown until end + 30.
        val cool = decide(rule, end + 1, sessionEnd = end)
        assertEquals(Decision.Block(Reason.COOLDOWN, end + 30 * min), cool)
        // After cooldown -> gate again.
        assertEquals(Decision.Gate(10), decide(rule, end + 30 * min, sessionEnd = end))
    }

    @Test
    fun maxOpensBlocksNewSessions() {
        val rule = AppRule("a", "A", sessionMin = 10, cooldownMin = 0, maxOpens = 3)
        val now = at(5, 12)
        assertEquals(Decision.Gate(10), decide(rule, now, opens = 2))
        val d = decide(rule, now, opens = 3)
        assertEquals(Decision.Block(Reason.OPENS, at(6, 0)), d)
    }

    @Test
    fun gateIsCappedByRemainingDailyTime() {
        val rule = AppRule("a", "A", dailyLimitMin = 30, sessionMin = 20)
        // 25 min used -> 5 min left -> gate max is 5.
        assertEquals(Decision.Gate(5), decide(rule, at(5, 12), usage = 25 * min))
        // 29.5 min used -> rounds up to 1 min.
        assertEquals(Decision.Gate(1), decide(rule, at(5, 12), usage = 29 * min + 30_000L))
    }

    @Test
    fun alwaysBlockedAndFocusOverrideEverything() {
        val blocked = AppRule("a", "A", alwaysBlocked = true)
        assertEquals(Decision.Block(Reason.ALWAYS, 0L), decide(blocked, at(5, 12)))

        val rule = AppRule("a", "A")
        val now = at(5, 12)
        val cfg = Config(rules = listOf(rule), focusUntil = now + 25 * min)
        assertEquals(Decision.Block(Reason.FOCUS, now + 25 * min), decide(rule, now, cfg))
        // Focus over -> allowed again.
        assertEquals(Decision.Allow, decide(rule, now + 26 * min, cfg))
    }

    @Test
    fun overnightScheduleSpansMidnight() {
        // Every day 22:30 -> 06:30
        val s = Schedule("s", "Sleep", Logic.ALL_DAYS, 22 * 60 + 30, 6 * 60 + 30)
        assertNull(Logic.scheduleEnd(s, at(5, 12), utc))
        assertNull(Logic.scheduleEnd(s, at(5, 22, 29), utc))
        assertEquals(at(6, 6, 30), Logic.scheduleEnd(s, at(5, 22, 30), utc))
        assertEquals(at(6, 6, 30), Logic.scheduleEnd(s, at(5, 23, 59, 30), utc))
        assertEquals(at(6, 6, 30), Logic.scheduleEnd(s, at(6, 3, 10), utc))
        assertNull(Logic.scheduleEnd(s, at(6, 6, 30), utc))
    }

    @Test
    fun overnightScheduleUsesStartDayForDayMask() {
        // Fridays only, 23:00 -> 02:00. Nov 6 2026 = Friday, Nov 7 = Saturday.
        val fri = 1 shl 4
        val s = Schedule("s", "Fri night", fri, 23 * 60, 2 * 60)
        assertEquals(at(7, 2), Logic.scheduleEnd(s, at(6, 23, 30), utc))
        assertEquals(at(7, 2), Logic.scheduleEnd(s, at(7, 1, 0), utc)) // Saturday 01:00 still belongs to Friday's window
        assertNull(Logic.scheduleEnd(s, at(7, 23, 30), utc))          // Saturday night: not enabled
        assertNull(Logic.scheduleEnd(s, at(5, 23, 30), utc))          // Thursday night: not enabled
    }

    @Test
    fun daytimeScheduleRespectsDaysAndEnabledFlag() {
        val work = Schedule("w", "Work", Logic.WEEKDAYS, 9 * 60, 17 * 60)
        assertEquals(at(5, 17), Logic.scheduleEnd(work, at(5, 10), utc))   // Thursday
        assertNull(Logic.scheduleEnd(work, at(7, 10), utc))                 // Saturday
        assertNull(Logic.scheduleEnd(work.copy(enabled = false), at(5, 10), utc))
        assertNull(Logic.scheduleEnd(work, at(5, 17), utc))                 // end is exclusive
    }

    @Test
    fun scheduleBlocksOnlyRulesThatFollowSchedules() {
        val work = Schedule("w", "Work", Logic.ALL_DAYS, 9 * 60, 17 * 60)
        val follow = AppRule("a", "A")
        val ignore = AppRule("b", "B", followSchedules = false)
        val cfg = Config(rules = listOf(follow, ignore), schedules = listOf(work))
        val now = at(5, 10)
        assertEquals(Decision.Block(Reason.SCHEDULE, at(5, 17), "Work"), decide(follow, now, cfg))
        assertEquals(Decision.Allow, decide(ignore, now, cfg))
    }

    @Test
    fun remainingSecondsUsesTheTighterLimit() {
        val rule = AppRule("a", "A", dailyLimitMin = 30, sessionMin = 10)
        val now = at(5, 12)
        // 5 min left in session, 20 min left in day -> 300 s
        assertEquals(300L, Logic.remainingSec(rule, now, 10 * min, now + 5 * min))
        // 5 min left in session, 2 min left in day -> 120 s
        assertEquals(120L, Logic.remainingSec(rule, now, 28 * min, now + 5 * min))
        assertEquals(Long.MAX_VALUE, Logic.remainingSec(AppRule("a", "A"), now, 0, 0))
    }

    @Test
    fun hostParsingHandlesCommonUrlBarFormats() {
        assertEquals("youtube.com", Logic.hostOf("https://www.youtube.com/watch?v=1"))
        assertEquals("m.youtube.com", Logic.hostOf("m.youtube.com/shorts/xyz"))
        assertEquals("reddit.com", Logic.hostOf("Reddit.com"))
        assertNull(Logic.hostOf("how to cook rice"))
        assertNull(Logic.hostOf("localhost:3000"))
        assertNull(Logic.hostOf(""))
    }

    @Test
    fun siteMatchingCoversSubdomainsButNotLookalikes() {
        val sites = listOf("youtube.com", "x.com")
        assertEquals("youtube.com", Logic.matchSite("m.youtube.com/feed", sites))
        assertEquals("youtube.com", Logic.matchSite("https://www.youtube.com", sites))
        assertEquals("x.com", Logic.matchSite("x.com/home", sites))
        assertNull(Logic.matchSite("notyoutube.com", sites))
        assertNull(Logic.matchSite("fox.com", sites))
        assertNull(Logic.matchSite("youtube.com.evil.org", sites))
    }

    @Test
    fun dayKeyAndMidnightFollowTheZone() {
        assertEquals("2026-11-05", Logic.dayKey(at(5, 23, 59, 59), utc))
        assertEquals("2026-11-06", Logic.dayKey(at(6, 0, 0, 0), utc))
        assertEquals(at(6, 0), Logic.nextMidnight(at(5, 13, 45), utc))
    }
}
