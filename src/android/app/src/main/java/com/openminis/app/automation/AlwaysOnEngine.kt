package com.openminis.app.automation

import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-always-on] Off-device task continuation (user spec §8-1).
 *
 * The in-app sandbox is ephemeral — long tasks die with the app process.
 * When «اجرای همیشگی» is enabled, this engine mirrors task state and
 * project files to a server the USER configures (VPS / Railway / Render /
 * another Termux device), so work survives app closure and reboots.
 *
 * Everything runs through the app's own sandbox shell (`ssh`, `scp`, `rsync`
 * — installable via `apk add openssh-client` on first use). The secret is
 * stored only in app-private SharedPreferences and is NEVER synced to the
 * cloud or included in logs.
 */
object AlwaysOnEngine {

    data class CheckResult(val ok: Boolean, val detail: String)

    private const val REMOTE_STATE_DIR = "~/minis-fork-state"

    /** One-shot connectivity test through the sandbox shell. */
    suspend fun testConnection(config: AlwaysOnConfig): CheckResult =
        withContext(Dispatchers.IO) {
            if (config.host.isBlank()) return@withContext CheckResult(false, "آدرس سرور خالی است")
            val pre = ensureSshClient()
            if (!pre.ok) return@withContext pre
            val portFlag = if (config.useSsh) "-p ${config.port} " else ""
            val cmd = buildString {
                append("timeout 15 sshpass -p '${config.secret.replace("'", "")}' ")
                append("ssh -o StrictHostKeyChecking=no -o BatchMode=no ")
                append(portFlag)
                append("${config.username}@${config.host} ")
                append("echo __AON_OK__ && uname -a")
            }
            val r = ExecutionCoordinator.execute("automation-test", cmd, timeout = 25_000L)
            val ok = r.output.contains("__AON_OK__")
            CheckResult(ok, r.output.take(800))
        }

    /**
     * Push task state + project files to the remote server.
     * [projectDir] is an in-sandbox path (e.g. /var/minis/workspace/myapp).
     */
    suspend fun syncProject(config: AlwaysOnConfig, projectDir: String): CheckResult =
        withContext(Dispatchers.IO) {
            val pre = ensureSshClient()
            if (!pre.ok) return@withContext pre
            val name = projectDir.trim('/').replace('/', '_').ifBlank { "workspace" }
            val cmd = buildString {
                append("sshpass -p '${config.secret.replace("'", "")}' ")
                append("ssh -o StrictHostKeyChecking=no -p ${config.port} ")
                append("${config.username}@${config.host} 'mkdir -p $REMOTE_STATE_DIR' && ")
                append("sshpass -p '${config.secret.replace("'", "")}' ")
                append("scp -o StrictHostKeyChecking=no -P ${config.port} -r ")
                append("$projectDir ${config.username}@${config.host}:$REMOTE_STATE_DIR/$name")
            }
            val r = ExecutionCoordinator.execute("automation-sync", cmd, timeout = 300_000L)
            CheckResult(r.exitCode == 0, r.output.take(800))
        }

    /** Pull the remote state back (resume-from-checkpoint). */
    suspend fun resumeFromServer(config: AlwaysOnConfig, remoteName: String, localDir: String): CheckResult =
        withContext(Dispatchers.IO) {
            val cmd = buildString {
                append("sshpass -p '${config.secret.replace("'", "")}' ")
                append("scp -o StrictHostKeyChecking=no -P ${config.port} -r ")
                append("${config.username}@${config.host}:$REMOTE_STATE_DIR/$remoteName ")
                append(localDir)
            }
            val r = ExecutionCoordinator.execute("automation-resume", cmd, timeout = 300_000L)
            CheckResult(r.exitCode == 0, r.output.take(800))
        }

    /** Install openssh-client + sshpass inside the sandbox once. */
    private suspend fun ensureSshClient(): CheckResult {
        val r = ExecutionCoordinator.execute(
            "automation-setup",
            "command -v sshpass >/dev/null 2>&1 || apk add openssh-client sshpass >/dev/null 2>&1; command -v ssh >/dev/null && echo __SSH_OK__",
            timeout = 120_000L,
        )
        return if (r.output.contains("__SSH_OK__")) CheckResult(true, "ssh ready")
        else CheckResult(false, "نصب openssh-client در سندباکس ناموفق بود:\n${r.output.take(400)}")
    }
}
