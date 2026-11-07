package com.focusguard.app

/** Thin bridge between the stored state (Repo) and the pure rules (Logic). */
object Engine {
    fun decide(pkg: String): Decision {
        val rule = Repo.rule(pkg) ?: return Decision.Allow
        return Logic.decide(
            rule = rule,
            cfg = Repo.config,
            now = Clock.now(),
            usageMs = Repo.usageMs(pkg),
            opens = Repo.opens(pkg),
            sessionEnd = Repo.sessionEnd(pkg),
        )
    }

    fun remainingSec(pkg: String): Long {
        val rule = Repo.rule(pkg) ?: return Long.MAX_VALUE
        return Logic.remainingSec(rule, Clock.now(), Repo.usageMs(pkg), Repo.sessionEnd(pkg))
    }
}
