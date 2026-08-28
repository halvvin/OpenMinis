package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [T-webextract-tool] Fetch a web page and extract its content for real.
 *
 * Fixes the "web browse + save info" gap: fetches the URL, pulls the
 * <title>, visible text, links and image URLs, and OPTIONALLY saves a clean
 * text/markdown file into the workspace (or writes an HTML snapshot).
 *
 * Pure JVM HTTP — no WebView needed, works headless.
 */
object WebExtractTool {

    const val NAME = "web_extract"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Fetch a web page and extract its content: title, visible text, links, and image URLs. " +
            "Optionally SAVE the extracted text to a file (save=true → /var/minis/workspace/). " +
            "Use for: summarizing a page, grabbing info, saving articles as files, finding download links or images on a page.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'استخراج مقاله از سایت')."),
            "url" to AgentToolParam("string", "The page URL to fetch."),
            "save" to AgentToolParam("string", "'true' to save the extracted text as a .md file in /var/minis/workspace. Default 'false' (just return content)."),
            "max_chars" to AgentToolParam("integer", "Max characters of extracted text to return (default 12000)."),
            "timeout_ms" to AgentToolParam("integer", "Fetch timeout in ms (default 20000)."),
        ),
        required = listOf("tool_title", "url"),
        propertyOrdering = listOf("tool_title", "url", "save", "max_chars", "timeout_ms"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val url = args.optString("url", "").trim()
        val save = args.optString("save", "false").equals("true", ignoreCase = true)
        val maxChars = args.optInt("max_chars", 12000).coerceIn(1000, 60000)
        val timeoutMs = args.optInt("timeout_ms", 20000).coerceIn(5000, 60000)

        if (url.isEmpty() || !url.startsWith("http")) {
            return ToolExecutionResult("Error: 'url' must be a valid http(s) URL.", false, toolTitle = toolTitle)
        }

        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*")
                instanceFollowRedirects = true
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return ToolExecutionResult("❌ HTTP $code برای $url", false, toolTitle = toolTitle)
            }
            val html = conn.inputStream.bufferedReader().use { it.readText() }.take(2_000_000)
            conn.disconnect()

            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim().orEmpty()
            // Strip scripts/styles/tags → visible text.
            val text = html
                .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                .replace(Regex("(?is)<br\\s*/?>"), "\n")
                .replace(Regex("(?is)</p|</div|</h[1-6]|</li|</tr>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            // Links + images.
            val links = Regex("(?i)href=[\"']([^\"']+)[\"']").findAll(html)
                .map { it.groupValues[1] }.filter { it.startsWith("http") }.distinct().take(40).toList()
            val images = Regex("(?i)src=[\"']([^\"']+)[\"']").findAll(html)
                .map { it.groupValues[1] }.filter { it.startsWith("http") }.distinct().take(30).toList()

            val excerpt = text.take(maxChars)
            val sb = StringBuilder()
            sb.append("🌐 $title\n")
            sb.append("📎 $url\n\n")
            sb.append(if (excerpt.isNotEmpty()) excerpt else "(متن قابل استخراجی پیدا نشد — صفحه احتمالاً جاوااسکریپتی است)")
            sb.append(if (text.length > maxChars) "\n\n… [ادامه در فایل ذخیره‌شده]" else "")
            if (links.isNotEmpty()) {
                sb.append("\n\n🔗 لینک‌ها:\n")
                links.take(20).forEach { sb.append("  • $it\n") }
            }
            if (images.isNotEmpty()) {
                sb.append("\n🖼️ تصاویر (برای دانلود از download_file استفاده کن):\n")
                images.take(15).forEach { sb.append("  • $it\n") }
            }

            if (save) {
                val safeName = (title.ifBlank { "page" }).replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
                    .replace(" ", "_").take(60).ifBlank { "page" }
                val outFile = File(context.filesDir, "workspace/${safeName}.md")
                outFile.parentFile?.mkdirs()
                outFile.writeText("# $title\n\nSource: $url\n\n$excerpt\n\n## Links\n${links.joinToString("\n")}\n\n## Images\n${images.joinToString("\n")}")
                sb.append("\n\n💾 ذخیره شد: ${outFile.absolutePath}")
            }
            ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("❌ دریافت صفحه ناموفق: ${e.message ?: "خطا"}", false, toolTitle = toolTitle)
        }
    }
}
