package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    var enabled by remember { mutableStateOf(prefs.assistantEnabled) }

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
                .padding(16.dp),
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
                            "اجرای روی همه برنامه‌ها و پس‌زمینه گوشی",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it; toggleService(it) }
                    )
                }
            }
            Text(
                "نکته: در اولین فعال‌سازی، مجوز «نمایش روی سایر برنامه‌ها» درخواست می‌شود. " +
                        "پس از اعطای مجوز، دوباره سوییچ را فعال کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}