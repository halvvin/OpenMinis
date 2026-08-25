package com.openminis.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-browser-tabs] Chrome-like persistent browser tabs (user spec §3, rev2).
 *
 * Each tab = one site the user opened, with its own embedded AI chat
 * history. Tabs persist across app restarts and reboots; ONLY the user
 * closes/deletes them. The chat is a lightweight assistant panel bound to
 * the tab: it reads the visible page (extract/translate/summarize/…) and
 * uses whichever model/API the user picks from the same configured pool
 * as the main chat (ProviderRepository agent-loop entries).
 */
data class BrowserChatMsg(
    val role: String,          // "user" | "assistant"
    val text: String,
    val model: String = "",
    val at: Long = System.currentTimeMillis(),
)

data class BrowserTab(
    val id: String,
    val title: String = "تب جدید",
    val url: String = "",
    val messages: List<BrowserChatMsg> = emptyList(),
    val artifacts: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("title", title); put("url", url)
        put("messages", JSONArray().apply { messages.forEach { m ->
            put(JSONObject().apply {
                put("role", m.role); put("text", m.text); put("model", m.model); put("at", m.at)
            })
        } })
        put("artifacts", JSONArray(artifacts))
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJson(o: JSONObject): BrowserTab = BrowserTab(
            id = o.optString("id"),
            title = o.optString("title", "تب جدید").ifBlank { "تب جدید" },
            url = o.optString("url"),
            messages = o.optJSONArray("messages")?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    BrowserChatMsg(
                        role = m.optString("role"),
                        text = m.optString("text"),
                        model = m.optString("model"),
                        at = m.optLong("at"),
                    )
                }
            } ?: emptyList(),
            artifacts = o.optJSONArray("artifacts")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

class BrowserTabStore private constructor(context: Context) {

    private val file: File = File(context.filesDir, "browser/tabs.json")

    @Synchronized
    fun load(): List<BrowserTab> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { BrowserTab.fromJson(arr.getJSONObject(it)) }
            .sortedBy { it.createdAt }   // tab order = open order, like Chrome
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tabs: List<BrowserTab>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        tabs.forEach { arr.put(JSONObject(it.toJson())) }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun upsert(tab: BrowserTab) {
        val cur = load().filterNot { it.id == tab.id } +
            tab.copy(updatedAt = System.currentTimeMillis())
        save(cur)
    }

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun get(id: String): BrowserTab? = load().firstOrNull { it.id == id }

    /** Append a chat message to a tab (thread-safe, re-reads from disk). */
    @Synchronized
    fun appendMessage(tabId: String, msg: BrowserChatMsg) {
        get(tabId)?.let { t ->
            upsert(t.copy(messages = t.messages + msg))
        }
    }

    fun newId(): String = "tab_" + java.util.UUID.randomUUID().toString().take(8)

    // ── [FIX-3] Async variants — disk I/O off the main thread. A SINGLE-
    // threaded executor keeps write ordering (no interleaved read-modify-
    // write races between rapid navigations). ─────────────────────────────
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tabs-writer").apply { isDaemon = true } }

    fun upsertAsync(tab: BrowserTab) = writeExecutor.execute { runCatching { upsert(tab) } }

    fun removeAsync(id: String) = writeExecutor.execute { runCatching { remove(id) } }

    fun appendMessageAsync(tabId: String, msg: BrowserChatMsg) =
        writeExecutor.execute { runCatching { appendMessage(tabId, msg) } }

    companion object {
        @Volatile private var instance: BrowserTabStore? = null

        fun get(context: Context): BrowserTabStore =
            instance ?: synchronized(this) {
                instance ?: BrowserTabStore(context.applicationContext).also { instance = it }
            }
    }
}
