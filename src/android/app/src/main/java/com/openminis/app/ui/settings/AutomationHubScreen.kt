package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.openminis.app.automation.AutomationPrefs

/**
 * [T-automation-hub] «اتوماسیون و ایجنت‌ها» — user spec §8 hub.
 *
 * Three fully independent entries; each sub-feature only ever activates
 * through its own manual switch inside its own screen. The hub switches
 * here mirror the same prefs so state stays consistent from both places.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationHubScreen(
    onBack: () -> Unit,
    onAlwaysOnClick: () -> Unit,
    onTermuxClick: () -> Unit,
    onAgentManagerClick: () -> Unit,
    onCrossChatToggle: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { AutomationPrefs.get(context) }
    var alwaysOn by remember { mutableStateOf(false) }
    var termux by remember { mutableStateOf(false) }
    var crossChat by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        alwaysOn = prefs.alwaysOnEnabled
        termux = prefs.termuxEnabled
        crossChat = prefs.crossChatEnabled
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("اتوماسیون و ایجنت‌ها") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AutomationItem(
                title = "اجرای همیشگی",
                subtitle = "ادامه‌ی وظایف طولانی روی سرور خارجی (VPS / Railway / Render / Termux) — حتی با بسته شدن اپ",
                enabled = alwaysOn,
                onToggle = { alwaysOn = it; prefs.alwaysOnEnabled = it },
                onClick = onAlwaysOnClick,
            )
            AutomationItem(
                title = "یکپارچه‌سازی Termux",
                subtitle = "اجرای فرمان‌های هوش مصنوعی داخل اپ Termux گوشی و بازگشت نتیجه به چت",
                enabled = termux,
                onToggle = { termux = it; prefs.termuxEnabled = it },
                onClick = onTermuxClick,
            )
            AutomationItem(
                title = "ارتباط بین چت‌ها",
                subtitle = "اجازه بده چت فعلی چت‌های دیگر را ببیند، پیام بفرستد و بسازد (پیش‌فرض خاموش)",
                enabled = crossChat,
                onToggle = { crossChat = it; prefs.crossChatEnabled = it; onCrossChatToggle(it) },
                onClick = { val nv = !crossChat; crossChat = nv; prefs.crossChatEnabled = nv; onCrossChatToggle(nv) },
            )
            AutomationItem(
                title = "مدیریت ایجنت‌ها",
                subtitle = "نصب، به‌روزرسانی، حذف و اجرای هر ابزار خط‌فرمانی — کاملاً پویا و بدون لیست ثابت",
                enabled = true,
                toggleVisible = false,
                onToggle = {},
                onClick = onAgentManagerClick,
            )
        }
    }
}

@Composable
private fun AutomationItem(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    toggleVisible: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (toggleVisible) {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Modifier.androidClickableRemoved() = this
