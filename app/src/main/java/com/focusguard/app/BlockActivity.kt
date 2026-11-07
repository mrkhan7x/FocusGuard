package com.focusguard.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Full-screen interruption. Three modes, chosen live from the Engine:
 *  - Block: the app is locked (reason + countdown),
 *  - Gate: the app is allowed after a mindful pause, a stated intent and a chosen session length,
 *  - Allow: the lock has just expired; offer to open the app.
 */
class BlockActivity : ComponentActivity() {
    private var pkg by mutableStateOf("")
    private var site by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Restore from saved state if Android killed and restarted the process;
        // fall back to the launching intent if no saved state exists yet.
        pkg = savedInstanceState?.getString("pkg")
            ?: intent?.getStringExtra(GuardService.EXTRA_PKG)
            ?: ""
        site = savedInstanceState?.getString("site")
            ?: intent?.getStringExtra(GuardService.EXTRA_SITE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                goHome()
            }
        })
        setContent {
            FocusGuardTheme {
                BlockScreen(
                    pkg = pkg,
                    site = site,
                    onHome = { goHome() },
                    onOpenApp = { launchApp(pkg) },
                    onStart = { minutes, why -> startSession(minutes, why) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pkg", pkg)
        site?.let { outState.putString("site", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        pkg = i?.getStringExtra(GuardService.EXTRA_PKG) ?: ""
        site = i?.getStringExtra(GuardService.EXTRA_SITE)
    }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(home)
        finish()
    }

    private fun launchApp(target: String) {
        val launch = packageManager.getLaunchIntentForPackage(target)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
            finish()
        } else {
            goHome()
        }
    }

    private fun startSession(minutes: Int, why: String) {
        val d = Engine.decide(pkg)
        if (d !is Decision.Gate || minutes > d.maxMinutes) return
        Repo.startSession(pkg, minutes, why)
        launchApp(pkg)
    }
}

private fun blockTitle(b: Decision.Block): String = when (b.reason) {
    Reason.FOCUS -> "Focus session in progress"
    Reason.SCHEDULE -> "Blocked by schedule"
    Reason.ALWAYS -> "Blocked"
    Reason.DAILY_LIMIT -> "Daily limit reached"
    Reason.COOLDOWN -> "Cooling down"
    Reason.OPENS -> "No opens left today"
    Reason.SITE -> "Website blocked"
}

private fun blockDetail(b: Decision.Block, label: String): String = when (b.reason) {
    Reason.FOCUS -> "Everything on your block list is locked until the timer ends."
    Reason.SCHEDULE -> "\"${b.detail}\" is active right now."
    Reason.ALWAYS -> "You set $label to always blocked."
    Reason.DAILY_LIMIT -> "You've used all of today's time for $label."
    Reason.COOLDOWN -> "You finished a session. Wait before starting the next one."
    Reason.OPENS -> "You've opened $label as many times as you allowed today."
    Reason.SITE -> "${b.detail} is on your blocked sites."
}

@Composable
fun BlockScreen(
    pkg: String,
    site: String?,
    onHome: () -> Unit,
    onOpenApp: () -> Unit,
    onStart: (Int, String) -> Unit,
) {
    val ctx = LocalContext.current
    var nowMs by remember { mutableLongStateOf(Clock.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = Clock.now()
            delay(1000L)
        }
    }
    val label = remember(pkg) { Repo.rule(pkg)?.label ?: appLabel(ctx, pkg) }
    val decision: Decision = remember(nowMs, pkg, site) {
        if (site != null) Decision.Block(Reason.SITE, 0L, site) else Engine.decide(pkg)
    }
    val quote = remember { QUOTES[Logic.dayKey(Clock.now()).hashCode().mod(QUOTES.size)] }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (pkg.isNotEmpty()) AppIcon(pkg, size = 72.dp)
            Spacer(Modifier.height(20.dp))

            when (val d = decision) {
                is Decision.Block -> {
                    Text(
                        blockTitle(d),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        blockDetail(d, label),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (d.untilMs > 0L) {
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Back in ${fmtDuration(d.untilMs - nowMs)}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "“$quote”",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Take me home") }
                }

                is Decision.Gate -> {
                    GateContent(
                        label = label,
                        pkg = pkg,
                        maxMinutes = d.maxMinutes,
                        onStart = onStart,
                        onHome = onHome,
                    )
                }

                is Decision.Allow -> {
                    Text(
                        "Unlocked",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$label is available again.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onOpenApp, modifier = Modifier.fillMaxWidth()) { Text("Open $label") }
                    TextButton(onClick = onHome) { Text("Not now") }
                }
            }
        }
    }
}

@Composable
private fun GateContent(
    label: String,
    pkg: String,
    maxMinutes: Int,
    onStart: (Int, String) -> Unit,
    onHome: () -> Unit,
) {
    val options = remember(maxMinutes) {
        listOf(5, 10, 15, 20, 30, 45, 60).filter { it <= maxMinutes }.ifEmpty { listOf(maxMinutes) }
    }
    var minutes by remember(maxMinutes) { mutableIntStateOf(options.first()) }
    var why by remember(pkg) { mutableStateOf("") }
    var wait by remember(pkg) { mutableIntStateOf(Repo.config.gateDelaySec) }
    LaunchedEffect(pkg) {
        wait = Repo.config.gateDelaySec
        while (wait > 0) {
            delay(1000L)
            wait -= 1
        }
    }
    val ready = wait == 0 && why.trim().length >= 3

    Text(
        "Pause. Take a breath.",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Is $label worth your time right now?",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = why,
        onValueChange = { why = it.take(80) },
        label = { Text("What do you need it for?") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    Text("How long?", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { m ->
            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text("$m min") })
        }
    }
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = { onStart(minutes, why) },
        enabled = ready,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                wait > 0 -> "Wait ${wait}s…"
                why.trim().length < 3 -> "Say why first"
                else -> "Open for $minutes min"
            }
        )
    }
    TextButton(onClick = onHome) { Text("Not now") }
}
