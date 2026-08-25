package com.openminis.app.tools

import android.content.Context
import com.openminis.app.automation.AlwaysOnEngine
import com.openminis.app.automation.AutomationPrefs
import com.openminis.app.automation.TermuxBridge
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-automation-tools] Chat tools for the Automation hub (audit FIX-2/3).
 *
 * Wires the previously-dead engines into the agent loop:
 *  - `termux_run`        → TermuxBridge.execute()      (gated: termuxEnabled)
 *  - `always_on_sync`    → AlwaysOnEngine.syncProject  (gated: alwaysOnEnabled)
 *  - `always_on_resume`  → AlwaysOnEngine.resumeFromServer (gated: alwaysOnEnabled)
 *
 * When the corresponding AutomationPrefs flag is OFF, the tool is REMOVED
 * from the schema entirely (AgentTools.makeAgentTools) — the model can't
 * even attempt it. Every call is audit-logged via AutomationPrefs.appendLog.
 */
object AutomationTools {

    const val TERMUX_RUN = "termux_run"
    const val ALWAYS_ON_SYNC = "always_on_sync"
    const val ALWAYS_ON_RESUME = "always_on_resume"

    private fun log(context: Context, line: String) {
        runCatching { AutomationPrefs.get(context).appendLog(line) }
    }

    // ── Definitions ──────────────────────────────────────────────────────

    fun termuxRunDefinition() = AgentToolDefinition(
        name = TERMUX_RUN,
        description = "Execute a shell command inside the external Termux app (user's Android terminal). " +
            "Output round-trips through a shared exchange directory and may take up to 2 minutes. " +
            "Only available when the user enabled یکپارچه‌سازی Termux in settings.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "command" to AgentToolParam("string", "The shell command to run inside Termux (e.g. 'pkg install python', 'ls ~/')."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command"),
    )

    fun alwaysOnSyncDefinition() = AgentToolDefinition(
        name = ALWAYS_ON_SYNC,
        description = "Upload the task state and project files to the user's configured Always-On server (SSH). " +
            "Use after finishing a significant milestone of a long task so it survives app closure. " +
            "Only available when the user enabled اجرای همیشگی and configured a server.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "project_dir" to AgentToolParam("string", "In-sandbox project directory to sync (e.g. /var/minis/workspace/myapp). Defaults to /var/minis/workspace."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "project_dir"),
    )

    fun alwaysOnResumeDefinition() = AgentToolDefinition(
        name = ALWAYS_ON_RESUME,
        description = "Pull a previously-synced project back from the user's Always-On server (resume from checkpoint).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user."),
            "remote_name" to AgentToolParam("string", "Remote folder name to pull (usually the project name used when syncing)."),
            "local_dir" to AgentToolParam("string", "Local in-sandbox directory to restore into (e.g. /var/minis/workspace/myapp)."),
        ),
        required = listOf("tool_title", "remote_name", "local_dir"),
        propertyOrdering = listOf("tool_title", "remote_name", "local_dir"),
    )

    // ── Executors ────────────────────────────────────────────────────────

    suspend fun executeTermuxRun(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val command = args.optString("command").trim()
            if (command.isEmpty()) return@withContext ToolExecutionResult("Error: 'command' is required.", false)
            val prefs = AutomationPrefs.get(context)
            if (!prefs.termuxEnabled) {
                return@withContext ToolExecutionResult("Error: Termux integration is disabled (Settings → اتوماسیون و ایجنت‌ها).", false)
            }
            log(context, "termux_run: ${command.take(80)}")
            val exchangeDir = prefs.loadTermux().exchangeDir
            val r = TermuxBridge.execute(context, command, exchangeDir, timeoutMs = 120_000L)
            log(context, "termux_run → ${if (r.ok) "OK" else "FAIL"}")
            ToolExecutionResult((if (r.ok) "" else "FAILED: ") + r.output.take(8000), r.ok)
        }

    suspend fun executeAlwaysOnSync(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val prefs = AutomationPrefs.get(context)
            if (!prefs.alwaysOnEnabled) {
                return@withContext ToolExecutionResult("Error: Always-On is disabled (Settings → اتوماسیون و ایجنت‌ها).", false)
            }
            val cfg = prefs.loadAlwaysOn()
            if (cfg.host.isBlank()) {
                return@withContext ToolExecutionResult("Error: no Always-On server configured (Settings → اجرای همیشگی).", false)
            }
            val dir = args.optString("project_dir", "/var/minis/workspace").trim()
            log(context, "always_on_sync: $dir")
            val r = AlwaysOnEngine.syncProject(cfg, dir)
            log(context, "always_on_sync → ${if (r.ok) "OK" else "FAIL"}")
            ToolExecutionResult((if (r.ok) "Synced ✓\n" else "FAILED:\n") + r.detail.take(4000), r.ok)
        }

    suspend fun executeAlwaysOnResume(argsJson: String, context: Context): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
            val prefs = AutomationPrefs.get(context)
            if (!prefs.alwaysOnEnabled) {
                return@withContext ToolExecutionResult("Error: Always-On is disabled.", false)
            }
            val cfg = prefs.loadAlwaysOn()
            val remote = args.optString("remote_name").trim()
            val local = args.optString("local_dir").trim()
            if (remote.isEmpty() || local.isEmpty()) {
                return@withContext ToolExecutionResult("Error: 'remote_name' and 'local_dir' are required.", false)
            }
            log(context, "always_on_resume: $remote → $local")
            val r = AlwaysOnEngine.resumeFromServer(cfg, remote, local)
            ToolExecutionResult((if (r.ok) "Restored ✓\n" else "FAILED:\n") + r.detail.take(4000), r.ok)
        }
}
