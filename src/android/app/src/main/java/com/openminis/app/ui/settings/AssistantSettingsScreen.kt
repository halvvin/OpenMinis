package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.ui.floating.FloatingAssistantService

/**
 * [T-floating-system] Settings screen for the system-wide floating assistant.
 * Checks SYSTEM_ALERT_WINDOW overlay permission, starts/stops the
 * Foreground Service, and persists the toggle.
 *
 * [F-A1] Adds the Execution-mode selector (AUTO / PLANNING / ACCEPT) that
 * writes [AutomationPrefs.executionMode] — previously this pref was read by
 * the assistant VM but had NO writer, so the mode was stuck on AUTO.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    var enabled by remember { mutableStateOf(prefs.assistantEnabled) }
    var executionMode by remember { mutableStateOf(prefs.executionMode) }

    fun toggleService(enable: Boolean) {
        val intent = Intent(context, FloatingAssistantService::class.java)
        if (enable) {
            // Check overlay permission first (Android 6+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                val req = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(req)
                // Will persist after permission granted on next visit
                prefs.assistantEnabled = false
                enabled = false
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            context.stopService(intent)
        }
        prefs.assistantEnabled = enable
        enabled = enable
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("دستیار شناور هوشمند") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("فعال‌سازی دستیار شناور سراسری",
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            "اجرای روی همه برنامه‌ها و پس‌زمینه گوشی — چت با ابزار واقعی: شل، فایل، مرورگر و سنسورهای گوشی",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { toggleService(it) }
                    )
                }
            }

            // ── [F-A1] Execution mode selector ──
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("حالت اجرا", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "تعیین می‌کند دستیار چقدر مستقل ابزارها را اجرا کند",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    val modes = listOf(
                        0 to "خودکار",
                        1 to "برنامه‌ریزی",
                        2 to "تایید هر عمل",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, label) in modes) {
                            FilterChip(
                                selected = executionMode == mode,
                                onClick = {
                                    executionMode = mode
                                    prefs.executionMode = mode
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (executionMode) {
                            1 -> "دستیار اول برنامه کار را ارائه می‌دهد و پس از تایید شما اجرا می‌کند."
                            2 -> "قبل از هر بار استفاده از ابزار، از شما اجازه می‌گیرد."
                            else -> "دستیار اختیار کامل دارد و کار را تا پایان انجام می‌دهد."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "نکته: در اولین فعال‌سازی، مجوز «نمایش روی سایر برنامه‌ها» درخواست می‌شود. " +
                        "پس از اعطای مجوز، دوباره سوییچ را فعال کنید. دستیار بعد از ری‌استارت گوشی " +
                        "در صورت روشن بودن این سوییچ، خودبه‌خود بالا می‌آید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // [B7] Android force-stop puts the app in stopped-state: the OS
            // will NOT deliver BOOT_COMPLETED or restart the service until the
            // user opens the app once. Tell them instead of leaving the
            // toggle looking broken after "App info → Force stop".
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "اگر از «توقف اجباری» (Force stop) در تنظیمات اندروید استفاده کرده باشید، " +
                            "اندروید تا باز کردن بعدیِ اپ هیچ سرویسی — از جمله دستیار شناور — را " +
                            "اجرا نمی‌کند. کافی است یک‌بار اپ را باز کنید تا دستیار دوباره بالا بیاید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
