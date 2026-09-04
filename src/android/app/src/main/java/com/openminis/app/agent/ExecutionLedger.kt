package com.openminis.app.agent

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * [F-A3 / P1-01, EXECUTION-STATE-*, TOOL-REPLAY-01] Durable Execution Ledger.
 *
 * The FINAL forensic audit's top root cause: tool executions had NO persistent
 * identity, so after a crash the system could not distinguish "never ran" from
 * "ran but result lost", and recovery replayed continuations blindly —
 * potentially repeating non-idempotent side effects (files, messages, calendar
 * events, downloads, MCP mutations).
 *
 * What this ledger provides:
 *  1. [begin]/[complete] — every ChatViewModel tool execution is recorded
 *     RUNNING before dispatch and COMPLETED/FAILED after, persisted to
 *     app-private JSON with an atomic tmp+rename write.
 *  2. Startup reconciliation ([markInterruptedAtStartup], called from
 *     MinisApp.onCreate) — entries still RUNNING from the previous process
 *     become INTERRUPTED: the side effect MAY have landed but the result is
 *     unknown. Recovery surfaces this instead of silently replaying.
 *  3. In-flight duplicate rejection ([findRunningDuplicate]) — an identical
 *     call (same tool + same args hash) issued while the first is still
 *     RUNNING is blocked instead of double-firing. This is the practical
 *     replay guard for recovery paths that re-invoke the same tool call.
 *  4. Bounded recent-completed suppression ([recentCompletedDedupe] /
 *     [noteSideEffect]) — exact retries of side-effecting operations
 *     (chat_send, calendar create) inside a short window are suppressed.
 *
 * Honesty note (also the audit's framing): this is a ledger + replay guard,
 * NOT a full exactly-once transaction coordinator — true idempotency of
 * external systems needs provider-side support. But recovery is no longer
 * blind: ambiguity is recorded and duplicates are blocked.
 */
object ExecutionLedger {

    private const val TAG = "ExecLedger"
    private const val MAX_ENTRIES = 400

    enum class State { RUNNING, COMPLETED, FAILED, INTERRUPTED }

    data class Entry(
        val id: String,
        val sessionId: String,
        val toolName: String,
        val dedupeKey: String,
        val state: State,
        val startedAt: Long,
        var finishedAt: Long = 0L,
    )

    private val lock = Any()

    // ── Persistence ──────────────────────────────────────────────────────
    private fun file(context: Context) = File(context.filesDir, "execution_ledger.json")

    private fun load(context: Context): MutableList<Entry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        id = o.getString("id"),
                        sessionId = o.optString("sid"),
                        toolName = o.optString("tool"),
                        dedupeKey = o.optString("key"),
                        state = runCatching { State.valueOf(o.getString("state")) }
                            .getOrDefault(State.INTERRUPTED),
                        startedAt = o.optLong("startedAt", 0L),
                        finishedAt = o.optLong("finishedAt", 0L),
                    )
                )
            }
            out
        }.getOrElse {
            Log.w(TAG, "ledger load failed: ${it.message}")
            mutableListOf()
        }
    }

    private fun save(context: Context, entries: List<Entry>) {
        try {
            val trimmed = if (entries.size > MAX_ENTRIES) {
                entries.subList(entries.size - MAX_ENTRIES, entries.size)
            } else entries
            val arr = JSONArray()
            for (e in trimmed) {
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("sid", e.sessionId)
                        .put("tool", e.toolName)
                        .put("key", e.dedupeKey)
                        .put("state", e.state.name)
                        .put("startedAt", e.startedAt)
                        .put("finishedAt", e.finishedAt)
                )
            }
            val dst = file(context)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, dst.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ledger save failed: ${e.message}")
        }
    }

    // ── Keys ─────────────────────────────────────────────────────────────
    /**
     * Content hash of (tool, args). NOTE: JSON key order differences across
     * retries produce different hashes — this is a best-effort exact-replay
     * detector, not a canonical-args normalizer.
     */
    fun dedupeKey(toolName: String, argsJson: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((toolName + "\u0000" + argsJson.trim()).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ── Core API ─────────────────────────────────────────────────────────
    /** Record dispatch of a tool execution. Returns the ledger entry id. */
    fun begin(context: Context, sessionId: String, toolName: String, argsJson: String): Entry =
        synchronized(lock) {
            val e = Entry(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                toolName = toolName,
                dedupeKey = dedupeKey(toolName, argsJson),
                state = State.RUNNING,
                startedAt = System.currentTimeMillis(),
            )
            val entries = load(context)
            entries.add(e)
            save(context, entries)
            e
        }

    /** Commit the outcome. Call in a finally so crashes before this leave RUNNING (→ INTERRUPTED at next startup). */
    fun complete(context: Context, id: String, success: Boolean) {
        synchronized(lock) {
            val entries = load(context)
            val idx = entries.indexOfLast { it.id == id }
            if (idx >= 0) {
                val e = entries[idx]
                entries[idx] = e.copy(
                    state = if (success) State.COMPLETED else State.FAILED,
                    finishedAt = System.currentTimeMillis(),
                )
                save(context, entries)
            }
        }
    }

    /**
     * An identical call (same tool + args) still RUNNING? Used by the agent
     * loop to reject duplicate in-flight dispatches instead of double-firing.
     */
    fun findRunningDuplicate(context: Context, toolName: String, argsJson: String, excludeId: String): Entry? =
        synchronized(lock) {
            val key = dedupeKey(toolName, argsJson)
            load(context).lastOrNull {
                it.id != excludeId && it.state == State.RUNNING && it.dedupeKey == key
            }
        }

    /**
     * True when an IDENTICAL COMPLETED execution of [toolName] with [argsJson]
     * finished within [windowMs]. Used to suppress exact retries of
     * side-effecting tools (a recovery replay produces byte-identical args).
     */
    fun recentCompletedDedupe(context: Context, toolName: String, argsJson: String, windowMs: Long): Boolean =
        synchronized(lock) {
            val key = dedupeKey(toolName, argsJson)
            val now = System.currentTimeMillis()
            load(context).any {
                it.state == State.COMPLETED && it.dedupeKey == key &&
                    it.finishedAt > 0 && now - it.finishedAt <= windowMs
            }
        }

    /**
     * CLI-plane side effects (offload handlers) don't pass through the agent
     * loop, so they self-record: returns true (duplicate — caller must abort)
     * when an identical [dedupeRaw] was already noted within [windowMs];
     * otherwise records it now and returns false. Atomic check+record under
     * one lock, unlike a naive query-then-insert.
     */
    fun noteSideEffect(context: Context, scope: String, dedupeRaw: String, windowMs: Long): Boolean =
        synchronized(lock) {
            val key = dedupeKey(scope, dedupeRaw)
            val now = System.currentTimeMillis()
            val entries = load(context)
            val duplicate = entries.any {
                it.state == State.COMPLETED && it.dedupeKey == key &&
                    it.finishedAt > 0 && now - it.finishedAt <= windowMs
            }
            if (!duplicate) {
                entries.add(
                    Entry(
                        id = UUID.randomUUID().toString(),
                        sessionId = "",
                        toolName = scope,
                        dedupeKey = key,
                        state = State.COMPLETED,
                        startedAt = now,
                        finishedAt = now,
                    )
                )
                save(context, entries)
            }
            duplicate
        }

    /** Count of ambiguous (interrupted) executions for a session since [since]. */
    fun interruptedSince(context: Context, sessionId: String, since: Long): Int =
        synchronized(lock) {
            load(context).count {
                it.state == State.INTERRUPTED && it.sessionId == sessionId && it.startedAt >= since
            }
        }

    /**
     * Startup reconciliation: RUNNING entries belong to a dead process — the
     * side effect may have landed but the result is unknown. Mark INTERRUPTED.
     * Call EXACTLY ONCE per process (MinisApp.onCreate), never per session
     * load, or fresh RUNNING entries would be wrongly invalidated.
     */
    fun markInterruptedAtStartup(context: Context) {
        synchronized(lock) {
            val entries = load(context)
            var changed = 0
            for (i in entries.indices) {
                if (entries[i].state == State.RUNNING) {
                    entries[i] = entries[i].copy(
                        state = State.INTERRUPTED,
                        finishedAt = System.currentTimeMillis(),
                    )
                    changed++
                }
            }
            if (changed > 0) {
                save(context, entries)
                Log.w(TAG, "startup reconciliation: $changed RUNNING execution(s) → INTERRUPTED (result unknown)")
            }
        }
    }
}
