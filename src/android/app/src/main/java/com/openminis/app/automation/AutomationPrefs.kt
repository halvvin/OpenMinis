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
    // [F-A1 cleanup] useTermuxApi removed — it was a dead option: declared,
    // saved, and synced to cloud JSON, but TermuxBridge never read it.
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

    /** [T-cross-chat] Cross-chat communication tools — default OFF. */
    var crossChatEnabled: Boolean
        get() = prefs.getBoolean(KEY_CROSS_CHAT, false)
        set(v) = prefs.edit().putBoolean(KEY_CROSS_CHAT, v).apply()

    /**
     * [FIX-NEW-5] Cross-chat granular mode: 0=OFF, 1=READ_ONLY
     * (list/read/watch — no chat_send), 2=READ_WRITE (all 5 tools).
     * Migration: legacy boolean ON → mode 2; absent → mode 0.
     */
    var crossChatMode: Int
        get() = prefs.getInt(KEY_CROSS_CHAT_MODE, if (prefs.getBoolean(KEY_CROSS_CHAT, false)) 2 else 0)
        set(v) {
            prefs.edit()
                .putInt(KEY_CROSS_CHAT_MODE, v)
                .putBoolean(KEY_CROSS_CHAT, v != 0)
                .apply()
        }

    // ── Floating AI panel geometry — REMOVED [F-A1 cleanup] ─────────────
    // floatPanelOpen/X/Y/W/H belonged to the in-app FloatingAssistantOverlay,
    // which was dead code (imported in AppNavigation but never called) and has
    // been deleted. The system-wide FloatingAssistantService manages its own
    // WindowManager layout params.

    // ── Smart Assistant floating window ─────────────────────────────────
    /** Master switch for the floating assistant (default OFF).
     *  Read by AlarmReceiver's BOOT_COMPLETED branch so the service comes
     *  back up after a reboot / process death — previously this pref had no
     *  startup reader, leaving the toggle lying about the service state. */
    var assistantEnabled: Boolean
        get() = prefs.getBoolean(KEY_FA_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FA_ENABLED, v).apply()

    // ── [T-execution-modes] Execution mode (0=AUTO, 1=PLANNING, 2=ACCEPT) ──
    var executionMode: Int
        get() = prefs.getInt(KEY_EXEC_MODE, 0)
        set(v) = prefs.edit().putInt(KEY_EXEC_MODE, v).apply()

    /** Whether the assistant panel is currently expanded — REMOVED [F-A1
     *  cleanup]: assistantOpen/X/Y only ever had readers in the deleted
     *  in-app overlay. assistantW/H stay: the system overlay panel uses
     *  them for its minimum expanded size. */

    var assistantW: Int
        get() = prefs.getInt(KEY_FA_W, 340)
        set(v) = prefs.edit().putInt(KEY_FA_W, v).apply()
    var assistantH: Int
        get() = prefs.getInt(KEY_FA_H, 460)
        set(v) = prefs.edit().putInt(KEY_FA_H, v).apply()
    /** Last selected model entry id inside the floating assistant. */
    var assistantModelEntryId: String
        get() = prefs.getString(KEY_FA_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_FA_MODEL, v).apply()

    // ── Always-On server config ──────────────────────────────────────────
    // [F-A1 security] The SSH secret now lives in EncryptedSharedPreferences
    // (same Keystore-backed wrapper as provider API keys / OAuth tokens) —
    // it used to sit in PLAIN SharedPreferences next to allowBackup=true.
    // Migration: first read after this change moves the old plaintext value
    // into the encrypted store and deletes the plaintext key.
    private val securePrefs: android.content.SharedPreferences by lazy {
        com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "automation_secure")
    }

    private var alwaysOnSecret: String
        get() {
            var s = securePrefs.getString(KEY_AO_SECRET, null)
            if (s == null) {
                // One-time migration from the legacy plaintext slot.
                s = prefs.getString(KEY_AO_SECRET, null)
                if (!s.isNullOrEmpty()) {
                    securePrefs.edit().putString(KEY_AO_SECRET, s).apply()
                    prefs.edit().remove(KEY_AO_SECRET).apply()
                }
            }
            return s ?: ""
        }
        set(v) {
            securePrefs.edit().putString(KEY_AO_SECRET, v).apply()
            prefs.edit().remove(KEY_AO_SECRET).apply()
        }

    fun loadAlwaysOn(): AlwaysOnConfig = AlwaysOnConfig(
        serverType = prefs.getString(KEY_AO_TYPE, "vps") ?: "vps",
        host = prefs.getString(KEY_AO_HOST, "") ?: "",
        port = prefs.getInt(KEY_AO_PORT, 22),
        username = prefs.getString(KEY_AO_USER, "") ?: "",
        secret = alwaysOnSecret,
        useSsh = prefs.getBoolean(KEY_AO_SSH, true),
    )

    fun saveAlwaysOn(c: AlwaysOnConfig) {
        alwaysOnSecret = c.secret
        prefs.edit()
            .putString(KEY_AO_TYPE, c.serverType)
            .putString(KEY_AO_HOST, c.host)
            .putInt(KEY_AO_PORT, c.port)
            .putString(KEY_AO_USER, c.username)
            .putBoolean(KEY_AO_SSH, c.useSsh)
            .apply()
    }

    // ── Termux config ────────────────────────────────────────────────────
    fun loadTermux(): TermuxConfig = TermuxConfig(
        exchangeDir = prefs.getString(KEY_TX_DIR, "/sdcard/MinisFork") ?: "/sdcard/MinisFork",
    )

    fun saveTermux(c: TermuxConfig) {
        prefs.edit()
            .putString(KEY_TX_DIR, c.exchangeDir)
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
        private const val KEY_CROSS_CHAT = "auto.cross_chat.enabled"
        private const val KEY_CROSS_CHAT_MODE = "auto.cross_chat.mode"
        private const val KEY_AO_TYPE = "ao.server_type"
        private const val KEY_AO_HOST = "ao.host"
        private const val KEY_AO_PORT = "ao.port"
        private const val KEY_AO_USER = "ao.user"
        // [F-A1 security] KEY_AO_SECRET now lives in the encrypted
        // "automation_secure" prefs file, not in the plaintext store.
        private const val KEY_AO_SSH = "ao.use_ssh"
        private const val KEY_TX_DIR = "tx.exchange_dir"
        private const val KEY_AO_LOG = "ao.log"
        private const val KEY_FA_ENABLED = "fa.enabled"
        private const val KEY_FA_W = "fa.w"
        private const val KEY_FA_H = "fa.h"
        private const val KEY_FA_MODEL = "fa.model"
        private const val KEY_EXEC_MODE = "fa.exec_mode"

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
    put("exchangeDir", exchangeDir)
}.toString()
