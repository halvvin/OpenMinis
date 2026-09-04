package com.openminis.app.tasks

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * [F-A3 / Master Prompt "Task Engine"] Agent-facing tools over [TaskEngine].
 *
 * task_create — durable task with ordered steps (the "plan" the agent made
 *               becomes persistent, crash-surviving state).
 * task_update — start / complete_step / fail_step / append_log / cancel.
 * task_list   — query tasks (optionally by status); the agent uses this to
 *               resume after a crash instead of guessing.
 */
object TaskTools {

    const val TASK_CREATE = "task_create"
    const val TASK_UPDATE = "task_update"
    const val TASK_LIST = "task_list"

    private const val TOOL_TITLE_DESC =
        "A concise 5-10 word summary of what this tool call does, shown to the user. Use the same language as the user."

    // ── Definitions ──────────────────────────────────────────────────────
    fun createDefinition() = AgentToolDefinition(
        name = TASK_CREATE,
        description = "Create a durable task with ordered steps. Tasks persist across app restarts. " +
            "Use this at the start of any multi-step piece of work so progress is trackable and resumable " +
            "(e.g. 'build a website': steps = plan, scaffold, implement, test, deliver).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", TOOL_TITLE_DESC),
            "title" to AgentToolParam("string", "Short task title (e.g. 'Build landing page')."),
            "request" to AgentToolParam("string", "The original user request / goal this task fulfills (optional)."),
            "steps" to AgentToolParam(
                "string",
                "Ordered step titles, one per line or separated by ' | '. Example: 'plan | scaffold | implement | test'",
            ),
        ),
        required = listOf("tool_title", "title"),
        propertyOrdering = listOf("tool_title", "title", "request", "steps"),
    )

    fun updateDefinition() = AgentToolDefinition(
        name = TASK_UPDATE,
        description = "Update a task's lifecycle: start it, complete/fail a step, append a log line, or cancel it. " +
            "Call task_create first; use task_list to find task/step ids after a restart.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", TOOL_TITLE_DESC),
            "task_id" to AgentToolParam("string", "The task id returned by task_create."),
            "action" to AgentToolParam(
                "string",
                "One of: start | complete_step | fail_step | append_log | cancel",
                enumValues = listOf("start", "complete_step", "fail_step", "append_log", "cancel"),
            ),
            "step_id" to AgentToolParam("string", "Step id (for complete_step / fail_step). Omit to target the current step."),
            "log" to AgentToolParam("string", "Log line / error text (for append_log / fail_step)."),
        ),
        required = listOf("tool_title", "task_id", "action"),
        propertyOrdering = listOf("tool_title", "task_id", "action", "step_id", "log"),
    )

    fun listDefinition() = AgentToolDefinition(
        name = TASK_LIST,
        description = "List durable tasks (optionally filtered by status). Use after a restart to find " +
            "unfinished work and its step ids.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", TOOL_TITLE_DESC),
            "status" to AgentToolParam(
                "string",
                "Optional filter: PENDING | RUNNING | COMPLETED | FAILED | CANCELLED",
                enumValues = listOf("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"),
            ),
            "limit" to AgentToolParam("string", "Max tasks to return (default 20)."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "status", "limit"),
    )

    // ── Executors ────────────────────────────────────────────────────────
    fun executeCreate(argsJson: String, context: Context): ToolExecutionResult = try {
        val args = JSONObject(argsJson)
        val title = args.optString("title").trim()
        if (title.isEmpty()) {
            ToolExecutionResult("Error: 'title' is required.", false, toolTitle = TASK_CREATE)
        } else {
            val request = args.optString("request", "").trim()
            val rawSteps = args.optString("steps", "").trim()
            val steps = if (rawSteps.isEmpty()) emptyList() else {
                rawSteps.split("|", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
            val t = TaskEngine.create(context, title, request, steps)
            ToolExecutionResult(
                "Task created ✓\n" + renderTask(t),
                true, toolTitle = TASK_CREATE,
            )
        }
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false, toolTitle = TASK_CREATE)
    }

    fun executeUpdate(argsJson: String, context: Context): ToolExecutionResult = try {
        val args = JSONObject(argsJson)
        val taskId = args.optString("task_id").trim()
        val action = args.optString("action").trim().lowercase()
        val stepId = args.optString("step_id", "").trim()
        val log = args.optString("log", "").trim()

        if (taskId.isEmpty()) {
            ToolExecutionResult("Error: 'task_id' is required (see task_list).", false, toolTitle = TASK_UPDATE)
        } else {
            val updated: TaskEngine.Task? = when (action) {
                "start" -> TaskEngine.start(context, taskId)
                "complete_step" -> {
                    val sid = stepId.ifEmpty {
                        TaskEngine.get(context, taskId)?.steps
                            ?.firstOrNull { it.status == TaskEngine.Status.RUNNING }?.id.orEmpty()
                    }
                    if (sid.isEmpty()) null
                    else TaskEngine.completeStep(context, taskId, sid, log.ifEmpty { null })
                }
                "fail_step" -> {
                    val sid = stepId.ifEmpty {
                        TaskEngine.get(context, taskId)?.steps
                            ?.firstOrNull { it.status == TaskEngine.Status.RUNNING }?.id.orEmpty()
                    }
                    if (sid.isEmpty()) null
                    else TaskEngine.failStep(context, taskId, sid, log.ifEmpty { "step failed" })
                }
                "append_log" -> TaskEngine.appendLog(context, taskId, log.ifEmpty { "(no text)" })
                "cancel" -> TaskEngine.cancel(context, taskId)
                else -> null
            }
            when {
                action !in listOf("start", "complete_step", "fail_step", "append_log", "cancel") ->
                    ToolExecutionResult("Error: unknown action '$action'.", false, toolTitle = TASK_UPDATE)
                updated == null ->
                    ToolExecutionResult("Error: task or step not found (task_id=$taskId). Use task_list.", false, toolTitle = TASK_UPDATE)
                else ->
                    ToolExecutionResult("Task updated ✓\n" + renderTask(updated), true, toolTitle = TASK_UPDATE)
            }
        }
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false, toolTitle = TASK_UPDATE)
    }

    fun executeList(argsJson: String, context: Context): ToolExecutionResult = try {
        val args = JSONObject(argsJson)
        val statusStr = args.optString("status", "").trim().uppercase()
        val status = if (statusStr.isEmpty()) null else runCatching {
            TaskEngine.Status.valueOf(statusStr)
        }.getOrNull()
        val limit = args.optString("limit", "20").toIntOrNull() ?: 20
        val tasks = TaskEngine.list(context, status, limit)
        if (tasks.isEmpty()) {
            ToolExecutionResult("No tasks${if (status != null) " with status $status" else ""}.", true, toolTitle = TASK_LIST)
        } else {
            ToolExecutionResult(
                tasks.joinToString("\n\n") { renderTask(it) },
                true, toolTitle = TASK_LIST,
            )
        }
    } catch (e: Exception) {
        ToolExecutionResult("Error: ${e.message}", false, toolTitle = TASK_LIST)
    }

    // ── Rendering ────────────────────────────────────────────────────────
    private fun renderTask(t: TaskEngine.Task): String = buildString {
        append("[${t.id}] ${t.title} — ${t.status}")
        if (!t.error.isNullOrBlank()) append("  (error: ${t.error})")
        for (s in t.steps) {
            append("\n  • ${s.title} — ${s.status} (${s.id})")
            val last = s.log.lastOrNull()
            if (last != null) append("\n      last log: ${last.take(140)}")
        }
    }
}
