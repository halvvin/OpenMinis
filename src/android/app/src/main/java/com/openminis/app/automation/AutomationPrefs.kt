package com.openminis.app.automation

import android.content.Context
import org.json.JSONObject

/**
 * [T-automation-hub] Settings for the "اتوماسیون و ایجنت‌ها" hub (user spec §8).
 *
 * Three fully independent features, each gated by its own manual switch:
 *  1. [alwaysOnEnabled]  — off-device execution on a user-provided server
 *  2. [termuxEnabled]    — run AI commands inside the external Termux app
 *  3. (Agent Manager has no global switch — it is a passive registry UI;
 *     nothing runs without the user explicitly pressing install/run.)
 *
 * Plus the §3-6 Reverse API Engineer toggle which lives with the browser.
 * NOTHING here is enabled by default.
 */
data class AlwaysOnConfig(
    val serverType: String = "vps",          // vps | railway | render | termux
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val secret: String = "",                 // password or API token
    val useSsh: Boolean = true,
)

data class TermuxConfig(
    val exchangeDir: String = "/sdcard/MinisFork",
    val useTermuxApi: Boolean = false,       // prefer Termux:API helpers when true
)

class AutomationPrefs private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Master switches (all default OFF) ────────────────────────────────
    var alwaysOnEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALWAYS_ON, false)
        set(v) = prefs.edit().putBoolean(KEY_ALWAYS_ON, v).apply()

    var termuxEnabled: Boolean
        get() = prefs.getBoolean(KEY_TERMUX, false)
        set(v) = prefs.edit().putBoolean(KEY_TERMUX, v).apply()

    /** §3-6 Reverse API Engineer — browser-scoped toggle, default OFF. */
    var reverseApiEnabled: Boolean
        get() = prefs.getBoolean(KEY_REVERSE_API, false)
        set(v) = prefs.edit().putBoolean(KEY_REVERSE_API, v).apply()

    // ── Floating AI panel geometry (persists across sessions) ────────────
    var floatPanelOpen: Boolean
        get() = prefs.getBoolean(KEY_FP_OPEN, false)
        set(v) = prefs.edit().putBoolean(KEY_FP_OPEN, v).apply()
    var floatPanelX: Float
        get() = prefs.getFloat(KEY_FP_X, 0f)
        set(v) = prefs.edit().putFloat(KEY_FP_X, v).apply()
    var floatPanelY: Float
        get() = prefs.getFloat(KEY_FP_Y, 250f)
        set(v) = prefs.edit().putFloat(KEY_FP_Y, v).apply()
    var floatPanelW: Int
        get() = prefs.getInt(KEY_FP_W, 300)
        set(v) = prefs.edit().putInt(KEY_FP_W, v).apply()
    var floatPanelH: Int
        get() = prefs.getInt(KEY_FP_H, 380)
        set(v) = prefs.edit().putInt(KEY_FP_H, v).apply()

    // ── Always-On server config ──────────────────────────────────────────
    fun loadAlwaysOn(): AlwaysOnConfig = AlwaysOnConfig(
        serverType = prefs.getString(KEY_AO_TYPE, "vps") ?: "vps",
        host = prefs.getString(KEY_AO_HOST, "") ?: "",
        port = prefs.getInt(KEY_AO_PORT, 22),
        username = prefs.getString(KEY_AO_USER, "") ?: "",
        secret = prefs.getString(KEY_AO_SECRET, "") ?: "",
        useSsh = prefs.getBoolean(KEY_AO_SSH, true),
    )

    fun saveAlwaysOn(c: AlwaysOnConfig) {
        prefs.edit()
            .putString(KEY_AO_TYPE, c.serverType)
            .putString(KEY_AO_HOST, c.host)
            .putInt(KEY_AO_PORT, c.port)
            .putString(KEY_AO_USER, c.username)
            .putString(KEY_AO_SECRET, c.secret)
            .putBoolean(KEY_AO_SSH, c.useSsh)
            .apply()
    }

    // ── Termux config ────────────────────────────────────────────────────
    fun loadTermux(): TermuxConfig = TermuxConfig(
        exchangeDir = prefs.getString(KEY_TX_DIR, "/sdcard/MinisFork") ?: "/sdcard/MinisFork",
        useTermuxApi = prefs.getBoolean(KEY_TX_API, false),
    )

    fun saveTermux(c: TermuxConfig) {
        prefs.edit()
            .putString(KEY_TX_DIR, c.exchangeDir)
            .putBoolean(KEY_TX_API, c.useTermuxApi)
            .apply()
    }

    // ── Always-On runtime log (last N lines, shown in UI) ────────────────
    fun appendLog(line: String) {
        val cur = prefs.getString(KEY_AO_LOG, "") ?: ""
        val stamped = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()) + "  " + line
        val next = (cur + "\n" + stamped).split('\n').takeLast(200).joinToString("\n")
        prefs.edit().putString(KEY_AO_LOG, next).apply()
    }

    fun readLog(): String = prefs.getString(KEY_AO_LOG, "") ?: ""

    fun clearLog() = prefs.edit().putString(KEY_AO_LOG, "").apply()

    companion object {
        private const val PREFS_NAME = "automation_prefs"
        private const val KEY_ALWAYS_ON = "auto.alwayson.enabled"
        private const val KEY_TERMUX = "auto.termux.enabled"
        private const val KEY_REVERSE_API = "auto.reverse_api.enabled"
        private const val KEY_AO_TYPE = "ao.server_type"
        private const val KEY_AO_HOST = "ao.host"
        private const val KEY_AO_PORT = "ao.port"
        private const val KEY_AO_USER = "ao.user"
        private const val KEY_AO_SECRET = "ao.secret"
        private const val KEY_AO_SSH = "ao.use_ssh"
        private const val KEY_TX_DIR = "tx.exchange_dir"
        private const val KEY_TX_API = "tx.use_api"
        private const val KEY_AO_LOG = "ao.log"
        private const val KEY_FP_OPEN = "fp.open"
        private const val KEY_FP_X = "fp.x"
        private const val KEY_FP_Y = "fp.y"
        private const val KEY_FP_W = "fp.w"
        private const val KEY_FP_H = "fp.h"

        @Volatile private var instance: AutomationPrefs? = null

        fun get(context: Context): AutomationPrefs =
            instance ?: synchronized(this) {
                instance ?: AutomationPrefs(context.applicationContext).also { instance = it }
            }
    }
}

/** JSON round-trip helpers used by the cloud sync layer. */
fun AlwaysOnConfig.toJson(): String = JSONObject().apply {
    put("serverType", serverType); put("host", host); put("port", port)
    put("username", username); put("useSsh", useSsh)
    // NOTE: secret intentionally NOT exported to cloud sync.
}.toString()

fun TermuxConfig.toJson(): String = JSONObject().apply {
    put("exchangeDir", exchangeDir); put("useTermuxApi", useTermuxApi)
}.toString()
