package com.focusguard.app

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

/** Well-known social / entertainment apps, shown first in the app picker. */
val SOCIAL_PACKAGES = setOf(
    "com.instagram.android", "com.instagram.barcelona", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
    "com.facebook.katana", "com.facebook.lite", "com.facebook.orca", "com.twitter.android",
    "com.google.android.youtube", "com.snapchat.android", "com.reddit.frontpage", "com.whatsapp",
    "org.telegram.messenger", "com.netflix.mediaclient", "com.pinterest", "com.discord",
    "com.linkedin.android", "tv.twitch.android.app",
)

val QUOTES = listOf(
    "You can have anything you want, but not everything you want.",
    "Where attention goes, life grows.",
    "The scroll will still be there. Your goals won't wait.",
    "Boredom is the doorway to your best ideas.",
    "Do the hard thing first; the feed can have the leftovers.",
    "Your future self is watching what you do right now.",
    "Small, boring, repeated focus beats occasional heroics.",
)

fun appLabel(ctx: Context, pkg: String): String = try {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) {
    pkg
}

fun fmtDuration(ms: Long): String {
    val s = (ms / 1000L).coerceAtLeast(0L)
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return when {
        h > 0L -> "${h}h ${m.toString().padStart(2, '0')}m"
        m > 0L -> "${m}m ${sec.toString().padStart(2, '0')}s"
        else -> "${sec}s"
    }
}

fun fmtMin(min: Int): String = when {
    min <= 0 -> "Off"
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

fun fmtTime(minuteOfDay: Int): String =
    "${(minuteOfDay / 60).toString().padStart(2, '0')}:${(minuteOfDay % 60).toString().padStart(2, '0')}"

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun daysLabel(days: Int): String = when (days) {
    Logic.ALL_DAYS -> "Every day"
    Logic.WEEKDAYS -> "Mon–Fri"
    96 -> "Sat–Sun"
    else -> DAY_NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }.joinToString(", ")
}

@Composable
fun AppIcon(pkg: String, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        try {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    val shape = RoundedCornerShape(10.dp)
    if (bmp != null) {
        Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size).clip(shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer))
    }
}

@Composable
fun FgCard(
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * Slider that snaps to [step] and commits only when the finger lifts
 * (so strict-mode refusals and saves happen once, not on every pixel of a drag).
 *
 * [min] clamps the lower bound. Under strict mode callers set [min] to the current
 * saved value so the slider can only move toward stricter (lower for limits) values.
 */
@Composable
fun StepSlider(
    title: String,
    value: Int,
    max: Int,
    step: Int,
    display: (Int) -> String,
    enabled: Boolean = true,
    min: Int = 0,
    onCommit: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    fun snapped(): Int = ((local / step).roundToInt() * step).coerceIn(min, max)
    val range = min.toFloat()..max.toFloat()
    val steps = if (max > min && step > 0) ((max - min) / step - 1).coerceAtLeast(0) else 0
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(display(snapped()), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it.coerceIn(min.toFloat(), max.toFloat()) },
            onValueChangeFinished = { onCommit(snapped()) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
        )
    }
}
