package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import org.mozilla.geckoview.WebResponse
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.io.File
import java.net.URLDecoder
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
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
        var shouldActivateOnNavigation: Boolean = false
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
    private var selectedSearchEngine = UrlNormalizer.SearchEngine.DUCKDUCKGO
    private val bookmarks = mutableListOf<Bookmark>()

    private data class HistoryItem(val url: String, val visitedAt: Long, var title: String? = null)
    private val history = mutableListOf<HistoryItem>()

    private data class ClosedTab(val url: String, val title: String?, val isDesktopMode: Boolean)
    private val recentlyClosedTabs = mutableListOf<ClosedTab>()

    private data class Bookmark(var title: String, val url: String)

    private data class DownloadItem(val name: String, val bytes: Long, val completedAt: Long)
    private val downloads = mutableListOf<DownloadItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        browserPrefs = getSharedPreferences("astra_browser_library", MODE_PRIVATE)
        selectedSearchEngine = UrlNormalizer.SearchEngine.fromId(
            browserPrefs.getString("search_engine", null)
        )
        homeSearch.hint = "Search with ${selectedSearchEngine.label}"
        loadBrowserLibrary()

        runtime = GeckoRuntime.create(applicationContext)
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
        menu.addView(menuItem("New tab", "Open a separate browser tab") {
            createTab(switchTo = true)
        })
        menu.addView(menuItem("Switch tabs", "View and close open tabs") {
            showTabsDialog()
        })
        menu.addView(menuItem("Reopen closed tab", "Restore the most recently closed webpage") {
            reopenLastClosedTab()
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
        menu.addView(menuItem("Desktop site", "Switch the current tab to desktop layout") {
            toggleDesktopSite()
        })
        menu.addView(menuItem("Bookmarks", "Saved pages") { showBookmarksDialog() })
        menu.addView(menuItem("History", "Recently visited pages") { showHistoryDialog() })
        menu.addView(menuItem("Downloads", "View and open downloaded files") { showDownloadsDialog() })
        menu.addView(menuItem("Search engine", "Choose the default web search provider") {
            showSearchEngineDialog()
        })

        menu.addView(sectionLabel("PRIVACY & TOOLS"), menuLabelParams().apply {
            topMargin = dp(22)
        })
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

    private fun showPrivacyInfo() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & security")
            .setMessage(
                "Current prototype:\n" +
                    "• Only the Internet permission is declared by Astra.\n" +
                    "• Cleartext HTTP is disabled at the Android app level.\n" +
                    "• The address bar restricts direct navigation to HTTP(S) destinations.\n" +
                    "• Clear browsing data removes saved history and GeckoView site data.\n\n" +
                    "Still in development:\n" +
                    "• Tracker blocking and anti-fingerprinting controls.\n" +
                    "• Private sessions and automatic data clearing.\n" +
                    "• Site-permission controls and extension permissions."
            )
            .setPositiveButton("OK", null)
            .show()
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
                            bookmarks.add(Bookmark(title, url))
                        }
                    }
                    is String -> {
                        // Migrate bookmarks saved by older Astra builds (URL-only strings).
                        if (isWebUrl(value)) {
                            bookmarks.add(Bookmark(defaultBookmarkTitle(value), value))
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

    private fun recordHistory(url: String) {
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
        bookmarks.add(0, Bookmark(title, url))
        persistBookmarks()
        Toast.makeText(this, "Bookmark saved", Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog() {
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
            val entries = bookmarks.toList().filter { bookmark ->
                trimmedQuery.isEmpty() ||
                    bookmark.title.contains(trimmedQuery, ignoreCase = true) ||
                    bookmark.url.contains(trimmedQuery, ignoreCase = true)
            }

            if (entries.isEmpty()) {
                listPanel.addView(TextView(this).apply {
                    text = when {
                        bookmarks.isEmpty() ->
                            "No bookmarks yet. Open a page and choose ‘Bookmark this page’ from the menu."
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
            .setTitle("Bookmarks · ${bookmarks.size}")
            .setView(contentPanel)
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

    private fun showBookmarkOptions(bookmark: Bookmark) {
        AlertDialog.Builder(this)
            .setTitle(bookmark.title)
            .setItems(arrayOf("Rename bookmark", "Remove bookmark")) { _, which ->
                when (which) {
                    0 -> showRenameBookmarkDialog(bookmark)
                    1 -> AlertDialog.Builder(this)
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
        val data = JSONArray()
        tabs.take(maxOpenTabs).forEach { tab ->
            val savedUrl: Any = if (tab.isHome || !isWebUrl(tab.url)) JSONObject.NULL else tab.url!!
            data.put(
                JSONObject()
                    .put("url", savedUrl)
                    .put("desktop", tab.isDesktopMode)
                    .put("title", tab.title ?: "")
            )
        }
        val selected = if (tabs.isEmpty()) 0 else currentTabIndex.coerceIn(0, tabs.lastIndex)
        browserPrefs.edit()
            .putString("open_tabs", data.toString())
            .putInt("selected_tab", selected)
            .apply()
    }

    private fun createTab(switchTo: Boolean) {
        if (tabs.size >= maxOpenTabs) {
            Toast.makeText(this, "Astra supports up to $maxOpenTabs open tabs", Toast.LENGTH_SHORT).show()
            return
        }

        val newSession = GeckoSession()
        newSession.open(runtime)
        configureSession(newSession)
        tabs.add(BrowserTab(session = newSession))
        if (switchTo) {
            switchToTab(tabs.lastIndex)
        } else {
            updateTabCount()
        }
    }

    private fun configureSession(newSession: GeckoSession) {
        newSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                handleExternalResponse(response)
            }

            override fun onTitleChange(session: GeckoSession, title: String?) {
                val tab = tabs.firstOrNull { it.session === session } ?: return
                tab.title = title?.trim()?.takeIf { it.isNotEmpty() }
                tab.url?.let { updateHistoryTitle(it, tab.title) }
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
                    recordHistory(url)
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
                val popupSession = GeckoSession()
                configureSession(popupSession)
                val initialUrl = uri.takeIf { isWebUrl(it) }
                tabs.add(
                    BrowserTab(
                        session = popupSession,
                        url = initialUrl,
                        isHome = initialUrl == null,
                        shouldActivateOnNavigation = true
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
                    recordHistory(url)
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
        rememberClosedTab(closing)
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

    private fun reopenLastClosedTab() {
        if (recentlyClosedTabs.isEmpty()) {
            Toast.makeText(this, "No recently closed tabs", Toast.LENGTH_SHORT).show()
            return
        }
        if (tabs.size >= maxOpenTabs) {
            Toast.makeText(this, "Close a tab before reopening another", Toast.LENGTH_SHORT).show()
            return
        }

        val saved = recentlyClosedTabs.first()
        val oldCount = tabs.size
        createTab(switchTo = true)
        if (tabs.size == oldCount) return

        val restored = currentTab
        applyDesktopMode(restored, saved.isDesktopMode)
        restored.url = saved.url
        restored.title = saved.title
        restored.isHome = false
        restored.session.loadUri(saved.url)
        recentlyClosedTabs.removeAt(0)
        persistRecentlyClosedTabs()
        persistOpenTabsIfReady()
        Toast.makeText(this, "Reopened closed tab", Toast.LENGTH_SHORT).show()
    }

    private fun updateTabCount() {
        if (::tabCountButton.isInitialized) {
            tabCountButton.text = "▢ ${tabs.size}"
            tabCountButton.contentDescription = "Show ${tabs.size} open tabs"
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
            rememberClosedTab(tab)
            tab.session.close()
        }
        tabs.clear()
        tabs.add(keptTab)
        currentTabIndex = 0
        switchToTab(0)
        Toast.makeText(this, "Other tabs closed", Toast.LENGTH_SHORT).show()
    }

    private fun showTabsDialog() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Open tabs · ${tabs.size}")
            .setView(panel)
            .setNeutralButton("Tab actions", null)
            .setNegativeButton("Done", null)
            .setPositiveButton("New tab", null)
            .create()

        tabs.forEachIndexed { index, tab ->
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
                val titleLine = (if (index == currentTabIndex) "●  " else "○  ") + tabName
                text = if (tab.url.isNullOrBlank()) {
                    titleLine
                } else {
                    "$titleLine\n${tab.url}"
                }
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
            panel.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(66)
            ).apply { bottomMargin = dp(6) })
        }

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
        persistOpenTabsIfReady()
        tabs.forEach { tab -> tab.session.close() }
        tabs.clear()
        super.onDestroy()
    }
}
