package org.aaaasta03.astrabrowser

import java.net.URLEncoder
import java.util.Locale

/**
 * Turns address-bar input into an HTTP(S) URL or the selected search engine URL.
 *
 * Non-HTTP(S) schemes are intentionally treated as search text rather than loaded
 * directly; the browser should not blindly forward intent:, file:, or javascript: URLs.
 */
object UrlNormalizer {
    enum class SearchEngine(
        val id: String,
        val label: String,
        private val searchPrefix: String
    ) {
        GOOGLE("google", "Google", "https://www.google.com/search?q="),
        DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q="),
        BING("bing", "Bing", "https://www.bing.com/search?q="),
        BRAVE("brave", "Brave Search", "https://search.brave.com/search?q=");

        fun searchUrl(query: String): String =
            searchPrefix + URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")

        companion object {
            fun fromId(id: String?): SearchEngine =
                values().firstOrNull { it.id == id } ?: DUCKDUCKGO
        }
    }

    fun normalize(
        rawInput: String,
        searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO
    ): String {
        val input = rawInput.trim()
        if (input.isEmpty()) return ""

        val lower = input.lowercase(Locale.ROOT)
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return input
        }

        val schemePattern = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
        if (schemePattern.containsMatchIn(input)) {
            return search(input, searchEngine)
        }

        val looksLikeHost = !input.any { it.isWhitespace() } &&
            (input.contains('.') || input.startsWith("localhost", ignoreCase = true))

        return if (looksLikeHost) {
            "https://$input"
        } else {
            search(input, searchEngine)
        }
    }

    private fun search(query: String, searchEngine: SearchEngine): String =
        searchEngine.searchUrl(query)
}
