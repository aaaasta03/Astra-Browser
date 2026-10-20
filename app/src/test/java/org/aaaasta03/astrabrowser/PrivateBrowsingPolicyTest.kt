package org.aaaasta03.astrabrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateBrowsingPolicyTest {
    @Test
    fun privateDownloadsAreNotPersisted() {
        assertFalse(PrivateBrowsingPolicy.shouldPersistDownload(true))
        assertTrue(PrivateBrowsingPolicy.shouldPersistDownload(false))
    }

    @Test
    fun downloadsAreVisibleOnlyInMatchingBrowsingMode() {
        assertTrue(PrivateBrowsingPolicy.shouldShowDownload(true, true))
        assertTrue(PrivateBrowsingPolicy.shouldShowDownload(false, false))
        assertFalse(PrivateBrowsingPolicy.shouldShowDownload(true, false))
        assertFalse(PrivateBrowsingPolicy.shouldShowDownload(false, true))
    }

    @Test
    fun privateDownloadsClearOnlyWhenLastPrivateTabCloses() {
        assertFalse(PrivateBrowsingPolicy.shouldClearPrivateDownloads(false, 0))
        assertFalse(PrivateBrowsingPolicy.shouldClearPrivateDownloads(true, 1))
        assertTrue(PrivateBrowsingPolicy.shouldClearPrivateDownloads(true, 0))
    }

    @Test
    fun completedPrivateDownloadRequiresActivePrivateSession() {
        assertTrue(PrivateBrowsingPolicy.shouldKeepCompletedPrivateDownload(false, true))
        assertFalse(PrivateBrowsingPolicy.shouldKeepCompletedPrivateDownload(true, true))
        assertFalse(PrivateBrowsingPolicy.shouldKeepCompletedPrivateDownload(false, false))
    }
}
