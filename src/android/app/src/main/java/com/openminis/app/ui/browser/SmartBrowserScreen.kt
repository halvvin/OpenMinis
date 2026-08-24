package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.browser.BrowserChatMsg
import com.openminis.app.browser.BrowserTab
import com.openminis.app.browser.BrowserTabStore
import com.openminis.app.browser.ReverseApi
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.launch

/**
 * [T-browser-tabs] Smart browser with Chrome-like TABS + a per-tab AI chat
 * panel in a SPLIT view (page stays visible while chatting).
 *
 * - Tab strip: open as many tabs as you like, switch, close each one; tabs
 *   persist across restarts/reboots until the user closes them.
 * - AI panel: pick ANY configured model/API (same pool as the main chat),
 *   ask about the visible page — read, translate, summarize, extract links,
 *   find download URLs. Heavy agentic work (real downloads, form filling)
 *   is one tap away via «چت کامل ایجنت» which opens the main chat pipeline.
 * - §3-6 Reverse API Engineer toggle lives in the panel's tools row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserTabStore.get(context) }
    val autoPrefs = remember { AutomationPrefs.get(context) }
    val providerRepo = remember { ProviderRepository(context) }

    var tabs by remember { mutableStateOf(store.load()) }
    var activeId by remember { mutableStateOf(tabs.firstOrNull()?.id ?: "") }
    var showChatPanel by remember { mutableStateOf(true) }
    var reverseApiOn by remember { mutableStateOf(autoPrefs.reverseApiEnabled) }
    var raeStatus by remember { mutableStateOf("") }

    fun refresh() { tabs = store.load() }

    fun newTab(url: String = ""): String {
        val t = BrowserTab(id = store.newId(), url = url)
        store.upsert(t)
        refresh()
        activeId = t.id
        return t.id
    }

    fun closeTab(id: String) {
        store.remove(id)
        refresh()
        if (activeId == id) activeId = tabs.firstOrNull()?.id ?: ""
    }

    val active = tabs.firstOrNull { it.id == activeId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مرورگر هوشمند") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { if (active == null) newTab() else newTab() }) {
                        Icon(Icons.Filled.Add, contentDescription = "New tab")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── Tab strip (Chrome-style) ─────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { t ->
                    val selected = t.id == activeId
                    Row(
                        modifier = Modifier
                            .clickable { activeId = t.id }
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            t.title.take(16),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close tab",
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { closeTab(t.id) },
                        )
                    }
                }
            }

            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { newTab() }) { Text("➕ باز کردن اولین تب") }
                }
                return@Column
            }

            BrowserTabContent(
                tab = active,
                store = store,
                providerRepo = providerRepo,
                autoPrefs = autoPrefs,
                showChatPanel = showChatPanel,
                onToggleChat = { showChatPanel = !showChatPanel },
                reverseApiOn = reverseApiOn,
                onReverseApiToggle = { reverseApiOn = it; autoPrefs.reverseApiEnabled = it },
                raeStatus = raeStatus,
                onRaeStatus = { raeStatus = it },
                onTitleChanged = { t -> store.get(active.id)?.let { store.upsert(it.copy(title = t)) }; refresh() },
                onMessagesChanged = { refresh() },
            )
        }
    }
}

@Composable
private fun BrowserTabContent(
    tab: BrowserTab,
    store: BrowserTabStore,
    providerRepo: ProviderRepository,
    autoPrefs: AutomationPrefs,
    showChatPanel: Boolean,
    onToggleChat: () -> Unit,
    reverseApiOn: Boolean,
    onReverseApiToggle: (Boolean) -> Unit,
    raeStatus: String,
    onRaeStatus: (String) -> Unit,
    onTitleChanged: (String) -> Unit,
    onMessagesChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember(tab.id) { mutableStateOf(tab.url) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val modelEntries = remember { runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList()) }
    var selectedEntryId by remember(tab.id) {
        mutableStateOf(modelEntries.firstOrNull()?.id ?: "")
    }
    val selectedEntry = modelEntries.firstOrNull { it.id == selectedEntryId }

    fun pageText(cb: (String) -> Unit) {
        webViewRef?.evaluateJavascript(
            "(function(){return document.body ? document.body.innerText.slice(0,12000) : '';})()",
        ) { v -> cb(v?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"").orEmpty()) }
    }

    fun send(prompt: String) {
        val entry = selectedEntry
        if (entry == null) {
            store.appendMessage(tab.id, BrowserChatMsg("assistant", "❌ هنوز مدلی تنظیم نشده — ابتدا در تنظیمات → Providers یک API اضافه کن.", ""))
            return
        }
        store.appendMessage(tab.id, BrowserChatMsg("user", prompt))
        onMessagesChanged()
        busy = true
        scope.launch {
            try {
                pageText { page ->
                    scope.launch {
                        try {
                            val instance = providerRepo.instance(entry.providerInstanceId)
                            val apiKey = instance?.let { providerRepo.usableApiKey(it) }
                            if (instance == null || apiKey == null) {
                                store.appendMessage(tab.id, BrowserChatMsg("assistant", "❌ کلید API برای ${entry.model.displayName} در دسترس نیست.", ""))
                                busy = false
                                return@launch
                            }
                            val provider = com.openminis.app.provider.ProviderFactory.create(instance, apiKey, entry.model, context)
                            val sys = "تو دستیار وبِ داخل مرورگر هستی. متن صفحه‌ی فعلی که کاربر می‌بیند در ادامه آمده. برای ترجمه، خلاصه، استخراج لینک/فایل و پاسخ درباره صفحه استفاده‌اش کن. فارسی جواب بده مگر اینکه خلافش خواسته شود."
                            val userMsg = if (page.isNotBlank()) "$prompt\n\n[متن صفحه]:\n$page" else prompt
                            val resp = provider.sendMessage(
                                messages = listOf(LLMMessageText(userMsg)),
                                systemPrompt = sys,
                                maxTokens = 4096,
                            )
                            val text = extractReplyText(resp)
                            store.appendMessage(tab.id, BrowserChatMsg("assistant", text.ifBlank { "(پاسخ خالی)" }, entry.model.displayName))
                            onMessagesChanged()
                        } catch (e: Exception) {
                            store.appendMessage(tab.id, BrowserChatMsg("assistant", "❌ ${e.message ?: "خطا"}", ""))
                            onMessagesChanged()
                        } finally {
                            busy = false
                        }
                    }
                }
            } catch (e: Exception) {
                store.appendMessage(tab.id, BrowserChatMsg("assistant", "❌ ${e.message ?: "خطا"}", ""))
                onMessagesChanged()
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                placeholder = { Text("جستجو یا آدرس سایت…") },
            )
            IconButton(onClick = { webViewRef?.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
            IconButton(onClick = {
                var u = urlInput.trim()
                if (u.isNotEmpty() && !u.startsWith("http")) {
                    u = if (u.contains(".") && !u.contains(" ")) "https://$u"
                    else "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(u, "UTF-8")
                }
                if (u.isNotEmpty()) {
                    urlInput = u
                    webViewRef?.loadUrl(u)
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Go") }
        }

        // Split view: page on top, AI chat panel below
        BoxWithWeight(
            webWeight = if (showChatPanel) 0.55f else 1f,
            chatWeight = if (showChatPanel) 0.45f else 0f,
            webView = { wv ->
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    url?.let {
                                        urlInput = it
                                        // Re-read from store: never clobber chat messages
                                        // appended since this composable's `tab` snapshot.
                                        store.get(tab.id)?.let { cur -> store.upsert(cur.copy(url = it)) }
                                        onTitleChanged(view?.title ?: it)
                                    }
                                }
                            }
                            if (tab.url.isNotBlank()) loadUrl(tab.url)
                        }.also { webViewRef = it }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { webViewRef = null },
                )
            },
            chatPanel = {
                if (showChatPanel) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Panel header: toggle + model selector + quick tools
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("دستیار وب", style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable(onClick = onToggleChat))
                            Box {
                                TextButton(onClick = { modelMenu = true }) {
                                    Text(
                                        selectedEntry?.model?.displayName ?: "انتخاب مدل",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    modelEntries.forEach { e ->
                                        DropdownMenuItem(
                                            text = { Text(e.model.displayName, maxLines = 1) },
                                            onClick = { selectedEntryId = e.id; modelMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TextButton(onClick = { send("این صفحه را خلاصه کن.") }) { Text("📋 خلاصه") }
                            TextButton(onClick = { send("کل متن این صفحه را به فارسی روان ترجمه کن.") }) { Text("🌐 ترجمه") }
                            TextButton(onClick = { send("لینک‌های قابل دانلود این صفحه (عکس، ویدئو، PDF) را لیست کن.") }) { Text("⬇️ فایل‌ها") }
                            TextButton(onClick = {
                                scope.launch {
                                    onRaeStatus("در حال بررسی…")
                                    val s = runCatching { ReverseApi.checkInstalled() }.getOrNull()
                                    onRaeStatus(
                                        if (s?.installed == true) "✅ Reverse API نصب است"
                                        else "نصب نیست — از دکمه «نصب» استفاده کن"
                                    )
                                }
                            }) { Text("🔧 Reverse API") }
                        }
                        if (reverseApiOn && raeStatus.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(raeStatus, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 3)
                                TextButton(onClick = {
                                    scope.launch {
                                        onRaeStatus("در حال نصب (چند دقیقه)…")
                                        val s = runCatching { ReverseApi.install() }.getOrNull()
                                        onRaeStatus(if (s?.installed == true) "✅ نصب شد — در چت بگو: «APIهای این سایت را استخراج کن»" else "❌ نصب ناموفق: ${s?.detail?.take(200)}")
                                    }
                                }) { Text("نصب") }
                            }
                        }

                        // Messages
                        val msgs = remember(tab.updatedAt) { tab.messages }
                        LazyColumn(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(msgs) { m ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        (if (m.role == "user") "🧑 " else "🤖 ") + m.text,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(8.dp),
                                        maxLines = 14,
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("درباره این صفحه بپرس…") },
                            )
                            IconButton(
                                onClick = { val p = input.trim(); if (p.isNotEmpty()) { input = ""; send(p) } },
                                enabled = !busy && input.isNotBlank(),
                            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
                        }
                        if (busy) CircularProgressIndicator(modifier = Modifier.padding(6.dp).size(18.dp))
                    }
                }
            },
        )
    }
}

@Composable
private fun BoxWithWeight(
    webWeight: Float,
    chatWeight: Float,
    webView: @Composable ((androidx.compose.ui.Modifier) -> Unit),
    chatPanel: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth().weight(webWeight)) {
            webView(Modifier.fillMaxSize())
        }
        if (chatWeight > 0f) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxWidth().weight(chatWeight)) {
                chatPanel()
            }
        }
    }
}

/** Minimal single-text LLMMessage construction + reply extraction helpers. */
private fun LLMMessageText(text: String): com.openminis.app.data.model.LLMMessage =
    com.openminis.app.data.model.LLMMessage(
        role = com.openminis.app.data.model.LLMMessage.Role.USER,
        content = text,
    )

private fun extractReplyText(resp: com.openminis.app.data.model.LLMResponse): String =
    resp.text
