package com.focusguard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not: warnings fall back to toasts */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { FocusGuardTheme { AppRoot() } }
    }

    override fun onStop() {
        super.onStop()
        Repo.flush()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val version by Repo.version.collectAsState()

    // The store is plain Kotlin (not Compose state), so screens refresh from this stamp:
    // it changes on every config change and every 3 seconds (live usage numbers, permission status).
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000L)
            tick += 1
        }
    }
    val stamp = version + tick
    val perms = remember(stamp) { Perms.status(ctx) }

    val tabs = remember {
        listOf(
            Tab("Today", Icons.Filled.Home),
            Tab("Apps", Icons.Filled.Menu),
            Tab("Focus", Icons.Filled.Lock),
            Tab("Rules", Icons.Filled.DateRange),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                0 -> TodayScreen(stamp, perms)
                1 -> AppsScreen(stamp)
                2 -> FocusScreen(stamp)
                else -> RulesScreen(stamp)
            }
        }
    }
}
