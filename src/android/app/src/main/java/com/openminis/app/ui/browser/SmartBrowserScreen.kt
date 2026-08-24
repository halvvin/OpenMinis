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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * [T-browser-tabs-v2] Smart browser, Chrome-style — rewritten for
 * robustness after on-device feedback:
 *
 *  - Tab strip is ALWAYS visible; a tab is auto-created on first entry so
 *    the user never lands on an empty "+" screen (the v1 stuck-screen bug).
 *  - URL/search bar always visible; plain words go to DuckDuckGo search.
 *  - Split view: page on top, AI panel below — page stays visible while
 *    chatting. Panel collapses via its header.
 *  - Model picker lists the SAME configured models/APIs as the main chat.
 *  - Every store mutation is write-through + state-list update; no stale
 *    snapshots can clobber chat history.
 *  - All inputs sit above the keyboard (imePadding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserTabStore.get(context) }
    val autoPrefs = remember { AutomationPrefs.get(context) }

    // Single source of truth — a snapshot list backed by disk on every change.
    val tabs = remember { mutableStateListOf<BrowserTab>().apply { addAll(store.load()) } }
    var activeId by remember { mutableStateOf(tabs.firstOrNull()?.id.orEmpty()) }
    var chatVisible by remember { mutableStateOf(true) }
    var reverseApiOn by remember { mutableStateOf(autoPrefs.reverseApiEnabled) }
    var raeStatus by remember { mutableStateOf("") }

    // v1 bug fix: never show an empty screen — auto-open a tab.
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val t = BrowserTab(id = store.newId())
            store.upsert(t)
            tabs.add(t)
            activeId = t.id
        }
        if (activeId.isEmpty() || tabs.none { it.id == activeId }) {
            activeId = tabs.first().id
        }
    }

    fun persist(tab: BrowserTab) {
        store.upsert(tab)
        val i = tabs.indexOfFirst { it.id == tab.id }
        if (i >= 0) tabs[i] = store.get(tab.id) ?: tab
    }

    fun newTab() {
        val t = BrowserTab(id = store.newId())
        store.upsert(t)
        tabs.add(t)
        activeId = t.id
    }

    fun closeTab(id: String) {
        store.remove(id)
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) tabs.removeAt(i)
        if (activeId == id) activeId = tabs.firstOrNull()?.id.orEmpty()
        if (tabs.isEmpty()) newTab()
    }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

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
                    IconButton(onClick = { newTab() }) {
                        Icon(Icons.Filled.Add, contentDescription = "تب جدید")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // ── Chrome-style tab strip (always visible) ─────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                tabs.forEach { t ->
                    val selected = t.id == active.id
                    Row(
                        modifier = Modifier
                            .clickable { activeId = t.id }
                            .background(
                                if (selected) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            t.title.take(14),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "بستن تب",
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { closeTab(t.id) },
                        )
                    }
                }
                // Trailing + inside the strip too (mirrors Chrome).
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "تب جدید",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { newTab() }
                        .padding(2.dp),
                )
            }

            BrowserTabContent(
                tab = active,
                store = store,
                providerRepo = remember { ProviderRepository(context) },
                autoPrefs = autoPrefs,
                chatVisible = chatVisible,
                onToggleChat = { chatVisible = !chatVisible },
                reverseApiOn = reverseApiOn,
                onReverseApiToggle = { reverseApiOn = it; autoPrefs.reverseApiEnabled = it },
                raeStatus = raeStatus,
                onRaeStatus = { raeStatus = it },
                onTabChanged = { persist(it) },
                onMessagesChanged = {
                    val i = tabs.indexOfFirst { it.id == active.id }
                    if (i >= 0) tabs[i] = store.get(active.id) ?: tabs[i]
                },
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
    chatVisible: Boolean,
    onToggleChat: () -> Unit,
    reverseApiOn: Boolean,
    onReverseApiToggle: (Boolean) -> Unit,
    raeStatus: String,
    onRaeStatus: (String) -> Unit,
    onTabChanged: (BrowserTab) -> Unit,
    onMessagesChanged: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlInput by remember(tab.id) { mutableStateOf(tab.url) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember(tab.id) { mutableStateOf(tab.title) }

    val modelEntries = remember {
        runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
    }
    var selectedEntryId by remember(tab.id) {
        mutableStateOf(modelEntries.firstOrNull()?.id.orEmpty())
    }
    val selectedEntry = modelEntries.firstOrNull { it.id == selectedEntryId }

    fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty()) return u
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return if (u.contains(".") && !u.contains(" ")) "https://$u"
        else "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(u, "UTF-8")
    }

    fun send(prompt: String) {
        val entry = selectedEntry
        if (entry == null) {
            store.appendMessage(
                tab.id,
                BrowserChatMsg("assistant", "❌ هنوز مدلی انتخاب نشده. از منوی «انتخاب مدل» بالای پنل یک مدل/API انتخاب کن. اگر لیست خالی است، اول در تنظیمات → Providers یک API اضافه کن."),
            )
            onMessagesChanged()
            return
        }
        store.appendMessage(tab.id, BrowserChatMsg("user", prompt))
        onMessagesChanged()
        busy = true

        fun finish(reply: String, model: String) {
            store.appendMessage(tab.id, BrowserChatMsg("assistant", reply, model))
            onMessagesChanged()
            busy = false
        }

        // Ask for page text with a guaranteed callback (fallback: no page).
        val wv = webViewRef
        if (wv == null) {
            scope.launch { finish(callModel(providerRepo, entry, prompt, ""), entry.model.displayName) }
            return
        }
        var answered = false
        wv.evaluateJavascript(
            "(function(){try{return (document.body?document.body.innerText:'').slice(0,10000);}catch(e){return '';}})()",
        ) { v ->
            answered = true
            val page = v?.trim('"')
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\\\", "\\")
                .orEmpty()
            scope.launch {
                finish(callModel(providerRepo, entry, prompt, page), entry.model.displayName)
            }
        }
        // Safety net: if the JS callback never fires, proceed without page.
        scope.launch {
            kotlinx.coroutines.delay(4000)
            if (!answered && busy) finish(callModel(providerRepo, entry, prompt, ""), entry.model.displayName)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── URL / search bar (always visible) ───────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("جستجو یا آدرس سایت…") },
                textStyle = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "بارگذاری مجدد")
            }
            IconButton(onClick = {
                val u = normalizeUrl(urlInput)
                if (u.isNotEmpty()) {
                    urlInput = u
                    webViewRef?.loadUrl(u)
                }
            }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "برو") }
        }

        // ── Page (top) ──────────────────────────────────────────────────
        Box(modifier = Modifier.fillMaxWidth().weight(if (chatVisible) 0.5f else 1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                url?.let {
                                    urlInput = it
                                    onTabChanged(tab.copy(url = it))
                                }
                                view?.title?.let {
                                    pageTitle = it
                                    onTabChanged(tab.copy(title = it))
                                }
                            }
                        }
                        if (tab.url.isNotBlank()) loadUrl(tab.url)
                    }.also { webViewRef = it }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { webViewRef = null },
            )
        }

        // ── AI panel (bottom, collapsible) ─────────────────────────────
        if (chatVisible) {
            Box(modifier = Modifier.fillMaxWidth().weight(0.5f)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "دستیار وب ▾",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable(onClick = onToggleChat),
                        )
                        Box {
                            TextButton(onClick = { modelMenu = true }) {
                                Text(
                                    selectedEntry?.model?.displayName ?: "انتخاب مدل",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                if (modelEntries.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("(لیست خالی — اول یک API تنظیم کن)") },
                                        onClick = { modelMenu = false },
                                    )
                                }
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
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        TextButton(onClick = { send("این صفحه را خلاصه کن.") }) { Text("خلاصه", maxLines = 1) }
                        TextButton(onClick = { send("کل متن این صفحه را به فارسی روان ترجمه کن.") }) { Text("ترجمه", maxLines = 1) }
                        TextButton(onClick = { send("لینک‌های قابل دانلود این صفحه (عکس، ویدئو، PDF، فایل) را با آدرس کامل لیست کن.") }) { Text("فایل‌ها", maxLines = 1) }
                        TextButton(onClick = { send("فرم‌ها و فیلدهای قابل پر شدن این صفحه را لیست کن و بگو هر کدام چه چیزی باید بنویسم.") }) { Text("فرم‌ها", maxLines = 1) }
                        TextButton(onClick = {
                            scope.launch {
                                onRaeStatus("در حال بررسی…")
                                val s = runCatching { ReverseApi.checkInstalled() }.getOrDefault(ReverseApi.Status(false, "خطا"))
                                onRaeStatus(if (s.installed) "✅ Reverse API نصب است — بگو: «APIهای این سایت را استخراج کن»" else "نصب نیست — دکمه «نصب» را بزن")
                            }
                        }) { Text("Reverse API", maxLines = 1) }
                    }
                    if (reverseApiOn && raeStatus.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                raeStatus,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 3,
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    onRaeStatus("در حال نصب (چند دقیقه)…")
                                    val s = runCatching { ReverseApi.install() }.getOrDefault(ReverseApi.Status(false, "خطا"))
                                    onRaeStatus(if (s.installed) "✅ نصب شد — در چت بگو: «APIهای این سایت را استخراج کن»" else "❌ نصب ناموفق: ${s.detail.take(150)}")
                                }
                            }) { Text("نصب") }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(tab.messages) { m ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    (if (m.role == "user") "🧑 " else "🤖 ") + m.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("درباره این صفحه بپرس…") },
                            textStyle = MaterialTheme.typography.bodySmall,
                            singleLine = true,
                        )
                        IconButton(
                            onClick = { val p = input.trim(); if (p.isNotEmpty()) { input = ""; send(p) } },
                            enabled = !busy && input.isNotBlank(),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال") }
                    }
                    if (busy) {
                        Row(modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/** One-shot LLM call with optional page context. Fail-soft by design. */
private suspend fun callModel(
    repo: ProviderRepository,
    entry: com.openminis.app.data.model.ModelEntry,
    prompt: String,
    pageText: String,
): String = runCatching {
    val instance = repo.instance(entry.providerInstanceId)
    val apiKey = instance?.let { repo.usableApiKey(it) }
    if (instance == null || apiKey == null) return "❌ کلید API برای «${entry.model.displayName}» در دسترس نیست — تنظیمات → Providers را چک کن."
    val provider = com.openminis.app.provider.ProviderFactory.create(instance, apiKey, entry.model, null)
    val sys = "تو دستیار وبِ داخل مرورگر هستی. متن صفحه‌ی فعلی که کاربر می‌بیند ضمیمه شده است؛ برای ترجمه، خلاصه‌سازی، استخراج لینک و فایل و پاسخ درباره‌ی صفحه از آن استفاده کن. فارسی جواب بده مگر خلافش خواسته شود."
    val user = if (pageText.isNotBlank()) "$prompt\n\n[متن صفحه]:\n$pageText" else prompt
    val resp = provider.sendMessage(
        messages = listOf(
            com.openminis.app.data.model.LLMMessage(
                role = com.openminis.app.data.model.LLMMessage.Role.USER,
                content = user,
            )
        ),
        systemPrompt = sys,
        maxTokens = 4096,
    )
    resp.text.ifBlank { "(پاسخ خالی از مدل)" }
}.getOrElse { "❌ ${it.message ?: "خطای ناشناخته"}" }
