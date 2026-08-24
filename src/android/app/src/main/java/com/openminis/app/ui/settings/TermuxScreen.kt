package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.automation.TermuxBridge
import kotlinx.coroutines.launch

/**
 * [T-termux-bridge] «یکپارچه‌سازی Termux» — user spec §8-2, rev2.
 *
 * Includes a real mini-terminal: a scrollable console with the session
 * history plus an input line (keyboard-aware via imePadding) that executes
 * inside Termux through the official RUN_COMMAND intent. Output round-trips
 * through the shared exchange directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AutomationPrefs.get(context) }

    var enabled by remember { mutableStateOf(false) }
    var exchangeDir by remember { mutableStateOf("/sdcard/MinisFork") }
    var installed by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<String>() }
    var historyIdx by remember { mutableStateOf(-1) }
    // Auto-open the keyboard when entering the terminal.
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(loaded) { if (loaded) focusRequester.requestFocus() }

    LaunchedEffect(Unit) {
        enabled = prefs.termuxEnabled
        exchangeDir = prefs.loadTermux().exchangeDir
        installed = TermuxBridge.isTermuxInstalled(context)
        console = TermuxBridge.isTermuxInstalled(context)
            .let { if (it) "Termux پیدا شد ✓ — دستور بنویس و اجرا کن.\n" else "Termux نصب نیست — راهنمای زیر را انجام بده.\n" }
        loaded = true
    }

    fun log(line: String) {
        console = (console + line + "\n").takeLast(20_000)
    }

    fun run(cmd: String) {
        if (!enabled) {
            log("❌ اول کلید «فعال» را روشن کن.")
            return
        }
        log("\$ $cmd")
        testing = true
        scope.launch {
            val r = TermuxBridge.execute(context, cmd, exchangeDir, timeoutMs = 120_000L)
            log(r.output.ifBlank { "(بدون خروجی)" })
            testing = false
        }
    }

    fun runAndRemember(cmd: String) {
        if (cmd.isNotBlank()) { history.add(cmd); historyIdx = -1 }
        run(cmd)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("یکپارچه‌سازی Termux") },
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
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("فعال", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.termuxEnabled = it
                })
            }

            Text(
                if (installed) "✅ Termux روی گوشی نصب است" else "❌ Termux پیدا نشد",
                style = MaterialTheme.typography.bodyMedium,
                color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )

            // ── Mini terminal console ────────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF101418))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    Text(
                        console,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color(0xFF9FEF9F),
                    )
                }
            }

            // ── Terminal key row: history ↑/↓ + shell characters ────────
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("↑", "↓", "/", "~", "-", "|", ">", ">>", ".", "=", "\"", "'", "\$", "&&", "sudo ").forEach { k ->
                    TextButton(onClick = {
                        when (k) {
                            "↑" -> {
                                if (history.isNotEmpty()) {
                                    historyIdx = if (historyIdx == -1) history.size - 1 else (historyIdx - 1).coerceAtLeast(0)
                                    command = history[historyIdx]
                                }
                            }
                            "↓" -> {
                                if (history.isNotEmpty()) {
                                    historyIdx = (historyIdx + 1).coerceAtMost(history.size - 1)
                                    command = history[historyIdx]
                                }
                            }
                            "sudo " -> command += k
                            else -> command += k
                        }
                    }) { Text(k, style = MaterialTheme.typography.labelMedium) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = { Text("دستور Termux… مثلاً pkg install python") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                )
                IconButton(
                    onClick = { val c = command.trim(); if (c.isNotEmpty()) { command = ""; runAndRemember(c) } },
                    enabled = !testing && command.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "اجرا") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { run("echo __TERMUX_OK__ && uname -a") },
                    enabled = !testing && enabled,
                ) { Text("تست اتصال") }
                Button(
                    onClick = { run("termux-setup-storage") },
                    enabled = !testing && enabled,
                ) { Text("اجازه حافظه") }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("راهنمای راه‌اندازی (یک‌بار)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "۱. Termux را از F-Droid نصب کن (نسخه Play Store قدیمی است).\n" +
                        "۲. داخل Termux: pkg update && pkg install termux-api\n" +
                        "۳. داخل Termux: termux-setup-storage  (اجازه حافظه را تأیید کن)\n" +
                        "۴. در Termux: Settings → Allow external apps را روشن کن.\n" +
                        "۵. به این اپ اجازه «دسترسی به همه فایل‌ها» بده (تنظیمات → مجوزهای سیستمی).\n" +
                        "پوشه تبادل فایل بین دو اپ: $exchangeDir",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
