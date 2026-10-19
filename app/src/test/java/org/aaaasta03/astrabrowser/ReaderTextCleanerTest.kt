package org.aaaasta03.astrabrowser

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextCleanerTest {
    @Test
    fun emptyInputStaysEmpty() {
        assertEquals("", ReaderTextCleaner.clean("  "))
    }
}
