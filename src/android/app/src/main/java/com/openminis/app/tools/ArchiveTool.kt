package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * [T-archive-tool] REAL archive (ZIP) handling — list, extract, read entries.
 * Mirrors the Vega-Agent list_archive / extract_archive_entry / read_archive_entry
 * tools. Works on /var/minis/* and /sdcard/* paths.
 */
object ArchiveTool {

    const val NAME = "archive"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Work with ZIP archives: list_entries (show what's inside), extract (unzip to a folder), or read one entry's content. " +
            "Use when the user says 'unzip this', 'what's in this zip', or needs a file out of an archive. " +
            "Supports .zip paths on the device (/sdcard/...) and in the app (/var/minis/...).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'استخراج فایل ZIP')."),
            "operation" to AgentToolParam("string", "'list_entries', 'extract', or 'read_entry'."),
            "archive" to AgentToolParam("string", "Path to the .zip file."),
            "dest" to AgentToolParam("string", "Destination folder for 'extract' (e.g. /var/minis/workspace/unzipped or /sdcard/Download)."),
            "entry" to AgentToolParam("string", "Entry path inside the archive for 'read_entry' (e.g. 'folder/file.txt')."),
        ),
        required = listOf("tool_title", "operation", "archive"),
        propertyOrdering = listOf("tool_title", "operation", "archive", "dest", "entry"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val op = args.optString("operation", "").lowercase().trim()
        val archivePath = args.optString("archive", "").trim()
        val dest = args.optString("dest", "").trim()
        val entry = args.optString("entry", "").trim()

        if (op.isEmpty() || archivePath.isEmpty()) {
            return ToolExecutionResult("Error: 'operation' and 'archive' are required.", false, toolTitle = toolTitle)
        }
        val file = resolvePath(archivePath)
            ?: return ToolExecutionResult("Error: Invalid archive path: $archivePath", false, toolTitle = toolTitle)
        if (!file.exists()) {
            return ToolExecutionResult("Error: Archive not found: $archivePath", false, toolTitle = toolTitle)
        }

        return try {
            when (op) {
                "list_entries" -> {
                    val sb = StringBuilder("📦 ${file.name} — محتوا:\n")
                    var count = 0
                    ZipInputStream(FileInputStream(file)).use { zis ->
                        var ze = zis.nextEntry
                        while (ze != null) {
                            count++
                            if (count <= 100) {
                                val size = if (ze.isDirectory) "[پوشه]" else String.format("%.1fKB", ze.size / 1024.0)
                                sb.append("  ${if (ze.isDirectory) "📂" else "📄"} ${ze.name}  [$size]\n")
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                    if (count > 100) sb.append("  … و ${count - 100} ورودی دیگر")
                    sb.insert(0, "📦 ${file.name} — ${count} ورودی:\n")
                    ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
                }
                "extract" -> {
                    if (dest.isEmpty()) return ToolExecutionResult("Error: 'dest' folder is required for extract.", false, toolTitle = toolTitle)
                    val destFile = resolvePath(dest)
                        ?: return ToolExecutionResult("Error: Invalid dest: $dest", false, toolTitle = toolTitle)
                    destFile.mkdirs()
                    var extracted = 0
                    ZipInputStream(FileInputStream(file)).use { zis ->
                        var ze = zis.nextEntry
                        while (ze != null) {
                            val out = File(destFile, ze.name)
                            if (ze.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { os -> zis.copyTo(os) }
                                extracted++
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                    ToolExecutionResult("✅ ${extracted} فایل استخراج شد به $dest", true, toolTitle = toolTitle)
                }
                "read_entry" -> {
                    if (entry.isEmpty()) return ToolExecutionResult("Error: 'entry' is required for read_entry.", false, toolTitle = toolTitle)
                    var found: String? = null
                    ZipInputStream(FileInputStream(file)).use { zis ->
                        var ze = zis.nextEntry
                        while (ze != null) {
                            if (ze.name == entry && !ze.isDirectory) {
                                found = zis.readBytes().toString(Charsets.UTF_8).take(15000)
                                break
                            }
                            zis.closeEntry()
                            ze = zis.nextEntry
                        }
                    }
                    if (found == null) ToolExecutionResult("ورودی «$entry» در آرشیو پیدا نشد.", false, toolTitle = toolTitle)
                    else ToolExecutionResult("📄 $entry:\n\n$found", true, toolTitle = toolTitle)
                }
                else -> ToolExecutionResult("Error: Unknown operation '$op'. Use list_entries/extract/read_entry.", false, toolTitle = toolTitle)
            }
        } catch (e: Exception) {
            ToolExecutionResult("❌ خطا در آرشیو: ${e.message ?: "خطا"} (آیا فایل ZIP سالم است؟)", false, toolTitle = toolTitle)
        }
    }

    private fun resolvePath(path: String): File? = when {
        path.startsWith("/var/minis") -> File(path)
        path.startsWith("/sdcard") -> File("/storage/emulated/0" + path.substringAfter("/sdcard"))
        path.startsWith("/storage/emulated/0") -> File(path)
        else -> null
    }
}
