package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs

/**
 * [T-floating-assistant] Settings screen for the Smart Assistant.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    var enabled by remember { mutableStateOf(prefs.assistantEnabled) }
    LaunchedEffect(enabled) { prefs.assistantEnabled = enabled }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("دستیار شناور هوشمند") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("فعال بودن دستیار شناور", style = MaterialTheme.typography.bodyLarge)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            // [T-execution-modes] Mode selector (AUTO / PLANNING / ACCEPT).
            var mode by remember { mutableStateOf(prefs.executionMode) }
            Text("حالت اجرا", style = MaterialTheme.typography.bodyLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "خودکار", 1 to "برنامه‌ریزی", 2 to "تأیید مرحله‌ای").forEach { (m, label) ->
                    TextButton(
                        onClick = { mode = m; prefs.executionMode = m },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (mode == m) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text(
                when (mode) {
                    1 -> "دستیار اول برنامه می‌دهد، تأیید شما را می‌گیرد، بعد اجرا می‌کند."
                    2 -> "قبل از هر ابزار، از شما تأیید می‌گیرد."
                    else -> "دستیار آزادانه و بدون تأیید مرحله‌به‌مرحله کارها را انجام می‌دهد."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "دستیار شناور یک پنجره شناور روی همه صفحه‌های اپ است که می‌توانید با هر مدل/API گفتگو کنید و (در نسخه‌های بعدی) کارهای مختلفی روی گوشی انجام دهید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("نحوه استفاده", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "1. یک حباب 🤖 روی صفحه ظاهر می‌شود\n" +
                        "2. آن را بکشید و به هر جایی ببرید\n" +
                        "3. روی حباب تپ کنید → پنجره باز می‌شود\n" +
                        "4. از بالا API و مدل را انتخاب کنید\n" +
                        "5. در پایین پیام بنویسید و ارسال کنید\n" +
                        "6. با ＋/－ اندازه پنجره را تغییر دهید\n" +
                        "7. با ✕ ببندید — حباب می‌ماند",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}