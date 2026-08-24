package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.browser.BrowserWindow
import com.openminis.app.browser.BrowserWindowStore
import com.openminis.app.browser.ReverseApi
import kotlinx.coroutines.launch

/**
 * [T-browser-windows] A single persistent smart-browser window (user spec
 * §3): embedded WebView + its own linked chat session (full agent/model
 * access via the normal chat pipeline) + persistent artifacts. The window
 * and everything in it survive restarts; only the user deletes them.
 *
 * The §3-6 Reverse API Engineer panel lives here (browser-scoped), gated
 * by its own default-OFF toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserWindowScreen(
    windowId: String,
    onBack: () -> Unit,
    onOpenChat: (sessionId: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserWindowStore.get(context) }
    val autoPrefs = remember { AutomationPrefs.get(context) }

    var window by remember { mutableStateOf(store.get(windowId)) }
    var urlInput by remember { mutableStateOf(window?.url.orEmpty()) }
    var reverseApiOn by remember { mutableStateOf(autoPrefs.reverseApiEnabled) }
    var raeStatus by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val w = window ?: return

    fun persistUrl(u: String) {
        window = w.copy(url = u).also { store.upsert(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            // URL bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("آدرس سایت…") },
                )
                IconButton(onClick = {
                    var u = urlInput.trim()
                    if (u.isNotEmpty() && !u.startsWith("http")) u = "https://$u"
                    if (u.isNotEmpty()) {
                        urlInput = u
                        persistUrl(u)
                        webView?.loadUrl(u)
                    }
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Go") }
            }

            // WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                url?.let { urlInput = it; persistUrl(it) }
                            }
                        }
                        if (w.url.isNotBlank()) loadUrl(w.url)
                    }.also { webView = it }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // Window actions
            Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val sid = w.chatSessionId.ifBlank { java.util.UUID.randomUUID().toString() }
                                val target = if (sid.startsWith("__new__")) sid else sid
                                store.upsert(w.copy(chatSessionId = sid))
                                onOpenChat(target)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("💬 چت این پنجره (ایجنت + مدل)") }

                    // ── §3-6 Reverse API Engineer (browser-scoped, default OFF) ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("مهندسی معکوس وب به API", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "ضبط ترافیک سایت (HAR) و تولید کلاینت API با مدل خودت",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = reverseApiOn, onCheckedChange = {
                            reverseApiOn = it
                            autoPrefs.reverseApiEnabled = it
                        })
                    }
                    if (reverseApiOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = {
                                scope.launch {
                                    raeStatus = "در حال بررسی…"
                                    val s = ReverseApi.checkInstalled()
                                    raeStatus = if (s.installed) "✅ ابزار نصب است — در چت این پنجره بگو: «APIهای این سایت را استخراج کن»" else "نصب نیست"
                                }
                            }) { Text("بررسی") }
                            TextButton(onClick = {
                                scope.launch {
                                    raeStatus = "در حال نصب (چند دقیقه)…"
                                    val s = ReverseApi.install()
                                    raeStatus = if (s.installed) "✅ نصب شد\n${s.detail.take(300)}" else "❌\n${s.detail.take(500)}"
                                }
                            }) { Text("نصب خودکار") }
                        }
                        if (raeStatus.isNotBlank()) {
                            Text(raeStatus, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "خروجی‌ها (HAR / کلاینت‌ها) در همین پنجره نگه داشته می‌شوند تا خودت حذفشان کنی.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
