package com.openminis.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.browser.BrowserChatMsg
import com.openminis.app.browser.BrowserTab
import com.openminis.app.browser.BrowserTabStore
import com.openminis.app.browser.ReverseApi
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [T-browser-v4] Smart browser — full rebuild per spec §1:
 *
 * Layout (top→bottom): Tab strip card → Omnibox card (+progress) →
 * Extensions bar card → web content → floating draggable AI panel.
 * Everything framed in themed cards; real page loading with progress,
 * error page + retry, HAR request capture (for Reverse API), pooled
 * WebViews (max 8 live tabs), long-press tab menu, per-tab persistent AI
 * chat history.
 */
private const val MAX_ACTIVE_TABS = 8

/** One captured network request (Reverse API extension). */
data class HarEntry(val method: String, val url: String, val at: Long)

/** Extension definitions — small panels, never full screens. */
private data class BrowserExtension(
    val id: String,
    val icon: String,
    val label: String,
    val prompt: String,
)

private val EXTENSIONS = listOf(
    BrowserExtension("translate", "🌐", "ترجمه", "کل متن این صفحه را به فارسی روان ترجمه کن. اگر کاربر زبان دیگری خواست، به همان زبان ترجمه کن."),
    BrowserExtension("read", "📖", "خواندن", "متن اصلی این صفحه را تمیز و مرتب استخراج کن و یک خلاصه ۵ خطی هم در انتها بگذار."),
    BrowserExtension("download", "⬇️", "دانلود", "همه فایل‌های قابل دانلود این صفحه (عکس، ویدئو، صدا، PDF، فایل) را با آدرس مستقیم و نوع‌شان لیست کن."),
    BrowserExtension("surf", "🧭", "گشت هوشمند", "بر اساس محتوای این صفحه، ۸ لینک مرتبط و ارزشمند برای ادامه گشت پیشنهاد بده — با عنوان کوتاه و دلیل."),
    BrowserExtension("data", "📊", "جمع‌آوری داده", "داده‌های ساخت‌یافت این صفحه (جدول‌ها، لیست‌ها، قیمت‌ها، نام‌ها) را استخراج کن و در قالب Markdown جدولی مرتب تحویل بده."),
    BrowserExtension("search", "🔍", "جستجوی سایت", "یک جستجوی هوشمند در محتوای همین صفحه انجام بده؛ کلیدواژه‌ها و بخش‌های مرتبط را با نقل‌قول نشان بده."),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BrowserTabStore.get(context) }
    val autoPrefs = remember { AutomationPrefs.get(context) }
    val appContext = remember { context.applicationContext }
    val providerRepo = remember {
        (context.applicationContext as com.openminis.app.MinisApp).providerRepository
    }

    val tabs = remember { mutableStateListOf<BrowserTab>().apply { addAll(store.load()) } }
    var activeId by remember { mutableStateOf(tabs.firstOrNull()?.id.orEmpty()) }
    var reverseApiOn by remember { mutableStateOf(autoPrefs.reverseApiEnabled) }
    var aiPanelOpen by remember { mutableStateOf(autoPrefs.floatPanelOpen) }

    // Per-tab in-memory state
    val webViewPool = remember { LinkedHashMap<String, WebView>() }
    val harLog = remember { mutableMapOf<String, MutableList<HarEntry>>() }
    var progress by remember { mutableStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    // Model pool (reactive — repo loads async)
    val modelEntries = remember { mutableStateOf<List<com.openminis.app.data.model.ModelEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        providerRepo.configLoaded.first { it }
        modelEntries.value = runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
    }
    var selectedEntryId by remember { mutableStateOf(modelEntries.value.firstOrNull()?.id.orEmpty()) }

    // Extensions UI
    var openExtension by remember { mutableStateOf<String?>(null) }
    var extResult by remember { mutableStateOf("") }
    var extBusy by remember { mutableStateOf(false) }
    var extMenuFor by remember { mutableStateOf<String?>(null) } // long-press tab menu

    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val t = BrowserTab(id = store.newId())
            store.upsert(t)
            tabs.add(t)
        }
        if (activeId.isEmpty() || tabs.none { it.id == activeId }) activeId = tabs.first().id
    }

    fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        store.get(tabId)?.let { cur ->
            val next = transform(cur)
            store.upsert(next)
            val i = tabs.indexOfFirst { it.id == tabId }
            if (i >= 0) tabs[i] = next
        }
    }

    fun newTab() {
        val t = BrowserTab(id = store.newId())
        store.upsert(t)
        tabs.add(t)
        activeId = t.id
        pageError = null
        progress = 0
    }

    fun closeTab(id: String) {
        store.remove(id)
        webViewPool.remove(id)?.destroy()
        harLog.remove(id)
        val i = tabs.indexOfFirst { it.id == id }
        if (i >= 0) tabs.removeAt(i)
        if (activeId == id) activeId = tabs.firstOrNull()?.id.orEmpty()
        if (tabs.isEmpty()) newTab()
    }

    /** Enforce the live-tab cap: evict the oldest INACTIVE WebView. */
    fun enforceTabBudget(activeTabId: String) {
        while (webViewPool.size > MAX_ACTIVE_TABS) {
            val evict = webViewPool.keys.firstOrNull { it != activeTabId } ?: break
            webViewPool.remove(evict)?.destroy()
        }
    }

    val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

    fun runExtension(ext: BrowserExtension, tab: BrowserTab, webViewRef: WebView?) {
        val entry = modelEntries.value.firstOrNull { it.id == selectedEntryId }
        if (entry == null) {
            extResult = "❌ اول از منوی «انتخاب مدل» یک مدل/API انتخاب کن."
            return
        }
        extBusy = true
        extResult = "در حال اجرا…"
        val wv = webViewRef
        if (wv == null) {
            extBusy = false
            extResult = "❌ صفحه‌ای باز نیست."
            return
        }
        var answered = false
        wv.evaluateJavascript(
            "(function(){try{return (document.body?document.body.innerText:'').slice(0,10000);}catch(e){return '';}})()",
        ) { v ->
            answered = true
            val page = v?.trim('"')?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\").orEmpty()
            scope.launch {
                extResult = runCatching { callModel(providerRepo, entry, ext.prompt, page) }
                    .getOrDefault("❌ خطا در اجرا")
                extBusy = false
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(4000)
            if (!answered && extBusy) {
                extResult = runCatching { callModel(providerRepo, entry, ext.prompt, "") }.getOrDefault("❌ خطا")
                extBusy = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مرورگر هوشمند") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (active == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Box
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .imePadding(),
            ) {
                // ── 1) Tab strip card ───────────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        tabs.forEach { t ->
                            val selected = t.id == active.id
                            Box {
                                Row(
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { activeId = t.id; pageError = null },
                                            onLongClick = { extMenuFor = t.id },
                                        )
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text("🔒", style = MaterialTheme.typography.labelSmall)
                                    Text(t.title.take(14), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "بستن تب",
                                        modifier = Modifier.size(14.dp).clickable { closeTab(t.id) },
                                    )
                                }
                                // Long-press tab menu (close / refresh / copy link)
                                DropdownMenu(
                                    expanded = extMenuFor == t.id,
                                    onDismissRequest = { extMenuFor = null },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("بستن تب") },
                                        onClick = { extMenuFor = null; closeTab(t.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("بارگذاری مجدد") },
                                        onClick = {
                                            extMenuFor = null
                                            if (t.id == activeId) webViewPool[t.id]?.reload()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("کپی لینک") },
                                        onClick = {
                                            extMenuFor = null
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", t.url))
                                        },
                                    )
                                }
                            }
                        }
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "تب جدید",
                            modifier = Modifier.size(22.dp).clickable { newTab() }.padding(2.dp),
                        )
                    }
                }

                // ── 2) Omnibox card ─────────────────────────────────────
                var urlInput by remember(active.id) { mutableStateOf(active.url) }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(0.dp),
                        ) {
                            IconButton(
                                onClick = { webViewPool[active.id]?.goBack() },
                                enabled = canGoBack,
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "عقب") }
                            IconButton(
                                onClick = { webViewPool[active.id]?.goForward() },
                                enabled = canGoForward,
                            ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "جلو") }
                            OutlinedTextField(
                                value = urlInput,
                                onValueChange = { urlInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("جستجو یا آدرس…", style = MaterialTheme.typography.bodySmall) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                shape = RoundedCornerShape(20.dp),
                            )
                            IconButton(onClick = { webViewPool[active.id]?.reload() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "بارگذاری مجدد")
                            }
                            IconButton(onClick = {
                                // Bookmark = save to tab title as ★ prefix (lightweight).
                                updateTab(active.id) { cur -> if (cur.title.startsWith("★")) cur else cur.copy(title = "★" + cur.title) }
                            }) { Icon(Icons.Filled.Star, contentDescription = "نشان‌گذاری") }
                        }
                        if (progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { progress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            )
                        }
                    }
                }

                // ── 3) Extensions bar card ──────────────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text("انتخاب مدل:", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(2.dp))
                        Box {
                            var modelMenu by remember { mutableStateOf(false) }
                            Text(
                                modelEntries.value.firstOrNull { it.id == selectedEntryId }?.model?.displayName ?: "انتخاب مدل",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                modifier = Modifier
                                    .clickable { modelMenu = true }
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                if (modelEntries.value.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("(لیست خالی — اول یک API تنظیم کن)") },
                                        onClick = { modelMenu = false },
                                    )
                                }
                                modelEntries.value.forEach { e ->
                                    DropdownMenuItem(
                                        text = { Text(e.model.displayName, maxLines = 1) },
                                        onClick = { selectedEntryId = e.id; modelMenu = false },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        EXTENSIONS.forEach { ext ->
                            val isActive = openExtension == ext.id
                            Text(
                                ext.icon,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clickable { openExtension = if (isActive) null else ext.id }
                                    .background(
                                        if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        CircleShape,
                                    )
                                    .padding(6.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (reverseApiOn) {
                            Text(
                                "🔧",
                                modifier = Modifier
                                    .size(34.dp)
                                    .clickable { openExtension = if (openExtension == "reverse") null else "reverse" }
                                    .background(
                                        if (openExtension == "reverse") MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        CircleShape,
                                    )
                                    .padding(6.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        // [T-float-summon] Persistent entry point for the AI panel —
                        // collapsing it can never orphan the user.
                        Text(
                            "🤖",
                            modifier = Modifier
                                .size(34.dp)
                                .clickable { autoPrefs.floatPanelOpen = true }
                                .padding(6.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }

                // ── 4) Web content ──────────────────────────────────────
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    // [T-webview-always] The WebView is composed UNCONDITIONALLY —
                    // v4 skipped it for empty-URL tabs, so webViewRef was null and
                    // the Go button did literally nothing on a new tab.
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            enforceTabBudget(active.id)
                            webViewPool.getOrPut(active.id) {
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                }
                            }.apply {
                                webViewClient = object : WebViewClient() {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): WebResourceResponse? {
                                        // [T-har-capture] Record main-frame + XHR
                                        // traffic for the Reverse API extension.
                                        request?.let { req ->
                                            val list = harLog.getOrPut(active.id) { mutableListOf() }
                                            if (list.size < 500) {
                                                list.add(HarEntry(req.method ?: "GET", req.url.toString(), System.currentTimeMillis()))
                                            }
                                        }
                                        return null
                                    }

                                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                        url?.let {
                                            urlInput = it
                                            updateTab(active.id) { cur -> cur.copy(url = it, title = view?.title ?: cur.title) }
                                        }
                                        canGoBack = view?.canGoBack() ?: false
                                        canGoForward = view?.canGoForward() ?: false
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        req: WebResourceRequest?,
                                        err: android.webkit.WebResourceError?,
                                    ) {
                                        if (req?.isForMainFrame == true) {
                                            pageError = err?.description?.toString() ?: "خطای شبکه"
                                        }
                                    }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                // [T-blank-page-fix] A pooled/recreated WebView's
                                // getUrl() is "about:blank" — NOT blank — so the
                                // old isNullOrBlank() check never fired.
                                val cur = url
                                if ((cur.isNullOrBlank() || cur == "about:blank") && active.url.isNotBlank()) {
                                    loadUrl(active.url)
                                }
                            }
                        },
                        onRelease = { /* pooled */ },
                    )

                    if (pageError != null) {
                        // Friendly error page with retry (overlay, non-blocking).
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("📡", style = MaterialTheme.typography.displayMedium)
                            Text("صفحه باز نشد", style = MaterialTheme.typography.titleMedium)
                            Text(
                                pageError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = {
                                pageError = null
                                webViewPool[active.id]?.reload()
                            }) { Text("تلاش دوباره") }
                        }
                    }
                    // ── 5) Floating AI panel (draggable, collapsible) ────
                    FloatingAiPanel(
                        open = aiPanelOpen,
                        onOpenChange = { aiPanelOpen = it; autoPrefs.floatPanelOpen = it },
                        tab = active,
                        store = store,
                        providerRepo = providerRepo,
                        modelTitle = modelEntries.value.firstOrNull { it.id == selectedEntryId }?.model?.displayName ?: "انتخاب مدل",
                        hasModel = selectedEntryId.isNotEmpty(),
                        onMessagesChanged = {
                            val i = tabs.indexOfFirst { it.id == active.id }
                            if (i >= 0) tabs[i] = store.get(active.id) ?: tabs[i]
                        },
                        getWebView = { webViewPool[active.id] },
                    )
                }

                // ── Extension result panel (small, above keyboard) ──────
                if (openExtension != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val ext = EXTENSIONS.firstOrNull { it.id == openExtension }
                                Text(
                                    if (openExtension == "reverse") "🔧 Reverse API" else "${ext?.icon} ${ext?.label}",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "بستن",
                                    modifier = Modifier.size(18.dp).clickable { openExtension = null },
                                )
                            }
                            if (openExtension == "reverse") {
                                ReverseApiPanel(
                                    har = harLog[active.id].orEmpty(),
                                    providerRepo = providerRepo,
                                    selectedEntryId = selectedEntryId,
                                )
                            } else {
                                val ext = EXTENSIONS.first { it.id == openExtension }
                                TextButton(onClick = { runExtension(ext, active, webViewPool[active.id]) }, enabled = !extBusy) {
                                    Text("اجرا روی این صفحه")
                                }
                                if (extBusy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                if (extResult.isNotBlank()) {
                                    Text(
                                        extResult,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp)
                                            .verticalScroll(rememberScrollState()),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun tab_url(tab: BrowserTab): String = tab.url

/** Draggable, collapsible floating AI chat panel (spec §1-5). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.FloatingAiPanel(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    tab: BrowserTab,
    store: BrowserTabStore,
    providerRepo: ProviderRepository,
    modelTitle: String,
    hasModel: Boolean,
    onMessagesChanged: () -> Unit,
    getWebView: () -> WebView?,
) {
    val context = LocalContext.current
    val autoPrefs = remember { AutomationPrefs.get(context) }
    var offsetX by remember { mutableStateOf(autoPrefs.floatPanelX) }
    var offsetY by remember { mutableStateOf(autoPrefs.floatPanelY) }
    var panelW by remember { mutableStateOf(autoPrefs.floatPanelW) }
    var panelH by remember { mutableStateOf(autoPrefs.floatPanelH) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun saveGeom() {
        autoPrefs.floatPanelX = offsetX
        autoPrefs.floatPanelY = offsetY
        autoPrefs.floatPanelW = panelW
        autoPrefs.floatPanelH = panelH
    }

    if (!open) {
        // Collapsed bubble — tap to open, drag to move.
        // NOTE: no fillMaxSize wrapper — it would swallow every WebView touch.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                    .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                    .combinedClickable(
                        onClick = { onOpenChange(true) },
                        onLongClick = { onOpenChange(true) },
                    )
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { saveGeom() },
                        ) { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("🤖", style = MaterialTheme.typography.titleLarge)
        }
        return
    }

    Card(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .width(panelW.dp)
            .height(panelH.dp)
            .imePadding(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragEnd = { saveGeom() },
                        ) { change, drag ->
                            change.consume()
                            offsetX += drag.x
                            offsetY += drag.y
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("🤖 $modelTitle ⠿", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Row {
                    Text("－", modifier = Modifier.clickable {
                        panelW = (panelW - 40).coerceAtLeast(220)
                        panelH = (panelH - 40).coerceAtLeast(240)
                        saveGeom()
                    }, style = MaterialTheme.typography.titleMedium)
                    Text("＋", modifier = Modifier.clickable {
                        panelW = (panelW + 40).coerceAtMost(520)
                        panelH = (panelH + 40).coerceAtMost(760)
                        saveGeom()
                    }, style = MaterialTheme.typography.titleMedium)
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "جمع کردن",
                        modifier = Modifier.size(18.dp).clickable { onOpenChange(false) },
                    )
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
                            modifier = Modifier.padding(6.dp),
                            maxLines = 10,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("بپرس…", style = MaterialTheme.typography.labelSmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                )
                IconButton(
                    onClick = {
                        val p = input.trim()
                        if (p.isEmpty() || busy) return@IconButton
                        input = ""
                        store.appendMessage(tab.id, BrowserChatMsg("user", p))
                        onMessagesChanged()
                        busy = true
                        scopeLaunch {
                            val entries = runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
                            val entry = entries.firstOrNull { it.id == entryIdFor(modelTitle, providerRepo) }
                                ?: entries.firstOrNull()
                            val reply = if (entry == null) "❌ مدلی تنظیم نشده — تنظیمات → Providers."
                            else {
                                val wv = getWebView()
                                var page = ""
                                if (wv != null) {
                                    wv.evaluateJavascript(
                                        "(function(){try{return (document.body?document.body.innerText:'').slice(0,8000);}catch(e){return '';}})()",
                                    ) { v -> page = v?.trim('"')?.replace("\\n", "\n").orEmpty() }
                                    kotlinx.coroutines.delay(1500)
                                }
                                runCatching { callModel(providerRepo, entry, p, page) }.getOrDefault("❌ خطا")
                            }
                            store.appendMessage(tab.id, BrowserChatMsg("assistant", reply, modelTitle))
                            onMessagesChanged()
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "ارسال") }
            }
        }
    }
}

// Small helper to keep the panel code readable.
private fun entryIdFor(modelTitle: String, repo: ProviderRepository): String =
    runCatching { repo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
        .firstOrNull { it.model.displayName == modelTitle }?.id.orEmpty()

private fun scopeLaunch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch(block = block)
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

/** Reverse API extension panel: HAR stats + client generation. */
@Composable
private fun ReverseApiPanel(
    har: List<HarEntry>,
    providerRepo: ProviderRepository,
    selectedEntryId: String,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "درخواست‌های ضبط‌شده از این صفحه: ${har.size}" + if (har.isNotEmpty()) "\nنمونه: " + har.take(3).joinToString("\n") { "${it.method} ${it.url.take(80)}" } else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = {
                scope.launch {
                    busy = true; status = "در حال بررسی…"
                    val s = runCatching { ReverseApi.checkInstalled() }.getOrDefault(ReverseApi.Status(false, "خطا"))
                    status = if (s.installed) "✅ نصب است" else "نصب نیست"
                    busy = false
                }
            }) { Text("بررسی نصب") }
            TextButton(onClick = {
                scope.launch {
                    busy = true; status = "در حال نصب (چند دقیقه)…"
                    val s = runCatching { ReverseApi.install() }.getOrDefault(ReverseApi.Status(false, "خطا"))
                    status = if (s.installed) "✅ نصب شد" else "❌ ${s.detail.take(120)}"
                    busy = false
                }
            }) { Text("نصب") }
            TextButton(onClick = {
                if (har.isEmpty()) { result = "اول صفحه را باز کن تا ترافیک ضبط شود."; return@TextButton }
                busy = true
                scope.launch {
                    val entry = runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
                        .firstOrNull { it.id == selectedEntryId }
                    result = if (entry == null) "❌ مدل انتخاب نشده."
                    else runCatching {
                        val harText = har.take(60).joinToString("\n") { "${it.method} ${it.url}" }
                        callModel(providerRepo, entry, "از این ترافیک شبکه‌ی ضبط‌شده، یک کلاینت API تمیز و تایپ‌شده به زبان Python تولید کن. endpointها، پارامترها و هدرهای لازم را استخراج کن:\n$harText", "")
                    }.getOrDefault("❌ خطا")
                    busy = false
                }
            }) { Text("ساخت کلاینت API") }
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall)
        if (result.isNotBlank()) {
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().height(140.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}
