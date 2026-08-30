package com.openminis.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-keep-working-engine] Persistent settings for the Keep Working Engine
 * (موتور ادامه‌ی خودکار وظایف).
 *
 * v2 additions (user spec §7-rev):
 *  - [intervalSeconds] has NO upper cap — the user may set any gap from 5
 *    seconds up to weeks (UI offers sec/min/hour/day units).
 *  - [chatFilterEnabled] + [targetChats]: when the filter is on, the engine
 *    ONLY auto-continues inside chats whose title matches one of the
 *    user-listed names. Off = every chat, as before.
 *
 * State is stored in SharedPreferences so it survives app restarts.
 */
data class KeepWorkingConfig(
    val enabled: Boolean = false,
    /** Text sent to the model each time the engine continues the task. */
    val command: String = "وظیفه را از جایی که متوقف شده ادامه بده",
    /** Delay between retries, in seconds. No upper cap (user choice). */
    val intervalSeconds: Long = 30L,
    /** Max automatic continuation attempts per task. */
    val maxAttempts: Int = 5,
    /** When true, auto-continue only fires in chats named in [targetChats]. */
    val chatFilterEnabled: Boolean = false,
    /** Chat-title allowlist used when [chatFilterEnabled] is true. */
    val targetChats: Set<String> = emptySet(),
)

class KeepWorkingPrefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): KeepWorkingConfig = KeepWorkingConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        command = prefs.getString(KEY_COMMAND, null) ?: KeepWorkingConfig().command,
        intervalSeconds = prefs.getLong(KEY_INTERVAL, 30L).coerceIn(5L, 31_536_000L),
        maxAttempts = prefs.getInt(KEY_MAX_ATTEMPTS, 5).coerceIn(1, 100),
        chatFilterEnabled = prefs.getBoolean(KEY_CHAT_FILTER, false),
        targetChats = run {
            val arr = runCatching { JSONArray(prefs.getString(KEY_TARGET_CHATS, "[]") ?: "[]") }
            val out = mutableSetOf<String>()
            arr.getOrNull()?.let { a ->
                for (i in 0 until a.length()) out.add(a.optString(i))
            }
            out.filter { it.isNotBlank() }.toSet()
        },
    )

    fun save(config: KeepWorkingConfig) {
        val arr = JSONArray()
        config.targetChats.forEach { arr.put(it) }
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_COMMAND, config.command)
            .putLong(KEY_INTERVAL, config.intervalSeconds)
            .putInt(KEY_MAX_ATTEMPTS, config.maxAttempts)
            .putBoolean(KEY_CHAT_FILTER, config.chatFilterEnabled)
            .putString(KEY_TARGET_CHATS, arr.toString())
            .apply()
    }

    // ── Per-task runtime state (survives process death) ──────────────────

    // [FIX-4] Per-session attempt counters (JSON map) — the old single
    // activeSessionId slot had a cross-chat race: opening chat B orphaned
    // chat A's task state. Now every session tracks its own attempts.
    private val activeSessions: MutableMap<String, Int>
        get() = runCatching {
            val o = JSONObject(prefs.getString(KEY_ACTIVE_SESSIONS, "{}") ?: "{}")
            val out = mutableMapOf<String, Int>()
            for (k in o.keys()) out[k] = o.optInt(k, 0)
            out
        }.getOrDefault(mutableMapOf())

    fun attemptsFor(sessionId: String): Int = activeSessions[sessionId] ?: 0

    fun setAttemptsFor(sessionId: String, attempts: Int) {
        val map = activeSessions.toMutableMap()
        if (attempts <= 0) map.remove(sessionId) else map[sessionId] = attempts
        prefs.edit().putString(KEY_ACTIVE_SESSIONS, JSONObject(map).toString()).apply()
    }

    fun resetTaskState(sessionId: String) = setAttemptsFor(sessionId, 0)

    // ── [FIX-CIRCUIT-BREAKER] Global cooldown — prevents a self-talk loop
    // across sessions from hammering the API (user-reported: opening a new
    // chat can cause the model to talk to itself and 'corrupt' the key —
    // actually the key got rate-limited by a runaway retry loop). When the
    // GLOBAL auto-continue count in a rolling window exceeds the cap, the
    // whole engine pauses for COOLDOWN_MS. Individual per-session counters
    // don't help here — many sessions × many retries still flood the API.

    /** Timestamp (epoch ms) before which the engine stays paused. */
    fun cooldownUntil(): Long = prefs.getLong(KEY_COOLDOWN_UNTIL, 0L)

    /** True when the engine is currently in global cooldown. */
    fun inCooldown(now: Long = System.currentTimeMillis()): Boolean =
        now < cooldownUntil()

    /**
     * Called before every auto-continue. Returns true if this retry is
     * allowed; when the cap is exceeded, arms the cooldown and returns false.
     */
    fun tryAcquireRetry(now: Long = System.currentTimeMillis()): Boolean {
        if (inCooldown(now)) return false
        val windowStart = now - WINDOW_MS
        val recent = recentRetryTimes().filter { it > windowStart }
        if (recent.size >= GLOBAL_CAP) {
            prefs.edit().putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS).apply()
            return false
        }
        prefs.edit()
            .putString(KEY_RETRY_TIMES, JSONArray(recent + now).toString())
            .apply()
        return true
    }

    private fun recentRetryTimes(): List<Long> = runCatching {
        val a = JSONArray(prefs.getString(KEY_RETRY_TIMES, "[]") ?: "[]")
        (0 until a.length()).map { a.optLong(it) }
    }.getOrDefault(emptyList())

    fun clearCooldown() = prefs.edit().remove(KEY_COOLDOWN_UNTIL).apply()

    /** Legacy single-slot accessors — kept for migration of old prefs. */
    @Deprecated("Use attemptsFor/setAttemptsFor")

    companion object {
        private const val PREFS_NAME = "keep_working_prefs"
        private const val KEY_ENABLED = "kw.enabled"
        private const val KEY_COMMAND = "kw.command"
        private const val KEY_INTERVAL = "kw.interval_seconds"
        private const val KEY_MAX_ATTEMPTS = "kw.max_attempts"
        private const val KEY_CHAT_FILTER = "kw.chat_filter_enabled"
        private const val KEY_TARGET_CHATS = "kw.target_chats"
        private const val KEY_ACTIVE_SESSIONS = "kw.active_sessions"
        private const val KEY_ATTEMPTS_USED = "kw.attempts_used"
        // [FIX-CIRCUIT-BREAKER] Global retry budget.
        private const val KEY_RETRY_TIMES = "kw.retry_times"
        private const val KEY_COOLDOWN_UNTIL = "kw.cooldown_until"
        /** Max global auto-continues per rolling window. */
        private const val GLOBAL_CAP = 8
        /** Rolling window for [GLOBAL_CAP]. */
        private const val WINDOW_MS = 10 * 60_000L
        /** Pause duration after the cap is exceeded. */
        private const val COOLDOWN_MS = 5 * 60_000L

        @Volatile private var instance: KeepWorkingPrefs? = null

        fun get(context: Context): KeepWorkingPrefs =
            instance ?: synchronized(this) {
                instance ?: KeepWorkingPrefs(context.applicationContext).also { instance = it }
            }
    }
}
