package com.openminis.app.tasks

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [F-A3 / Master Prompt "Task Engine"] Durable task engine — the first-class
 * execution unit the Master Prompt v6 requires: Task ID, request, ordered
 * steps, status, logs, timestamps, errors, and full lifecycle states,
 * persisted across process death.
 *
 * This is a REAL store + engine, not a spec stub:
 *  - tasks live in filesDir/task_engine/tasks.json (atomic tmp+rename write)
 *  - every step carries its own lifecycle (PENDING/RUNNING/…) and log lines
 *  - the agent drives it through the task_create / task_update / task_list
 *    tools (see [TaskTools]), and the UI/agent can query durable state after
 *    a crash — the property the ScheduledTask store alone did not provide.
 *
 * Deliberately sequential (single owner per task, no scheduler) — the
 * Workflow-Graph / parallel-orchestrator layer is a separate, larger system.
 */
object TaskEngine {

    private const val TAG = "TaskEngine"
    private const val MAX_TASKS = 200
    private const val MAX_LOG_LINES_PER_STEP = 200

    enum class Status { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

    data class Step(
        val id: String,
        val title: String,
        var status: Status = Status.PENDING,
        val log: MutableList<String> = mutableListOf(),
        var startedAt: Long = 0L,
        var finishedAt: Long = 0L,
    )

    data class Task(
        val id: String,
        val title: String,
        val request: String,
        var status: Status = Status.PENDING,
        val steps: MutableList<Step> = mutableListOf(),
        var error: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis(),
    )

    private val lock = Any()

    private fun dir(context: Context) = File(context.filesDir, "task_engine")
    private fun file(context: Context) = File(dir(context), "tasks.json")

    // ── Persistence ──────────────────────────────────────────────────────
    private fun load(context: Context): MutableList<Task> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Task>()
            for (i in 0 until arr.length()) {
                out.add(fromJson(arr.getJSONObject(i)))
            }
            out
        }.getOrElse {
            Log.w(TAG, "task store load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun save(context: Context, tasks: List<Task>) {
        try {
            val trimmed = if (tasks.size > MAX_TASKS) {
                // Keep the newest MAX_TASKS by updatedAt.
                tasks.sortedByDescending { it.updatedAt }.take(MAX_TASKS)
            } else tasks
            val arr = JSONArray()
            trimmed.forEach { arr.put(toJson(it)) }
            val dst = file(context)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "task store save failed: ${e.message}")
        }
    }

    private fun toJson(t: Task): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("request", t.request)
        put("status", t.status.name)
        put("error", t.error ?: JSONObject.NULL)
        put("createdAt", t.createdAt)
        put("updatedAt", t.updatedAt)
        val steps = JSONArray()
        for (s in t.steps) {
            steps.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("status", s.status.name)
                put("startedAt", s.startedAt)
                put("finishedAt", s.finishedAt)
                put("log", JSONArray(s.log.takeLast(MAX_LOG_LINES_PER_STEP)))
            })
        }
        put("steps", steps)
    }

    private fun fromJson(o: JSONObject): Task = Task(
        id = o.getString("id"),
        title = o.optString("title"),
        request = o.optString("request"),
        status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PENDING),
        error = o.optString("error").ifEmpty { null },
        createdAt = o.optLong("createdAt", 0L),
        updatedAt = o.optLong("updatedAt", 0L),
        steps = buildList {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val so = arr.getJSONObject(i)
                add(
                    Step(
                        id = so.optString("id"),
                        title = so.optString("title"),
                        status = runCatching { Status.valueOf(so.getString("status")) }
                            .getOrDefault(Status.PENDING),
                        log = mutableListOf<String>().apply {
                            val la = so.optJSONArray("log") ?: JSONArray()
                            for (j in 0 until la.length()) add(la.optString(j))
                        },
                        startedAt = so.optLong("startedAt", 0L),
                        finishedAt = so.optLong("finishedAt", 0L),
                    )
                )
            }
        }.toMutableList(),
    )

    // ── API ──────────────────────────────────────────────────────────────
    fun create(context: Context, title: String, request: String, stepTitles: List<String>): Task =
        synchronized(lock) {
            val t = Task(
                id = "task_" + UUID.randomUUID().toString().take(8),
                title = title,
                request = request,
                steps = stepTitles.filter { it.isNotBlank() }
                    .map { Step(id = "step_${UUID.randomUUID().toString().take(6)}", title = it.trim()) }
                    .toMutableList(),
            )
            val tasks = load(context)
            tasks.add(t)
            save(context, tasks)
            Log.i(TAG, "task created id=${t.id} steps=${t.steps.size}")
            t
        }

    fun list(context: Context, statusFilter: Status? = null, limit: Int = 20): List<Task> =
        synchronized(lock) {
            load(context)
                .filter { statusFilter == null || it.status == statusFilter }
                .sortedByDescending { it.updatedAt }
                .take(limit.coerceIn(1, 100))
        }

    fun get(context: Context, taskId: String): Task? =
        synchronized(lock) { load(context).firstOrNull { it.id == taskId } }

    /** Begin the task and its first PENDING step. */
    fun start(context: Context, taskId: String): Task? = mutate(context, taskId) { t ->
        if (t.status == Status.PENDING) t.status = Status.RUNNING
        t.steps.firstOrNull { it.status == Status.PENDING }?.let {
            it.status = Status.RUNNING
            it.startedAt = System.currentTimeMillis()
        }
    }

    /** Mark a step done and auto-start the next PENDING step. */
    fun completeStep(context: Context, taskId: String, stepId: String, log: String?): Task? =
        mutate(context, taskId) { t ->
            val s = t.steps.firstOrNull { it.id == stepId }
            if (s != null) {
                s.status = Status.COMPLETED
                s.finishedAt = System.currentTimeMillis()
                log?.let { appendLogLine(s, it) }
            }
            if (t.steps.all { it.status == Status.COMPLETED }) {
                t.status = Status.COMPLETED
                t.updatedAt = System.currentTimeMillis()
            } else {
                t.steps.firstOrNull { it.status == Status.PENDING }?.let {
                    it.status = Status.RUNNING
                    it.startedAt = System.currentTimeMillis()
                }
                if (t.status == Status.PENDING) t.status = Status.RUNNING
            }
        }

    /** Fail a step (and the task) with an error note. */
    fun failStep(context: Context, taskId: String, stepId: String, error: String): Task? =
        mutate(context, taskId) { t ->
            t.steps.firstOrNull { it.id == stepId }?.let { s ->
                s.status = Status.FAILED
                s.finishedAt = System.currentTimeMillis()
                appendLogLine(s, "ERROR: $error")
            }
            t.status = Status.FAILED
            t.error = error
        }

    fun appendLog(context: Context, taskId: String, line: String): Task? =
        mutate(context, taskId) { t ->
            val s = t.steps.lastOrNull { it.status == Status.RUNNING }
                ?: t.steps.lastOrNull()
            s?.let { appendLogLine(it, line) }
        }

    fun cancel(context: Context, taskId: String): Task? =
        mutate(context, taskId) { t ->
            t.status = Status.CANCELLED
            t.steps.filter { it.status == Status.RUNNING }.forEach { s ->
                s.status = Status.CANCELLED
                s.finishedAt = System.currentTimeMillis()
            }
        }

    fun failTask(context: Context, taskId: String, error: String): Task? =
        mutate(context, taskId) { t ->
            t.status = Status.FAILED
            t.error = error
        }

    /** Currently RUNNING tasks (durable across process death — callers can resume). */
    fun running(context: Context): List<Task> = list(context, Status.RUNNING, 50)

    // ── Helpers ──────────────────────────────────────────────────────────
    private fun appendLogLine(s: Step, line: String) {
        s.log.add("${System.currentTimeMillis()} $line")
        if (s.log.size > MAX_LOG_LINES_PER_STEP) {
            s.log.subList(0, s.log.size - MAX_LOG_LINES_PER_STEP).clear()
        }
    }

    /** Load-modify-save under the engine lock; mutation runs only on a found task. */
    private fun mutate(context: Context, taskId: String, mutation: (Task) -> Unit): Task? =
        synchronized(lock) {
            val tasks = load(context)
            val idx = tasks.indexOfFirst { it.id == taskId }
            if (idx < 0) return@synchronized null
            val t = tasks[idx]
            mutation(t)
            t.updatedAt = System.currentTimeMillis()
            tasks[idx] = t
            save(context, tasks)
            t
        }
}
