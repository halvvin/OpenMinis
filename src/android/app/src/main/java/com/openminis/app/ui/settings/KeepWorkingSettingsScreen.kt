package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.data.KeepWorkingConfig
import com.openminis.app.data.KeepWorkingPrefs

/**
 * [T-keep-working-engine] Settings → Keep Working screen (v2).
 *
 * - Interval is value + unit (ثانیه/دقیقه/ساعت/روز) with NO upper cap —
 *   the user may set multi-hour / multi-day gaps freely.
 * - Optional chat filter: when enabled, auto-continue only fires inside
 *   chats whose title matches a user-listed name (any number of chats).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepWorkingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { KeepWorkingPrefs.get(context) }

    var enabled by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var intervalValue by remember { mutableStateOf("30") }
    var intervalUnit by remember { mutableStateOf("ثانیه") }
    var attemptsText by remember { mutableStateOf("5") }
    var chatFilterEnabled by remember { mutableStateOf(false) }
    var targetChats by remember { mutableStateOf(emptySet<String>()) }
    var newChatName by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val c = prefs.load()
        enabled = c.enabled
        command = c.command
        // Decompose stored seconds into value + largest fitting unit.
        val (v, u) = decomposeInterval(c.intervalSeconds)
        intervalValue = v
        intervalUnit = u
        attemptsText = c.maxAttempts.toString()
        chatFilterEnabled = c.chatFilterEnabled
        targetChats = c.targetChats
        loaded = true
    }

    fun unitToSeconds(): Long {
        val v = intervalValue.toLongOrNull() ?: return 30L
        val factor = when (intervalUnit) {
            "دقیقه" -> 60L
            "ساعت" -> 3600L
            "روز" -> 86_400L
            else -> 1L
        }
        return (v * factor).coerceIn(5L, 31_536_000L)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("موتور ادامه خودکار") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold

        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("فعال", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "اگر اینترنت قطع شود، سقف مصرف پر شود یا خطایی پیش بیاید، وظیفه به‌طور خودکار ادامه می‌یابد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text("متن دستور ادامه") },
                supportingText = { Text("هر بار برای ادامه‌ی کار به مدل ارسال می‌شود") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            // ── Interval: value + unit, no artificial cap ─────────────────
            Text("فاصله بین تلاش‌ها", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = intervalValue,
                    onValueChange = { intervalValue = it.filter { ch -> ch.isDigit() } },
                    label = { Text("مقدار") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Row(modifier = Modifier.weight(1.4f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("ثانیه", "دقیقه", "ساعت", "روز").forEach { u ->
                        FilterChip(
                            selected = intervalUnit == u,
                            onClick = { intervalUnit = u },
                            label = { Text(u, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = attemptsText,
                onValueChange = { attemptsText = it.filter { ch -> ch.isDigit() } },
                label = { Text("حداکثر تعداد تلاش") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ── Chat-name filter ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("محدود به چت‌های انتخابی", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "اگر روشن باشد، ادامه‌ی خودکار فقط در چت‌هایی که اسمشان را در لیست پایین اضافه کرده‌ای انجام می‌شود (هر چند تا که بخواهی). اگر خاموش باشد در همه‌ی چت‌ها فعال است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = chatFilterEnabled, onCheckedChange = { chatFilterEnabled = it })
            }

            if (chatFilterEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newChatName,
                        onValueChange = { newChatName = it },
                        label = { Text("اسم چت") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    IconButton(onClick = {
                        val n = newChatName.trim()
                        if (n.isNotEmpty()) {
                            targetChats = targetChats + n
                            newChatName = ""
                        }
                    }) { Icon(Icons.Filled.Add, contentDescription = "Add") }
                }
                targetChats.sorted().forEach { name ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        AssistChip(
                            onClick = {},
                            label = { Text(name) },
                        )
                        IconButton(onClick = { targetChats = targetChats - name }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                }
                if (targetChats.isEmpty()) {
                    Text(
                        "هنوز چتی اضافه نکرده‌ای — اسم چت‌ها باید دقیقاً با عنوانی که در لیست چت‌ها می‌بینی یکی باشد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Button(
                onClick = {
                    prefs.save(
                        KeepWorkingConfig(
                            enabled = enabled,
                            command = command.ifBlank { KeepWorkingConfig().command },
                            intervalSeconds = unitToSeconds(),
                            maxAttempts = attemptsText.toIntOrNull()?.coerceIn(1, 100) ?: 5,
                            chatFilterEnabled = chatFilterEnabled,
                            targetChats = targetChats,
                        )
                    )
                    onBack()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) { Text("ذخیره") }
        }
    }
}

/** Decompose seconds into (value string, unit label) using the largest fitting unit. */
private fun decomposeInterval(seconds: Long): Pair<String, String> = when {
    seconds % 86_400L == 0L && seconds >= 86_400L -> (seconds / 86_400L).toString() to "روز"
    seconds % 3600L == 0L && seconds >= 3600L -> (seconds / 3600L).toString() to "ساعت"
    seconds % 60L == 0L && seconds >= 60L -> (seconds / 60L).toString() to "دقیقه"
    else -> seconds.toString() to "ثانیه"
}
