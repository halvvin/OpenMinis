package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * [T-filesearch-tool] REAL file search by glob pattern on the device.
 * Based on the Vega-Agent search_files/glob pattern. Searches /var/minis
 * and /sdcard with a glob (e.g. *.pdf, *report*, IMG_*).
 */
object FileSearchTool {

    const val NAME = "search_files"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search for files on the device by name pattern. " +
            "Glob patterns: '*' matches any chars within a name, '**' matches across folders. " +
            "Examples: '*.pdf' in /sdcard/Documents, '*report*', 'IMG_2024*' in /sdcard/DCIM/Camera. " +
            "Use this to FIND a file/folder the user is looking for (photos, videos, documents, downloads).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'پیدا کردن فایل PDF')."),
            "pattern" to AgentToolParam("string", "Glob pattern, e.g. '*.pdf', '*report*', 'IMG_*'."),
            "base" to AgentToolParam("string", "Starting folder (default /var/minis). Use /sdcard or /sdcard/DCIM/Camera for the phone's storage."),
            "max_results" to AgentToolParam("integer", "Max results (default 30)."),
        ),
        required = listOf("tool_title", "pattern"),
        propertyOrdering = listOf("tool_title", "pattern", "base", "max_results"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val pattern = args.optString("pattern", "").trim()
        val baseArg = args.optString("base", "/var/minis").trim()
        val maxResults = args.optInt("max_results", 30).coerceIn(1, 100)

        if (pattern.isEmpty()) {
            return ToolExecutionResult("Error: 'pattern' is required.", false, toolTitle = toolTitle)
        }
        val base = resolveBase(baseArg, context)
            ?: return ToolExecutionResult("Error: Invalid base: $baseArg (use /var/minis or /sdcard paths).", false, toolTitle = toolTitle)
        if (!base.exists()) {
            return ToolExecutionResult("Error: Base folder not found: $baseArg", false, toolTitle = toolTitle)
        }

        val regex = globToRegex(pattern)
        val results = mutableListOf<File>()
        val root = if (baseArg.startsWith("/sdcard")) "/storage/emulated/0" + baseArg.substringAfter("/sdcard") else baseArg
        walk(base, regex, results, maxResults, root)

        if (results.isEmpty()) {
            return ToolExecutionResult("فایلی با الگوی «$pattern» در $baseArg پیدا نشد.", true, toolTitle = toolTitle)
        }
        val sb = StringBuilder("🔍 ${results.size} فایل با الگوی «$pattern»:\n")
        results.take(maxResults).forEachIndexed { i, f ->
            val size = if (f.isDirectory) "[پوشه]" else String.format("%.1fKB", f.length() / 1024.0)
            sb.append("${i + 1}. $size  ${f.absolutePath}\n")
        }
        if (results.size > maxResults) sb.append("… و ${results.size - maxResults} مورد دیگر")
        return ToolExecutionResult(sb.toString().trim(), true, toolTitle = toolTitle)
    }

    private fun resolveBase(arg: String, context: Context): File? = when {
        // [F-A2 fix / MOUNT-FILEOPS-01] Same /var/minis host-mapping fix as
        // FileOpsTool: guest paths resolve through the session-aware resolver
        // instead of a host path that never exists.
        arg.startsWith("/var/minis") -> PRootKernel.resolveSessionHostPath("floating-assistant", arg, context)
            ?: PRootKernel.resolveHostPath(arg)
        arg.startsWith("/sdcard") -> File("/storage/emulated/0" + arg.substringAfter("/sdcard"))
        arg.startsWith("/storage/emulated/0") -> File(arg)
        else -> null
    }

    private fun walk(dir: File, regex: Regex, out: MutableList<File>, max: Int, displayRoot: String) {
        val children = dir.listFiles() ?: return
        children.sortedBy { it.name }.forEach { c ->
            if (out.size >= max * 3) return
            val rel = c.absolutePath.removePrefix(displayRoot)
            if (regex.matches(c.name)) out.add(c)
            if (c.isDirectory) {
                // Limit recursion depth / breadth for performance.
                walk(c, regex, out, max, displayRoot)
            }
        }
    }

    /** Convert a glob to a regex (supports *, ?, **). */
    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    if (i + 1 < glob.length && glob[i + 1] == '*') {
                        sb.append(".*"); i += 2; continue
                    }
                    sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> { sb.append('\\').append(c) }
                else -> sb.append(c)
            }
            i++
        }
        return Regex("^" + sb.toString() + "$", RegexOption.IGNORE_CASE)
    }
}
