package com.focusguard.app

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val SITE_SUGGESTIONS = listOf(
    "youtube.com", "instagram.com", "tiktok.com", "x.com", "reddit.com", "facebook.com", "twitch.tv",
)

private fun pickTime(ctx: Context, minuteOfDay: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPick(h * 60 + m) }, minuteOfDay / 60, minuteOfDay % 60, true).show()
}

@Composable
fun RulesScreen(stamp: Int) {
    val ctx = LocalContext.current
    val cfg = Repo.config
    var adding by remember { mutableStateOf(false) }
    var siteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Rules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---- Schedules ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Schedules",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { adding = true }) { Text("New") }
        }
        Text(
            "During a schedule, every app on your list that follows schedules is blocked.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (cfg.schedules.isEmpty()) {
            FgCard {
                Text(
                    "No schedules yet. Try a Sleep schedule (22:30–06:30) or Work hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        cfg.schedules.forEach { s ->
            FgCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${daysLabel(s.days)} · ${fmtTime(s.startMin)}–${fmtTime(s.endMin)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = s.enabled,
                        onCheckedChange = { on -> if (!Repo.setScheduleEnabled(s.id, on)) ctx.toast(STRICT_MSG) },
                    )
                    IconButton(onClick = { if (!Repo.removeSchedule(s.id)) ctx.toast(STRICT_MSG) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete schedule")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- Blocked websites ----
        Text("Blocked websites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Always blocked in supported browsers (Chrome, Edge, Brave, Firefox, Opera, Vivaldi, Samsung Internet, " +
                "DuckDuckGo), including subdomains. Use the apps list for the apps themselves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FgCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = siteText,
                    onValueChange = { siteText = it },
                    label = { Text("e.g. youtube.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (Repo.addSite(siteText)) siteText = "" else ctx.toast("Enter a domain like youtube.com")
                }) { Text("Block") }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SITE_SUGGESTIONS.filter { it !in cfg.sites }.forEach { site ->
                    FilterChip(selected = false, onClick = { Repo.addSite(site) }, label = { Text(site) })
                }
            }
            cfg.sites.forEach { site ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(site, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    IconButton(onClick = { if (!Repo.removeSite(site)) ctx.toast(STRICT_MSG) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Unblock $site")
                    }
                }
            }
        }
    }

    if (adding) {
        ScheduleDialog(
            onDismiss = { adding = false },
            onSave = { s ->
                Repo.upsertSchedule(s)
                adding = false
            },
        )
    }
}

@Composable
private fun DayToggle(letter: String, on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ScheduleDialog(onDismiss: () -> Unit, onSave: (Schedule) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(Logic.ALL_DAYS) }
    var start by remember { mutableIntStateOf(22 * 60 + 30) }
    var end by remember { mutableIntStateOf(6 * 60 + 30) }
    val valid = days != 0 && start != end

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New schedule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = false, onClick = {
                        name = "Sleep"; days = Logic.ALL_DAYS; start = 22 * 60 + 30; end = 6 * 60 + 30
                    }, label = { Text("Sleep") })
                    FilterChip(selected = false, onClick = {
                        name = "Work hours"; days = Logic.WEEKDAYS; start = 9 * 60; end = 17 * 60
                    }, label = { Text("Work") })
                    FilterChip(selected = false, onClick = {
                        name = "Study"; days = Logic.ALL_DAYS; start = 18 * 60; end = 21 * 60
                    }, label = { Text("Study") })
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, letter ->
                        DayToggle(letter, (days and (1 shl i)) != 0) { days = days xor (1 shl i) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx, start) { start = it } }) { Text("From ${fmtTime(start)}") }
                    OutlinedButton(onClick = { pickTime(ctx, end) { end = it } }) { Text("To ${fmtTime(end)}") }
                }
                if (start != end && start > end) {
                    Text(
                        "Runs overnight into the next morning.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!valid) {
                    Text(
                        if (days == 0) "Pick at least one day." else "Start and end must be different.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = { onSave(Schedule(Repo.newId(), name.trim().ifEmpty { "Schedule" }, days, start, end)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
