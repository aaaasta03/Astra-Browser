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
    fun emptyInputStaysEmpty() {
        assertEquals("", UrlNormalizer.normalize("   "))
    }
}
