package org.aaaasta03.astrabrowser

/** Small platform-independent rules for private-download isolation. */
object PrivateBrowsingPolicy {
    fun shouldPersistDownload(isPrivateMode: Boolean): Boolean = !isPrivateMode

    fun shouldShowDownload(downloadIsPrivateMode: Boolean, currentTabIsPrivateMode: Boolean): Boolean =
        downloadIsPrivateMode == currentTabIsPrivateMode

    fun shouldClearPrivateDownloads(closingTabIsPrivateMode: Boolean, remainingPrivateTabs: Int): Boolean =
        closingTabIsPrivateMode && remainingPrivateTabs == 0

    fun shouldKeepCompletedPrivateDownload(activityFinishing: Boolean, hasOpenPrivateTab: Boolean): Boolean =
        !activityFinishing && hasOpenPrivateTab
}
