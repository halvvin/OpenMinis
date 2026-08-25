package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-cross-chat] Cross-chat communication tools (user feature request §C).
 *
 * Lets the AI in chat A list / read / message / create / watch OTHER chat
 * sessions — only when the user explicitly enabled it
 * (AutomationPrefs.crossChatEnabled, default OFF; when OFF the tools are
 * removed from the schema entirely). Every call is audit-logged.
 *
 * Safety:
 *  - Self-target guard: chat_read / chat_send / chat_watch refuse to act on
 *    the session that issued the call (infinite-loop guard).
 *  - No new persistence: everything goes through the existing ChatRepository.
 */
object CrossChatTools {

    const val CHAT_LIST = "chat_list"
    const val CHAT_READ = "chat_read"
    const val CHAT_SEND = "chat_send"
    const val CHAT_CREATE = "chat_create"
    const val CHAT_WATCH = "chat_watch"

    private fun log(context: Context, line: String) {
        runCatching {
            com.openminis.app.automation.AutomationPrefs.get(context).appendLog("[cross-chat] $line")
        }
    }

    // ── Definitions ──────────────────────────────────────────────────────

    fun chatListDefinition() = AgentToolDefinition(
        name = CHAT_LIST,
        description = "List the user's other chat sessions (id, title, last message, updated time). " +
            "Use to discover which chat the user means before reading or sending.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "query" to AgentToolParam("string", "Optional: filter sessions whose title contains this text (case-insensitive)."),
            "limit" to AgentToolParam("integer", "Max results (default 20)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "query", "limit"),
    )

    fun chatReadDefinition() = AgentToolDefinition(
        name = CHAT_READ,
        description = "Read the last N messages of ANOTHER chat session. Cannot target the current chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "session_title" to AgentToolParam("string", "Exact title of the target chat (from chat_list)."),
            "max_messages" to AgentToolParam("integer", "How many of the latest messages to return (default 50)."),
        ),
        required = listOf("tool_title", "session_title"),
        propertyOrdering = listOf("tool_title", "session_title", "max_messages"),
    )

    fun chatSendDefinition() = AgentToolDefinition(
        name = CHAT_SEND,
        description = "Append a message to ANOTHER chat session on the user's behalf. " +
            "The message is attributed to this chat's title. Cannot target the current chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "session_title" to AgentToolParam("string", "Exact title of the target chat."),
            "text" to AgentToolParam("string", "The message text to append."),
            "as_user" to AgentToolParam("string", "'true' to record it as a user message; default records it as an assistant note."),
        ),
        required = listOf("tool_title", "session_title", "text"),
        propertyOrdering = listOf("tool_title", "session_title", "text", "as_user"),
    )

    fun chatCreateDefinition() = AgentToolDefinition(
        name = CHAT_CREATE,
        description = "Create a NEW chat session with the given title and optional first message.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "title" to AgentToolParam("string", "Title of the new chat."),
            "initial_message" to AgentToolParam("string", "Optional first message to place in the new chat."),
        ),
        required = listOf("tool_title", "title"),
        propertyOrdering = listOf("tool_title", "title", "initial_message"),
    )

    fun chatWatchDefinition() = AgentToolDefinition(
        name = CHAT_WATCH,
        description = "Watch ANOTHER chat for new messages: polls it and returns any messages newer than the current latest, " +
            "or an empty result when nothing arrived within max_wait_seconds (default 120). Cannot target the current chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "session_title" to AgentToolParam("string", "Exact title of the target chat."),
            "max_wait_seconds" to AgentToolParam("integer", "How long to wait for new messages (default 120, max 600)."),
        ),
        required = listOf("tool_title", "session_title"),
        propertyOrdering = listOf("tool_title", "session_title", "max_wait_seconds"),
    )

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Extract readable text from a stored parts_json payload. */
    private fun partsToText(partsJson: String): String = runCatching {
        val arr = JSONArray(partsJson)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("text").takeIf { it.isNotBlank() }
        }.joinToString(" ")
    }.getOrDefault("")

    /** Resolve a target session by EXACT title (case-insensitive). Null when absent. */
    private suspend fun findByTitle(repo: ChatRepository, title: String) =
        repo.searchSessions(title).firstOrNull {
            it.title?.trim()?.equals(title.trim(), ignoreCase = true) == true
        }

    // ── Executors (all suspend; called from ChatViewModel.executeTool) ───

    suspend fun executeChatList(argsJson: String, repo: ChatRepository, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val query = args.optString("query").trim()
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val sessions = if (query.isEmpty()) repo.observeSessions().first().sortedByDescending { it.updatedAt }
            else repo.searchSessions(query).sortedByDescending { it.updatedAt }
            val arr = JSONArray()
            sessions.take(limit).forEach { s ->
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title ?: "")
                    put("last_message", s.lastMessage?.take(120) ?: "")
                    put("updated_at", s.updatedAt)
                })
            }
            log(context, "chat_list query='$query' → ${arr.length()} sessions")
            ToolExecutionResult(arr.toString(), true)
        }

    suspend fun executeChatRead(argsJson: String, repo: ChatRepository, sourceSessionId: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val title = args.optString("session_title").trim()
            val max = args.optInt("max_messages", 50).coerceIn(1, 200)
            val target = findByTitle(repo, title)
                ?: return@withContext ToolExecutionResult("Error: no chat titled \"$title\". Use chat_list first.", false)
            if (target.id == sourceSessionId) {
                return@withContext ToolExecutionResult("Error: that IS the current chat (self-target blocked).", false)
            }
            val msgs = repo.loadMessages(target.id).takeLast(max)
            val arr = JSONArray()
            msgs.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", partsToText(m.partsJson).take(4000))
                    put("at", m.createdAt)
                })
            }
            log(context, "chat_read '$title' → ${msgs.size} msgs")
            ToolExecutionResult(arr.toString(), true)
        }

    suspend fun executeChatSend(argsJson: String, repo: ChatRepository, sourceSessionId: String, sourceTitle: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val title = args.optString("session_title").trim()
            val text = args.optString("text")
            val asUser = args.optString("as_user").equals("true", ignoreCase = true)
            if (text.isBlank()) return@withContext ToolExecutionResult("Error: 'text' is required.", false)
            val target = findByTitle(repo, title)
                ?: return@withContext ToolExecutionResult("Error: no chat titled \"$title\". Use chat_list first.", false)
            if (target.id == sourceSessionId) {
                return@withContext ToolExecutionResult("Error: that IS the current chat (self-target blocked). Just reply normally.", false)
            }
            val labelled = "[از چت «${sourceTitle.ifBlank { "چت دیگر" }}»]\n$text"
            repo.appendMessage(target.id, if (asUser) "user" else "assistant", JSONArray().put(JSONObject().put("text", labelled)).toString())
            log(context, "chat_send → '$title' (${text.length} chars, asUser=$asUser)")
            ToolExecutionResult("Delivered to chat \"$title\" ✓", true)
        }

    suspend fun executeChatCreate(argsJson: String, repo: ChatRepository, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val title = args.optString("title").trim()
            if (title.isEmpty()) return@withContext ToolExecutionResult("Error: 'title' is required.", false)
            val initial = args.optString("initial_message").trim()
            val session = repo.createSession(modelId = "", title = title)
            if (initial.isNotEmpty()) {
                repo.appendMessage(session.id, "user", JSONArray().put(JSONObject().put("text", initial)).toString())
            }
            log(context, "chat_create '$title' → ${session.id}")
            ToolExecutionResult(JSONObject().apply { put("id", session.id); put("title", title) }.toString(), true)
        }

    suspend fun executeChatWatch(argsJson: String, repo: ChatRepository, sourceSessionId: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val title = args.optString("session_title").trim()
            val maxWait = args.optInt("max_wait_seconds", 120).coerceIn(10, 600)
            val target = findByTitle(repo, title)
                ?: return@withContext ToolExecutionResult("Error: no chat titled \"$title\". Use chat_list first.", false)
            if (target.id == sourceSessionId) {
                return@withContext ToolExecutionResult("Error: that IS the current chat (self-target blocked).", false)
            }
            val baseline = repo.loadMessages(target.id).lastOrNull()?.createdAt ?: 0L
            log(context, "chat_watch '$title' baseline=$baseline wait=${maxWait}s")
            val deadline = System.currentTimeMillis() + maxWait * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(5000)
                val fresh = repo.loadMessages(target.id).filter { it.createdAt > baseline }
                if (fresh.isNotEmpty()) {
                    val arr = JSONArray()
                    fresh.takeLast(20).forEach { m ->
                        arr.put(JSONObject().apply {
                            put("role", m.role)
                            put("content", partsToText(m.partsJson).take(4000))
                            put("at", m.createdAt)
                        })
                    }
                    log(context, "chat_watch '$title' → ${fresh.size} new msgs")
                    return@withContext ToolExecutionResult(arr.toString(), true)
                }
            }
            log(context, "chat_watch '$title' → timeout, no new msgs")
            ToolExecutionResult("(no new messages in \"$title\" within ${maxWait}s)", true)
        }
}
