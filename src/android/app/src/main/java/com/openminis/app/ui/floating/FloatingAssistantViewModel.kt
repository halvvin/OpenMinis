package com.openminis.app.ui.floating

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-floating-assistant] ViewModel for the floating Smart Assistant window.
 *
 * Keeps its own lightweight chat state (independent of the session-based
 * ChatViewModel) so the assistant can be opened from anywhere and talk to
 * ANY configured model/API. Real tool execution is wired via the agent loop
 * in later stages; v1 does direct model calls (same pattern as the browser
 * AI panel).
 */
data class AssistantMessage(
    val role: String,          // "user" | "assistant"
    val text: String,
)

class FloatingAssistantViewModel(
    private val appContext: Context,
    private val providerRepo: ProviderRepository?,
) : ViewModel() {

    private val prefs: AutomationPrefs = AutomationPrefs.get(appContext)

    val messages = MutableStateFlow<List<AssistantMessage>>(emptyList())
    val busy = MutableStateFlow(false)
    val modelEntries = MutableStateFlow<List<com.openminis.app.data.model.ModelEntry>>(emptyList())

    private val _selectedEntryId = MutableStateFlow(prefs.assistantModelEntryId)
    val selectedEntryId: StateFlow<String> = _selectedEntryId
    private val _selectedEntry = MutableStateFlow<com.openminis.app.data.model.ModelEntry?>(null)
    val selectedEntry: StateFlow<com.openminis.app.data.model.ModelEntry?> = _selectedEntry

    private var lastError: String? = null

    init {
        viewModelScope.launch {
            if (providerRepo != null) {
                providerRepo.configLoaded.collect { loaded ->
                    if (loaded) loadModels()
                }
            }
        }
    }

    fun loadModels() {
        // [T-floating-system] repo may be null before MinisApp finishes init.
        val repo = providerRepo ?: return
        // Same fallback rule as the browser: Agent Loop entries first, else ALL
        // enabled-provider entries (grouped per API in the UI).
        val loop = runCatching { repo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
        val all = runCatching {
            val cfg = repo.config.value
            val enabled = cfg.instances.filter { it.isEnabled }.map { it.id }.toSet()
            cfg.modelEntries.filter { it.providerInstanceId in enabled && !it.isHidden }
        }.getOrDefault(emptyList())
        val list = if (loop.isNotEmpty()) loop else all
        modelEntries.value = list
        // Restore last selection if still valid, else pick first.
        val sel = _selectedEntryId.value
        _selectedEntryId.value = if (list.any { it.id == sel }) sel else list.firstOrNull()?.id.orEmpty()
        _selectedEntry.value = list.firstOrNull { it.id == _selectedEntryId.value }
    }

    fun selectModel(entryId: String) {
        _selectedEntryId.value = entryId
        prefs.assistantModelEntryId = entryId
        _selectedEntry.value = modelEntries.value.firstOrNull { it.id == entryId }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        val repo = providerRepo
        if (repo == null) {
            appendAssistant("❌ سرویس‌های مدل هنوز آماده نیستند — کمی بعد دوباره تلاش کن.")
            return
        }
        messages.value = messages.value + AssistantMessage("user", t)
        val entry = selectedEntry.value
        if (entry == null) {
            appendAssistant("❌ مدلی انتخاب نشده — از منوی بالا یک مدل/API انتخاب کن.")
            return
        }
        busy.value = true
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    val instance = repo.instance(entry.providerInstanceId)
                    val apiKey = instance?.let { repo.usableApiKey(it) }
                    if (instance == null || apiKey == null) {
                        "❌ کلید API برای «${entry.model.displayName}» در دسترس نیست — تنظیمات → Providers را چک کن."
                    } else {
                        val provider = ProviderFactory.create(instance, apiKey, entry.model, appContext)
                        val sys = buildString {
                            append("تو دستیار هوشمند همه‌کاره هستی. می‌توانی برای کاربر هر کاری انجام دهی: جستجو، توضیح، ترجمه، کدنویسی، برنامه‌ریزی، تحلیل فایل و هر سؤال دیگر. اگر کاربر چیزی می‌خواهد که نیاز به ابزار (اجرای دستور، خواندن فایل، وب‌گردی) دارد، راهنمایی کن که چه ابزاری لازم است. فارسی جواب بده مگر خلافش خواسته شود.")
                            // [T-execution-modes] Append the mode instruction.
                            val modes = listOf(
                                "AUTO MODE: You have full autonomy. Use every tool freely — reading, writing, editing, deleting, searching the web and downloading — to accomplish the task end to end. Do not stop to ask, do not offer to proceed. Just do the work and report what you did.",
                                "PLANNING MODE: Review the task and present a step-by-step plan BEFORE executing anything. Ask the user to confirm the plan, then execute. After execution, report the results.",
                                "ACCEPT MODE: Before EVERY tool call — reading a file, listing a folder, searching, browsing the web, writing, editing, deleting, downloading — explain to the user what you are about to do and WHY. Wait for their explicit go-ahead (they'll say 'continue' or 'ok'). Never call a tool without first announcing it and getting a response. After the user approves, call the tool, then report the result.",
                            )
                            val mode = prefs.executionMode.coerceIn(0, 2)
                            append("\n\n${modes[mode]}")
                        }
                        val resp = provider.sendMessage(
                            messages = listOf(
                                LLMMessage(
                                    role = LLMMessage.Role.USER,
                                    content = t,
                                )
                            ),
                            systemPrompt = sys,
                            maxTokens = 2048,
                        )
                        resp.text.ifBlank { "(پاسخ خالی از مدل)" }
                    }
                }.getOrElse { e -> "❌ ${e.message ?: "خطای ناشناخته"}" }
            }
            appendAssistant(reply)
            busy.value = false
        }
    }

    private fun appendAssistant(text: String) {
        messages.value = messages.value + AssistantMessage("assistant", text)
    }

    fun clear() {
        messages.value = emptyList()
        lastError = null
    }

    fun error(): String? = lastError
}
