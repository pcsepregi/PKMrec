package me.csepregi.pkmrec.tcg

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Every TCG Collector page embeds its state as JSON in an inline script:
 *
 * ```
 * window.tcgcollector = {
 *   appState: { ...JSON... },
 * };
 * ```
 *
 * This extracts and parses that JSON object.
 */
object AppStateExtractor {

    private const val MARKER = "appState:"
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the raw appState JSON text, or null if the page does not contain it. */
    fun extractJsonText(html: String): String? {
        val scriptStart = html.indexOf("window.tcgcollector")
        if (scriptStart < 0) return null
        val markerIndex = html.indexOf(MARKER, scriptStart)
        if (markerIndex < 0) return null
        val start = html.indexOf('{', markerIndex + MARKER.length)
        if (start < 0) return null
        val end = findMatchingBrace(html, start) ?: return null
        return html.substring(start, end + 1)
    }

    fun extract(html: String): JsonObject? {
        val text = extractJsonText(html) ?: return null
        return runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    }

    /** Index of the brace closing the object that opens at [start]; string literals are skipped. */
    internal fun findMatchingBrace(text: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }
}
