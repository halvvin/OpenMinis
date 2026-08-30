package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.derivedStateOf
import com.openminis.app.automation.AlwaysOnConfig
import com.openminis.app.automation.AlwaysOnEngine
import com.openminis.app.automation.AutomationPrefs
import kotlinx.coroutines.launch

/**
 * [T-always-on] «اجرای همیشگی» — user spec §8-1.
 *
 * Mirrors task state / project files to a USER-PROVIDED server so long
 * tasks survive app closure. Fully manual: nothing runs unless the master
 * switch is on AND the user presses test/sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlwaysOnScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AutomationPrefs.get(context) }

    var enabled by remember { mutableStateOf(false) }
    var serverType by remember { mutableStateOf("vps") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var projectDir by remember { mutableStateOf("/var/minis/workspace") }
    var testing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        enabled = prefs.alwaysOnEnabled
        val c = prefs.loadAlwaysOn()
        serverType = c.serverType
        host = c.host
        port = c.port.toString()
        username = c.username
        secret = c.secret
        log = prefs.readLog()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("اجرای همیشگی") },
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
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "سندباکس داخل اپ موقتی است — با بسته شدن اپ، کارها می‌میرند. با این گزینه وضعیت وظیفه و فایل‌های پروژه روی سرورِ خودت آپلود می‌شود تا بعد از هر قطعی یا ری‌استارت، کار از همان‌جا ادامه یابد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("فعال", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    prefs.alwaysOnEnabled = it
                    prefs.appendLog(if (it) "ALWAYS-ON فعال شد" else "ALWAYS-ON غیرفعال شد")
                })
            }

            Text("نوع سرور", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("vps" to "VPS", "railway" to "Railway", "render" to "Render", "termux" to "گوشی/Termux").forEach { (k, label) ->
                    FilterChip(selected = serverType == k, onClick = { serverType = k }, label = { Text(label) })
                }
            }

            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("آدرس سرور (IP یا دامنه)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter { ch -> ch.isDigit() } },
                    label = { Text("پورت") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("نام کاربری") }, modifier = Modifier.weight(2f), singleLine = true)
            }
            OutlinedTextField(
                value = secret, onValueChange = { secret = it },
                label = { Text("رمز / توکن") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = projectDir, onValueChange = { projectDir = it },
                label = { Text("مسیر پروژه در سندباکس (برای همگام‌سازی)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        testing = true
                        status = "در حال تست اتصال…"
                        scope.launch {
                            val cfg = AlwaysOnConfig(serverType, host, port.toIntOrNull() ?: 22, username, secret)
                            prefs.saveAlwaysOn(cfg)
                            val r = AlwaysOnEngine.testConnection(cfg)
                            status = if (r.ok) "✅ متصل\n${r.detail}" else "❌ قطع\n${r.detail}"
                            prefs.appendLog("test → ${if (r.ok) "OK" else "FAIL"}")
                            log = prefs.readLog()
                            testing = false
                        }
                    },
                    enabled = !testing && enabled,
                ) { Text("تست اتصال") }

                OutlinedButton(
                    onClick = {
                        syncing = true
                        status = "در حال همگام‌سازی…"
                        scope.launch {
                            val cfg = AlwaysOnConfig(serverType, host, port.toIntOrNull() ?: 22, username, secret)
                            prefs.saveAlwaysOn(cfg)
                            val r = AlwaysOnEngine.syncProject(cfg, projectDir)
                            status = if (r.ok) "✅ همگام شد\n${r.detail}" else "❌ ناموفق\n${r.detail}"
                            prefs.appendLog("sync → ${if (r.ok) "OK" else "FAIL"}")
                            log = prefs.readLog()
                            syncing = false
                        }
                    },
                    enabled = !syncing && enabled,
                ) { Text("همگام‌سازی پروژه") }
            }

            if (testing || syncing) CircularProgressIndicator(modifier = Modifier.padding(8.dp))

            if (status.isNotBlank()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Text("لاگ اجرا", style = MaterialTheme.typography.titleSmall)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    log.ifBlank { "(هنوز فعالیتی ثبت نشده)" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Text(
                "نکته: سرور باید ssh در دسترس داشته باشد و sshpass روی آن لازم نیست — کلاینت داخل سندباکس نصب می‌شود. برای Railway/Render از آدرس و پورت عمومی SSH سرویس‌شان استفاده کن.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
