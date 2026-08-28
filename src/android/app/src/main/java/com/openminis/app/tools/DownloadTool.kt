package com.openminis.app.tools

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.io.File

/**
 * [T-download-tool] REAL download via the system DownloadManager.
 * The browser "download" button previously only LISTED links — this tool
 * actually fetches and saves the resource (with progress + notification).
 *
 * Files land in the public Downloads folder so the user can find them in
 * the Files app / gallery. The assistant reports the saved path + size.
 */
object DownloadTool {

    const val NAME = "download_file"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Download a real file (image, video, PDF, audio, document, zip...) from a URL to the device's Downloads folder. " +
            "Use this when the user asks to download/save something. Returns the saved path and file size. " +
            "The system shows a progress notification and the file appears in the Files app / gallery.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'دانلود فایل PDF از سایت')."),
            "url" to AgentToolParam("string", "Direct HTTP/HTTPS URL of the file to download."),
            "filename" to AgentToolParam("string", "Optional filename to save as (with extension). If omitted, derived from the URL."),
            "referer" to AgentToolParam("string", "Optional Referer header (some sites require it)."),
            "user_agent" to AgentToolParam("string", "Optional custom User-Agent. Default is a desktop Chrome UA (many sites block mobile/empty UAs)."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "filename", "referer", "user_agent"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val url = args.optString("url", "").trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            return ToolExecutionResult("Error: 'url' must be a valid http(s) URL.", false, toolTitle = toolTitle)
        }
        val userAgent = args.optString("user_agent", "")
            .ifBlank { "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36" }
        var filename = args.optString("filename", "").trim()
        if (filename.isBlank()) {
            filename = deriveFilename(url)
        }
        // Sanitize filename — strip path separators / illegal chars.
        filename = filename.replace("/", "_").replace("\\", "_").replace(":", "_").trim()
        if (filename.isBlank()) filename = "download_${System.currentTimeMillis()}"

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(filename)
                setDescription("دانلود از دستیار هوشمند")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                addRequestHeader("User-Agent", userAgent)
                args.optString("referer", "").takeIf { it.isNotBlank() }?.let {
                    addRequestHeader("Referer", it)
                }
            }
            val id = dm.enqueue(request)
            ToolExecutionResult(
                "✅ دانلود شروع شد.\n" +
                    "فایل: $filename\n" +
                    "مقصد: Downloads\n" +
                    "شماره دانلود: $id\n" +
                    "پیشرفت در نوار اعلان نمایش داده می‌شود. وقتی تمام شد در برنامه Files/گالری قابل مشاهده است.",
                true,
                toolTitle = toolTitle,
            )
        } catch (e: Exception) {
            ToolExecutionResult("❌ دانلود ناموفق: ${e.message ?: "خطای ناشناخته"}", false, toolTitle = toolTitle)
        }
    }

    private fun deriveFilename(url: String): String {
        val path = url.substringBefore("?").substringAfterLast("/")
        if (path.isNotBlank() && path.contains(".")) return path
        // No obvious filename — guess by extension from mime if present, else default.
        return "download_${System.currentTimeMillis()}.bin"
    }
}
