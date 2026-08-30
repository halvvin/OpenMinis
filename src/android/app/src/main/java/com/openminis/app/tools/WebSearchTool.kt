package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [T-web-search-tool] REAL web search — based on the Vega-Agent pattern.
 *
 * Queries DuckDuckGo HTML (falling back to DuckDuckGo Lite, then Bing)
 * and returns the top results (title + snippet + URL) as plain text.
 * This is a real live search — not a fake stub. Headless, no WebView.
 */
object WebSearchTool {

    const val NAME = "web_search"

    private const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    data class Hit(val title: String, val snippet: String, val url: String)

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the web (DuckDuckGo/Bing) and return the top results: title, snippet, URL. " +
            "Use this FIRST to find information or sites, then use web_extract to fetch a specific page's full content, " +
            "or download_file to save a file. Real live search.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'جستجو در وب برای دانلود ابزار')."),
            "query" to AgentToolParam("string", "The search query (plain words)."),
            "max_results" to AgentToolParam("integer", "Max results to return (default 8, max 10)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results"),
    )

    fun execute(argsJson: String, context: Context): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrDefault(JSONObject())
        val toolTitle = args.optString("tool_title", NAME)
        val query = args.optString("query", "").trim()
        val maxResults = args.optInt("max_results", 8).coerceIn(1, 10)

        if (query.isEmpty()) {
            return ToolExecutionResult("Error: 'query' is required.", false, toolTitle = toolTitle)
        }

        val hits = searchWithFallbacks(query)
        if (hits.isEmpty()) {
            return ToolExecutionResult("جستجو نتیجه‌ای نداشت برای: $query\n(نکته: عبارت را ساده‌تر کن)", false, toolTitle = toolTitle)
        }
        val sb = StringBuilder("🔍 نتایج جستجو برای «$query»:\n\n")
        hits.take(maxResults).forEachIndexed { i, h ->
            sb.append("${i + 1}. ${h.title}\n")
            if (h.snippet.isNotBlank()) sb.append("   ${h.snippet}\n")
            sb.append("   🔗 ${h.url}\n\n")
        }
        return ToolExecutionResult(sb.toString().trim(), true, toolTitle = toolTitle)
    }

    private fun searchWithFallbacks(query: String): List<Hit> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val engines = listOf(
            "https://html.duckduckgo.com/html/?q=$encoded",
            "https://lite.duckduckgo.com/lite/?q=$encoded",
            "https://www.bing.com/search?q=$encoded&count=10",
        )
        for (url in engines) {
            val hits = try {
                when {
                    url.contains("html.duckduckgo") -> parseDuckDuckGo(fetch(url))
                    url.contains("lite.duckduckgo") -> parseDuckDuckGoLite(fetch(url))
                    else -> parseBing(fetch(url))
                }
            } catch (_: Exception) { emptyList() }
            if (hits.isNotEmpty()) return hits
        }
        return emptyList()
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", UA)
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..299) return ""
        val body = conn.inputStream.bufferedReader().use { it.readText() }.take(2_000_000)
        conn.disconnect()
        return body
    }

    private fun strip(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun parseDuckDuckGo(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        val results = html.split(Regex("(?i)<div[^>]*class=\"[^\"]*result[^\"]*\"")).drop(1)
        for (r in results.take(12)) {
            val link = Regex("(?i)href=\"([^\"]+)\"").find(r)?.groupValues?.get(1) ?: continue
            val url = link.removePrefix("//duckduckgo.com/l/?uddg=").let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            }
            if (!url.startsWith("http")) continue
            val title = strip(r.substringBefore("</a>").take(300)).ifBlank { url }
            val snippet = strip(r.take(600)).removePrefix(title).take(250)
            out.add(Hit(title, snippet, url))
        }
        return out.distinctBy { it.url }
    }

    private fun parseDuckDuckGoLite(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        // Lite layout: <a rel="nofollow" href="...">title</a> then snippet <td class="result-snippet">
        val links = Regex("(?i)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(html)
        for (m in links.take(20)) {
            val url = m.groupValues[1]
            if (!url.startsWith("http")) continue
            val title = strip(m.groupValues[2]).ifBlank { url }
            out.add(Hit(title, "", url))
        }
        return out.distinctBy { it.url }
    }

    private fun parseBing(html: String): List<Hit> {
        val out = mutableListOf<Hit>()
        // Bing: <li class="b_algo">...<h2><a href="URL">Title</a></h2>...<p>snippet</p></li>
        val results = html.split(Regex("(?i)<li[^>]*class=\"b_algo\""))
        for (r in results.drop(1).take(12)) {
            val url = Regex("(?i)href=\"(http[^\"]+)\"").find(r)?.groupValues?.get(1) ?: continue
            val title = strip(Regex("(?i)<h2[^>]*>(.*?)</h2>").find(r)?.groupValues?.get(1).orEmpty()).ifBlank { url }
            val snippet = strip(Regex("(?i)<p[^>]*>(.*?)</p>").find(r)?.groupValues?.get(1).orEmpty()).take(250)
            out.add(Hit(title, snippet, url))
        }
        return out.distinctBy { it.url }
    }
}
