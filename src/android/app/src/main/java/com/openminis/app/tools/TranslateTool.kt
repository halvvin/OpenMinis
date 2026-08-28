package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.ProviderFactory
import org.json.JSONObject

/**
 * [T-translate-tool] Real translation with explicit source/target language.
 *
 * Fixes the browser "translate" extension gap (no language picker): the tool
 * takes a `target_lang` (mandatory) + optional `source_lang` (auto-detect)
 * and returns the translated text. Uses the currently selected model.
 */
object TranslateTool {

    const val NAME = "translate_text"

    private val LANGS = listOf(
        "فارسی", "English", "العربية", "Türkçe", "Deutsch", "Français",
        "Español", "Русский", "中文", "日本語", "한국어", "Italiano",
        "Português", "Hindi", "اردو", "Dutch", "Greek", "Hebrew",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Translate text into a chosen target language. " +
            "target_lang is REQUIRED (pick one of: ${LANGS.joinToString(", ")} or any language name). " +
            "source_lang is optional (default auto-detect). Returns only the translated text.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'ترجمه متن به انگلیسی')."),
            "text" to AgentToolParam("string", "The text to translate."),
            "target_lang" to AgentToolParam("string", "The language to translate INTO (e.g. 'English', 'فارسی'). Required."),
            "source_lang" to AgentToolParam("string", "The source language if known (e.g. 'English'). Default: auto-detect."),
        ),
        required = listOf("tool_title", "text", "target_lang"),
        propertyOrdering = listOf("tool_title", "text", "target_lang", "source_lang"),
    )

    suspend fun execute(
        argsJson: String,
        providerRepo: ProviderRepository,
        selectedEntryId: String,
        context: Context,
    ): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val text = args.optString("text", "").trim()
        val targetLang = args.optString("target_lang", "").trim()
        val sourceLang = args.optString("source_lang", "").trim()

        if (text.isEmpty()) return ToolExecutionResult("Error: 'text' is required.", false, toolTitle = toolTitle)
        if (targetLang.isEmpty()) return ToolExecutionResult("Error: 'target_lang' is required.", false, toolTitle = toolTitle)

        // Resolve a model (selected, else first available) — same fallback as elsewhere.
        val loop = runCatching { providerRepo.resolvedAgentLoopEntries() }.getOrDefault(emptyList())
        val all = runCatching {
            val cfg = providerRepo.config.value
            val enabled = cfg.instances.filter { it.isEnabled }.map { it.id }.toSet()
            cfg.modelEntries.filter { it.providerInstanceId in enabled && !it.isHidden }
        }.getOrDefault(emptyList())
        val pool = if (loop.isNotEmpty()) loop else all
        val entry = pool.firstOrNull { it.id == selectedEntryId } ?: pool.firstOrNull()
        if (entry == null) {
            return ToolExecutionResult("❌ مدلی تنظیم نشده — اول یک API در تنظیمات اضافه کن.", false, toolTitle = toolTitle)
        }

        return kotlinx.coroutines.runCatching {
            val instance = providerRepo.instance(entry.providerInstanceId)
            val apiKey = instance?.let { providerRepo.usableApiKey(it) }
            if (instance == null || apiKey == null) {
                return ToolExecutionResult("❌ کلید API در دسترس نیست.", false, toolTitle = toolTitle)
            }
            val provider = ProviderFactory.create(instance, apiKey, entry.model, context)
            val srcHint = if (sourceLang.isNotBlank()) " Source language: $sourceLang." else " Source language: auto-detect."
            val sys = "You are a precise translation engine. Translate the user's text into $targetLang.$srcHint " +
                "Return ONLY the translated text — no explanations, no quotes, no notes."
            val resp = provider.sendMessage(
                messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = text)),
                systemPrompt = sys,
                maxTokens = 4096,
            )
            val translated = resp.text.ifBlank { "(خروجی خالی از مدل)" }
            ToolExecutionResult("🌍 ترجمه به $targetLang:\n\n$translated", true, toolTitle = toolTitle)
        }.getOrElse { e ->
            ToolExecutionResult("❌ ترجمه ناموفق: ${e.message ?: "خطا"}", false, toolTitle = toolTitle)
        }
    }
}
