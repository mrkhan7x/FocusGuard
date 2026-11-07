package com.focusguard.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(stamp: Int, perms: PermStatus) {
    val ctx = LocalContext.current
    val cfg = Repo.config
    val now = Clock.now()
    val week = Repo.weekTotals()
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("FocusGuard", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            when {
                cfg.focusUntil > now -> "Focus session: ${fmtDuration(cfg.focusUntil - now)} left"
                cfg.strictUntil > now -> "Strict mode: ${fmtDuration(cfg.strictUntil - now)} left"
                else -> "Own your attention."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (Clock.tamperDetected) {
            FgCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "Clock change detected",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Your phone's date/time was changed. FocusGuard ignores it and keeps using the real time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (!perms.coreReady) {
            FgCard(container = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "Protection is OFF",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "Nothing is blocked until both switches below are on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        FgCard {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            PermRow(
                title = "Accessibility service",
                desc = "Required. Lets FocusGuard see which app is open.",
                ok = perms.accessibility,
                actionLabel = "Enable",
                onClick = { Perms.openAccessibility(ctx) },
            )
            if (!perms.accessibility) {
                Text(
                    "Switch greyed out? Open App info, tap the ⋮ menu and choose “Allow restricted settings”, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { Perms.openAppInfo(ctx) }, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Open App info")
                }
            }
            PermRow(
                title = "Display over other apps",
                desc = "Required. Lets the block screen appear on top of a blocked app.",
                ok = perms.overlay,
                actionLabel = "Allow",
                onClick = { Perms.openOverlay(ctx) },
            )
            PermRow(
                title = "Unrestricted battery",
                desc = "Recommended. Stops the system from putting FocusGuard to sleep.",
                ok = perms.battery,
                actionLabel = "Fix",
                onClick = { Perms.openBattery(ctx) },
            )
            PermRow(
                title = "Uninstall protection",
                desc = "Optional. Makes removing FocusGuard take extra steps.",
                ok = perms.admin,
                actionLabel = "Turn on",
                onClick = { Perms.requestAdmin(ctx) },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(fmtDuration(Repo.totalUsageMs()), "tracked today", Modifier.weight(1f))
            StatTile(Repo.blocksToday().toString(), "blocks", Modifier.weight(1f))
            StatTile(Repo.sessionsToday().toString(), "sessions", Modifier.weight(1f))
        }

        FgCard {
            Text("Last 7 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            WeekChart(week)
        }

        if (cfg.rules.isNotEmpty()) {
            FgCard {
                Text("Today by app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                cfg.rules.forEach { rule ->
                    val used = Repo.usageMs(rule.pkg)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(rule.pkg, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(rule.label, style = MaterialTheme.typography.bodyMedium)
                            if (rule.dailyLimitMin > 0 && !rule.alwaysBlocked) {
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { (used.toFloat() / (rule.dailyLimitMin * 60_000f)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            fmtDuration(used),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        FgCard {
            Text("Why you opened apps", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val log = Repo.intents().take(6)
            if (log.isEmpty()) {
                Text(
                    "When an app uses the mindful gate, the reason you typed shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                log.forEach { e ->
                    val whenText = Instant.ofEpochMilli(e.time).atZone(ZoneId.systemDefault()).format(timeFmt)
                    Text(
                        "$whenText · ${e.label} · ${e.minutes} min",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    Text("“${e.text}”", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, actionLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (ok) Icons.Filled.Check else Icons.Filled.Warning,
            contentDescription = if (ok) "Done" else "Needs attention",
            tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) {
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onClick) { Text(actionLabel) }
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    FgCard(modifier = modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WeekChart(data: List<Pair<LocalDate, Long>>) {
    val maxMs = (data.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    val bar = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        data.forEachIndexed { i, (date, ms) ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val barHeight = maxOf(4f, 80f * ms.toFloat() / maxMs.toFloat())
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i == data.lastIndex) bar else bar.copy(alpha = 0.4f))
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    date.dayOfWeek.name.first().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
