package com.focusguard.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun summary(r: AppRule): String {
    if (r.alwaysBlocked) return "Blocked completely"
    val parts = ArrayList<String>()
    if (r.dailyLimitMin > 0) parts.add("${fmtMin(r.dailyLimitMin)}/day")
    if (r.sessionMin > 0) {
        parts.add("${r.sessionMin} min sessions")
        if (r.cooldownMin > 0) parts.add("${r.cooldownMin} min cooldown")
        if (r.maxOpens > 0) parts.add("${r.maxOpens} opens/day")
    }
    if (parts.isEmpty()) parts.add("No time limit")
    if (r.followSchedules) parts.add("follows schedules")
    return parts.joinToString(" · ")
}

@Composable
fun AppsScreen(stamp: Int) {
    val ctx = LocalContext.current
    val cfg = Repo.config
    val strict = Repo.strictActive()
    var editing by remember { mutableStateOf<AppRule?>(null) }
    var picking by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your apps", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Apps on this list are limited. Everything else stays free.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { picking = true }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        if (strict) {
            FgCard(container = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "Strict mode is on. You can add apps and make existing limits stricter, but you can't loosen or remove any rule until it ends.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (cfg.rules.isEmpty()) {
            FgCard {
                Text("No apps yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tap Add and pick the apps that steal your time: Instagram, TikTok, YouTube, Reddit…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        cfg.rules.forEach { rule ->
            key(rule.pkg) { RuleCard(rule) { editing = rule } }
        }
    }

    editing?.let { rule ->
        RuleDialog(
            rule = rule,
            locked = strict,
            onDismiss = { editing = null },
            onSave = { updated ->
                if (!Repo.upsertRule(updated)) ctx.toast(STRICT_MSG)
                editing = null
            },
            onDelete = {
                if (!Repo.removeRule(rule.pkg)) ctx.toast(STRICT_MSG)
                editing = null
            },
        )
    }

    if (picking) {
        AppPicker(
            existing = cfg.rules.map { it.pkg }.toSet(),
            onDismiss = { picking = false },
            onAdd = { chosen ->
                chosen.forEach { app ->
                    Repo.upsertRule(
                        AppRule(app.pkg, app.label, dailyLimitMin = 30, sessionMin = 10, cooldownMin = 30)
                    )
                }
                picking = false
                ctx.toast("Added ${chosen.size}. Tap an app to tune its limits.")
            },
        )
    }
}

@Composable
private fun RuleCard(rule: AppRule, onClick: () -> Unit) {
    val used = Repo.usageMs(rule.pkg)
    FgCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(rule.pkg)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rule.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    summary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (rule.dailyLimitMin > 0 && !rule.alwaysBlocked) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (used.toFloat() / (rule.dailyLimitMin * 60_000f)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${fmtDuration(used)} of ${fmtMin(rule.dailyLimitMin)} used today",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RuleDialog(
    rule: AppRule,
    locked: Boolean,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit,
    onDelete: () -> Unit,
) {
    var daily by remember { mutableIntStateOf(rule.dailyLimitMin) }
    var session by remember { mutableIntStateOf(rule.sessionMin) }
    var cool by remember { mutableIntStateOf(rule.cooldownMin) }
    var opens by remember { mutableIntStateOf(rule.maxOpens) }
    var always by remember { mutableStateOf(rule.alwaysBlocked) }
    var sched by remember { mutableStateOf(rule.followSchedules) }

    // Under strict mode sliders stay interactive but are clamped so they can only
    // move toward stricter (lower) values. Zero-value fields that are "off" (no limit
    // set yet) stay unlocked, because adding a limit is always a tightening action.
    val dailyMin  = if (locked && rule.dailyLimitMin  > 0) 5                   else 0
    val dailyMax  = if (locked && rule.dailyLimitMin  > 0) rule.dailyLimitMin  else 240
    val sessionMin = if (locked && rule.sessionMin    > 0) 5                   else 0
    val sessionMax = if (locked && rule.sessionMin    > 0) rule.sessionMin     else 60
    // Cooldown and opens can only get longer/fewer under strict mode.
    val coolMax   = if (locked && rule.cooldownMin    > 0) 120                 else 120
    val coolMin   = if (locked && rule.cooldownMin    > 0) rule.cooldownMin    else 0
    val opensMax  = if (locked && rule.maxOpens       > 0) rule.maxOpens       else 20
    val opensMin  = if (locked && rule.maxOpens       > 0) 1                   else 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(rule.label) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (locked) {
                    Text(
                        "Strict mode is on. You can tighten limits but not loosen or remove them.",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StepSlider(
                    "Daily limit", daily, dailyMax, 5,
                    { fmtMin(it) }, min = dailyMin,
                ) { daily = it }
                StepSlider(
                    "Mindful session length", session, sessionMax, 5,
                    { fmtMin(it) }, min = sessionMin,
                ) { session = it }
                if (session > 0) {
                    StepSlider(
                        "Cooldown after a session", cool, coolMax, 5,
                        { if (it == 0) "None" else fmtMin(it) }, min = coolMin,
                    ) { cool = it }
                    StepSlider(
                        "Max opens per day", opens, opensMax, 1,
                        { if (it == 0) "Unlimited" else "$it" }, min = opensMin,
                    ) { opens = it }
                    Text(
                        "Each time you open this app you pause, say why, and pick a session length up to ${session} min. " +
                            "When it ends the app locks for the cooldown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Block completely", modifier = Modifier.weight(1f))
                    // Can always turn on; strict mode only blocks turning it off.
                    Switch(
                        checked = always,
                        onCheckedChange = { v -> if (!locked || v) always = v },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also block during schedules", modifier = Modifier.weight(1f))
                    // Can always turn on; strict mode only blocks turning it off.
                    Switch(
                        checked = sched,
                        onCheckedChange = { v -> if (!locked || v) sched = v },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        rule.copy(
                            dailyLimitMin = daily,
                            sessionMin = session,
                            cooldownMin = cool,
                            maxOpens = if (session > 0) opens else 0,
                            alwaysBlocked = always,
                            followSchedules = sched,
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!locked) {
                    TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private data class InstalledApp(val pkg: String, val label: String, val social: Boolean)

private fun loadApps(ctx: Context, exclude: Set<String>): List<InstalledApp> {
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val never = protectedPackages(ctx)
    return ctx.packageManager.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it !in never && it !in exclude }
        .map { InstalledApp(it, appLabel(ctx, it), it in SOCIAL_PACKAGES) }
        .sortedWith(compareByDescending<InstalledApp> { it.social }.thenBy { it.label.lowercase() })
}

@Composable
private fun AppPicker(existing: Set<String>, onDismiss: () -> Unit, onAdd: (List<InstalledApp>) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { loadApps(ctx, existing) }
    }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onAdd(apps.orEmpty().filter { it.pkg in selected }) },
                        enabled = selected.isNotEmpty(),
                    ) { Text("Add (${selected.size})") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.pkg }) { app ->
                            val checked = app.pkg in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (checked) selected - app.pkg else selected + app.pkg
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.pkg)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    if (app.social) {
                                        Text(
                                            "Social / entertainment",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                }
            }
        }
    }
}
