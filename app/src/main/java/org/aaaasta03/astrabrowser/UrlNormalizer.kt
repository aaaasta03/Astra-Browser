package org.aaaasta03.astrabrowser

import java.net.URLEncoder
import java.util.Locale

/**
 * Turns address-bar input into an HTTP(S) URL or a DuckDuckGo search URL.
 *
 * Non-HTTP(S) schemes are intentionally treated as search text rather than loaded
 * directly; the browser should not blindly forward intent:, file:, or javascript: URLs.
 */
object UrlNormalizer {
    private const val SEARCH_PREFIX = "https://duckduckgo.com/?q="

    fun normalize(rawInput: String): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return ""

        val lower = input.lowercase(Locale.ROOT)
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return input
        }

        val schemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        if (schemePattern.containsMatchIn(input)) {
            return search(input)
        }

        val looksLikeHost = !input.any { it.isWhitespace() } &&
            (input.contains('.') || input.startsWith("localhost", ignoreCase = true))

        return if (looksLikeHost) {
            "https://$input"
        } else {
            search(input)
        }
    }

    private fun search(query: String): String =
        SEARCH_PREFIX + URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
}
