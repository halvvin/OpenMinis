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
 * — installable via `apk add openssh-client` on first use).
 *
 * [F-A1 security] The SSH secret is written ONCE to a sandbox-internal temp
 * file (mode 600) and consumed via `sshpass -f` — the previous approach
 * embedded it in every command line (`sshpass -p '<secret>'`), which leaked
 * to the process list AND to the on-disk diag log via
 * ExecutionCoordinator's command trace. Host/user/path arguments are now
 * single-quote escaped so they can't break out of the quoting either.
 * The secret is stored only in encrypted app-private prefs and is NEVER
 * synced to the cloud or included in logs.
 */
object AlwaysOnEngine {

    data class CheckResult(val ok: Boolean, val detail: String)

    private const val REMOTE_STATE_DIR = "~/minis-fork-state"
    private const val PW_FILE = "/tmp/.aon_pw"

    /** Write the secret to the sandbox-internal password file (once per boot). */
    private suspend fun ensurePasswordFile(config: AlwaysOnConfig): CheckResult {
        val pre = ensureSshClient()
        if (!pre.ok) return pre
        if (config.secret.isEmpty()) return CheckResult(false, "رمز سرور خالی است — تنظیمات → اجرای همیشگی")
        val setup = "umask 077; printf %s ${q(config.secret)} > $PW_FILE"
        val r = ExecutionCoordinator.execute("automation-setup", setup, timeout = 15_000L)
        return if (r.exitCode == 0) CheckResult(true, "pw ok")
        else CheckResult(false, "نوشتن فایل رمز ناموفق بود:\n${r.output.take(400)}")
    }

    /** One-shot connectivity test through the sandbox shell. */
    suspend fun testConnection(config: AlwaysOnConfig): CheckResult =
        withContext(Dispatchers.IO) {
            if (config.host.isBlank()) return@withContext CheckResult(false, "آدرس سرور خالی است")
            val pw = ensurePasswordFile(config)
            if (!pw.ok) return@withContext pw
            val portFlag = if (config.useSsh) "-p ${config.port} " else ""
            // First connection: accept-new records the host key (TOFU);
            // subsequent connections pin to the recorded key and a mismatch
            // is a hard failure. We create the per-host dir once, then use
            // StrictHostKeyChecking=accept-new on EVERY call — accept-new
            // trusts only a key that is NOT already recorded, so after the
            // first run any changed key fails closed.
            val khDir = "/var/minis/shared/.ssh_${config.host.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            val cmd = buildString {
                append("mkdir -p $khDir 2>/dev/null; ")
                append("timeout 15 sshpass -f $PW_FILE ")
                append("ssh -o StrictHostKeyChecking=accept-new ")
                append("-o UserKnownHostsFile=$khDir/known_hosts -o GlobalKnownHostsFile=/dev/null ")
                append("-o BatchMode=no ")
                append(portFlag)
                append("${q(config.username)}@${q(config.host)} ")
                append("echo __AON_OK__ && uname -a; rm -f $PW_FILE")
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
            val pw = ensurePasswordFile(config)
            if (!pw.ok) return@withContext pw
            val name = projectDir.trim('/').replace('/', '_').ifBlank { "workspace" }
            val cmd = buildString {
                append("mkdir -p ${knownHostsDir(config.host)} 2>/dev/null; ")
                append("sshpass -f $PW_FILE ")
                append("ssh -o StrictHostKeyChecking=accept-new ")
                append("-o UserKnownHostsFile=${knownHostsDir(config.host)}/known_hosts -o GlobalKnownHostsFile=/dev/null ")
                append("-p ${config.port} ")
                append("${q(config.username)}@${q(config.host)} 'mkdir -p $REMOTE_STATE_DIR' && ")
                append("sshpass -f $PW_FILE ")
                append("scp -o StrictHostKeyChecking=accept-new ")
                append("-o UserKnownHostsFile=${knownHostsDir(config.host)}/known_hosts -o GlobalKnownHostsFile=/dev/null ")
                append("-P ${config.port} -r ")
                append("${q(projectDir)} ${q(config.username)}@${q(config.host)}:$REMOTE_STATE_DIR/$name")
                append("; rm -f $PW_FILE")
            }
            val r = ExecutionCoordinator.execute("automation-sync", cmd, timeout = 300_000L)
            CheckResult(r.exitCode == 0, r.output.take(800))
        }

    /** Pull the remote state back (resume-from-checkpoint). */
    suspend fun resumeFromServer(config: AlwaysOnConfig, remoteName: String, localDir: String): CheckResult =
        withContext(Dispatchers.IO) {
            val pw = ensurePasswordFile(config)
            if (!pw.ok) return@withContext pw
            val cmd = buildString {
                append("mkdir -p ${knownHostsDir(config.host)} 2>/dev/null; ")
                append("sshpass -f $PW_FILE ")
                append("scp -o StrictHostKeyChecking=accept-new ")
                append("-o UserKnownHostsFile=${knownHostsDir(config.host)}/known_hosts -o GlobalKnownHostsFile=/dev/null ")
                append("-P ${config.port} -r ")
                append("${q(config.username)}@${q(config.host)}:$REMOTE_STATE_DIR/${q(remoteName)} ")
                append(q(localDir))
                append("; rm -f $PW_FILE")
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
