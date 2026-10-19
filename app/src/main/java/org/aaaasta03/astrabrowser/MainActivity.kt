package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.net.URLDecoder
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.PageExtractionController
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController

/**
 * First native Astra shell. GeckoView owns webpage rendering; the start page,
 * navigation controls and drawer are native Android UI owned by Astra.
 */
class MainActivity : Activity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var addressBar: EditText
    private lateinit var tabCountButton: TextView
    private lateinit var reloadButton: TextView

    private data class BrowserTab(
        val session: GeckoSession,
        var url: String? = null,
        var isHome: Boolean = true,
        var isLoading: Boolean = false,
        var loadProgress: Int = 0,
        var isDesktopMode: Boolean = false,
        var title: String? = null,
        var shouldActivateOnNavigation: Boolean = false,
        val isPrivateMode: Boolean = false
    )

    private val tabs = mutableListOf<BrowserTab>()
    private val maxOpenTabs = 10
    private var currentTabIndex = 0
    private var restoringTabs = false
    private val currentTab: BrowserTab
        get() = tabs[currentTabIndex]
    private lateinit var homeSearch: EditText
    private lateinit var geckoView: GeckoView
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var homeView: View
    private lateinit var menuScrim: View
    private lateinit var menuPanel: View

    private var showingHome = true
    private var menuOpen = false

    private val pageBackground = Color.rgb(15, 18, 20)
    private val panelColor = Color.rgb(25, 29, 32)
    private val fieldColor = Color.rgb(37, 42, 45)
    private val primaryText = Color.rgb(242, 246, 245)
    private val secondaryText = Color.rgb(160, 174, 174)
    private val accentColor = Color.rgb(143, 221, 199)

    private lateinit var browserPrefs: SharedPreferences
    private val androidPermissionRequestCode = 4182
    private val importBookmarksRequestCode = 4183
    private val exportBookmarksRequestCode = 4184
    private val deniedDevicePermissionsPreference = "denied_device_permissions"
    private var pendingAndroidPermissionCallback: GeckoSession.PermissionDelegate.Callback? = null
    private var pendingAndroidPermissions: Array<String> = emptyArray()
    private var trackingProtectionMenuItem: View? = null
    private var globalPrivacyControlMenuItem: View? = null
    private var selectedSearchEngine = UrlNormalizer.SearchEngine.DUCKDUCKGO
    private val bookmarks = mutableListOf<Bookmark>()
    private enum class BookmarkSort { RECENT, TITLE, URL, FAVORITES_FIRST }

    private data class HistoryItem(val url: String, val visitedAt: Long, var title: String? = null)
    private val history = mutableListOf<HistoryItem>()

    private data class ClosedTab(val url: String, val title: String?, val isDesktopMode: Boolean)
    private val recentlyClosedTabs = mutableListOf<ClosedTab>()

    private data class Bookmark(
        var title: String,
        val url: String,
        var folder: String = "Unsorted",
        var isFavorite: Boolean = false
    )

    private data class DownloadItem(val name: String, val bytes: Long, val completedAt: Long)
    private val downloads = mutableListOf<DownloadItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The menu reads persistent settings while the UI is being built.
        // Initialize preferences before constructing any views.
        browserPrefs = getSharedPreferences("astra_browser_library", MODE_PRIVATE)
        selectedSearchEngine = UrlNormalizer.SearchEngine.fromId(
            browserPrefs.getString("search_engine", null)
        )
        window.statusBarColor = pageBackground
        window.navigationBarColor = pageBackground
        window.decorView.systemUiVisibility = 0

        val root = FrameLayout(this).apply {
            setBackgroundColor(pageBackground)
            fitsSystemWindows = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageBackground)
        }
        column.addView(buildTopBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
        ))

        val content = FrameLayout(this).apply {
            setBackgroundColor(pageBackground)
        }
        homeView = buildHomePage()
        geckoView = GeckoView(this).apply {
            visibility = View.GONE
            setBackgroundColor(pageBackground)
        }
        content.addView(geckoView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        content.addView(homeView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        loadingProgressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(accentColor)
            progressBackgroundTintList = ColorStateList.valueOf(fieldColor)
            contentDescription = "Page loading progress"
        }
        content.addView(loadingProgressBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            dp(3),
            Gravity.TOP
        ))
        column.addView(content, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        column.addView(buildBottomBar(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        ))
        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        menuScrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            setOnClickListener { closeMenu() }
        }
        root.addView(menuScrim, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        menuPanel = buildMenuPanel().apply {
            visibility = View.GONE
            elevation = dp(16).toFloat()
        }
        val menuWidth = minOf(dp(340), resources.displayMetrics.widthPixels - dp(28))
        root.addView(menuPanel, FrameLayout.LayoutParams(
            menuWidth, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END
        ))
        setContentView(root)

        homeSearch.hint = "Search with ${selectedSearchEngine.label}"
        loadBrowserLibrary()

        runtime = GeckoRuntime.create(
            applicationContext,
            GeckoRuntimeSettings.Builder()
                .globalPrivacyControlEnabled(isGlobalPrivacyControlEnabled())
                .build()
        )
        restoreOpenTabs()
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(pageBackground)
        }
        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 15f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), 0, dp(8), 0)
            background = rounded(fieldColor, 18)
            setSelectAllOnFocus(false)
        }
        val go = smallButton("GO", accentColor, pageBackground) { loadAddress(addressBar.text?.toString().orEmpty()) }
        val menu = iconButton("⋮", "Open Astra menu") { openMenu() }
        bar.addView(addressBar, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginEnd = dp(6)
        })
        bar.addView(go, LinearLayout.LayoutParams(dp(56), dp(46)).apply {
            marginEnd = dp(4)
        })
        bar.addView(menu, LinearLayout.LayoutParams(dp(42), dp(46)))
        addressBar.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                loadAddress(addressBar.text?.toString().orEmpty())
                true
            } else false
        }
        return bar
    }

    private fun buildHomePage(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageBackground)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        val star = TextView(this).apply {
            text = "✦"
            textSize = 46f
            gravity = Gravity.CENTER
            setTextColor(accentColor)
        }
        body.addView(star, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
        ))

        val title = TextView(this).apply {
            text = "ASTRA"
            textSize = 30f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        body.addView(title)

        val tagline = TextView(this).apply {
            text = "Browse beyond."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(secondaryText)
        }
        body.addView(tagline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
        ))

        val spacer = View(this)
        body.addView(spacer, LinearLayout.LayoutParams(1, dp(34)))

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        homeSearch = EditText(this).apply {
            hint = "Search the web"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(16), 0, dp(10), 0)
            background = rounded(fieldColor, 24)
        }
        val searchButton = smallButton("Search", accentColor, pageBackground) {
            loadAddress(homeSearch.text?.toString().orEmpty())
        }
        searchRow.addView(homeSearch, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(8)
        })
        searchRow.addView(searchButton, LinearLayout.LayoutParams(dp(94), dp(54)))
        body.addView(searchRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ))
        homeSearch.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadAddress(homeSearch.text?.toString().orEmpty())
                true
            } else false
        }

        val shortcutsTitle = sectionLabel("QUICK LINKS")
        body.addView(shortcutsTitle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply {
            topMargin = dp(24)
        })

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shortcuts.addView(shortcut("DuckDuckGo") {
            loadAddress("https://duckduckgo.com/")
        }, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginEnd = dp(6) })
        shortcuts.addView(shortcut("Wikipedia") {
            loadAddress("https://wikipedia.org/")
        }, LinearLayout.LayoutParams(0, dp(70), 1f).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
        shortcuts.addView(shortcut("GitHub") {
            loadAddress("https://github.com/")
        }, LinearLayout.LayoutParams(0, dp(70), 1f).apply { marginStart = dp(6) })
        body.addView(shortcuts, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(70)
        ))

        val privacyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(panelColor, 20)
        }
        val privacyTitle = TextView(this).apply {
            text = "A calmer way to browse"
            textSize = 17f
            setTextColor(primaryText)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        privacyCard.addView(privacyTitle)
        val privacyText = TextView(this).apply {
            text = "Astra is an early browser prototype. Privacy controls, tracker blocking, and extension management are still being developed."
            textSize = 13f
            setTextColor(secondaryText)
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        privacyCard.addView(privacyText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        body.addView(privacyCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(26) })

        scroll.addView(body)
        return scroll
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(panelColor)
        }
        bar.addView(iconButton("‹", "Go back") {
            if (!currentTab.isHome) currentTab.session.goBack()
        }, equalButtonParams())
        bar.addView(iconButton("›", "Go forward") {
            if (!currentTab.isHome) currentTab.session.goForward()
        }, equalButtonParams())
        bar.addView(iconButton("⌂", "Home") {
            showHome()
        }, equalButtonParams())
        reloadButton = iconButton("↻", "Reload page") {
            handleReloadStopClick()
        } as TextView
        bar.addView(reloadButton, equalButtonParams())
        tabCountButton = iconButton("▢ ${tabs.size}", "Show open tabs") {
            showTabsDialog()
        } as TextView
        bar.addView(tabCountButton, equalButtonParams())
        return bar
    }

    private fun buildMenuPanel(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(panelColor)
            isFillViewport = true
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(18), dp(28))
            setBackgroundColor(panelColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = TextView(this).apply {
            text = "✦  ASTRA"
            textSize = 22f
            setTextColor(accentColor)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        val close = iconButton("×", "Close menu") { closeMenu() }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(48)))
        menu.addView(header)

        menu.addView(sectionLabel("BROWSE"), menuLabelParams())
        menu.addView(menuItem("Home", "Return to Astra start page") { showHome() })
        menu.addView(menuItem("New tab", "Open a regular browser tab") {
            createTab(switchTo = true)
        })
        menu.addView(menuItem("New private tab", "Browse without saving history or restoring this tab after restart") {
            createTab(switchTo = true, privateMode = true)
            Toast.makeText(this, "Private tab opened. Close it to end this private session.", Toast.LENGTH_LONG).show()
        })
        menu.addView(menuItem("Duplicate current tab", "Open this page in another tab") {
            duplicateCurrentTab()
        })
        menu.addView(menuItem("Switch tabs", "View and close open tabs") {
            showTabsDialog()
        })
        menu.addView(menuItem("Reopen closed tab", "Restore the most recently closed webpage") {
            reopenLastClosedTab()
        })
        menu.addView(menuItem("Recently closed tabs", "Choose from recently closed webpages") {
            showRecentlyClosedTabsDialog()
        })
        menu.addView(menuItem("Bookmark this page", "Save the current address") {
            bookmarkCurrentPage()
        })
        menu.addView(menuItem("Share current page", "Send this page to another app") {
            shareCurrentPage()
        })
        menu.addView(menuItem("Copy page address", "Copy the current URL to clipboard") {
            copyCurrentPageAddress()
        })
        menu.addView(menuItem("Find in page", "Search for text on this webpage") {
            showFindInPageDialog()
        })
        menu.addView(menuItem("Reader mode", "Extract the article into a clean reading view") {
            showReaderMode()
        })
        menu.addView(menuItem("Desktop site", "Switch the current tab to desktop layout") {
            toggleDesktopSite()
        })
        menu.addView(menuItem("Bookmarks", "Saved pages") { showBookmarksDialog() })
        menu.addView(menuItem("Favorites", "Open starred bookmarks") { showBookmarksDialog(favoritesOnly = true) })
        menu.addView(menuItem("Bookmark folders", "Browse and organize saved pages") { showBookmarkFoldersDialog() })
        menu.addView(menuItem("Import bookmarks", "Merge bookmarks from an Astra JSON backup") {
            importBookmarks()
        })
        menu.addView(menuItem("Export bookmarks", "Save a backup of your bookmarks as JSON") {
            exportBookmarks()
        })
        menu.addView(menuItem("History", "Recently visited pages") { showHistoryDialog() })
        menu.addView(menuItem("Downloads", "View and open downloaded files") { showDownloadsDialog() })
        menu.addView(menuItem("Search engine", "Choose the default web search provider") {
            showSearchEngineDialog()
        })
        menu.addView(menuItem("Homepage", "Choose the page opened in new tabs") {
            showHomepageDialog()
        })

        menu.addView(sectionLabel("PRIVACY & TOOLS"), menuLabelParams().apply {
            topMargin = dp(22)
        })
        trackingProtectionMenuItem = menuItem(
            "Tracking protection: ${if (isTrackingProtectionEnabled()) "On" else "Off"}",
            "Toggle GeckoView's built-in tracker blocking"
        ) {
            setTrackingProtectionEnabled(!isTrackingProtectionEnabled())
        }
        menu.addView(trackingProtectionMenuItem)
        globalPrivacyControlMenuItem = menuItem(
            "Global Privacy Control: ${if (isGlobalPrivacyControlEnabled()) "On" else "Off"}",
            "Ask websites not to sell or share your personal data"
        ) {
            setGlobalPrivacyControlEnabled(!isGlobalPrivacyControlEnabled())
        }
        menu.addView(globalPrivacyControlMenuItem)
        menu.addView(menuItem("Settings & privacy", "Privacy status and roadmap") {
            showPrivacyInfo()
        })
        menu.addView(menuItem("Clear browsing data", "Remove cookies, site data, cache and history") {
            showClearBrowsingDataDialog()
        })
        menu.addView(menuItem("Clear recent history", "Remove visited-page entries only") {
            AlertDialog.Builder(this)
                .setTitle("Clear recent history?")
                .setMessage("This removes saved history entries but keeps cookies, site data, bookmarks, tabs, and downloads.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear history") { _, _ ->
                    history.clear()
                    persistHistory()
                    Toast.makeText(this, "Browsing history cleared", Toast.LENGTH_SHORT).show()
                }
                .show()
        })
        menu.addView(menuItem("Extensions", "WebExtensions support is in development") {
            showComingSoon("Extension manager")
        })

        menu.addView(sectionLabel("ABOUT"), menuLabelParams().apply {
            topMargin = dp(22)
        })
        menu.addView(menuItem("About Astra", "Prototype build information") {
            AlertDialog.Builder(this)
                .setTitle("Astra Browser")
                .setMessage("Astra Browser 0.1.0\n\nAn experimental GeckoView-based Android browser. Built for ARM64; minimum Android 8.0 (API 26).")
                .setPositiveButton("OK", null)
                .show()
        })
        scroll.addView(menu)
        return scroll
    }

    private fun menuItem(title: String, subtitle: String, action: () -> Unit): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(6), dp(8))
            background = rounded(panelColor, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                closeMenu()
                action()
            }
        }
        item.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(primaryText)
        })
        item.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(secondaryText)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        return item
    }

    private fun shortcut(label: String, action: () -> Unit): View =
        TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            background = rounded(panelColor, 16)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun sectionLabel(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(accentColor)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun menuLabelParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
    )

    private fun equalButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun iconButton(label: String, description: String, action: () -> Unit): View =
        TextView(this).apply {
            text = label
            contentDescription = description
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(primaryText)
            background = rounded(panelColor, 14)
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun smallButton(label: String, backgroundColor: Int, textColor: Int, action: () -> Unit): View =
        Button(this).apply {
            text = label
            contentDescription = label
            textSize = 13f
            setAllCaps(false)
            setTextColor(textColor)
            background = rounded(backgroundColor, 16)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun handleReloadStopClick() {
        if (currentTab.isLoading) {
            currentTab.session.stop()
            currentTab.isLoading = false
            loadingProgressBar.visibility = View.GONE
            updateReloadStopButton()
            return
        }

        if (!currentTab.isHome) {
            currentTab.session.reload()
        } else {
            homeSearch.requestFocus()
        }
    }

    private fun updateReloadStopButton() {
        if (!::reloadButton.isInitialized || tabs.isEmpty()) return
        val loading = currentTab.isLoading
        reloadButton.text = if (loading) "×" else "↻"
        reloadButton.contentDescription = when {
            loading -> "Stop loading"
            currentTab.isHome -> "Focus search"
            else -> "Reload page"
        }
    }

    private fun loadAddress(input: String) {
        val destination = UrlNormalizer.normalize(input, selectedSearchEngine)
        if (destination.isBlank()) {
            Toast.makeText(this, "Enter a web address or search term", Toast.LENGTH_SHORT).show()
            return
        }
        addressBar.setText(destination)
        currentTab.url = destination
        currentTab.isHome = false
        showingHome = false
        homeView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        geckoView.setSession(currentTab.session)
        currentTab.session.loadUri(destination)
    }

    private fun showHome() {
        closeMenu()
        currentTab.isHome = true
        currentTab.url = null
        showingHome = true
        homeView.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
        addressBar.setText("")
        homeSearch.setText("")
        updateTabCount()
        persistOpenTabsIfReady()
    }

    private fun openMenu() {
        menuOpen = true
        menuScrim.visibility = View.VISIBLE
        menuPanel.visibility = View.VISIBLE
        menuScrim.bringToFront()
        menuPanel.bringToFront()
    }

    private fun closeMenu() {
        menuOpen = false
        menuPanel.visibility = View.GONE
        menuScrim.visibility = View.GONE
    }

    private fun showComingSoon(feature: String) {
        Toast.makeText(this, "$feature is planned for a future milestone", Toast.LENGTH_SHORT).show()
    }

    private fun showSearchEngineDialog() {
        val engines = UrlNormalizer.SearchEngine.values()
        val labels = engines.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Default search engine")
            .setSingleChoiceItems(labels, engines.indexOf(selectedSearchEngine)) { dialog, which ->
                val choice = engines.getOrNull(which) ?: return@setSingleChoiceItems
                selectedSearchEngine = choice
                browserPrefs.edit().putString("search_engine", choice.id).apply()
                homeSearch.hint = "Search with ${choice.label}"
                Toast.makeText(this, "Search engine set to ${choice.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isGlobalPrivacyControlEnabled(): Boolean =
        browserPrefs.getBoolean("global_privacy_control_enabled", true)

    private fun setGlobalPrivacyControlEnabled(enabled: Boolean) {
        browserPrefs.edit().putBoolean("global_privacy_control_enabled", enabled).apply()
        runtime.settings.setGlobalPrivacyControl(enabled)
        val title = (globalPrivacyControlMenuItem as? LinearLayout)?.getChildAt(0) as? TextView
        title?.text = "Global Privacy Control: ${if (enabled) "On" else "Off"}"
        Toast.makeText(
            this,
            if (enabled) "Global Privacy Control enabled" else "Global Privacy Control disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun isTrackingProtectionEnabled(): Boolean =
        browserPrefs.getBoolean("tracking_protection_enabled", true)

    private fun setTrackingProtectionEnabled(enabled: Boolean) {
        browserPrefs.edit().putBoolean("tracking_protection_enabled", enabled).apply()
        tabs.forEach { tab -> tab.session.settings.setUseTrackingProtection(enabled) }
        val title = (trackingProtectionMenuItem as? LinearLayout)?.getChildAt(0) as? TextView
        title?.text = "Tracking protection: ${if (enabled) "On" else "Off"}"
        Toast.makeText(
            this,
            if (enabled) "Tracking protection enabled" else "Tracking protection disabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showPrivacyInfo() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & security")
            .setMessage(
                "Current protections:\n" +
                    "• GeckoView tracking protection is ${if (isTrackingProtectionEnabled()) "on" else "off"} for browser tabs.\n" +
                    "• Global Privacy Control is ${if (isGlobalPrivacyControlEnabled()) "on" else "off"}; sites may choose how to respond.\n" +
                    "• Astra requests location, camera, or microphone access only when a website asks.\n" +
                    "• Location and camera/microphone requests require an Astra prompt; camera and microphone also require Android permission.\n" +
                    "• Other unsupported site permissions are denied by default.\n" +
                    "• Cleartext HTTP is disabled at the Android app level.\n" +
                    "• Clear browsing data removes saved history and GeckoView site data/permissions.\n\n" +
                    "Still in development:\n" +
                    "• Advanced anti-fingerprinting controls.\n" +
                    "• Automatic private-session data clearing.\n" +
                    "• Full site-permission management and extension permissions."
            )
            .setNeutralButton("Android app permissions") { _, _ -> openAndroidAppSettings() }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openAndroidAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Could not open Android app settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showClearBrowsingDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear browsing data?")
            .setMessage(
                "Astra will close all open tabs and clear saved browsing history, cookies, " +
                    "site storage, caches, and saved site permissions. Bookmarks and downloaded " +
                    "files will be kept."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear data") { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        // GeckoView warns that open sessions can recreate data after a clear, so close every
        // session before invoking StorageController.clearData().
        val sessionsToClose = tabs.toList()
        geckoView.releaseSession()
        sessionsToClose.forEach { it.session.close() }
        tabs.clear()
        currentTabIndex = 0
        persistOpenTabsIfReady()

        history.clear()
        persistHistory()
        showingHome = true
        homeView.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
        addressBar.setText("")
        homeSearch.setText("")
        updateTabCount()

        try {
            runtime.storageController.clearData(StorageController.ClearFlags.ALL).accept(
                { _ ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        createTab(switchTo = true)
                        Toast.makeText(
                            this,
                            "Browsing data cleared. Bookmarks and downloads were kept.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                { error ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        createTab(switchTo = true)
                        Toast.makeText(
                            this,
                            "History cleared, but site data could not be fully cleared: " +
                                (error?.localizedMessage ?: "unknown error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (error: Exception) {
            createTab(switchTo = true)
            Toast.makeText(
                this,
                "History cleared, but site data could not be fully cleared: " +
                    (error.localizedMessage ?: "unknown error"),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadBrowserLibrary() {
        bookmarks.clear()
        try {
            val savedBookmarks = JSONArray(browserPrefs.getString("bookmarks", "[]"))
            for (i in 0 until savedBookmarks.length()) {
                when (val value = savedBookmarks.opt(i)) {
                    is JSONObject -> {
                        val url = value.optString("url")
                        if (isWebUrl(url)) {
                            val title = value.optString("title")
                                .takeIf { it.isNotBlank() }
                                ?: defaultBookmarkTitle(url)
                            bookmarks.add(Bookmark(
                                title, url,
                                value.optString("folder", "Unsorted").trim().take(80).ifBlank { "Unsorted" },
                                value.optBoolean("favorite", false)
                            ))
                        }
                    }
                    is String -> {
                        // Migrate bookmarks saved by older Astra builds (URL-only strings).
                        if (isWebUrl(value)) {
                            bookmarks.add(Bookmark(defaultBookmarkTitle(value), value, "Unsorted"))
                        }
                    }
                }
            }
        } catch (_: Exception) {
            bookmarks.clear()
        }

        history.clear()
        try {
            val savedHistory = JSONArray(browserPrefs.getString("history", "[]"))
            for (i in 0 until savedHistory.length()) {
                val item = savedHistory.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val time = item.optLong("visitedAt", 0L)
                val title = item.optString("title").takeIf { it.isNotBlank() }
                if ((url.startsWith("https://") || url.startsWith("http://")) && time > 0L) {
                    history.add(HistoryItem(url, time, title))
                }
            }
        } catch (_: Exception) {
            history.clear()
        }

        recentlyClosedTabs.clear()
        try {
            val savedClosedTabs = JSONArray(browserPrefs.getString("recently_closed_tabs", "[]"))
            for (i in 0 until minOf(savedClosedTabs.length(), 10)) {
                val item = savedClosedTabs.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (isWebUrl(url)) {
                    val title = item.optString("title").takeIf { it.isNotBlank() }
                    recentlyClosedTabs.add(ClosedTab(url, title, item.optBoolean("desktop", false)))
                }
            }
        } catch (_: Exception) {
            recentlyClosedTabs.clear()
        }

        downloads.clear()
        try {
            val savedDownloads = JSONArray(browserPrefs.getString("downloads", "[]"))
            for (i in 0 until savedDownloads.length()) {
                val item = savedDownloads.optJSONObject(i) ?: continue
                val name = safeDownloadFileName(item.optString("name"))
                val file = File(downloadDirectory(), name)
                val completedAt = item.optLong("completedAt", 0L)
                if (file.isFile && completedAt > 0L) {
                    downloads.add(DownloadItem(name, file.length(), completedAt))
                }
            }
        } catch (_: Exception) {
            downloads.clear()
        }
    }

    private fun persistBookmarks() {
        val data = JSONArray()
        bookmarks.forEach { bookmark ->
            data.put(
                JSONObject()
                    .put("title", bookmark.title)
                    .put("url", bookmark.url)
                    .put("folder", bookmark.folder)
                    .put("favorite", bookmark.isFavorite)
            )
        }
        browserPrefs.edit().putString("bookmarks", data.toString()).apply()
    }

    private fun persistHistory() {
        val data = JSONArray()
        history.forEach { item ->
            data.put(JSONObject().put("url", item.url).put("visitedAt", item.visitedAt).put("title", item.title ?: ""))
        }
        browserPrefs.edit().putString("history", data.toString()).apply()
    }

    private fun recordHistory(url: String, session: GeckoSession? = null) {
        val matchingTab = if (session != null) tabs.firstOrNull { it.session === session }
            else tabs.firstOrNull { it.url == url }
        if (matchingTab?.isPrivateMode == true) return
        val existingTitle = history.firstOrNull { it.url == url }?.title
        val tabTitle = tabs.firstOrNull { it.url == url }?.title
        history.removeAll { it.url == url }
        history.add(0, HistoryItem(url, System.currentTimeMillis(), tabTitle ?: existingTitle))
        while (history.size > 200) history.removeAt(history.lastIndex)
        persistHistory()
    }

    private fun updateHistoryTitle(url: String, title: String?) {
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() && it != url } ?: return
        val item = history.firstOrNull { it.url == url } ?: return
        if (item.title != cleanTitle) {
            item.title = cleanTitle
            persistHistory()
        }
    }

    private fun persistRecentlyClosedTabs() {
        val data = JSONArray()
        recentlyClosedTabs.forEach { tab ->
            data.put(
                JSONObject()
                    .put("url", tab.url)
                    .put("title", tab.title ?: "")
                    .put("desktop", tab.isDesktopMode)
            )
        }
        browserPrefs.edit().putString("recently_closed_tabs", data.toString()).apply()
    }

    private fun rememberClosedTab(tab: BrowserTab) {
        val url = tab.url?.takeIf { isWebUrl(it) } ?: return
        recentlyClosedTabs.removeAll { it.url == url }
        recentlyClosedTabs.add(0, ClosedTab(url, tab.title, tab.isDesktopMode))
        while (recentlyClosedTabs.size > 10) recentlyClosedTabs.removeAt(recentlyClosedTabs.lastIndex)
        persistRecentlyClosedTabs()
    }

    private fun persistDownloads() {
        val data = JSONArray()
        downloads.forEach { item ->
            data.put(JSONObject()
                .put("name", item.name)
                .put("bytes", item.bytes)
                .put("completedAt", item.completedAt))
        }
        browserPrefs.edit().putString("downloads", data.toString()).apply()
    }

    private fun downloadDirectory(): File =
        File(filesDir, "downloads").apply { if (!exists()) mkdirs() }

    private fun suggestedDownloadName(response: WebResponse): String {
        val disposition = response.headers.entries.firstOrNull {
            it.key.equals("content-disposition", ignoreCase = true)
        }?.value.orEmpty()
        val declaredName = Regex(
            """filename\*?=(?:UTF-8'')?["']?([^;"']+)""",
            RegexOption.IGNORE_CASE
        ).find(disposition)?.groupValues?.getOrNull(1)?.trim()
        val decodedName = declaredName?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
        }
        val uriName = Uri.parse(response.uri).lastPathSegment
        return safeDownloadFileName(decodedName?.takeIf { it.isNotBlank() } ?: uriName.orEmpty())
            .ifBlank { "download-${System.currentTimeMillis()}" }
    }

    private fun safeDownloadFileName(candidate: String): String {
        val lastPart = candidate.substringAfterLast('/').substringAfterLast('\\')
        val safe = lastPart
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trim('.')
            .take(120)
        return safe.ifBlank { "download-${System.currentTimeMillis()}" }
    }

    private fun handleExternalResponse(response: WebResponse) {
        val body = response.body
        if (body == null) {
            Toast.makeText(this, "This download has no response body", Toast.LENGTH_LONG).show()
            return
        }

        val fileName = suggestedDownloadName(response)
        val sourceHost = Uri.parse(response.uri).host ?: "web content"
        AlertDialog.Builder(this)
            .setTitle("Download file?")
            .setMessage("$fileName\n\nFrom: $sourceHost\n\nOnly save files you trust.")
            .setNegativeButton("Cancel") { _, _ ->
                try { body.close() } catch (_: Exception) { }
            }
            .setPositiveButton("Download") { _, _ ->
                startDownload(response, fileName)
            }
            .setOnCancelListener {
                try { body.close() } catch (_: Exception) { }
            }
            .show()
    }

    private fun startDownload(response: WebResponse, suggestedName: String) {
        val body = response.body
        if (body == null) {
            Toast.makeText(this, "Download failed: response body unavailable", Toast.LENGTH_LONG).show()
            return
        }

        val directory = downloadDirectory()
        val base = safeDownloadFileName(suggestedName)
        val dot = base.lastIndexOf('.')
        val stem = if (dot > 0) base.substring(0, dot) else base
        val extension = if (dot > 0) base.substring(dot) else ""
        var target = File(directory, base)
        var suffix = 1
        while (target.exists()) {
            target = File(directory, "$stem ($suffix)$extension")
            suffix++
        }
        val finalFile = target
        val temporaryFile = File(directory, ".${finalFile.name}.part")

        Toast.makeText(this, "Downloading ${finalFile.name}…", Toast.LENGTH_SHORT).show()
        Thread({
            try {
                temporaryFile.outputStream().buffered().use { output ->
                    body.use { input -> input.copyTo(output) }
                }
                if (!temporaryFile.renameTo(finalFile)) {
                    temporaryFile.copyTo(finalFile, overwrite = false)
                    temporaryFile.delete()
                }
                val item = DownloadItem(
                    name = finalFile.name,
                    bytes = finalFile.length(),
                    completedAt = System.currentTimeMillis()
                )
                runOnUiThread {
                    downloads.removeAll { it.name == item.name }
                    downloads.add(0, item)
                    while (downloads.size > 100) {
                        val removed = downloads.removeAt(downloads.lastIndex)
                        File(directory, removed.name).delete()
                    }
                    persistDownloads()
                    Toast.makeText(this, "Saved ${item.name}", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                try { body.close() } catch (_: Exception) { }
                temporaryFile.delete()
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${error.localizedMessage ?: "I/O error"}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }, "AstraDownload").start()
    }

    private fun showDownloadsDialog() {
        lateinit var dialog: AlertDialog

        val contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val searchField = EditText(this).apply {
            hint = "Search downloads by filename"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(fieldColor, 14)
        }
        contentPanel.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) })

        val listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listPanel)
        }
        contentPanel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
        ))

        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun renderDownloads(query: String) {
            listPanel.removeAllViews()
            val trimmedQuery = query.trim()
            val entries = downloads.toList().filter { item ->
                trimmedQuery.isEmpty() || item.name.contains(trimmedQuery, ignoreCase = true)
            }

            if (entries.isEmpty()) {
                listPanel.addView(TextView(this).apply {
                    text = when {
                        downloads.isEmpty() ->
                            "No downloads yet. Files downloaded from pages will appear here."
                        trimmedQuery.isNotEmpty() ->
                            "No downloads match \"$trimmedQuery\"."
                        else -> "No downloads yet."
                    }
                    textSize = 14f
                    setTextColor(secondaryText)
                    setPadding(dp(12), dp(18), dp(12), dp(18))
                })
                return
            }

            entries.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(panelColor, 12)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openDownloadedFile(item) }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete download?")
                            .setMessage(item.name)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Delete") { _, _ ->
                                File(downloadDirectory(), item.name).delete()
                                downloads.removeAll { it.name == item.name }
                                persistDownloads()
                                dialog.setTitle("Downloads · ${downloads.size}")
                                renderDownloads(searchField.text?.toString().orEmpty())
                            }
                            .show()
                        true
                    }
                }
                row.addView(TextView(this).apply {
                    text = item.name
                    textSize = 14f
                    maxLines = 2
                    setTextColor(primaryText)
                })
                row.addView(TextView(this).apply {
                    text = "${formatBytes(item.bytes)} · ${timeFormat.format(Date(item.completedAt))}"
                    textSize = 11f
                    setTextColor(secondaryText)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
                listPanel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Downloads · ${downloads.size}")
            .setView(contentPanel)
            .setNegativeButton("Close", null)
            .setPositiveButton("Clear all", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Delete all downloaded files?")
                    .setMessage("This deletes files saved by Astra in its private download storage.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete all") { _, _ ->
                        downloads.toList().forEach { File(downloadDirectory(), it.name).delete() }
                        downloads.clear()
                        persistDownloads()
                        dialog.setTitle("Downloads · 0")
                        renderDownloads("")
                        searchField.setText("")
                    }
                    .show()
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderDownloads(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderDownloads("")
        dialog.show()
    }

    private fun openDownloadedFile(item: DownloadItem) {
        val file = File(downloadDirectory(), item.name)
        if (!file.isFile) {
            downloads.removeAll { it.name == item.name }
            persistDownloads()
            Toast.makeText(this, "File no longer exists", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val extension = item.name.substringAfterLast('.', "").lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Open download"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app can open this file type", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Unable to open file: ${error.localizedMessage ?: "error"}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun applyDesktopMode(tab: BrowserTab, enabled: Boolean) {
        tab.isDesktopMode = enabled
        val settings = tab.session.settings
        settings.setUserAgentMode(
            if (enabled) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
            else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        )
        settings.setViewportMode(
            if (enabled) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
            else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        )
    }

    private fun toggleDesktopSite() {
        if (currentTab.isHome || !isWebUrl(currentTab.url)) {
            Toast.makeText(this, "Open a webpage before switching site layout", Toast.LENGTH_SHORT).show()
            return
        }

        val enabled = !currentTab.isDesktopMode
        applyDesktopMode(currentTab, enabled)
        persistOpenTabsIfReady()
        currentTab.session.reload()
        Toast.makeText(
            this,
            if (enabled) "Desktop site enabled for this tab" else "Desktop site disabled for this tab",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyCurrentPageAddress() {
        val url = currentTab.url
        if (!isWebUrl(url)) {
            Toast.makeText(this, "Open a webpage before copying its address", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Page address", url))
        Toast.makeText(this, "Page address copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareCurrentPage() {
        val url = currentTab.url
        if (!isWebUrl(url)) {
            Toast.makeText(this, "Open a webpage before sharing", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, url)
        }
        startActivity(Intent.createChooser(shareIntent, "Share page"))
    }

    private fun showReaderMode() {
        val tab = currentTab
        val sourceUrl = tab.url.orEmpty()
        if (tab.isHome || !isWebUrl(sourceUrl)) {
            Toast.makeText(this, "Open a webpage before using Reader mode", Toast.LENGTH_SHORT).show()
            return
        }

        var cancelled = false
        val loading = AlertDialog.Builder(this)
            .setTitle("Reader mode")
            .setMessage("Checking for an article…")
            .setNegativeButton("Cancel", null)
            .create()
        loading.setOnShowListener {
            loading.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelled = true
                loading.dismiss()
            }
        }
        loading.show()

        val extractor = tab.session.sessionPageExtractor

        fun finishLoading() {
            if (loading.isShowing) loading.dismiss()
        }

        try {
            extractor.getPageMetadata().accept(
                { metadata ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || cancelled) {
                            finishLoading()
                            return@runOnUiThread
                        }

                        if (!metadata.isReaderable) {
                            finishLoading()
                            Toast.makeText(
                                this,
                                "No main article was detected. Open a specific article and try Reader mode again.",
                                Toast.LENGTH_LONG
                            ).show()
                            return@runOnUiThread
                        }

                        loading.setMessage("Extracting article text…")
                        try {
                            extractor.getPageContent(
                                PageExtractionController.ContentParams(true, true)
                            ).accept(
                                { extracted ->
                                    runOnUiThread {
                                        if (isFinishing || isDestroyed || cancelled) {
                                            finishLoading()
                                            return@runOnUiThread
                                        }
                                        finishLoading()
                                        val text = extracted.trim()
                                        if (text.isBlank()) {
                                            Toast.makeText(
                                                this,
                                                "No readable text was found on this page",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            return@runOnUiThread
                                        }
                                        showReaderContent(
                                            tab.title?.takeIf { it.isNotBlank() } ?: sourceUrl,
                                            sourceUrl,
                                            text
                                        )
                                    }
                                },
                                { error ->
                                    runOnUiThread {
                                        if (isFinishing || isDestroyed || cancelled) {
                                            finishLoading()
                                            return@runOnUiThread
                                        }
                                        finishLoading()
                                        Toast.makeText(
                                            this,
                                            "Reader mode couldn't extract this page: " +
                                                (error?.localizedMessage ?: "unsupported page"),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            )
                        } catch (error: Exception) {
                            finishLoading()
                            if (!cancelled) {
                                Toast.makeText(
                                    this,
                                    "Reader mode unavailable: " +
                                        (error.localizedMessage ?: "unsupported page"),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                },
                { error ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed || cancelled) {
                            finishLoading()
                            return@runOnUiThread
                        }
                        finishLoading()
                        Toast.makeText(
                            this,
                            "Reader mode couldn't identify the article: " +
                                (error?.localizedMessage ?: "unknown error"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        } catch (error: Exception) {
            finishLoading()
            if (!cancelled) {
                Toast.makeText(
                    this,
                    "Reader mode unavailable: " +
                        (error.localizedMessage ?: "unsupported page"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showReaderContent(title: String, sourceUrl: String, articleText: String) {
        val body = TextView(this).apply {
            text = articleText
            textSize = 18f
            setTextColor(Color.rgb(35, 35, 35))
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setTextIsSelectable(true)
            setLineSpacing(dp(4).toFloat(), 1.08f)
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(250, 248, 242))
            addView(body, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(title.take(120))
            .setView(scroll)
            .setNeutralButton("Share source") { _, _ -> shareText(sourceUrl, title) }
            .setPositiveButton("Done", null)
            .show()
    }

    private fun shareText(url: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        startActivity(Intent.createChooser(intent, "Share article"))
    }

    private fun showFindInPageDialog() {
        if (currentTab.isHome || !isWebUrl(currentTab.url)) {
            Toast.makeText(this, "Open a webpage before searching its text", Toast.LENGTH_SHORT).show()
            return
        }

        val finder = currentTab.session.finder
        val searchField = EditText(this).apply {
            hint = "Find on this page"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 16f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(fieldColor, 14)
        }
        val resultLabel = TextView(this).apply {
            text = "Enter text, then tap Next or Previous."
            textSize = 13f
            setTextColor(secondaryText)
            setPadding(dp(4), dp(10), dp(4), dp(4))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(searchField, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
            ))
            addView(resultLabel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        finder.setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Find in page")
            .setView(content)
            .setNegativeButton("Close", null)
            .setNeutralButton("Previous", null)
            .setPositiveButton("Next", null)
            .create()

        val runSearch: (Int) -> Unit = { flags ->
            val query = searchField.text?.toString().orEmpty()
            if (query.isBlank()) {
                finder.clear()
                resultLabel.text = "Enter text to find."
            } else {
                finder.find(query, flags).accept(
                    { result ->
                        runOnUiThread {
                            if (dialog.isShowing) {
                                val finderResult = result
                                resultLabel.text = when {
                                    finderResult == null -> "Searching…"
                                    !finderResult.found -> "No matches found"
                                    finderResult.total > 0 ->
                                        "Match ${finderResult.current} of ${finderResult.total}"
                                    finderResult.current > 0 -> "Match ${finderResult.current}"
                                    else -> "Searching…"
                                }
                            }
                        }
                    },
                    { error ->
                        runOnUiThread {
                            if (dialog.isShowing) {
                                resultLabel.text =
                                    "Search failed: " + (error?.localizedMessage ?: "unknown error")
                            }
                        }
                    }
                )
            }
        }

        searchField.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                runSearch(GeckoSession.FINDER_FIND_FORWARD)
                true
            } else {
                false
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runSearch(GeckoSession.FINDER_FIND_FORWARD)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                runSearch(GeckoSession.FINDER_FIND_BACKWARDS)
            }
        }
        dialog.setOnDismissListener { finder.clear() }
        dialog.show()
        searchField.requestFocus()
    }

    private fun defaultBookmarkTitle(url: String): String =
        Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: url

    private fun bookmarkCurrentPage() {
        val url = currentTab.url?.takeIf { isWebUrl(it) }
        if (url == null) {
            Toast.makeText(this, "Open a webpage before bookmarking", Toast.LENGTH_SHORT).show()
            return
        }
        if (bookmarks.any { it.url == url }) {
            Toast.makeText(this, "Already bookmarked", Toast.LENGTH_SHORT).show()
            return
        }
        val title = currentTab.title?.trim()?.takeIf { it.isNotEmpty() && it != url }
            ?: defaultBookmarkTitle(url)
        bookmarks.add(0, Bookmark(title, url, "Unsorted"))
        persistBookmarks()
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
    }

    private fun getBookmarkSort(): BookmarkSort {
        val saved = browserPrefs.getString("bookmark_sort", BookmarkSort.RECENT.name)
        return runCatching { BookmarkSort.valueOf(saved ?: BookmarkSort.RECENT.name) }
            .getOrDefault(BookmarkSort.RECENT)
    }

    private fun BookmarkSort.label(): String = when (this) {
        BookmarkSort.RECENT -> "Recent"
        BookmarkSort.TITLE -> "Title A–Z"
        BookmarkSort.URL -> "URL"
        BookmarkSort.FAVORITES_FIRST -> "Favorites first"
    }

    private fun cycleBookmarkSort() {
        val values = BookmarkSort.values()
        val next = values[(getBookmarkSort().ordinal + 1) % values.size]
        browserPrefs.edit().putString("bookmark_sort", next.name).apply()
        Toast.makeText(this, "Bookmark sorting: ${next.label()}", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog(folderFilter: String? = null, favoritesOnly: Boolean = false) {
        lateinit var dialog: AlertDialog

        val contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val searchField = EditText(this).apply {
            hint = "Search bookmarks by title or URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(fieldColor, 14)
        }
        contentPanel.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) })

        val listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listPanel)
        }
        contentPanel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(340)
        ))

        fun renderBookmarks(query: String) {
            listPanel.removeAllViews()
            val trimmedQuery = query.trim()
            val filtered = bookmarks.toList().filter { bookmark ->
                (folderFilter == null || bookmark.folder == folderFilter) &&
                (!favoritesOnly || bookmark.isFavorite) &&
                (trimmedQuery.isEmpty() ||
                    bookmark.title.contains(trimmedQuery, ignoreCase = true) ||
                    bookmark.url.contains(trimmedQuery, ignoreCase = true))
            }
            val entries = when (getBookmarkSort()) {
                BookmarkSort.RECENT -> filtered
                BookmarkSort.TITLE -> filtered.sortedBy { it.title.lowercase() }
                BookmarkSort.URL -> filtered.sortedBy { it.url.lowercase() }
                BookmarkSort.FAVORITES_FIRST -> filtered.sortedWith(
                    compareByDescending<Bookmark> { it.isFavorite }.thenBy { it.title.lowercase() }
                )
            }

            if (entries.isEmpty()) {
                listPanel.addView(TextView(this).apply {
                    text = when {
                        bookmarks.isEmpty() ->
                            "No bookmarks yet. Open a page and choose ‘Bookmark this page’ from the menu."
                        favoritesOnly ->
                            "No favorites yet. Long-press a bookmark and mark it as a favorite."
                        trimmedQuery.isNotEmpty() ->
                            "No bookmarks match \"$trimmedQuery\"."
                        else -> "No bookmarks yet."
                    }
                    textSize = 14f
                    setTextColor(secondaryText)
                    setPadding(dp(12), dp(18), dp(12), dp(18))
                })
                return
            }

            entries.forEach { bookmark ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(panelColor, 12)
                    isClickable = true
                    isFocusable = true
                    addView(TextView(this@MainActivity).apply {
                        text = bookmark.title
                        textSize = 15f
                        setTextColor(primaryText)
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        maxLines = 2
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = "${if (bookmark.isFavorite) "★ Favorite · " else ""}📁 ${bookmark.folder}"
                        textSize = 11f
                        setTextColor(accentColor)
                        setPadding(0, dp(4), 0, 0)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = bookmark.url
                        textSize = 12f
                        setTextColor(secondaryText)
                        maxLines = 2
                        setPadding(0, dp(4), 0, 0)
                    })
                    setOnClickListener {
                        dialog.dismiss()
                        loadAddress(bookmark.url)
                    }
                    setOnLongClickListener {
                        dialog.dismiss()
                        showBookmarkOptions(bookmark)
                        true
                    }
                }
                listPanel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(when {
                favoritesOnly -> "Favorites · ${bookmarks.count { it.isFavorite }}"
                folderFilter == null -> "Bookmarks · ${bookmarks.size}"
                else -> "$folderFilter · ${bookmarks.count { it.folder == folderFilter }}"
            })
            .setView(contentPanel)
            .setNeutralButton("Sort: ${getBookmarkSort().label()}") { _, _ ->
                cycleBookmarkSort()
                showBookmarksDialog(folderFilter, favoritesOnly)
            }
            .setNegativeButton("Close", null)
            .setPositiveButton("Bookmark current page", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                bookmarkCurrentPage()
                dialog.dismiss()
                showBookmarksDialog()
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderBookmarks(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderBookmarks("")
        dialog.show()
    }

    private fun getBookmarkFolders(): List<String> {
        val stored = try {
            val array = JSONArray(browserPrefs.getString("bookmark_folders", "[]"))
            (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { name -> name.isNotBlank() } }
        } catch (_: Exception) {
            emptyList()
        }
        return (stored + bookmarks.map { it.folder }).distinct().filterNot { it.equals("All bookmarks", true) }.sorted()
    }

    private fun showBookmarkFoldersDialog() {
        val folders = (listOf("All bookmarks") + getBookmarkFolders())
        AlertDialog.Builder(this)
            .setTitle("Bookmark folders")
            .setItems(folders.toTypedArray()) { _, which ->
                if (which == 0) showBookmarksDialog()
                else showBookmarksDialog(folders[which])
            }
            .setPositiveButton("Create folder") { _, _ -> promptCreateBookmarkFolder() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun promptCreateBookmarkFolder(bookmark: Bookmark? = null) {
        val input = EditText(this).apply {
            hint = "Folder name"
            setSingleLine(true)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(fieldColor, 14)
        }
        AlertDialog.Builder(this)
            .setTitle(if (bookmark == null) "Create bookmark folder" else "Move bookmark to folder")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (bookmark == null) "Create" else "Move") { _, _ ->
                val name = input.text?.toString()?.trim()?.take(80).orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "Folder name cannot be empty", Toast.LENGTH_SHORT).show()
                } else if (name.equals("All bookmarks", ignoreCase = true)) {
                    Toast.makeText(this, "Choose another folder name", Toast.LENGTH_SHORT).show()
                } else {
                    if (bookmark != null) {
                        if (name !in getBookmarkFolders()) {
                            browserPrefs.edit().putString(
                                "bookmark_folders",
                                JSONArray(getBookmarkFolders() + name).toString()
                            ).apply()
                        }
                        bookmark.folder = name
                        persistBookmarks()
                        showBookmarksDialog(name)
                    } else {
                        browserPrefs.edit().putString(
                            "bookmark_folders",
                            JSONArray((getBookmarkFolders() + name).distinct()).toString()
                        ).apply()
                        Toast.makeText(this, "Folder created", Toast.LENGTH_SHORT).show()
                        showBookmarkFoldersDialog()
                    }
                }
            }
            .show()
    }

    private fun showMoveBookmarkDialog(bookmark: Bookmark) {
        val folders = (getBookmarkFolders() + "Create new folder…").distinct()
        AlertDialog.Builder(this)
            .setTitle("Move to folder")
            .setItems(folders.toTypedArray()) { _, which ->
                if (folders[which] == "Create new folder…") promptCreateBookmarkFolder(bookmark)
                else {
                    bookmark.folder = folders[which]
                    persistBookmarks()
                    showBookmarksDialog(bookmark.folder)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarkOptions(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle(bookmark.title)
            .setItems(arrayOf(
                if (bookmark.isFavorite) "Remove from favorites" else "Add to favorites",
                "Move to folder", "Rename bookmark", "Remove bookmark"
            )) { _, which ->
                when (which) {
                    0 -> {
                        bookmark.isFavorite = !bookmark.isFavorite
                        persistBookmarks()
                        showBookmarksDialog()
                    }
                    1 -> showMoveBookmarkDialog(bookmark)
                    2 -> showRenameBookmarkDialog(bookmark)
                    3 -> AlertDialog.Builder(this)
                        .setTitle("Remove bookmark?")
                        .setMessage(bookmark.title)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove") { _, _ ->
                            bookmarks.removeAll { it.url == bookmark.url }
                            persistBookmarks()
                            showBookmarksDialog()
                        }
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameBookmarkDialog(bookmark: Bookmark) {
        val titleInput = EditText(this).apply {
            hint = "Bookmark title"
            setSingleLine(true)
            setText(bookmark.title)
            setSelection(text.length)
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(fieldColor, 14)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename bookmark")
            .setView(titleInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = titleInput.text?.toString()?.trim().orEmpty()
                if (newTitle.isBlank()) {
                    Toast.makeText(this, "Bookmark title cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    bookmark.title = newTitle
                    persistBookmarks()
                    showBookmarksDialog()
                }
            }
            .show()
    }

    private fun showHistoryDialog() {
        lateinit var dialog: AlertDialog

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val searchField = EditText(this).apply {
            hint = "Search history by URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(fieldColor, 14)
        }
        content.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        content.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(320)
        ))

        val timeFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        fun renderHistory(query: String) {
            panel.removeAllViews()
            val trimmedQuery = query.trim()
            val entries = history.toList().filter { item ->
                trimmedQuery.isEmpty() || item.url.contains(trimmedQuery, ignoreCase = true) ||
                    item.title.orEmpty().contains(trimmedQuery, ignoreCase = true)
            }

            if (entries.isEmpty()) {
                panel.addView(TextView(this).apply {
                    text = when {
                        history.isEmpty() -> "No browsing history yet. Pages you open will appear here."
                        trimmedQuery.isNotEmpty() -> "No history matches \"$trimmedQuery\"."
                        else -> "No browsing history yet."
                    }
                    textSize = 14f
                    setTextColor(secondaryText)
                    setPadding(dp(12), dp(18), dp(12), dp(18))
                })
                return
            }

            entries.forEach { item ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(panelColor, 12)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        loadAddress(item.url)
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove history entry?")
                            .setMessage(item.url)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove") { _, _ ->
                                history.removeAll { it.url == item.url }
                                persistHistory()
                                dialog.setTitle("History · ${history.size}")
                                renderHistory(searchField.text?.toString().orEmpty())
                            }
                            .show()
                        true
                    }
                }
                item.title?.takeIf { it.isNotBlank() && it != item.url }?.let { pageTitle ->
                    row.addView(TextView(this).apply {
                        text = pageTitle
                        textSize = 14f
                        maxLines = 2
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(primaryText)
                    })
                }
                row.addView(TextView(this).apply {
                    text = item.url
                    textSize = 12f
                    maxLines = 2
                    setTextColor(secondaryText)
                })
                row.addView(TextView(this).apply {
                    text = timeFormat.format(Date(item.visitedAt))
                    textSize = 11f
                    setTextColor(secondaryText)
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) })
                panel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("History · ${history.size}")
            .setView(content)
            .setNegativeButton("Close", null)
            .setPositiveButton("Clear history", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Clear browsing history?")
                    .setMessage("This removes the history saved by Astra on this device.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Clear") { _, _ ->
                        history.clear()
                        persistHistory()
                        dialog.setTitle("History · 0")
                        renderHistory("")
                    }
                    .show()
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderHistory(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderHistory("")
        dialog.show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isWebUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            (uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun restoreOpenTabs() {
        val savedTabs = try {
            JSONArray(browserPrefs.getString("open_tabs", "[]"))
        } catch (_: Exception) {
            JSONArray()
        }

        // Accept the previous URL/null format and the newer object format.
        data class RestoredTab(val url: String?, val desktopMode: Boolean, val title: String?)
        val restoredTabs = mutableListOf<RestoredTab>()
        for (i in 0 until minOf(savedTabs.length(), maxOpenTabs)) {
            val value = savedTabs.opt(i)
            if (value is JSONObject) {
                val url = value.optString("url").takeIf { isWebUrl(it) }
                val title = value.optString("title").takeIf { it.isNotBlank() }
                restoredTabs.add(RestoredTab(url, value.optBoolean("desktop", false), title))
            } else {
                val url = (value as? String)?.takeIf { isWebUrl(it) }
                restoredTabs.add(RestoredTab(url, false, null))
            }
        }

        if (restoredTabs.isEmpty()) {
            createTab(switchTo = true)
            return
        }

        val requestedIndex = browserPrefs.getInt("selected_tab", 0)
        restoringTabs = true
        try {
            restoredTabs.forEach { savedTab ->
                createTab(switchTo = false)
                val tab = tabs.lastOrNull() ?: return@forEach
                applyDesktopMode(tab, savedTab.desktopMode)
                tab.title = savedTab.title
                val url = savedTab.url
                if (url != null) {
                    tab.url = url
                    tab.isHome = false
                    tab.session.loadUri(url)
                }
            }
        } finally {
            restoringTabs = false
        }

        if (tabs.isEmpty()) {
            createTab(switchTo = true)
        } else {
            switchToTab(requestedIndex.coerceIn(0, tabs.lastIndex))
        }
        persistOpenTabsIfReady()
    }

    private fun persistOpenTabsIfReady() {
        if (!::browserPrefs.isInitialized || restoringTabs) return
        val regularTabs = tabs.filterNot { it.isPrivateMode }.take(maxOpenTabs)
        val data = JSONArray()
        regularTabs.forEach { tab ->
            val savedUrl: Any = if (tab.isHome || !isWebUrl(tab.url)) JSONObject.NULL else tab.url!!
            data.put(
                JSONObject()
                    .put("url", savedUrl)
                    .put("desktop", tab.isDesktopMode)
                    .put("title", tab.title ?: "")
            )
        }
        val currentRegularIndex = regularTabs.indexOfFirst { it === tabs.getOrNull(currentTabIndex) }
        val selected = if (currentRegularIndex >= 0) currentRegularIndex
            else currentTabIndex.coerceAtMost((regularTabs.size - 1).coerceAtLeast(0))
        browserPrefs.edit()
            .putString("open_tabs", data.toString())
            .putInt("selected_tab", selected)
            .apply()
    }

    private fun getCustomHomepage(): String =
        browserPrefs.getString("custom_homepage", "")?.trim().orEmpty().takeIf { isWebUrl(it) }.orEmpty()

    private fun showHomepageDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com (leave empty for Astra start page)"
            setSingleLine(true)
            setText(getCustomHomepage())
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(fieldColor, 14)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("New-tab homepage")
            .setMessage("Choose a website to open in new regular tabs. Leave blank to use Astra's built-in start page. Existing tabs won't change.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Use Astra start page") { _, _ ->
                browserPrefs.edit().remove("custom_homepage").apply()
                Toast.makeText(this, "Astra start page restored", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Save", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val value = input.text?.toString()?.trim().orEmpty()
                        if (value.isBlank()) {
                            browserPrefs.edit().remove("custom_homepage").apply()
                            dialog.dismiss()
                            Toast.makeText(this, "Astra start page restored", Toast.LENGTH_SHORT).show()
                        } else if (!isWebUrl(value)) {
                            input.error = "Enter a valid http:// or https:// address"
                        } else {
                            browserPrefs.edit().putString("custom_homepage", value).apply()
                            dialog.dismiss()
                            Toast.makeText(this, "Homepage saved for new tabs", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun createTab(switchTo: Boolean, privateMode: Boolean = false) {
        if (tabs.size >= maxOpenTabs) {
            Toast.makeText(this, "Astra supports up to $maxOpenTabs open tabs", Toast.LENGTH_SHORT).show()
            return
        }

        val settings = GeckoSessionSettings.Builder().usePrivateMode(privateMode).build()
        val newSession = GeckoSession(settings)
        newSession.open(runtime)
        configureSession(newSession)
        val newTab = BrowserTab(session = newSession, isPrivateMode = privateMode)
        tabs.add(newTab)
        // Only apply the custom homepage to explicitly created regular tabs.
        // Restored tabs and private tabs keep their existing behavior.
        if (switchTo && !privateMode && !restoringTabs) {
            getCustomHomepage().takeIf { it.isNotBlank() }?.let { homepage ->
                newTab.url = homepage
                newTab.isHome = false
                newSession.loadUri(homepage)
            }
        }
        if (switchTo) {
            switchToTab(tabs.lastIndex)
        } else {
            updateTabCount()
        }
    }


    private fun permissionOrigin(uri: String): String =
        Uri.parse(uri).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "This website"

    private fun isLocationPermission(permission: String): Boolean =
        permission == Manifest.permission.ACCESS_COARSE_LOCATION ||
            permission == Manifest.permission.ACCESS_FINE_LOCATION

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun isAndroidPermissionGranted(permission: String): Boolean =
        if (isLocationPermission(permission)) hasLocationPermission()
        else ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun deniedDevicePermissions(): MutableSet<String> =
        (browserPrefs.getStringSet(deniedDevicePermissionsPreference, emptySet())
            ?: emptySet()).toMutableSet()

    private fun clearDenialsForGrantedPermissions(permissions: Array<out String>) {
        val denied = deniedDevicePermissions()
        var changed = false
        permissions.forEach { permission ->
            if (isAndroidPermissionGranted(permission)) {
                changed = denied.remove(if (isLocationPermission(permission)) "location" else permission) || changed
            }
        }
        if (permissions.any { isLocationPermission(it) } && hasLocationPermission()) {
            changed = denied.remove("location") || changed
        }
        if (changed) browserPrefs.edit().putStringSet(deniedDevicePermissionsPreference, denied).apply()
    }

    private fun rememberDevicePermissionResults(permissions: Array<out String>) {
        val denied = deniedDevicePermissions()
        permissions.filterNot { isLocationPermission(it) }.forEach { permission ->
            if (isAndroidPermissionGranted(permission)) denied.remove(permission) else denied.add(permission)
        }
        if (permissions.any { isLocationPermission(it) }) {
            if (hasLocationPermission()) denied.remove("location") else denied.add("location")
        }
        browserPrefs.edit().putStringSet(deniedDevicePermissionsPreference, denied).apply()
    }

    private fun handleAndroidPermissionRequest(
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback
    ) {
        val requested = permissions?.distinct()?.toTypedArray() ?: emptyArray()
        val supported = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (requested.isEmpty() || requested.any { it !in supported }) {
            callback.reject()
            return
        }

        clearDenialsForGrantedPermissions(requested)
        if (requested.all { isAndroidPermissionGranted(it) }) {
            callback.grant()
            return
        }

        val deniedBefore = deniedDevicePermissions()
        val previouslyBlocked = requested.any { permission ->
            !isAndroidPermissionGranted(permission) &&
                (if (isLocationPermission(permission)) "location" else permission) in deniedBefore
        }
        if (previouslyBlocked) {
            Toast.makeText(
                this,
                "A device permission was previously blocked. Review Android app permissions to try again.",
                Toast.LENGTH_LONG
            ).show()
            callback.reject()
            return
        }
        if (pendingAndroidPermissionCallback != null || isFinishing || isDestroyed) {
            callback.reject()
            return
        }
        pendingAndroidPermissionCallback = callback
        pendingAndroidPermissions = requested
        requestPermissions(requested, androidPermissionRequestCode)
    }

    private fun importBookmarks() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        try {
            startActivityForResult(intent, importBookmarksRequestCode)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportBookmarks() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "astra-bookmarks.json")
        }
        try {
            startActivityForResult(intent, exportBookmarksRequestCode)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated by Android, retained for compatibility with this Activity-based app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            importBookmarksRequestCode -> readBookmarksBackup(uri)
            exportBookmarksRequestCode -> writeBookmarksBackup(uri)
        }
    }

    private fun writeBookmarksBackup(uri: Uri) {
        val data = JSONObject()
            .put("format", "astra-bookmarks")
            .put("version", 1)
            .put("bookmarks", JSONArray().apply {
                bookmarks.forEach { bookmark ->
                    put(JSONObject().put("title", bookmark.title).put("url", bookmark.url).put("folder", bookmark.folder).put("favorite", bookmark.isFavorite))
                }
            })
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(data.toString(2).toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("Could not open selected file")
            Toast.makeText(this, "Exported ${bookmarks.size} bookmarks", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Bookmark export failed: ${error.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readBookmarksBackup(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("Could not read selected file")
            require(raw.length <= 2_000_000) { "Backup file is too large" }
            val root = JSONObject(raw)
            require(root.optString("format") == "astra-bookmarks") { "This is not an Astra bookmark backup" }
            require(root.optInt("version", -1) == 1) { "Unsupported bookmark backup version" }
            val entries = root.optJSONArray("bookmarks") ?: throw IllegalArgumentException("Backup has no bookmarks list")
            val knownUrls = bookmarks.map { it.url }.toMutableSet()
            var added = 0
            var skipped = 0
            val imported = mutableListOf<Bookmark>()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i)
                if (entry == null) {
                    skipped++
                    continue
                }
                val url = entry.optString("url").trim()
                if (!isWebUrl(url) || url.length > 8192 || !knownUrls.add(url)) {
                    skipped++
                    continue
                }
                val title = entry.optString("title").trim().take(500).ifBlank { defaultBookmarkTitle(url) }
                val folder = entry.optString("folder", "Unsorted").trim().take(80).ifBlank { "Unsorted" }
                imported.add(Bookmark(title, url, folder, entry.optBoolean("favorite", false)))
                added++
            }
            bookmarks.addAll(0, imported)
            persistBookmarks()
            Toast.makeText(this, "Imported $added bookmarks; skipped $skipped invalid or duplicate entries", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Toast.makeText(this, "Bookmark import failed: ${error.localizedMessage ?: "invalid backup"}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != androidPermissionRequestCode) return

        val callback = pendingAndroidPermissionCallback
        val requested = pendingAndroidPermissions
        pendingAndroidPermissionCallback = null
        pendingAndroidPermissions = emptyArray()
        rememberDevicePermissionResults(requested)

        val granted = requested.isNotEmpty() && requested.all { isAndroidPermissionGranted(it) }
        if (granted) callback?.grant()
        else {
            callback?.reject()
            Toast.makeText(this, "Android device permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleContentPermissionRequest(
        perm: GeckoSession.PermissionDelegate.ContentPermission
    ): GeckoResult<Int> {
        val valueAllow = GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
        val valueDeny = GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        if (perm.value == valueAllow) return GeckoResult.fromValue(valueAllow)
        if (perm.value == valueDeny) return GeckoResult.fromValue(valueDeny)

        // Location is the only content permission Astra currently handles with a prompt.
        // Notifications, persistent storage, tracking exceptions, and unknown types remain
        // denied until their complete UI and revocation flows are implemented.
        if (perm.permission != GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION) {
            return GeckoResult.fromValue(valueDeny)
        }
        if (isFinishing || isDestroyed) return GeckoResult.fromValue(valueDeny)

        val origin = permissionOrigin(perm.uri)
        val result = GeckoResult<Int>()
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow $origin to access your location?")
            .setMessage(
                "Only allow location access on sites you trust. Android may also ask for device " +
                    "permission. Saved site permissions can be reset with Clear browsing data."
            )
            .setNegativeButton("Block") { _, _ -> result.complete(valueDeny) }
            .setPositiveButton("Allow") { _, _ -> result.complete(valueAllow) }
            .create()
        dialog.setOnCancelListener { result.complete(valueDeny) }
        dialog.setOnShowListener { perm.notifyShown() }
        dialog.show()
        return result
    }

    private fun handleMediaPermissionRequest(
        uri: String,
        video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
        callback: GeckoSession.PermissionDelegate.MediaCallback
    ) {
        val wantsCamera = !video.isNullOrEmpty()
        val wantsMicrophone = !audio.isNullOrEmpty()
        if ((!wantsCamera && !wantsMicrophone) ||
            (wantsCamera && !isAndroidPermissionGranted(Manifest.permission.CAMERA)) ||
            (wantsMicrophone && !isAndroidPermissionGranted(Manifest.permission.RECORD_AUDIO)) ||
            isFinishing || isDestroyed
        ) {
            callback.reject()
            return
        }

        val requestedDevices = buildList {
            if (wantsCamera) add("camera")
            if (wantsMicrophone) add("microphone")
        }.joinToString(" and ")
        val origin = permissionOrigin(uri)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow $origin to use $requestedDevices?")
            .setMessage(
                "Astra will provide only the requested device source for this request. " +
                    "Block access if you do not trust this site."
            )
            .setNegativeButton("Block") { _, _ -> callback.reject() }
            .setPositiveButton("Allow") { _, _ ->
                val selectedVideo = if (wantsCamera) video?.firstOrNull() else null
                val selectedAudio = if (wantsMicrophone) audio?.firstOrNull() else null
                if ((wantsCamera && selectedVideo == null) ||
                    (wantsMicrophone && selectedAudio == null)
                ) callback.reject()
                else callback.grant(selectedVideo?.id, selectedAudio?.id)
            }
            .create()
        dialog.setOnCancelListener { callback.reject() }
        dialog.show()
    }

    private fun configureSession(newSession: GeckoSession) {
        newSession.settings.setUseTrackingProtection(isTrackingProtectionEnabled())
        newSession.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onAndroidPermissionsRequest(
                session: GeckoSession,
                permissions: Array<out String>?,
                callback: GeckoSession.PermissionDelegate.Callback
            ) {
                handleAndroidPermissionRequest(permissions, callback)
            }

            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int> = handleContentPermissionRequest(perm)

            override fun onMediaPermissionRequest(
                session: GeckoSession,
                uri: String,
                video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                callback: GeckoSession.PermissionDelegate.MediaCallback
            ) {
                handleMediaPermissionRequest(uri, video, audio, callback)
            }
        }

        newSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                handleExternalResponse(response)
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                tab.title = title?.trim()?.takeIf { it.isNotEmpty() }
                if (!tab.isPrivateMode) tab.url?.let { updateHistoryTitle(it, tab.title) }
                persistOpenTabsIfReady()
            }
        }
        newSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url.isNullOrBlank()) return
                if (!url.startsWith("https://") && !url.startsWith("http://")) return

                val tab = tabs.firstOrNull { it.session === session } ?: return
                val urlChanged = tab.url != url
                tab.url = url
                tab.isHome = false
                val tabIndex = tabs.indexOfFirst { it.session === session }
                if (tab.shouldActivateOnNavigation && tabIndex >= 0) {
                    tab.shouldActivateOnNavigation = false
                    switchToTab(tabIndex)
                } else if (tabs.getOrNull(currentTabIndex) === tab) {
                    if (addressBar.text?.toString() != url) addressBar.setText(url)
                    showingHome = false
                    homeView.visibility = View.GONE
                    geckoView.visibility = View.VISIBLE
                }

                // Single-page apps (such as YouTube) can change the URL without a new
                // page-stop event. Save changed locations here so History matches the bar.
                if (urlChanged) {
                    recordHistory(url, session)
                    persistOpenTabsIfReady()
                }
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                if (tabs.size >= maxOpenTabs) {
                    Toast.makeText(
                        this@MainActivity,
                        "Astra supports up to $maxOpenTabs open tabs",
                        Toast.LENGTH_SHORT
                    ).show()
                    return null
                }

                // GeckoView opens and loads this newly-created session after this result
                // is returned. Do not call open() or loadUri() here.
                val sourceTab = tabs.firstOrNull { it.session === session }
                val popupPrivate = sourceTab?.isPrivateMode == true
                val popupSession = GeckoSession(
                    GeckoSessionSettings.Builder().usePrivateMode(popupPrivate).build()
                )
                configureSession(popupSession)
                val initialUrl = uri.takeIf { isWebUrl(it) }
                tabs.add(
                    BrowserTab(
                        session = popupSession,
                        url = initialUrl,
                        isHome = initialUrl == null,
                        shouldActivateOnNavigation = true,
                        isPrivateMode = popupPrivate
                    )
                )
                updateTabCount()
                persistOpenTabsIfReady()
                return GeckoResult.fromValue(popupSession)
            }
        }
        newSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                tab.isLoading = true
                tab.loadProgress = 0
                if (tabs.getOrNull(currentTabIndex)?.session === session) {
                    loadingProgressBar.progress = 0
                    loadingProgressBar.visibility = View.VISIBLE
                    updateReloadStopButton()
                }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                tab.loadProgress = progress.coerceIn(0, 100)
                if (tabs.getOrNull(currentTabIndex)?.session !== session) return
                loadingProgressBar.progress = tab.loadProgress
                if (tab.isLoading && progress in 0..99) {
                    loadingProgressBar.visibility = View.VISIBLE
                } else {
                    loadingProgressBar.visibility = View.GONE
                }
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                tab.isLoading = false
                if (tabs.getOrNull(currentTabIndex)?.session === session) {
                    loadingProgressBar.visibility = View.GONE
                    updateReloadStopButton()
                }
                if (!success) return
                val url = tab.url ?: return
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    recordHistory(url, session)
                }
            }
        }
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index
        val tab = currentTab
        geckoView.setSession(tab.session)
        loadingProgressBar.progress = tab.loadProgress
        loadingProgressBar.visibility =
            if (tab.isLoading && tab.loadProgress < 100) View.VISIBLE else View.GONE
        updateReloadStopButton()
        showingHome = tab.isHome
        homeView.visibility = if (tab.isHome) View.VISIBLE else View.GONE
        geckoView.visibility = if (tab.isHome) View.GONE else View.VISIBLE
        addressBar.setText(tab.url.orEmpty())
        homeSearch.setText("")
        updateTabCount()
        persistOpenTabsIfReady()
    }

    private fun closeTabAt(index: Int) {
        if (index !in tabs.indices) return

        val wasCurrent = index == currentTabIndex
        val closing = tabs.removeAt(index)
        if (!closing.isPrivateMode) rememberClosedTab(closing)
        closing.session.close()

        if (tabs.isEmpty()) {
            currentTabIndex = 0
            createTab(switchTo = true)
            return
        }

        if (index < currentTabIndex) currentTabIndex--
        if (wasCurrent && currentTabIndex >= tabs.size) {
            currentTabIndex = tabs.lastIndex
        }
        switchToTab(currentTabIndex)
    }

    private fun duplicateCurrentTab() {
        val source = currentTab
        val previousCount = tabs.size
        createTab(switchTo = true, privateMode = source.isPrivateMode)
        if (tabs.size == previousCount) return

        val duplicate = currentTab
        val sourceUrl = source.url?.takeIf { isWebUrl(it) }
        duplicate.title = source.title
        applyDesktopMode(duplicate, source.isDesktopMode)

        if (source.isHome || sourceUrl == null) {
            duplicate.url = null
            duplicate.title = null
            duplicate.isHome = true
            switchToTab(tabs.lastIndex)
        } else {
            duplicate.url = sourceUrl
            duplicate.isHome = false
            duplicate.session.loadUri(sourceUrl)
        }

        persistOpenTabsIfReady()
        Toast.makeText(this, "Tab duplicated", Toast.LENGTH_SHORT).show()
    }

    private fun reopenLastClosedTab() {
        if (recentlyClosedTabs.isEmpty()) {
            Toast.makeText(this, "No recently closed tabs", Toast.LENGTH_SHORT).show()
            return
        }
        reopenClosedTabAt(0)
    }

    private fun reopenClosedTabAt(index: Int) {
        if (index !in recentlyClosedTabs.indices) return
        if (tabs.size >= maxOpenTabs) {
            Toast.makeText(this, "Close a tab before reopening another", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = recentlyClosedTabs[index]
        val previousCount = tabs.size
        createTab(switchTo = true)
        if (tabs.size == previousCount) return

        val restored = currentTab
        applyDesktopMode(restored, saved.isDesktopMode)
        restored.url = saved.url
        restored.title = saved.title
        restored.isHome = false
        restored.session.loadUri(saved.url)
        recentlyClosedTabs.removeAt(index)
        persistRecentlyClosedTabs()
        persistOpenTabsIfReady()
        Toast.makeText(this, "Reopened closed tab", Toast.LENGTH_SHORT).show()
    }

    private fun showRecentlyClosedTabsDialog() {
        lateinit var dialog: AlertDialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }

        if (recentlyClosedTabs.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "No recently closed tabs. Pages you close will appear here."
                textSize = 14f
                setTextColor(secondaryText)
                setPadding(dp(12), dp(18), dp(12), dp(18))
            })
        } else {
            recentlyClosedTabs.toList().forEachIndexed { index, saved ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(panelColor, 12)
                    isClickable = true
                    isFocusable = true
                    addView(TextView(this@MainActivity).apply {
                        text = saved.title?.takeIf { it.isNotBlank() } ?: defaultBookmarkTitle(saved.url)
                        textSize = 14f
                        maxLines = 2
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(primaryText)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = saved.url
                        textSize = 12f
                        maxLines = 2
                        setTextColor(secondaryText)
                        setPadding(0, dp(4), 0, 0)
                    })
                    if (saved.isDesktopMode) {
                        addView(TextView(this@MainActivity).apply {
                            text = "Desktop site"
                            textSize = 11f
                            setTextColor(accentColor)
                            setPadding(0, dp(4), 0, 0)
                        })
                    }
                    setOnClickListener {
                        dialog.dismiss()
                        reopenClosedTabAt(index)
                    }
                }
                panel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Recently closed tabs · ${recentlyClosedTabs.size}")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun currentTabOrNull(): BrowserTab? = tabs.getOrNull(currentTabIndex)

    private fun updateTabCount() {
        if (::tabCountButton.isInitialized) {
            val privateCount = tabs.count { it.isPrivateMode }
            tabCountButton.text = if (currentTabOrNull()?.isPrivateMode == true) "▣ ${tabs.size}" else "▢ ${tabs.size}"
            tabCountButton.contentDescription =
                "Show ${tabs.size} open tabs, including $privateCount private tabs"
        }
    }

    private fun closeAllTabs() {
        geckoView.releaseSession()
        tabs.toList().forEach { it.session.close() }
        tabs.clear()
        currentTabIndex = 0
        loadingProgressBar.progress = 0
        loadingProgressBar.visibility = View.GONE
        persistOpenTabsIfReady()
        createTab(switchTo = true)
        Toast.makeText(this, "All tabs closed", Toast.LENGTH_SHORT).show()
    }

    private fun closeOtherTabs() {
        if (tabs.size <= 1) {
            Toast.makeText(this, "Only one tab is open", Toast.LENGTH_SHORT).show()
            return
        }

        val keptTab = currentTab
        tabs.toList().filter { it !== keptTab }.forEach { tab ->
            if (!tab.isPrivateMode) rememberClosedTab(tab)
            tab.session.close()
        }
        tabs.clear()
        tabs.add(keptTab)
        currentTabIndex = 0
        switchToTab(0)
        Toast.makeText(this, "Other tabs closed", Toast.LENGTH_SHORT).show()
    }

    private fun showTabsDialog() {
        lateinit var dialog: AlertDialog
        val contentPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val searchField = EditText(this).apply {
            hint = "Search tabs by title or URL"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            textSize = 14f
            setTextColor(primaryText)
            setHintTextColor(secondaryText)
            setPadding(dp(14), 0, dp(12), 0)
            background = rounded(fieldColor, 14)
        }
        contentPanel.addView(searchField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
        ).apply { bottomMargin = dp(8) })

        val listPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listPanel)
        }
        contentPanel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(360)
        ))

        fun renderTabs(query: String) {
            listPanel.removeAllViews()
            val trimmedQuery = query.trim()
            val entries = tabs.mapIndexed { index, tab -> index to tab }.filter { (_, tab) ->
                trimmedQuery.isEmpty() ||
                    tab.title.orEmpty().contains(trimmedQuery, ignoreCase = true) ||
                    tab.url.orEmpty().contains(trimmedQuery, ignoreCase = true) ||
                    (tab.isHome && "Astra start page".contains(trimmedQuery, ignoreCase = true))
            }

            if (entries.isEmpty()) {
                listPanel.addView(TextView(this).apply {
                    text = if (tabs.isEmpty()) {
                        "No open tabs."
                    } else {
                        "No tabs match \"$trimmedQuery\"."
                    }
                    textSize = 14f
                    setTextColor(secondaryText)
                    setPadding(dp(12), dp(18), dp(12), dp(18))
                })
                return
            }

            entries.forEach { (index, tab) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(4), dp(4), dp(4))
                    background = rounded(panelColor, 12)
                }
                val label = TextView(this).apply {
                    val tabName = tab.title?.takeIf { it.isNotBlank() }
                        ?: tab.url
                        ?: "Astra start page"
                    val modeLabel = if (tab.isPrivateMode) "🕶 Private · " else ""
                    val titleLine = (if (index == currentTabIndex) "●  " else "○  ") + modeLabel + tabName
                    text = if (tab.url.isNullOrBlank()) titleLine else "$titleLine\n${tab.url}"
                    textSize = 13f
                    maxLines = 2
                    setTextColor(if (index == currentTabIndex) accentColor else primaryText)
                    setPadding(dp(6), 0, dp(4), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        dialog.dismiss()
                        switchToTab(index)
                    }
                }
                val close = TextView(this).apply {
                    text = "×"
                    textSize = 24f
                    gravity = Gravity.CENTER
                    setTextColor(primaryText)
                    contentDescription = "Close tab"
                    isClickable = true
                    isFocusable = true
                    setPadding(dp(12), 0, dp(12), 0)
                    setOnClickListener {
                        dialog.dismiss()
                        closeTabAt(index)
                        showTabsDialog()
                    }
                }
                row.addView(label, LinearLayout.LayoutParams(0, dp(60), 1f))
                row.addView(close, LinearLayout.LayoutParams(dp(44), dp(48)))
                listPanel.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(66)
                ).apply { bottomMargin = dp(6) })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Open tabs · ${tabs.size}")
            .setView(contentPanel)
            .setNeutralButton("Tab actions", null)
            .setNegativeButton("Done", null)
            .setPositiveButton("New tab", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                createTab(switchTo = true)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Tab actions")
                    .setItems(arrayOf("Close other tabs", "Close all tabs")) { _, which ->
                        when (which) {
                            0 -> {
                                if (tabs.size <= 1) {
                                    Toast.makeText(
                                        this,
                                        "Only one tab is open",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    AlertDialog.Builder(this)
                                        .setTitle("Close other tabs?")
                                        .setMessage(
                                            "Keep the current tab open and close the other " +
                                                "${tabs.size - 1} tab(s). Bookmarks, history, " +
                                                "and downloads will be kept."
                                        )
                                        .setNegativeButton("Cancel", null)
                                        .setPositiveButton("Close other tabs") { _, _ ->
                                            dialog.dismiss()
                                            closeOtherTabs()
                                        }
                                        .show()
                                }
                            }
                            1 -> {
                                AlertDialog.Builder(this)
                                    .setTitle("Close all tabs?")
                                    .setMessage(
                                        "This closes every open tab and opens a fresh start page. " +
                                            "Bookmarks, history, and downloaded files will be kept."
                                    )
                                    .setNegativeButton("Cancel", null)
                                    .setPositiveButton("Close all") { _, _ ->
                                        dialog.dismiss()
                                        closeAllTabs()
                                    }
                                    .show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderTabs(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        renderTabs("")
        dialog.show()
    }

    override fun onBackPressed() {
        when {
            menuOpen -> closeMenu()
            !currentTab.isHome -> currentTab.session.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pendingAndroidPermissionCallback?.reject()
        pendingAndroidPermissionCallback = null
        pendingAndroidPermissions = emptyArray()
        persistOpenTabsIfReady()
        tabs.forEach { tab -> tab.session.close() }
        tabs.clear()
        super.onDestroy()
    }
}
