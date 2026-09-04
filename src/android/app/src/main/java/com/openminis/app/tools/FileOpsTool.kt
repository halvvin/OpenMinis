package com.openminis.app.tools

import android.content.Context
import android.os.Environment
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File

/**
 * [T-fileops-tool] REAL file operations on the device.
 *
 * Works on BOTH the app sandbox paths (/var/minis/...) and the public
 * device storage (/sdcard/... = /storage/emulated/0/...). Public storage
 * access requires the MANAGE_EXTERNAL_STORAGE permission (user grants it in
 * system settings — the assistant guides them if it's missing).
 *
 * Operations: list (with sizes + dates), move, copy, delete, rename.
 * Option B from the user decision: direct full-access (no SAF picker).
 */
object FileOpsTool {

    const val NAME = "file_ops"

    private const val BASE = "/var/minis"
    private val SDCARD = listOf("/sdcard", "/storage/emulated/0")

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Real file operations on the device: LIST a folder, MOVE/COPY/RENAME/DELETE a file or folder. " +
            "Works on app paths (/var/minis/workspace, /var/minis/attachments, /var/minis/shared) AND the phone's public storage " +
            "(/sdcard/Download, /sdcard/DCIM, /sdcard/Pictures, /sdcard/Movies, /sdcard/Documents — whatever the user names). " +
            "This is how you edit, delete, or move the user's photos/videos/files when asked. " +
            "Public storage needs the 'All files access' permission — if missing, tell the user to enable it (Settings → System permissions).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'حذف عکس قدیمی از گالری')."),
            "operation" to AgentToolParam("string", "One of: list, move, copy, delete, rename."),
            "path" to AgentToolParam("string", "Source path (e.g. /var/minis/workspace/x.pdf or /sdcard/DCIM/Camera/IMG_1.jpg)."),
            "target" to AgentToolParam("string", "Destination path — required for move/copy/rename."),
            "recursive" to AgentToolParam("string", "'true' to list/delete folders recursively (default false for list — pass 'true' to include subfolders)."),
        ),
        required = listOf("tool_title", "operation", "path"),
        propertyOrdering = listOf("tool_title", "operation", "path", "target", "recursive"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val op = args.optString("operation", "").lowercase().trim()
        val path = args.optString("path", "").trim()
        val target = args.optString("target", "").trim()
        val recursive = args.optString("recursive", "false").equals("true", ignoreCase = true)

        if (op.isEmpty() || path.isEmpty()) {
            return ToolExecutionResult("Error: 'operation' and 'path' are required.", false, toolTitle = toolTitle)
        }
        val file = resolve(path, sessionId, context)
            ?: return ToolExecutionResult("Error: Invalid path: $path (only app paths and /sdcard are allowed).", false, toolTitle = toolTitle)
        if (op == "list") {
            if (!file.exists()) return ToolExecutionResult("Error: Path not found: $path", false, toolTitle = toolTitle)
            val children = file.listFiles()?.sortedBy { it.name } ?: emptyList()
            val sb = StringBuilder()
            sb.append("📁 ${file.absolutePath} — ${children.size} مورد\n")
            children.take(200).forEach { c ->
                val size = if (c.isDirectory) "DIR " else String.format("%.1f KB", c.length() / 1024.0)
                sb.append("  • ${if (c.isDirectory) "📂" else "📄"} ${c.name}  [$size]\n")
            }
            if (children.size > 200) sb.append("  … و ${children.size - 200} مورد دیگر (لیست محدود شد)\n")
            return ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
        }
        if (!file.exists()) return ToolExecutionResult("Error: Path not found: $path", false, toolTitle = toolTitle)

        return try {
            when (op) {
                "delete" -> {
                    if (file.isDirectory && !recursive) {
                        ToolExecutionResult("⚠️ «$path» یک پوشه است — برای حذف پوشه، recursive=true بفرست.", false, toolTitle = toolTitle)
                    } else {
                        val ok = file.deleteRecursively()
                        ToolExecutionResult(if (ok) "✅ حذف شد: $path" else "❌ حذف ناموفق: $path", ok, toolTitle = toolTitle)
                    }
                }
                "move", "rename", "copy" -> {
                    val targetFile = resolve(target, context)
                        ?: return ToolExecutionResult("Error: Invalid target: $target", false, toolTitle = toolTitle)
                    if (targetFile.exists()) {
                        return ToolExecutionResult("⚠️ مقصد از قبل وجود دارد: $target — اول حذفش کن یا نام دیگری انتخاب کن.", false, toolTitle = toolTitle)
                    }
                    targetFile.parentFile?.mkdirs()
                    val ok = if (op == "copy") file.copyRecursively(targetFile, overwrite = false) else file.renameTo(targetFile)
                    ToolExecutionResult(
                        if (ok) "✅ ${if (op == "copy") "کپی" else "انتقال"} شد: $path → $target"
                        else "❌ ناموفق (اگر بین دو دستگاه/فایل‌سیستم متفاوت است، copy را امتحان کن)",
                        ok, toolTitle = toolTitle,
                    )
                }
                else -> ToolExecutionResult("Error: Unknown operation '$op'. Use list/move/copy/rename/delete.", false, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("❌ ${e.message ?: "خطا"} — آیا مجوز «دسترسی به همه فایل‌ها» فعال است؟", false, toolTitle = toolTitle)
        }
    }

    /** Resolve an app-path or /sdcard path to a real File. */
    private fun resolve(path: String, sessionId: String, context: Context): File? {
        return when {
            // [F-A2 fix / MOUNT-FILEOPS-01] /var/minis/... is a GUEST path —
            // on the host these live under filesDir/minis-global (shared dirs)
            // or filesDir/minis-sessions/<sid> (per-session dirs). The old
            // File(path) pointed at a host location that never exists, so
            // every file_ops call on an app path failed with ENOENT.
            // The "floating-assistant" session id is the shared executor
            // context; PRootKernel.resolveSessionHostPath resolves per-session
            // subdirs against it and falls back to the global map for
            // memory/skills/shared.
            path.startsWith(BASE) -> PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: PRootKernel.resolveHostPath(path)
            SDCARD.any { path.startsWith(it) } -> {
                val real = "/storage/emulated/0" + path.substringAfter("/sdcard").substringAfter("/storage/emulated/0")
                File(real)
            }
            else -> null
        }
    }
}
