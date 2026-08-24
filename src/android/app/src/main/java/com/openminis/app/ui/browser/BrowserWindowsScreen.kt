package com.openminis.app.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openminis.app.browser.BrowserWindow
import com.openminis.app.browser.BrowserWindowStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * [T-browser-windows] List of persistent smart-browser windows (user spec
 * §3). Each window keeps its own page, linked chat session and artifacts
 * until the user deletes it — across app restarts and reboots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserWindowsScreen(
    onBack: () -> Unit,
    onOpenWindow: (windowId: String) -> Unit,
) {
    val context = LocalContext.current
    val store = remember { BrowserWindowStore.get(context) }
    var windows by remember { mutableStateOf(store.load()) }
    var showAdd by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    fun refresh() { windows = store.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مرورگر هوشمند") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New window")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(horizontal = 16.dp)) {
            Text(
                "برای هر موضوع یک پنجره بساز؛ صفحه، چت و فایل‌هایش تا وقتی خودت حذف نکنی می‌مانند — حتی با بستن اپ یا ری‌استارت گوشی.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(windows, key = { it.id }) { w ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenWindow(w.id) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(w.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    w.url.ifBlank { "(صفحه‌ای باز نشده)" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                                Text(
                                    "${fmt.format(Date(w.updatedAt))} · ${w.artifacts.size} فایل",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                store.remove(w.id)
                                refresh()
                            }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("پنجره جدید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام پنجره (موضوع)") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("آدرس اولیه (اختیاری)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) {
                        val w = BrowserWindow(
                            id = store.newId(), name = n,
                            url = url.trim().let { if (it.isNotEmpty() && !it.startsWith("http")) "https://$it" else it },
                        )
                        store.upsert(w)
                        refresh()
                    }
                    showAdd = false
                }) { Text("ساخت") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("انصراف") } },
        )
    }
}
