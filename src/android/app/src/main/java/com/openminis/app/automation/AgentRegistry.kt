package com.openminis.app.automation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-agent-manager] Dynamic registry of user-defined AI agent CLI tools
 * (user spec §8-3).
 *
 * Deliberately GENERIC: there is NO hard-coded list of tools anywhere.
 * The user adds any tool with any name and any install/update/run commands
 * (npm / pip / curl / docker / ...). The AI's guidance about a tool comes
 * from the model's own knowledge at chat time — never from this registry.
 */
data class AgentEntry(
    val id: String,
    val name: String,
    val installCmd: String = "",
    val updateCmd: String = "",
    val runCmd: String = "",
    val notes: String = "",
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id); put("name", name)
        put("install", installCmd); put("update", updateCmd); put("run", runCmd)
        put("notes", notes); put("addedAt", addedAt)
    }.toString()

    companion object {
        fun fromJson(o: JSONObject): AgentEntry = AgentEntry(
            id = o.optString("id"),
            name = o.optString("name"),
            installCmd = o.optString("install"),
            updateCmd = o.optString("update"),
            runCmd = o.optString("run"),
            notes = o.optString("notes"),
            addedAt = o.optLong("addedAt"),
        )
    }
}

class AgentRegistry private constructor(context: Context) {

    private val file = File(context.filesDir, "automation/agents.json")

    @Synchronized
    fun load(): List<AgentEntry> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { AgentEntry.fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(entries: List<AgentEntry>) {
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject(it.toJson())) }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun upsert(entry: AgentEntry) {
        val cur = load().filterNot { it.id == entry.id } + entry
        save(cur.sortedBy { it.name })
    }

    @Synchronized
    fun remove(id: String) = save(load().filterNot { it.id == id })

    fun newId(): String = "agent_" + java.util.UUID.randomUUID().toString().take(8)

    companion object {
        @Volatile private var instance: AgentRegistry? = null

        fun get(context: Context): AgentRegistry =
            instance ?: synchronized(this) {
                instance ?: AgentRegistry(context.applicationContext).also { instance = it }
            }
    }
}
