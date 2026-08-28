package com.openminis.app.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.io.File

/**
 * [T-pdf-tool] Real PDF reading: renders PDF pages to images so the
 * vision-capable model (or the user) can actually READ the document.
 *
 * Android's PdfRenderer renders each page as a bitmap. The tool renders the
 * requested pages into the session's attachments dir and returns their
 * paths + page count + a short OCR-style hint. The model then uses
 * read_image on those pages to extract the content.
 */
object PdfTool {

    const val NAME = "read_pdf"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a PDF file: renders its pages as images into /var/minis/attachments/generated/ and returns the page count + image paths. " +
            "After calling this, use read_image on each returned path to actually extract the text/content. " +
            "Supports PDFs on the device (/sdcard/...) and in the app workspace (/var/minis/...).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'خواندن PDF گزارش')."),
            "path" to AgentToolParam("string", "Path to the PDF file (e.g. /sdcard/Documents/report.pdf or /var/minis/workspace/x.pdf)."),
            "pages" to AgentToolParam("string", "Optional page range 'a-b' (1-based). Default renders the first 3 pages. 'all' renders every page."),
            "dpi" to AgentToolParam("integer", "Optional render scale in percent (default 150 — good balance of size/readability)."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "pages", "dpi"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val path = args.optString("path", "").trim()
        val pagesSpec = args.optString("pages", "").trim().lowercase()
        val scalePct = args.optInt("dpi", 150).coerceIn(50, 400)

        if (path.isEmpty()) {
            return ToolExecutionResult("Error: 'path' is required.", false, toolTitle = toolTitle)
        }
        val file = resolvePdfPath(path)
            ?: return ToolExecutionResult("Error: Invalid path: $path", false, toolTitle = toolTitle)
        if (!file.exists()) {
            return ToolExecutionResult("Error: PDF not found: $path", false, toolTitle = toolTitle)
        }

        return try {
            val outDir = File(context.filesDir, "attachments/generated").apply { mkdirs() }
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val total = renderer.pageCount
                    val pages = resolvePages(pagesSpec, total)
                    if (pages.isEmpty()) {
                        return@use ToolExecutionResult("PDF باز شد — ${total} صفحه دارد. ولی هیچ صفحه‌ای برای رندر انتخاب نشد.", true, toolTitle = toolTitle)
                    }
                    val sb = StringBuilder()
                    sb.append("📄 PDF: ${file.name}\n📑 ${total} صفحه — رندر ${pages.size} صفحه\n")
                    val rendered = mutableListOf<String>()
                    pages.forEach { pageIndex ->
                        val page = renderer.openPage(pageIndex)
                        val scale = scalePct / 100f
                        val w = (page.width * scale).toInt()
                        val h = (page.height * scale).toInt()
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val outFile = File(outDir, "${file.nameWithoutExtension}_p${pageIndex + 1}.png")
                        outFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                        bmp.recycle()
                        page.close()
                        rendered.add(outFile.absolutePath)
                        sb.append("  صفحه ${pageIndex + 1} → ${outFile.name}\n")
                    }
                    sb.append("برای خواندن محتوا، روی هر مسیر read_image بزن.")
                    ToolExecutionResult(sb.toString(), true, toolTitle = toolTitle)
                }
            }
        } catch (e: Exception) {
            ToolExecutionResult("❌ خواندن PDF ناموفق: ${e.message ?: "خطا"} (ممکن است فایل رمزگذاری‌شده یا خراب باشد)", false, toolTitle = toolTitle)
        }
    }

    private fun resolvePdfPath(path: String): File? = when {
        path.startsWith("/var/minis") -> File(path)
        path.startsWith("/sdcard") || path.startsWith("/storage/emulated/0") -> {
            val real = "/storage/emulated/0" + path
                .substringAfter("/sdcard")
                .substringAfter("/storage/emulated/0")
            File(real)
        }
        else -> null
    }

    /** Returns zero-based page indexes to render. */
    private fun resolvePages(spec: String, total: Int): List<Int> {
        if (spec == "all") return (0 until total).toList()
        if (spec.isEmpty()) return (0 until minOf(3, total)).toList()
        val parts = spec.split("-")
        return try {
            val from = parts[0].trim().toInt().coerceIn(1, total)
            val to = (parts.getOrNull(1)?.trim()?.toInt() ?: from).coerceIn(from, total)
            (from - 1..to - 1).toList()
        } catch (_: Exception) {
            (0 until minOf(3, total)).toList()
        }
    }
}
