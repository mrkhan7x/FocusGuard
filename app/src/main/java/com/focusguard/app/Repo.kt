package com.focusguard.app

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class IntentEntry(val time: Long, val pkg: String, val label: String, val text: String, val minutes: Int)

/**
 * Single source of truth, shared by the accessibility service and the UI (same process).
 * Everything runs on the main thread, so no locking is needed beyond @Synchronized on init.
 *
 * Strict mode: while Config.strictUntil is in the future, every mutation that would loosen a
 * rule is refused here (not just hidden in the UI). Adding apps/schedules/sites is still allowed.
 */
object Repo {
    private lateinit var prefs: SharedPreferences

    var config: Config = Config()
        private set

    private var dayKey = ""
    private val usage = HashMap<String, Long>()          // pkg -> ms used today
    private val openCount = HashMap<String, Int>()       // pkg -> sessions started today
    private var blocks = 0
    private val sessionEnds = HashMap<String, Long>()    // pkg -> epoch ms when current/last session ends
    private val history = LinkedHashMap<String, Long>()  // yyyy-MM-dd -> total tracked ms (past days)
    private val intentLog = ArrayList<IntentEntry>()

    /** Bumped on every change to the config so the UI can refresh. */
    val version = MutableStateFlow(0)

    private var dirty = false
    private var lastSaveElapsed = 0L

    // ---- lifecycle ---------------------------------------------------------------------------

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        val app = ctx.applicationContext
        prefs = app.getSharedPreferences("focusguard", Context.MODE_PRIVATE)
        Clock.init(app)
        config = try {
            configFromJson(JSONObject(prefs.getString("config", "{}") ?: "{}"))
        } catch (e: Exception) {
            Config()
        }
        try {
            loadState(JSONObject(prefs.getString("state", "{}") ?: "{}"))
        } catch (e: Exception) {
            // corrupt state: start the day fresh
        }
        rollDay()
    }

    /** Called about once a second by the service. */
    fun tick() {
        rollDay()
        if (dirty && SystemClock.elapsedRealtime() - lastSaveElapsed > 15_000L) flush()
    }

    fun flush() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString("state", stateToJson().toString()).apply()
        Clock.persist()
        dirty = false
        lastSaveElapsed = SystemClock.elapsedRealtime()
    }

    private fun rollDay() {
        val today = Logic.dayKey(Clock.now())
        if (today == dayKey) return
        if (dayKey.isNotEmpty()) {
            history[dayKey] = usage.values.sum()
            while (history.size > 14) history.remove(history.keys.first())
        }
        dayKey = today
        usage.clear()
        openCount.clear()
        blocks = 0
        val cutoff = Clock.now() - 86_400_000L
        sessionEnds.entries.removeAll { it.value < cutoff }
        dirty = true
        flush()
        bump()
    }

    fun bump() {
        version.value = version.value + 1
    }

    // ---- reads -------------------------------------------------------------------------------

    fun rule(pkg: String): AppRule? = config.rules.firstOrNull { it.pkg == pkg }
    fun usageMs(pkg: String): Long = usage[pkg] ?: 0L
    fun totalUsageMs(): Long = usage.values.sum()
    fun opens(pkg: String): Int = openCount[pkg] ?: 0
    fun sessionEnd(pkg: String): Long = sessionEnds[pkg] ?: 0L
    fun blocksToday(): Int = blocks
    fun sessionsToday(): Int = openCount.values.sum()
    fun intents(): List<IntentEntry> = intentLog.toList()
    fun strictActive(now: Long = Clock.now()): Boolean = config.strictUntil > now
    fun focusActive(now: Long = Clock.now()): Boolean = config.focusUntil > now

    /** Last 7 days, oldest first: (date, tracked ms). The last entry is today. */
    fun weekTotals(): List<Pair<LocalDate, Long>> {
        val today = LocalDate.parse(Logic.dayKey(Clock.now()))
        return (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val ms = if (back == 0) totalUsageMs() else (history[d.toString()] ?: 0L)
            Pair(d, ms)
        }
    }

    // ---- usage bookkeeping (not config, so never blocked by strict mode) ----------------------

    fun addUsage(pkg: String, ms: Long) {
        usage[pkg] = (usage[pkg] ?: 0L) + ms
        dirty = true
    }

    fun recordBlock() {
        blocks += 1
        dirty = true
    }

    fun startSession(pkg: String, minutes: Int, intentText: String) {
        val now = Clock.now()
        sessionEnds[pkg] = now + minutes * 60_000L
        openCount[pkg] = (openCount[pkg] ?: 0) + 1
        val label = rule(pkg)?.label ?: pkg
        intentLog.add(0, IntentEntry(now, pkg, label, intentText.trim(), minutes))
        while (intentLog.size > 30) intentLog.removeAt(intentLog.size - 1)
        dirty = true
        flush()
        bump()
    }

    // ---- config mutations (return false when refused by strict mode / invalid input) ----------

    fun upsertRule(newRule: AppRule): Boolean {
        val old = rule(newRule.pkg)
        if (old != null && strictActive() && isLooser(newRule, old)) return false
        val others = config.rules.filter { it.pkg != newRule.pkg }
        config = config.copy(rules = (others + newRule).sortedBy { it.label.lowercase() })
        saveConfig()
        return true
    }

    /**
     * Returns true if [new] is less restrictive than [old] in any dimension.
     * Used by strict mode: loosening is refused, tightening is allowed.
     */
    private fun isLooser(new: AppRule, old: AppRule): Boolean {
        // Removing a daily limit, or raising it
        if (old.dailyLimitMin > 0 && (new.dailyLimitMin == 0 || new.dailyLimitMin > old.dailyLimitMin)) return true
        // Removing a session gate, or raising the session cap
        if (old.sessionMin > 0 && (new.sessionMin == 0 || new.sessionMin > old.sessionMin)) return true
        // Shortening the cooldown
        if (old.cooldownMin > 0 && new.cooldownMin < old.cooldownMin) return true
        // Raising (or removing) the opens cap
        if (old.maxOpens > 0 && (new.maxOpens == 0 || new.maxOpens > old.maxOpens)) return true
        // Unlocking an always-blocked app
        if (old.alwaysBlocked && !new.alwaysBlocked) return true
        // Opting out of schedules (removes a restriction)
        if (old.followSchedules && !new.followSchedules) return true
        return false
    }

    fun removeRule(pkg: String): Boolean {
        if (strictActive()) return false
        config = config.copy(rules = config.rules.filter { it.pkg != pkg })
        saveConfig()
        return true
    }

    fun upsertSchedule(s: Schedule): Boolean {
        val exists = config.schedules.any { it.id == s.id }
        if (exists && strictActive()) return false
        config = config.copy(schedules = config.schedules.filter { it.id != s.id } + s)
        saveConfig()
        return true
    }

    fun removeSchedule(id: String): Boolean {
        if (strictActive()) return false
        config = config.copy(schedules = config.schedules.filter { it.id != id })
        saveConfig()
        return true
    }

    fun setScheduleEnabled(id: String, enabled: Boolean): Boolean {
        if (!enabled && strictActive()) return false
        config = config.copy(schedules = config.schedules.map { if (it.id == id) it.copy(enabled = enabled) else it })
        saveConfig()
        return true
    }

    fun addSite(input: String): Boolean {
        val host = Logic.hostOf(input) ?: return false
        if (host in config.sites) return true
        config = config.copy(sites = (config.sites + host).sorted())
        saveConfig()
        return true
    }

    fun removeSite(site: String): Boolean {
        if (strictActive()) return false
        config = config.copy(sites = config.sites.filter { it != site })
        saveConfig()
        return true
    }

    fun startFocus(minutes: Int): Boolean {
        if (minutes <= 0) return false
        val until = Clock.now() + minutes * 60_000L
        if (until <= config.focusUntil) return false
        config = config.copy(focusUntil = until)
        saveConfig()
        return true
    }

    fun cancelFocus(): Boolean {
        if (strictActive()) return false
        config = config.copy(focusUntil = 0L)
        saveConfig()
        return true
    }

    /** Starts strict mode, or extends it (extension is always allowed). */
    fun startStrict(hours: Int): Boolean {
        if (hours <= 0) return false
        val now = Clock.now()
        val base = maxOf(config.strictUntil, now)
        config = config.copy(strictUntil = base + hours * 3_600_000L)
        saveConfig()
        return true
    }

    fun setGateDelay(sec: Int): Boolean {
        if (strictActive() && sec < config.gateDelaySec) return false
        config = config.copy(gateDelaySec = sec.coerceIn(0, 60))
        saveConfig()
        return true
    }

    fun newId(): String = UUID.randomUUID().toString().take(8)

    private fun saveConfig() {
        prefs.edit().putString("config", configToJson(config).toString()).apply()
        bump()
    }

    // ---- JSON ---------------------------------------------------------------------------------

    private fun ruleToJson(r: AppRule): JSONObject = JSONObject()
        .put("pkg", r.pkg)
        .put("label", r.label)
        .put("daily", r.dailyLimitMin)
        .put("session", r.sessionMin)
        .put("cool", r.cooldownMin)
        .put("opens", r.maxOpens)
        .put("always", r.alwaysBlocked)
        .put("sched", r.followSchedules)

    private fun ruleFromJson(o: JSONObject): AppRule = AppRule(
        pkg = o.getString("pkg"),
        label = o.optString("label", o.getString("pkg")),
        dailyLimitMin = o.optInt("daily", 0),
        sessionMin = o.optInt("session", 0),
        cooldownMin = o.optInt("cool", 30),
        maxOpens = o.optInt("opens", 0),
        alwaysBlocked = o.optBoolean("always", false),
        followSchedules = o.optBoolean("sched", true),
    )

    private fun scheduleToJson(s: Schedule): JSONObject = JSONObject()
        .put("id", s.id)
        .put("name", s.name)
        .put("days", s.days)
        .put("start", s.startMin)
        .put("end", s.endMin)
        .put("on", s.enabled)

    private fun scheduleFromJson(o: JSONObject): Schedule = Schedule(
        id = o.getString("id"),
        name = o.optString("name", "Schedule"),
        days = o.optInt("days", Logic.ALL_DAYS),
        startMin = o.optInt("start", 0),
        endMin = o.optInt("end", 0),
        enabled = o.optBoolean("on", true),
    )

    private fun configToJson(c: Config): JSONObject {
        val rules = JSONArray()
        c.rules.forEach { rules.put(ruleToJson(it)) }
        val schedules = JSONArray()
        c.schedules.forEach { schedules.put(scheduleToJson(it)) }
        val sites = JSONArray()
        c.sites.forEach { sites.put(it) }
        return JSONObject()
            .put("rules", rules)
            .put("schedules", schedules)
            .put("sites", sites)
            .put("focusUntil", c.focusUntil)
            .put("strictUntil", c.strictUntil)
            .put("gate", c.gateDelaySec)
    }

    private fun configFromJson(o: JSONObject): Config {
        val rules = ArrayList<AppRule>()
        val ra = o.optJSONArray("rules")
        if (ra != null) for (i in 0 until ra.length()) rules.add(ruleFromJson(ra.getJSONObject(i)))
        val schedules = ArrayList<Schedule>()
        val sa = o.optJSONArray("schedules")
        if (sa != null) for (i in 0 until sa.length()) schedules.add(scheduleFromJson(sa.getJSONObject(i)))
        val sites = ArrayList<String>()
        val xa = o.optJSONArray("sites")
        if (xa != null) for (i in 0 until xa.length()) sites.add(xa.getString(i))
        return Config(
            rules = rules,
            schedules = schedules,
            sites = sites,
            focusUntil = o.optLong("focusUntil", 0L),
            strictUntil = o.optLong("strictUntil", 0L),
            gateDelaySec = o.optInt("gate", 10),
        )
    }

    private fun longMapToJson(m: Map<String, Long>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun intMapToJson(m: Map<String, Int>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        return o
    }

    private fun stateToJson(): JSONObject {
        val log = JSONArray()
        for (e in intentLog) {
            log.put(
                JSONObject()
                    .put("t", e.time).put("pkg", e.pkg).put("label", e.label)
                    .put("text", e.text).put("min", e.minutes)
            )
        }
        return JSONObject()
            .put("day", dayKey)
            .put("usage", longMapToJson(usage))
            .put("opens", intMapToJson(openCount))
            .put("blocks", blocks)
            .put("sessions", longMapToJson(sessionEnds))
            .put("history", longMapToJson(history))
            .put("log", log)
    }

    private fun loadState(o: JSONObject) {
        dayKey = o.optString("day", "")
        o.optJSONObject("usage")?.let { u -> u.keys().forEach { k -> usage[k] = u.getLong(k) } }
        o.optJSONObject("opens")?.let { u -> u.keys().forEach { k -> openCount[k] = u.getInt(k) } }
        blocks = o.optInt("blocks", 0)
        o.optJSONObject("sessions")?.let { u -> u.keys().forEach { k -> sessionEnds[k] = u.getLong(k) } }
        o.optJSONObject("history")?.let { u -> u.keys().forEach { k -> history[k] = u.getLong(k) } }
        val log = o.optJSONArray("log")
        if (log != null) {
            for (i in 0 until log.length()) {
                val e = log.getJSONObject(i)
                intentLog.add(
                    IntentEntry(e.getLong("t"), e.getString("pkg"), e.optString("label", ""), e.optString("text", ""), e.optInt("min", 0))
                )
            }
        }
    }
}
