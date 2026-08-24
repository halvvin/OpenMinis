package com.openminis.app.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-browser-windows] Persistent smart-browser chat windows (user spec §3
 * + §3-6). Each window is a named, user-deletable container that survives
 * app restarts and phone reboots:
 *
 *  - [url]        last page (restored when the window reopens)
 *  - [chatSessionId] a REAL chat session (ChatRepository) — full agent +
 *                    model access, persisted by the existing chat DB
 *  - [artifacts]  files produced in the window (HAR captures, generated
 *                    API clients, downloads) — user-deleted only
 *
 * Storage: JSON file in app-private filesDir. Nothing auto-expires.
 */
data class BrowserWindow(
    val id: String,
    val name: String,
    val url: String = "",
    val chatSessionId: String = "",
    val artifacts: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name); put("url", url)
        put("chatSessionId", chatSessionId)
        put("artifacts", JSONArray(artifacts))
        put("createdAt", createdAt); put("updatedAt", updatedAt)
    }.toString()

    companion object {
        fun fromJson(o: JSONObject): BrowserWindow = BrowserWindow(
            id = o.optString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            chatSessionId = o.optString("chatSessionId"),
            artifacts = o.optJSONArray("artifacts")?.let { a ->
                (0 until a.length()).map { a.optString(it) }
            } ?: emptyList(),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }
}

class BrowserWindowStore private constructor(context: Context) {

    private val file: File = File(context.filesDir, "browser/windows.json")

    @Synchronized
    fun load(): List<BrowserWindow> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { BrowserWindow.fromJson(arr.getJSONObject(it)) }
            .sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(windows: List<BrowserWindow>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        windows.forEach { arr.put(JSONObject(it.toJson())) }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun upsert(window: BrowserWindow) {
        val cur = load().filterNot { it.id == window.id } +
            window.copy(updatedAt = System.currentTimeMillis())
        save(cur)
    }

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun get(id: String): BrowserWindow? = load().firstOrNull { it.id == id }

    /** Attach an artifact path (HAR / generated client / download). */
    @Synchronized
    fun addArtifact(id: String, path: String) {
        get(id)?.let { w ->
            if (path !in w.artifacts) upsert(w.copy(artifacts = w.artifacts + path))
        }
    }

    fun newId(): String = "bw_" + java.util.UUID.randomUUID().toString().take(8)

    companion object {
        @Volatile private var instance: BrowserWindowStore? = null

        fun get(context: Context): BrowserWindowStore =
            instance ?: synchronized(this) {
                instance ?: BrowserWindowStore(context.applicationContext).also { instance = it }
            }
    }
}
