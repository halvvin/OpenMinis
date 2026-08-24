package com.openminis.app.browser

import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [T-reverse-api] Reverse API Engineer (user spec §3-6) — turns websites
 * into typed API clients: browse the site, capture network traffic as HAR,
 * then the user-configured model generates a clean client.
 *
 * The reference implementation (nottelabs/reverse-api-engineer, MIT) is
 * forked to the OWNER'S GitHub account (halvvin/reverse-api-engineer) so
 * nothing depends on a third party at runtime; the tool is installed from
 * that fork inside the app's own sandbox. The toggle (AutomationPrefs.
 * reverseApiEnabled) is default-OFF and only the user can enable it.
 */
object ReverseApi {

    const val FORK_URL = "https://github.com/halvvin/reverse-api-engineer.git"

    data class Status(val installed: Boolean, val detail: String)

    /** Install (or repair) the toolchain inside the sandbox. */
    suspend fun install(): Status = withContext(Dispatchers.IO) {
        val script = """
            set -e
            command -v python3 >/dev/null || apk add python3 py3-pip >/dev/null 2>&1
            pip install --quiet --break-system-packages "git+$FORK_URL" 2>/dev/null \
              || pip install --quiet "git+$FORK_URL"
            python3 -c "import importlib; importlib.import_module('reverse_api_engineer')" 2>/dev/null \
              && echo __RAE_OK__ || echo __RAE_MISSING__
        """.trimIndent()
        val r = ExecutionCoordinator.execute("reverse-api-install", script, timeout = 600_000L)
        Status(r.output.contains("__RAE_OK__"), r.output.take(1200))
    }

    /** Cheap probe used by the browser window UI. */
    suspend fun checkInstalled(): Status = withContext(Dispatchers.IO) {
        val r = ExecutionCoordinator.execute(
            "reverse-api-check",
            "python3 -c \"import importlib; importlib.import_module('reverse_api_engineer')\" 2>/dev/null && echo __RAE_OK__ || echo __RAE_MISSING__",
            timeout = 30_000L,
        )
        Status(r.output.contains("__RAE_OK__"), r.output.take(300))
    }
}
