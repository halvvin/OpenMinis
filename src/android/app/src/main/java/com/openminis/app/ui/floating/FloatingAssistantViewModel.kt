package com.openminis.app.ui.floating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openminis.app.MinisApp
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.tools.ArchiveTool
import com.openminis.app.tools.AutomationTools
import com.openminis.app.tools.CrossChatTools
import com.openminis.app.tools.DownloadTool
import com.openminis.app.tools.FileEditTool
import com.openminis.app.tools.FileOpsTool
import com.openminis.app.tools.FileReadTool
import com.openminis.app.tools.FileSearchTool
import com.openminis.app.tools.FileWriteTool
import com.openminis.app.tools.MemoryTools
import com.openminis.app.tools.OcrTool
import com.openminis.app.tools.PdfTool
import com.openminis.app.tools.ReadImageTool
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.TranslateTool
import com.openminis.app.tools.WebExtractTool
import com.openminis.app.tools.WebSearchTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [F-A1] Floating assistant ViewModel — REAL agent loop.
 *
 * Replaces the v1 "direct sendMessage, no tools" implementation. This VM now
 * runs the same tool loop shape as the main chat agent loop:
 *
 *   send() → provider.streamMessage(history, systemPrompt, tools)
 *          → LLMStreamChunk.ToolCallComplete → execute via the shared Tool
 *            objects (shell / files / browser / device / memory / cross-chat
 *            / automation) → AgentContentPart.ToolResult appended to history
 *          → next model turn, until the model replies with no tool calls.
 *
 * Execution modes ([AutomationPrefs.executionMode]):
 *   0 = AUTO     — tools run immediately.
 *   1 = PLANNING — system prompt instructs plan-then-confirm (model-driven).
 *   2 = ACCEPT   — hard gate: every tool call waits for the user's approval
 *                  via [approvePendingTool] (CompletableDeferred bridge).
 *
 * [FA-BUG-01] The VM is created through a [ViewModelProvider] backed by the
 * service's own ViewModelStore (see FloatingAssistantService), so Compose
 * recomposition can never recreate it and drop the conversation.
 */
class FloatingAssistantViewModel(
    private val appContext: Context,
) : ViewModel() {

    // ── Lazy subsystem accessors (MinisApp may still be initializing when
    //    the service boots; re-resolve on every use instead of capturing null).
    private fun providerRepo(): ProviderRepository? =
        (appContext as? MinisApp)?.providerRepositoryOrNull
    private fun chatRepo(): ChatRepository? =
        (appContext as? MinisApp)?.chatRepositoryOrNull
    private fun memoryRepo(): MemoryRepository? =
        (appContext as? MinisApp)?.takeIf { it.subsystemsInitialized }?.memoryRepository

    private val prefs: AutomationPrefs = AutomationPrefs.get(appContext)

    // ── UI state ─────────────────────────────────────────────────────────
    data class AssistantMessage(
        val role: String,          // "user" | "assistant" | "tool"
        val text: String,
        val isError: Boolean = false,
    )

    val messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val busy = MutableStateFlow(false)
    val modelEntries = MutableStateFlow<List<com.openminis.app.data.model.ModelEntry>>(emptyList())

    private val _selectedEntryId = MutableStateFlow(prefs.assistantModelEntryId)
    val selectedEntryId: StateFlow<String> = _selectedEntryId.asStateFlow()
    private val _selectedEntry = MutableStateFlow<com.openminis.app.data.model.ModelEntry?>(null)
    val selectedEntry: StateFlow<com.openminis.app.data.model.ModelEntry?> = _selectedEntry.asStateFlow()

    /** Human-readable label of what the agent is doing right now (or null). */
    val currentAction = MutableStateFlow<String?>(null)

    /** Tool title awaiting the user's approval in ACCEPT mode (or null). */
    val pendingApproval = MutableStateFlow<String?>(null)

    // ── Loop state ───────────────────────────────────────────────────────
    private val history = mutableListOf<LLMMessage>()
    private var loopJob: Job? = null
    private var approvalDeferred: CompletableDeferred<Boolean>? = null
    private val browserPool by lazy { BrowserTabPool(appContext) }

    init {
        loadPersistedHistory()
        viewModelScope.launch {
            // [FA-BUG-01 companion] MinisApp may finish initializing AFTER the
            // service created this VM — poll until the repository exists, then
            // subscribe to config reloads for the VM's lifetime.
            var r = providerRepo()
            while (r == null && isActive) {
                delay(500)
                r = providerRepo()
            }
            if (r != null) {
                r.configLoaded.collect { loaded -> if (loaded) loadModels() }
            }
        }
    }

    // ── Model list ───────────────────────────────────────────────────────
    fun loadModels() {
        val repo = providerRepo() ?: return
        val loop = runCatching { repo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
        val all = runCatching {
            val cfg = repo.config.value
            val enabled = cfg.instances.filter { it.isEnabled }.map { it.id }.toSet()
            cfg.modelEntries.filter { it.providerInstanceId in enabled && !it.isHidden }
        }.getOrDefault(emptyList())
        val list = if (loop.isNotEmpty()) loop else all
        modelEntries.value = list
        val sel = _selectedEntryId.value
        _selectedEntryId.value = if (list.any { it.id == sel }) sel else list.firstOrNull()?.id.orEmpty()
        _selectedEntry.value = list.firstOrNull { it.id == _selectedEntryId.value }
    }

    fun selectModel(entryId: String) {
        _selectedEntryId.value = entryId
        prefs.assistantModelEntryId = entryId
        _selectedEntry.value = modelEntries.value.firstOrNull { it.id == entryId }
    }

    // ── Send / approval / stop ───────────────────────────────────────────
    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        // ACCEPT mode: a plain user message while an approval is pending is
        // treated as the approval decision (بله/ادامه/ok → allow; else deny).
        if (approvalDeferred != null) {
            val allow = APPROVE_WORDS.any { t.lowercase().contains(it) }
            approvalDeferred?.complete(allow)
            approvalDeferred = null
            append(AssistantMessage("user", t))
            return
        }
        if (busy.value) return

        messages.value = messages.value + AssistantMessage("user", t)
        history.add(LLMMessage(LLMMessage.Role.USER, content = t))
        persistHistory()

        val entry = selectedEntry.value
        if (entry == null) {
            appendError("❌ مدلی انتخاب نشده — از منوی بالا یک مدل/API انتخاب کن.")
            return
        }
        busy.value = true
        // [fix] NetworkOnMainThreadException: viewModelScope defaults to
        // Dispatchers.Main, and streamMessage's collect performs real network
        // I/O (Provider HTTP). The main chat's send path escalates to IO
        // upstream — this VM must do the same for the whole loop. StateFlow
        // writes below are thread-safe, and persistHistory() benefits from
        // being off the main thread too.
        loopJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runAgentLoop(entry)
            } catch (e: CancellationException) {
                append(AssistantMessage("assistant", "⏹ متوقف شد."))
            } catch (e: Exception) {
                appendError("❌ ${e.message ?: "خطای ناشناخته"}")
            } finally {
                busy.value = false
                currentAction.value = null
                pendingApproval.value = null
            }
        }
    }

    /** ACCEPT mode resolution from the inline buttons. */
    fun approvePendingTool(allow: Boolean) {
        approvalDeferred?.complete(allow)
        approvalDeferred = null
        pendingApproval.value = null
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        approvalDeferred?.complete(false)
        approvalDeferred = null
        busy.value = false
        currentAction.value = null
        pendingApproval.value = null
    }

    fun clear() {
        stop()
        messages.value = emptyList()
        history.clear()
        runCatching { historyFile().delete() }
    }

    // ── The agent loop ───────────────────────────────────────────────────
    private suspend fun runAgentLoop(entry: com.openminis.app.data.model.ModelEntry) {
        val repo = providerRepo()
        if (repo == null) {
            appendError("❌ سرویس‌های مدل هنوز آماده نیستند — کمی بعد دوباره تلاش کن.")
            return
        }
        val instance = repo.instance(entry.providerInstanceId)
        val apiKey = instance?.let { repo.usableApiKey(it) }
        if (instance == null || apiKey == null) {
            appendError("❌ کلید API برای «${entry.model.displayName}» در دسترس نیست — تنظیمات → Providers را چک کن.")
            return
        }
        val provider = ProviderFactory.create(instance, apiKey, entry.model, appContext)
        val tools = buildTools()
        val sys = buildSystemPrompt()

        for (turn in 0 until MAX_LOOP_TURNS) {
            currentAction.value = "فکر می‌کند…"

            val turnText = StringBuilder()
            val calls = mutableListOf<Triple<String, String, JSONObject>>()
            val signatures = mutableMapOf<String, String?>()

            try {
                provider.streamMessage(
                    messages = history.toList(),
                    systemPrompt = sys,
                    maxTokens = MAX_TOKENS,
                    tools = tools,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> turnText.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete -> {
                            signatures[chunk.id] = chunk.thoughtSignature
                            calls.add(Triple(chunk.id, chunk.name, chunk.args))
                            currentAction.value = "🔧 ${toolLabel(chunk.name, chunk.args)}"
                        }
                        else -> {}
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LLMError.TransientError) {
                appendError("⏳ خطای موقت سرور: ${e.message}")
                return
            } catch (e: Exception) {
                appendError("❌ ${e.message ?: e.javaClass.simpleName}")
                return
            }

            val text = turnText.toString()
            if (calls.isEmpty()) {
                if (text.isNotBlank()) append(AssistantMessage("assistant", text))
                currentAction.value = null
                persistHistory()
                return
            }
            if (text.isNotBlank()) append(AssistantMessage("assistant", text))

            // Record the assistant turn (text + tool_use parts) in history.
            val assistantParts = mutableListOf<AgentContentPart>()
            if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))
            for ((cId, cName, cArgs) in calls) {
                assistantParts.add(AgentContentPart.ToolUse(cId, cName, cArgs, thoughtSignature = signatures[cId]))
            }
            history.add(LLMMessage(LLMMessage.Role.ASSISTANT, content = text, contentParts = assistantParts))

            // Execute the tool calls (ACCEPT mode gates each one).
            val resultParts = mutableListOf<AgentContentPart>()
            for ((cId, cName, cArgs) in calls) {
                if (prefs.executionMode == 2) {
                    currentAction.value = null
                    pendingApproval.value = toolLabel(cName, cArgs)
                    val gate = CompletableDeferred<Boolean>()
                    approvalDeferred = gate
                    val allowed = gate.await()
                    if (!allowed) {
                        append(AssistantMessage("tool", "⛔ «${toolLabel(cName, cArgs)}» توسط کاربر رد شد."))
                        resultParts.add(AgentContentPart.ToolResult(
                            id = cId, name = cName,
                            content = "User DENIED this tool call. Do not retry it; ask the user how to proceed instead.",
                            isError = true,
                        ))
                        continue
                    }
                }
                currentAction.value = "🔧 ${toolLabel(cName, cArgs)}"
                val res = withContext(Dispatchers.IO) { executeTool(cName, cArgs.toString()) }
                append(AssistantMessage(
                    "tool",
                    (if (res.success) "✅ " else "❌ ") + toolLabel(cName, cArgs) +
                        " — " + res.output.lines().firstOrNull { it.isNotBlank() }?.take(160).orEmpty(),
                    isError = !res.success,
                ))
                resultParts.add(AgentContentPart.ToolResult(
                    id = cId,
                    name = cName,
                    content = res.output,
                    isError = !res.success,
                    imageData = res.imageData,
                    imageMimeType = res.imageMimeType,
                    imageLinuxPath = res.imageLinuxPath,
                ))
            }
            history.add(LLMMessage(LLMMessage.Role.USER, content = "", contentParts = resultParts))
            persistHistory()
        }
        append(AssistantMessage("assistant", "⚠️ به سقف $MAX_LOOP_TURNS مرحله رسیدم — برای ادامه پیام بفرست."))
    }

    // ── Tools ────────────────────────────────────────────────────────────
    private fun buildTools(): List<com.openminis.app.data.model.AgentToolDefinition> {
        val supportsImage = _selectedEntry.value?.model?.inputModalities
            ?.map { m -> m.lowercase() }?.contains("image") == true
        return com.openminis.app.tools.AgentTools.makeAgentTools(
            supportsImageInput = supportsImage,
            visionGroupConfigured = runCatching {
                val repo = providerRepo()
                repo != null && com.openminis.app.tools.VisionGroupResolver.isConfigured(repo, appContext)
            }.getOrDefault(false),
            memoryEnabled = true,
            termuxEnabled = prefs.termuxEnabled,
            alwaysOnEnabled = prefs.alwaysOnEnabled,
            crossChatMode = prefs.crossChatMode,
        )
    }

    private suspend fun executeTool(name: String, argsJson: String): ToolExecutionResult = try {
        val sessionId = FLOATING_SESSION_ID
        when (name) {
            "shell_execute" -> executeShell(argsJson)
            FileReadTool.NAME -> FileReadTool.execute(argsJson, sessionId, appContext)
            FileWriteTool.NAME -> FileWriteTool.execute(argsJson, sessionId, appContext)
            FileEditTool.NAME -> FileEditTool.execute(argsJson, sessionId, appContext)
            DownloadTool.NAME -> DownloadTool.execute(argsJson, appContext)
            FileOpsTool.NAME -> FileOpsTool.execute(argsJson, FLOATING_SESSION_ID, appContext)
            PdfTool.NAME -> PdfTool.execute(argsJson, appContext)
            TranslateTool.NAME -> {
                val repo = providerRepo()
                if (repo == null) ToolExecutionResult("Error: providers not ready", false)
                else TranslateTool.execute(argsJson, repo, _selectedEntryId.value.orEmpty(), appContext)
            }
            WebExtractTool.NAME -> WebExtractTool.execute(argsJson, appContext)
            OcrTool.NAME -> OcrTool.execute(argsJson, appContext)
            WebSearchTool.NAME -> WebSearchTool.execute(argsJson, appContext)
            FileSearchTool.NAME -> FileSearchTool.execute(argsJson, FLOATING_SESSION_ID, appContext)
            ArchiveTool.NAME -> ArchiveTool.execute(argsJson, appContext)
            ReadImageTool.NAME -> ReadImageTool.execute(argsJson, sessionId, appContext)
            // [F-A3 / Master Prompt] Task Engine tools — same as main chat.
            com.openminis.app.tasks.TaskTools.TASK_CREATE ->
                com.openminis.app.tasks.TaskTools.executeCreate(argsJson, appContext)
            com.openminis.app.tasks.TaskTools.TASK_UPDATE ->
                com.openminis.app.tasks.TaskTools.executeUpdate(argsJson, appContext)
            com.openminis.app.tasks.TaskTools.TASK_LIST ->
                com.openminis.app.tasks.TaskTools.executeList(argsJson, appContext)
            "browser_use" -> executeBrowser(argsJson)
            "memory_write" -> {
                val repo = memoryRepo()
                if (repo == null) ToolExecutionResult("Error: Memory not available", false)
                else MemoryTools.executeMemoryWrite(argsJson, repo).let {
                    ToolExecutionResult(it.output, it.success, toolTitle = it.toolTitle)
                }
            }
            "memory_get" -> {
                val repo = memoryRepo()
                if (repo == null) ToolExecutionResult("Error: Memory not available", false)
                else MemoryTools.executeMemoryGet(argsJson, repo).let {
                    ToolExecutionResult(it.output, it.success, toolTitle = it.toolTitle)
                }
            }
            AutomationTools.TERMUX_RUN -> AutomationTools.executeTermuxRun(argsJson, appContext)
            AutomationTools.ALWAYS_ON_SYNC -> AutomationTools.executeAlwaysOnSync(argsJson, appContext)
            AutomationTools.ALWAYS_ON_RESUME -> AutomationTools.executeAlwaysOnResume(argsJson, appContext)
            CrossChatTools.CHAT_LIST -> {
                val repo = chatRepo()
                if (repo == null) ToolExecutionResult("Error: chats not ready", false)
                else CrossChatTools.executeChatList(argsJson, repo, appContext)
            }
            CrossChatTools.CHAT_READ -> {
                val repo = chatRepo()
                if (repo == null) ToolExecutionResult("Error: chats not ready", false)
                else CrossChatTools.executeChatRead(argsJson, repo, sessionId, appContext)
            }
            CrossChatTools.CHAT_SEND -> {
                val repo = chatRepo()
                if (repo == null) ToolExecutionResult("Error: chats not ready", false)
                else CrossChatTools.executeChatSend(argsJson, repo, sessionId, "دستیار شناور", appContext)
            }
            CrossChatTools.CHAT_CREATE -> {
                val repo = chatRepo()
                if (repo == null) ToolExecutionResult("Error: chats not ready", false)
                else CrossChatTools.executeChatCreate(argsJson, repo, _selectedEntryId.value.orEmpty(), appContext)
            }
            CrossChatTools.CHAT_WATCH -> {
                val repo = chatRepo()
                if (repo == null) ToolExecutionResult("Error: chats not ready", false)
                else CrossChatTools.executeChatWatch(argsJson, repo, sessionId, appContext)
            }
            else -> ToolExecutionResult("Unknown tool: $name", false)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
    }

    private suspend fun executeShell(argsJson: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) return ToolExecutionResult("Error: 'command' is required", false)
        val timeoutSec = args.optInt("timeout", 900).coerceIn(1, 900)
        val delaySec = args.optInt("delay", 0).coerceAtLeast(0)
        if (delaySec > 0) delay(delaySec * 1000L)
        val r = com.openminis.app.sandbox.ExecutionCoordinator.execute(
            FLOATING_SESSION_ID, command, timeout = timeoutSec * 1000L,
        )
        val out = if (r.exitCode != 0 && r.exitCode != 124) "${r.output}" else r.output
        return ToolExecutionResult(out, r.exitCode == 0 || r.exitCode == 124)
    }

    private suspend fun executeBrowser(argsJson: String): ToolExecutionResult {
        val input = BrowserActionInput.parse(argsJson)
            ?: return ToolExecutionResult("Error: Invalid browser_use input", false)
        return try {
            val result = browserPool.execute(input)
            ToolExecutionResult(result.text, result.success, pageURL = result.pageURL)
        } catch (e: Exception) {
            ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
        }
    }

    private fun toolLabel(name: String, args: JSONObject): String {
        val title = runCatching { args.optString("tool_title", "") }.getOrDefault("")
        return title.ifBlank { name }
    }

    private fun buildSystemPrompt(): String = buildString {
        append(
            "تو دستیار هوشمند شناور روی گوشی کاربر هستی. ابزارهای واقعی در اختیار داری: " +
                "اجرای دستور لینوکسی (shell_execute)، خواندن/نوشتن/ویرایش فایل، جستجوی فایل، وب‌گردی (browser_use)، " +
                "جستجوی وب، دانلود، OCR، ترجمه، PDF، آرشیو، عملیات فایل اندروید، حافظه و (در صورت فعال بودن) " +
                "ابزارهای اتوماسیون و ارتباط بین چت‌ها. برای انجام کار مستقیماً ابزار را صدا بزن — " +
                "الکی نگو که نمی‌توانی. فارسی جواب بده مگر خلافش خواسته شود. کاربر تلفن همراه است؛ خروجی‌ها را کوتاه و کاربردی نگه دار.",
        )
        append("\n\nمحیط shell: Alpine Linux داخل سندباکس اپ (proot). پوشه‌های هم‌رسان: " +
            "/var/minis/workspace، /var/minis/attachments، /var/minis/shared، /var/minis/offloads.")
        append("\n\nابزار task_*: برای هر کار چندمرحله‌ای، اول task_create بساز (برنامه‌ات را ثبت کن)، " +
            "بعد پیشرفت واقعی را با task_update ثبت کن — این state ماندگار است و بعد از ری‌استارت اپ هم باقی می‌ماند.")
        append("\n\nتاریخ امروز: ").append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date()))
        when (prefs.executionMode) {
            1 -> append(
                "\n\nPLANNING MODE: قبل از اجرای هر کاری، برنامه‌ی گام‌به‌گام را ارائه بده و " +
                    "اجازه‌ی کاربر بگیر؛ بعد از تایید، اجرا کن و نتیجه را گزارش بده.",
            )
            2 -> append(
                "\n\nACCEPT MODE: قبل از هر فراخوانی ابزار، کوتاه اعلام کن چه می‌خواهی بکنی و چرا؛ " +
                    "این پنجره هر فراخوانی ابزار را تا تایید کاربر متوقف می‌کند.",
            )
            else -> append(
                "\n\nAUTO MODE: اختیار کامل داری — بدون پرسیدن، کار را تا آخر انجام بده و بعد گزارش بده.",
            )
        }
    }

    // ── UI helpers ───────────────────────────────────────────────────────
    private fun append(m: AssistantMessage) {
        messages.value = messages.value + m
    }

    private fun appendError(text: String) {
        append(AssistantMessage("assistant", text, isError = true))
    }

    // ── History persistence (survives service restarts) ──────────────────
    private fun historyFile(): File = File(appContext.filesDir, "floating_assistant_history.json")

    private fun persistHistory() {
        runCatching {
            // Trim to the last MAX_PERSISTED messages to bound the file.
            val trimmed = if (history.size > MAX_PERSISTED) {
                history.subList(history.size - MAX_PERSISTED, history.size).toList()
            } else history
            val arr = JSONArray()
            for (m in trimmed) {
                val o = JSONObject()
                o.put("role", m.role.value)
                o.put("content", m.content)
                val parts = JSONArray()
                for (p in m.contentParts) {
                    val po = JSONObject()
                    when (p) {
                        is AgentContentPart.Text -> { po.put("t", "text"); po.put("text", p.text) }
                        is AgentContentPart.ToolUse -> {
                            po.put("t", "tool_use"); po.put("id", p.id); po.put("name", p.name)
                            po.put("input", p.input.toString())
                            p.thoughtSignature?.let { po.put("sig", it) }
                        }
                        is AgentContentPart.ToolResult -> {
                            po.put("t", "tool_result"); po.put("id", p.id); po.put("name", p.name)
                            po.put("content", p.content); po.put("isError", p.isError)
                        }
                        else -> continue
                    }
                    parts.put(po)
                }
                o.put("parts", parts)
                arr.put(o)
            }
            historyFile().writeText(arr.toString())
        }
    }

    private fun loadPersistedHistory() {
        runCatching {
            val f = historyFile()
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            history.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val role = when (o.optString("role")) {
                    "assistant" -> LLMMessage.Role.ASSISTANT
                    else -> LLMMessage.Role.USER
                }
                val parts = mutableListOf<AgentContentPart>()
                val parr = o.optJSONArray("parts") ?: JSONArray()
                for (j in 0 until parr.length()) {
                    val po = parr.getJSONObject(j)
                    when (po.optString("t")) {
                        "text" -> parts.add(AgentContentPart.Text(po.optString("text")))
                        "tool_use" -> parts.add(AgentContentPart.ToolUse(
                            id = po.optString("id"),
                            name = po.optString("name"),
                            input = runCatching { JSONObject(po.optString("input")) }.getOrDefault(JSONObject()),
                            thoughtSignature = po.optString("sig").ifEmpty { null },
                        ))
                        "tool_result" -> parts.add(AgentContentPart.ToolResult(
                            id = po.optString("id"),
                            name = po.optString("name"),
                            content = po.optString("content"),
                            isError = po.optBoolean("isError"),
                        ))
                    }
                }
                history.add(LLMMessage(role, content = o.optString("content"), contentParts = parts))
            }
        }
    }

    companion object {
        private const val MAX_LOOP_TURNS = 40
        private const val MAX_TOKENS = 8192
        private const val MAX_PERSISTED = 80
        private const val FLOATING_SESSION_ID = "floating-assistant"
        private val APPROVE_WORDS = listOf("بله", "ادامه", "تایید", "تأیید", "اوکی", "ok", "yes", "continue", "اجازه", "برو")

        /**
         * [FA-BUG-01] Factory used by FloatingAssistantService so the VM is
         * created exactly once per service ViewModelStore — recomposition-proof.
         * Mirrors the working ChatViewModel.factory() pattern (anonymous object
         * instead of a named nested class).
         */
        fun factory(appContext: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(FloatingAssistantViewModel::class.java)) {
                        "Unknown ViewModel class: ${modelClass.name}"
                    }
                    return FloatingAssistantViewModel(appContext.applicationContext) as T
                }
            }
    }
}
