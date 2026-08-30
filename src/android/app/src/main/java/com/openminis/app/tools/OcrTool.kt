package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject

/**
 * [T-ocr-tool] Optical Character Recognition: extract TEXT from an image.
 *
 * Thin, real wrapper over the existing vision pipeline (read_image): the
 * image is sent to the model (or Vision Group) with a transcription-focused
 * prompt, and the extracted text is returned as plain text.
 *
 * Works on photos, screenshots, scans, camera captures — any image on the
 * device (/sdcard/...) or in the app (/var/minis/...).
 */
object OcrTool {

    const val NAME = "ocr_image"

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "OCR: extract all visible TEXT from an image (photo, screenshot, scan, document photo). " +
            "Returns the transcribed text. Use when the user wants to read text from a picture, scan a document, " +
            "copy text from a photo, etc. Works with images on the device (/sdcard/DCIM, /sdcard/Pictures, ...) and in the app.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'خواندن متن عکس')."),
            "path" to AgentToolParam("string", "Path to the image (e.g. /sdcard/DCIM/Camera/IMG_1.jpg or /var/minis/attachments/x.png)."),
            "language_hint" to AgentToolParam("string", "Optional language hint for the text (e.g. 'فارسی', 'English'). Improves accuracy."),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "language_hint"),
    )

    /** Returns a ToolExecutionResult with the extracted text. */
    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val path = args.optString("path", "").trim()
        val langHint = args.optString("language_hint", "").trim()

        if (path.isEmpty()) {
            return ToolExecutionResult("Error: 'path' is required.", false, toolTitle = toolTitle)
        }
        val prompt = buildString {
            append("Extract ALL text visible in this image verbatim. Transcribe every word, number, and label exactly as shown, ")
            append("preserving line breaks between lines. ")
            if (langHint.isNotBlank()) append("The text is mostly in $langHint. ")
            append("Do not describe the image — return ONLY the extracted text.")
        }
        // Reuse the real vision pipeline (read_image with an OCR prompt).
        val inner = JSONObject().apply {
            put("tool_title", toolTitle)
            put("path", path)
            put("prompt", prompt)
        }
        val result = ReadImageTool.execute(inner.toString(), sessionId = null, context = context)
        return ToolExecutionResult(
            "📄 متن استخراج‌شده از تصویر:\n\n${result.output}",
            result.success,
            toolTitle = toolTitle,
        )
    }
}
