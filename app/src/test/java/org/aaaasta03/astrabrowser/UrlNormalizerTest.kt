package org.aaaasta03.astrabrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun preservesHttpsAddress() {
        assertEquals(
            "https://example.com/path",
            UrlNormalizer.normalize("https://example.com/path")
        )
    }

    @Test
    fun upgradesBareHostToHttps() {
        assertEquals("https://example.com", UrlNormalizer.normalize("example.com"))
    }

    @Test
    fun searchesOrdinaryWords() {
        assertEquals(
            "https://duckduckgo.com/?q=hello%20browser",
            UrlNormalizer.normalize("hello browser")
        )
    }

    @Test
    fun doesNotLoadJavascriptScheme() {
        assertEquals(
            "https://duckduckgo.com/?q=javascript%3Aalert%281%29",
            UrlNormalizer.normalize("javascript:alert(1)")
        )
    }

    @Test
    fun searchesUnsupportedSchemesInsteadOfOpeningThem() {
        assertEquals(
            "https://duckduckgo.com/?q=mailto%3Atest%40example.com",
            UrlNormalizer.normalize("mailto:test@example.com")
        )
    }

    @Test
    fun encodesSearchSpecialCharacters() {
        assertEquals(
            "https://duckduckgo.com/?q=hello%20%26%20goodbye%3F",
            UrlNormalizer.normalize("hello & goodbye?")
        )
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", UrlNormalizer.normalize("   "))
    }
}
