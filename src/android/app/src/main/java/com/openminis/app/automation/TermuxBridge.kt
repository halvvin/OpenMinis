package com.openminis.app.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [T-termux-bridge] Bridge to the external Termux app (user spec §8-2).
 *
 * Transport: the official Termux `RUN_COMMAND` broadcast intent
 * (`com.termux.RUN_COMMAND` → `com.termux.app.RunCommandService`). The app
 * declares `com.termux.permission.RUN_COMMAND`; the USER must additionally
 * allow external-APK execution inside Termux (Settings → Termux →
 * "Allow external apps") — the in-app guide walks through this.
 *
 * Output round-trip: Termux has no synchronous stdout channel for
 * RUN_COMMAND, so the wrapped script tees stdout+stderr plus a final
 * `EXIT:<code>` marker into an exchange file under a user-configured shared
 * directory (default `/sdcard/MinisFork`). Reading that file requires the
 * app's MANAGE_EXTERNAL_STORAGE grant (Settings → System permissions) and
 * Termux's own storage grant (`termux-setup-storage`).
 */
object TermuxBridge {

    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val TERMUX_SHELL = "/data/data/com.termux/files/usr/bin/sh"

    data class TermuxResult(val ok: Boolean, val output: String)

    fun isTermuxInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Run [command] inside Termux and wait for its output.
     *
     * The exchange file lives in [exchangeDir] (shared storage). Both sides
     * must have storage access; see the class KDoc.
     */
    suspend fun execute(
        context: Context,
        command: String,
        exchangeDir: String = "/sdcard/MinisFork",
        timeoutMs: Long = 60_000L,
    ): TermuxResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled(context)) {
            return@withContext TermuxResult(false, "Termux نصب نیست — ابتدا Termux را از F-Droid نصب کن.")
        }
        val ts = System.currentTimeMillis()
        val outFile = "$exchangeDir/out-$ts.txt"
        val sd = exchangeDir.replace("/sdcard", "/storage/emulated/0")
        // Wrap: capture stdout+stderr and append a machine-readable exit marker.
        val wrapped = "mkdir -p $sd && { ( $command ) ; echo \"EXIT:\$?\" ; } > $sd/out-$ts.txt 2>&1"
        val sent = sendRunCommand(context, wrapped, exchangeDir)
        if (!sent) return@withContext TermuxResult(false, "ارسال دستور به Termux ناموفق بود — «Allow external apps» در تنظیمات Termux فعال است؟")

        val f = File(sd, "out-$ts.txt")
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        while (System.currentTimeMillis() < deadline) {
            delay(500)
            val text = runCatching { f.takeIf { it.exists() }?.readText() }.getOrNull()
            if (text != null && text.contains("EXIT:")) {
                return@withContext TermuxResult(true, text.trim())
            }
            if (text != null && sb.toString() != text) sb.clear().append(text)
        }
        val partial = sb.toString().ifBlank { "(بدون خروجی — مهلت تمام شد)" }
        TermuxResult(false, partial)
    }

    /** Connection test used by the Termux settings screen. */
    suspend fun test(context: Context, exchangeDir: String): TermuxResult =
        execute(context, "echo __TERMUX_OK__ && uname -a", exchangeDir, timeoutMs = 20_000L)

    private fun sendRunCommand(
        context: Context,
        script: String,
        workdir: String,
    ): Boolean = runCatching {
        val intent = Intent(RUN_COMMAND_ACTION).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
            putExtra("RUN_COMMAND_PATH", TERMUX_SHELL)
            putExtra("RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("RUN_COMMAND_WORKDIR", workdir)
            putExtra("RUN_COMMAND_BACKGROUND", true)
        }
        context.startService(intent)
        true
    }.getOrDefault(false)
}
