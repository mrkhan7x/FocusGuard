package com.focusguard.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val FOCUS_OPTIONS = listOf(15, 25, 45, 60, 90, 120)
private val STRICT_OPTIONS = listOf(1, 6, 24, 72, 168)

private fun hoursLabel(h: Int): String = if (h >= 24 && h % 24 == 0) "${h / 24} d" else "$h h"

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
fun FocusScreen(stamp: Int) {
    val ctx = LocalContext.current
    var nowMs by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = Clock.now()
            delay(1000L)
        }
    }
    val cfg = Repo.config
    val focusLeft = cfg.focusUntil - nowMs
    val strictLeft = cfg.strictUntil - nowMs

    var focusMin by rememberSaveable { mutableStateOf(25) }
    var strictHours by rememberSaveable { mutableStateOf(24) }
    var confirmStrict by rememberSaveable { mutableStateOf(false) }
    var confirmEnd by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Focus", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // ---- Focus session ----
        FgCard {
            Text("Focus session", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (focusLeft > 0L) {
                Text(
                    fmtDuration(focusLeft),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Every app on your list is locked. Everything else still works.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { if (Repo.strictActive()) ctx.toast(STRICT_MSG) else confirmEnd = true }) {
                    Text("End early")
                }
            } else {
                Text(
                    "Lock every app on your list for a fixed time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ChipRow(FOCUS_OPTIONS, focusMin, { "$it min" }) { focusMin = it }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (cfg.rules.isEmpty()) ctx.toast("Add some apps first (Apps tab).")
                        else if (!Repo.startFocus(focusMin)) ctx.toast("Could not start the focus session.")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start $focusMin min focus") }
            }
        }

        // ---- Strict mode ----
        FgCard {
            Text("Strict mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (strictLeft > 0L) {
                Text(
                    "Locked for ${fmtDuration(strictLeft)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "You can't remove or loosen limits, end focus early, switch schedules off, or open FocusGuard's " +
                        "Settings and uninstall screens. You can still add stricter rules.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("Extend", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                ChipRow(STRICT_OPTIONS, strictHours, { hoursLabel(it) }) { strictHours = it }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { Repo.startStrict(strictHours) }) {
                    Text("Extend by ${hoursLabel(strictHours)}")
                }
            } else {
                Text(
                    "Makes your rules binding. While it's on you can't remove or loosen limits, end focus early, " +
                        "switch schedules off, or open FocusGuard's Settings and uninstall screens. " +
                        "Adding stricter rules is still allowed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                ChipRow(STRICT_OPTIONS, strictHours, { hoursLabel(it) }) { strictHours = it }
                Spacer(Modifier.height(10.dp))
                Button(onClick = { confirmStrict = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Turn on for ${hoursLabel(strictHours)}")
                }
            }
        }

        // ---- Mindful pause ----
        FgCard {
            Text("Mindful pause", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "How long you must wait on the gate screen before an app with a session limit opens.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            StepSlider(
                title = "Wait",
                value = cfg.gateDelaySec,
                max = 60,
                step = 5,
                display = { if (it == 0) "None" else "$it s" },
            ) { v -> if (!Repo.setGateDelay(v)) ctx.toast(STRICT_MSG) }
        }
    }

    if (confirmStrict) {
        AlertDialog(
            onDismissRequest = { confirmStrict = false },
            title = { Text("Turn on strict mode?") },
            text = {
                Text(
                    "For the next ${hoursLabel(strictHours)} you won't be able to remove or loosen any limit, " +
                        "end a focus session early, or switch schedules off. It cannot be cancelled early."
                )
            },
            confirmButton = {
                Button(onClick = {
                    Repo.startStrict(strictHours)
                    confirmStrict = false
                }) { Text("Lock it in") }
            },
            dismissButton = { TextButton(onClick = { confirmStrict = false }) { Text("Cancel") } },
        )
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End focus session?") },
            text = { Text("You still have ${fmtDuration(focusLeft)} left. Staying locked is the point.") },
            confirmButton = {
                Button(onClick = {
                    if (!Repo.cancelFocus()) ctx.toast(STRICT_MSG)
                    confirmEnd = false
                }) { Text("End it") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep going") } },
        )
    }
}
