package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * First native Astra shell. GeckoView owns webpage rendering; the start page,
 * navigation controls and drawer are native Android UI owned by Astra.
 */
class MainActivity : Activity() {
    private lateinit var session: GeckoSession
    private lateinit var addressBar: EditText
    private lateinit var homeSearch: EditText
    private lateinit var geckoView: GeckoView
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

        val runtime = GeckoRuntime.create(applicationContext)
        session = GeckoSession()
        session.open(runtime)
        geckoView.setSession(session)
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
            hint = "Search privately"
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
            if (!showingHome) session.goBack()
        }, equalButtonParams())
        bar.addView(iconButton("›", "Go forward") {
            if (!showingHome) session.goForward()
        }, equalButtonParams())
        bar.addView(iconButton("⌂", "Home") {
            showHome()
        }, equalButtonParams())
        bar.addView(iconButton("↻", "Reload page") {
            if (!showingHome) session.reload() else homeSearch.requestFocus()
        }, equalButtonParams())
        bar.addView(iconButton("☷", "Open browser menu") {
            openMenu()
        }, equalButtonParams())
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
        menu.addView(menuItem("New tab", "Multiple tabs are not available yet") {
            showComingSoon("Multiple tabs")
        })
        menu.addView(menuItem("Bookmarks", "Saved pages") { showComingSoon("Bookmarks") })
        menu.addView(menuItem("History", "Recently visited pages") { showComingSoon("History") })
        menu.addView(menuItem("Downloads", "Downloaded files") { showComingSoon("Downloads") })

        menu.addView(sectionLabel("PRIVACY & TOOLS"), menuLabelParams().apply {
            topMargin = dp(22)
        })
        menu.addView(menuItem("Settings & privacy", "Privacy status and roadmap") {
            showPrivacyInfo()
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

    private fun loadAddress(input: String) {
        val destination = UrlNormalizer.normalize(input)
        if (destination.isBlank()) {
            Toast.makeText(this, "Enter a web address or search term", Toast.LENGTH_SHORT).show()
            return
        }
        addressBar.setText(destination)
        showingHome = false
        homeView.visibility = View.GONE
        geckoView.visibility = View.VISIBLE
        session.loadUri(destination)
    }

    private fun showHome() {
        closeMenu()
        showingHome = true
        homeView.visibility = View.VISIBLE
        geckoView.visibility = View.GONE
        addressBar.setText("")
        homeSearch.setText("")
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

    private fun showPrivacyInfo() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & security")
            .setMessage(
                "Current prototype:\n" +
                    "• Only the Internet permission is declared by Astra.\n" +
                    "• Cleartext HTTP is disabled at the Android app level.\n" +
                    "• The address bar restricts direct navigation to HTTP(S) destinations.\n\n" +
                    "Still in development:\n" +
                    "• Tracker blocking and anti-fingerprinting controls.\n" +
                    "• Private sessions and automatic data clearing.\n" +
                    "• Site-permission controls and extension permissions."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        when {
            menuOpen -> closeMenu()
            !showingHome -> session.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.close()
        super.onDestroy()
    }
}
