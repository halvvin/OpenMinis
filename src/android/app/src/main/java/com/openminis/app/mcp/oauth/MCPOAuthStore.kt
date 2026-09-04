package com.openminis.app.mcp.oauth

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.EncryptedPrefsFactory
import org.json.JSONObject

/**
 * [T-android-mcp-oauth] Secret storage for MCP OAuth: the client secret and the
 * issued access / refresh tokens, per server, in EncryptedSharedPreferences —
 * the Android analog of iOS keeping these in the Keychain (distinct from the
 * non-secret [MCPOAuthConfig] that lives in servers.json). All keys are
 * namespaced by server id.
 */
object MCPOAuthStore {

    private const val TAG = "MCPOAuthStore"
    private const val FILE = "mcp_oauth_secrets"

    /** Issued tokens for a server. [expiresAtMs] is 0 when the server gave no
     *  expires_in (treated as "never proactively refresh"). */
    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresAtMs: Long,
    )

    @Volatile
    private var prefsRef: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences =
        prefsRef ?: synchronized(this) {
            prefsRef ?: EncryptedPrefsFactory.safeCreate(context.applicationContext, FILE)
                .also { prefsRef = it }
        }

    private fun secretKey(server: String) = "secret::$server"
    private fun tokensKey(server: String) = "tokens::$server"

    // -- client secret --

    fun setClientSecret(context: Context, server: String, secret: String?) {
        val p = prefs(context)
        if (secret.isNullOrEmpty()) {
            p.edit().remove(secretKey(server)).apply()
        } else {
            p.edit().putString(secretKey(server), secret).apply()
        }
    }

    fun clientSecret(context: Context, server: String): String? =
        prefs(context).getString(secretKey(server), null)?.takeIf { it.isNotEmpty() }

    // -- tokens --

    fun setTokens(context: Context, server: String, tokens: StoredTokens) {
        val json = JSONObject().apply {
            put("access_token", tokens.accessToken)
            tokens.refreshToken?.let { put("refresh_token", it) }
            put("expires_at", if (tokens.expiresAtMs > 0) tokens.expiresAtMs / 1000 else 0L)
        }
        prefs(context).edit().putString(tokensKey(server), json.toString()).apply()
        // [F-A2 fix / P1/P2-21] Materialize the in-guest transport bridge.
        // The Python HTTP transport (minis-mcp-cli transport/http.py) reads
        // /var/minis/mcp-servers/oauth/<server>.json — with fields
        // access_token / refresh_token / expires_at (epoch SECONDS) — and
        // autonomously refreshes against the token endpoint, rewriting the
        // bridge file. Android persisted tokens ONLY in encrypted prefs, so
        // the CLI never saw them and OAuth-protected MCP servers could not
        // complete the flow end-to-end.
        writeBridgeFile(context, server, json)
    }

    /**
     * [F-A2 fix / P1/P2-21] Write the guest-side token bridge file.
     * Location matches OAUTH_DIR in transport/http.py exactly
     * (minis-global/mcp-servers is the host backing of /var/minis/mcp-servers).
     */
    private fun writeBridgeFile(context: Context, server: String, tokens: JSONObject) {
        try {
            val dir = java.io.File(
                context.filesDir,
                "minis-global/mcp-servers/oauth",
            )
            dir.mkdirs()
            val safeName = server.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val tmp = java.io.File(dir, "$safeName.json.tmp")
            tmp.writeText(tokens.toString())
            val dst = java.io.File(dir, "$safeName.json")
            if (!dst.exists() || dst.delete()) {
                if (!tmp.renameTo(dst)) {
                    tmp.copyTo(dst, overwrite = true)
                    tmp.delete()
                }
            }
            dst.setReadable(false, false)
            dst.setReadable(true, true)
            dst.setWritable(false, false)
            dst.setWritable(true, true)
            AppLogger.info(TAG, "MCP OAuth bridge written for '$server'")
        } catch (e: Exception) {
            AppLogger.warning(TAG, "MCP OAuth bridge write failed for '$server': ${e.message}")
        }
    }

    /** [F-A2 fix / P1/P2-21] Remove the bridge file when tokens are cleared. */
    fun clearTokens(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).apply()
        try {
            val safeName = server.replace(Regex("[^A-Za-z0-9._-]"), "_")
            java.io.File(context.filesDir, "minis-global/mcp-servers/oauth/$safeName.json")
                .delete()
        } catch (_: Exception) {}
    }
    fun tokens(context: Context, server: String): StoredTokens? {
        val raw = prefs(context).getString(tokensKey(server), null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            StoredTokens(
                accessToken = o.getString("access_token"),
                refreshToken = o.optString("refresh_token", "").ifBlank { null },
                // Bridge file writes epoch SECONDS ("expires_at"); encrypted
                // prefs keep MILLISECONDS ("expires_at_ms"). Accept either on
                // read so a bridge-round-trip value still parses.
                expiresAtMs = o.optLong("expires_at_ms", 0L)
                    .takeIf { it > 0 }
                    ?: (o.optLong("expires_at", 0L) * 1000L),
            )
        }.onFailure { AppLogger.warning(TAG, "corrupt tokens for $server: ${it.message}") }
            .getOrNull()
    }

    fun isAuthorized(context: Context, server: String): Boolean =
        tokens(context, server)?.accessToken?.isNotEmpty() == true

    /** Forget issued tokens but keep the client secret (sign out, re-auth later). */
    fun signOut(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).apply()
        clearTokens(context, server)
    }

    /** Forget everything for a server — tokens AND client secret (server deleted). */
    fun purge(context: Context, server: String) {
        prefs(context).edit().remove(tokensKey(server)).remove(secretKey(server)).apply()
    }
}
