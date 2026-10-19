package org.aaaasta03.astrabrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextCleanerTest {
    @Test
    fun convertsLinksToReadableLabels() {
        assertEquals("Read article", ReaderTextCleaner.clean("Read [article](https://example.com)"))
    }

    @Test
    fun removesHeadingAndBoldMarkers() {
        assertEquals("History\nThe old city", ReaderTextCleaner.clean("# History\n**The old city**"))
    }

    @Test
    fun collapsesExcessiveBlankLines() {
        assertEquals("First\n\nSecond", ReaderTextCleaner.clean(" First \n\n\n\nSecond "))
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", ReaderTextCleaner.clean("  "))
    }
}
