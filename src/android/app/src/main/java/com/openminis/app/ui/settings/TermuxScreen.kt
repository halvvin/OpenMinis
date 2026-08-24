package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.automation.TermuxBridge
import kotlinx.coroutines.launch

/**
 * [T-termux-bridge] «یکپارچه‌سازی Termux» — user spec §8-2.
 *
 * When enabled, the AI can run commands inside the external Termux app via
 * the official RUN_COMMAND intent, with output returned to chat through a
 * shared exchange directory. Includes the setup guide, connection test and
 * storage-permission notes.
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
    var result by remember { mutableStateOf("") }
    var manualCmd by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enabled = prefs.termuxEnabled
        exchangeDir = prefs.loadTermux().exchangeDir
        installed = TermuxBridge.isTermuxInstalled(context)
        loaded = true
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("راهنمای راه‌اندازی", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "۱. Termux را از F-Droid نصب کن (نسخه Play Store قدیمی است).\n" +
                        "۲. داخل Termux بزن: pkg update && pkg install termux-api\n" +
                        "۳. داخل Termux بزن: termux-setup-storage  (اجازه حافظه را تأیید کن)\n" +
                        "۴. در Termux: Settings → Allow external apps را روشن کن.\n" +
                        "۵. به این اپ اجازه «دسترسی به همه فایل‌ها» بده (تنظیمات → مجوزهای سیستمی).\n" +
                        "۶. تست اتصال را بزن.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedTextField(
                value = exchangeDir,
                onValueChange = { exchangeDir = it },
                label = { Text("پوشه تبادل فایل") },
                supportingText = { Text("خروجی دستورهای Termux اینجا نوشته و خوانده می‌شود") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Button(
                onClick = {
                    testing = true
                    result = "در حال تست…"
                    scope.launch {
                        prefs.saveTermux(prefs.loadTermux().copy(exchangeDir = exchangeDir))
                        val r = TermuxBridge.test(context, exchangeDir)
                        result = if (r.ok) "✅ متصل\n${r.output}" else "❌ ${r.output}"
                        testing = false
                    }
                },
                enabled = !testing && enabled,
            ) { Text("تست اتصال") }

            if (testing) CircularProgressIndicator(modifier = Modifier.padding(8.dp))

            OutlinedTextField(
                value = manualCmd,
                onValueChange = { manualCmd = it },
                label = { Text("اجرای دستوری در Termux (امتحانی)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            Button(
                onClick = {
                    testing = true
                    result = "در حال اجرا…"
                    scope.launch {
                        val r = TermuxBridge.execute(context, manualCmd, exchangeDir)
                        result = (if (r.ok) "✅\n" else "❌\n") + r.output
                        testing = false
                    }
                },
                enabled = !testing && enabled && manualCmd.isNotBlank(),
            ) { Text("اجرا در Termux") }

            if (result.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }

            Text(
                "انتقال فایل: فایل‌ها را در پوشه تبادل بگذار — هم این اپ و هم Termux به آن دسترسی دارند. مثال: cp /sdcard/MinisFork/proj.zip ~ && unzip proj.zip",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
