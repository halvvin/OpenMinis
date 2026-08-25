package com.openminis.app.data

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * [T-cloud-sync] Personal-cloud sync via a user-provided WebDAV endpoint
 * (user spec: «فضای ابری شخصی کاربر»).
 *
 * Works with any WebDAV-capable service the user owns (self-hosted
 * Nextcloud, Apache/Nginx WebDAV, InfiniCloud, Mega-webdav bridges, ...).
 * The endpoint + credentials live only in app-private storage. Nothing is
 * ever sent to any Minis-operated server — the app has none.
 *
 * Artifacts synced:
 *  - user_profile.json   (Settings → پروفایل کاربر)
 *  - keep_working.json   (موتور ادامه خودکار config — بدون state داخلی)
 *  - agents.json         (مدیریت ایجنت‌ها)
 *  - soul.md             (شخصیت)
 *  - skills.zip          (کل پوشه‌ی Skills)
 */
object CloudSyncStore {

    data class Config(
        val baseUrl: String = "",   // e.g. https://cloud.example.com/remote.php/dav/files/user/minis
        val username: String = "",
        val password: String = "",
    )

    private const val PREFS = "cloud_sync_prefs"

    fun load(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            baseUrl = p.getString("base", "") ?: "",
            username = p.getString("user", "") ?: "",
            password = p.getString("pass", "") ?: "",
        )
    }

    fun save(context: Context, c: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base", c.baseUrl)
            .putString("user", c.username)
            .putString("pass", c.password)
            .apply()
    }

    data class SyncResult(val ok: Boolean, val message: String)

    // ── Low-level WebDAV ─────────────────────────────────────────────────

    private fun open(cfg: Config, method: String, path: String): HttpURLConnection {
        val url = URL(cfg.baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        val auth = "${cfg.username}:${cfg.password}"
        conn.setRequestProperty(
            "Authorization",
            "Basic " + Base64.encodeToString(auth.toByteArray(), Base64.NO_WRAP),
        )
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        return conn
    }

    private fun put(cfg: Config, path: String, bytes: ByteArray): SyncResult = try {
        val conn = open(cfg, "PUT", path)
        conn.doOutput = true
        conn.outputStream.use { it.write(bytes) }
        SyncResult(conn.responseCode in 200..299, "PUT $path → HTTP ${conn.responseCode}")
    } catch (e: Exception) {
        SyncResult(false, "PUT $path → ${e.message}")
    }

    private fun get(cfg: Config, path: String): Pair<Boolean, ByteArray> = try {
        val conn = open(cfg, "GET", path)
        val ok = conn.responseCode in 200..299
        ok to (if (ok) conn.inputStream.use { it.readBytes() } else ByteArray(0))
    } catch (e: Exception) {
        false to ByteArray(0)
    }

    private fun getWithCode(cfg: Config, path: String): Triple<Int, ByteArray, String> = try {
        val conn = open(cfg, "GET", path)
        val code = conn.responseCode
        Triple(code, if (code in 200..299) conn.inputStream.use { it.readBytes() } else ByteArray(0), "HTTP $code")
    } catch (e: Exception) {
        Triple(0, ByteArray(0), e.message ?: "خطای اتصال")
    }

    // ── High-level sync ──────────────────────────────────────────────────

    fun pushAll(context: Context, cfg: Config): SyncResult {
        if (cfg.baseUrl.isBlank()) return SyncResult(false, "آدرس WebDAV تنظیم نشده")
        val results = mutableListOf<SyncResult>()

        // [FIX-9] Push in dependency order: soul → profile → keep_working → agents → skills.
        runCatching {
            val soul = File(context.filesDir, "minis-global/memory/SOUL.md")
            if (soul.exists()) results += put(cfg, "soul.md", soul.readBytes())
        }
        UserProfileStore.get(context).load().let {
            results += put(cfg, "user_profile.json", it.toJson().toByteArray())
        }
        KeepWorkingPrefs.get(context).load().let { c ->
            val json = JSONObject()
                .put("enabled", c.enabled)
                .put("command", c.command)
                .put("intervalSeconds", c.intervalSeconds)
                .put("maxAttempts", c.maxAttempts)
                .put("chatFilterEnabled", c.chatFilterEnabled)
                .put("targetChats", JSONArray(c.targetChats.toList()))
            results += put(cfg, "keep_working.json", json.toString().toByteArray())
        }
        runCatching {
            val agents = File(context.filesDir, "automation/agents.json")
            if (agents.exists()) results += put(cfg, "agents.json", agents.readBytes())
        }
        runCatching {
            val skillsDir = File(context.filesDir, "minis-global/skills")
            if (skillsDir.isDirectory) {
                results += put(cfg, "skills.zip", zipDir(skillsDir))
            }
        }
        val failed = results.filterNot { it.ok }
        return if (failed.isEmpty()) {
            SyncResult(true, "همه‌چیز سینک شد ✓ (${results.size} فایل)")
        } else {
            SyncResult(false, "${failed.size} مورد ناموفق:\n" + failed.joinToString("\n") { it.message })
        }
    }

    // [FIX-9] Dependency order: soul → profile → keep_working → agents → skills.
    private val PULL_ORDER = listOf("soul.md", "user_profile.json", "keep_working.json", "agents.json", "skills.zip")

    fun pullAll(context: Context, cfg: Config): SyncResult {
        if (cfg.baseUrl.isBlank()) return SyncResult(false, "آدرس WebDAV تنظیم نشده")
        val results = mutableListOf<SyncResult>()

        // [FIX-8] Probe the root first — a 5xx here means SERVER trouble, and
        // the user must see that instead of a misleading "no files".
        val (probeCode, _, probeMsg) = getWithCode(cfg, "")
        if (probeCode >= 500) {
            return SyncResult(false, "❌ خطای سرور ($probeMsg) — سرور WebDAV مشکل دارد، نه تنظیمات تو.")
        }

        get(cfg, "user_profile.json").takeIf { it.first }?.let { (_, bytes) ->
            runCatching { UserProfileStore.get(context).save(UserProfile.fromJson(String(bytes))) }
                .onSuccess { results += SyncResult(true, "profile") }
        }
        get(cfg, "keep_working.json").takeIf { it.first }?.let { (_, bytes) ->
            runCatching {
                val o = JSONObject(String(bytes))
                val cur = KeepWorkingPrefs.get(context).load()
                KeepWorkingPrefs.get(context).save(
                    cur.copy(
                        enabled = o.optBoolean("enabled", cur.enabled),
                        command = o.optString("command", cur.command),
                        intervalSeconds = o.optLong("intervalSeconds", cur.intervalSeconds),
                        maxAttempts = o.optInt("maxAttempts", cur.maxAttempts),
                        chatFilterEnabled = o.optBoolean("chatFilterEnabled", cur.chatFilterEnabled),
                        targetChats = o.optJSONArray("targetChats")?.let { a ->
                            (0 until a.length()).map { a.optString(it) }.toSet()
                        } ?: emptySet(),
                    )
                )
            }.onSuccess { results += SyncResult(true, "keep_working") }
        }
        get(cfg, "agents.json").takeIf { it.first }?.let { (_, bytes) ->
            runCatching {
                val f = File(context.filesDir, "automation/agents.json")
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
            }.onSuccess { results += SyncResult(true, "agents") }
        }
        get(cfg, "soul.md").takeIf { it.first }?.let { (_, bytes) ->
            runCatching {
                val f = File(context.filesDir, "minis-global/memory/SOUL.md")
                f.parentFile?.mkdirs(); f.writeBytes(bytes)
            }.onSuccess { results += SyncResult(true, "soul") }
        }
        get(cfg, "skills.zip").takeIf { it.first }?.let { (_, bytes) ->
            runCatching {
                val skillsDir = File(context.filesDir, "minis-global/skills")
                skillsDir.mkdirs()
                unzipTo(bytes, skillsDir)
            }.onSuccess { results += SyncResult(true, "skills") }
        }

        // [FIX-8] Report per-artifact server errors instead of silence.
        val serverErrors = PULL_ORDER.mapNotNull { path ->
            val (code, _, msg) = getWithCode(cfg, path)
            if (code >= 500) "$path → $msg" else null
        }
        serverErrors.forEach { results += SyncResult(false, it) }

        return if (results.isEmpty()) {
            SyncResult(false, "هیچ فایلی روی سرور پیدا نشد")
        } else {
            SyncResult(true, "بازیابی شد: " + results.joinToString(", ") { it.message })
        }
    }

    /** Quick reachability + auth probe (PROPFIND). */
    fun test(cfg: Config): SyncResult = try {
        val conn = open(cfg, "PROPFIND", "")
        SyncResult(
            conn.responseCode in 200..299 || conn.responseCode == 207,
            "HTTP ${conn.responseCode}",
        )
    } catch (e: Exception) {
        SyncResult(false, e.message ?: "خطای اتصال")
    }

    // ── zip helpers ──────────────────────────────────────────────────────

    private fun zipDir(dir: File): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(dir).path
                zos.putNextEntry(ZipEntry(rel))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun unzipTo(bytes: ByteArray, target: File) {
        ZipInputStream(bytes.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(target, e.name)
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
    }
}
