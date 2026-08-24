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

    /** Session id of the task the engine is currently shepherding, or "". */
    var activeSessionId: String
        get() = prefs.getString(KEY_ACTIVE_SESSION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACTIVE_SESSION, value).apply()

    /** Continuations already fired for the active task. */
    var attemptCount: Int
        get() = prefs.getInt(KEY_ATTEMPTS_USED, 0)
        set(value) = prefs.edit().putInt(KEY_ATTEMPTS_USED, value).apply()

    fun resetTaskState() {
        prefs.edit().putString(KEY_ACTIVE_SESSION, "").putInt(KEY_ATTEMPTS_USED, 0).apply()
    }

    companion object {
        private const val PREFS_NAME = "keep_working_prefs"
        private const val KEY_ENABLED = "kw.enabled"
        private const val KEY_COMMAND = "kw.command"
        private const val KEY_INTERVAL = "kw.interval_seconds"
        private const val KEY_MAX_ATTEMPTS = "kw.max_attempts"
        private const val KEY_CHAT_FILTER = "kw.chat_filter_enabled"
        private const val KEY_TARGET_CHATS = "kw.target_chats"
        private const val KEY_ACTIVE_SESSION = "kw.active_session"
        private const val KEY_ATTEMPTS_USED = "kw.attempts_used"

        @Volatile private var instance: KeepWorkingPrefs? = null

        fun get(context: Context): KeepWorkingPrefs =
            instance ?: synchronized(this) {
                instance ?: KeepWorkingPrefs(context.applicationContext).also { instance = it }
            }
    }
}
