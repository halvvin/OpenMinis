package com.openminis.app.data

import android.content.Context
import org.json.JSONObject

/**
 * [T-keep-working-engine] Persistent settings for the Keep Working Engine
 * (موتور ادامه‌ی خودکار وظایف).
 *
 * When enabled, a failed / interrupted turn (network loss, rate limit,
 * provider error, app restart mid-task) is automatically continued by
 * re-sending the configured continuation command after [intervalSeconds],
 * up to [maxAttempts] times per task.
 *
 * State is stored in SharedPreferences so it survives app restarts.
 */
data class KeepWorkingConfig(
    val enabled: Boolean = false,
    /** Text sent to the model each time the engine continues the task. */
    val command: String = "وظیفه را از جایی که متوقف شده ادامه بده",
    /** Delay between retries, in seconds. */
    val intervalSeconds: Int = 30,
    /** Max automatic continuation attempts per task. */
    val maxAttempts: Int = 5,
)

class KeepWorkingPrefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): KeepWorkingConfig = KeepWorkingConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        command = prefs.getString(KEY_COMMAND, null) ?: KeepWorkingConfig().command,
        intervalSeconds = prefs.getInt(KEY_INTERVAL, 30).coerceIn(5, 3600),
        maxAttempts = prefs.getInt(KEY_MAX_ATTEMPTS, 5).coerceIn(1, 100),
    )

    fun save(config: KeepWorkingConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_COMMAND, config.command)
            .putInt(KEY_INTERVAL, config.intervalSeconds)
            .putInt(KEY_MAX_ATTEMPTS, config.maxAttempts)
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
        private const val KEY_ACTIVE_SESSION = "kw.active_session"
        private const val KEY_ATTEMPTS_USED = "kw.attempts_used"

        @Volatile private var instance: KeepWorkingPrefs? = null

        fun get(context: Context): KeepWorkingPrefs =
            instance ?: synchronized(this) {
                instance ?: KeepWorkingPrefs(context.applicationContext).also { instance = it }
            }
    }
}
