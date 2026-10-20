package org.aaaasta03.astrabrowser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Dedicated, scrollable settings page. Values share the browser's existing preference file.
 */
class SettingsActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("astra_browser_library", Context.MODE_PRIVATE) }
    private val background = Color.rgb(15, 18, 27)
    private val surface = Color.rgb(27, 32, 44)
    private val primary = Color.rgb(240, 243, 250)
    private val secondary = Color.rgb(164, 174, 194)
    private val accent = Color.rgb(130, 165, 255)
    private val engines = UrlNormalizer.SearchEngine.values()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = background
        window.navigationBarColor = background
        window.decorView.systemUiVisibility = 0

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(background)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            setTextColor(primary)
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        val title = TextView(this).apply {
            text = "Settings"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(30))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        section(content, "GENERAL", "Personalize how Astra starts and searches.")
        row(content, "Search engine", "Choose your default search provider") {
            val labels = engines.map { it.label }.toTypedArray()
            val current = UrlNormalizer.SearchEngine.fromId(prefs.getString("search_engine", null))
            AlertDialog.Builder(this)
                .setTitle("Default search engine")
                .setSingleChoiceItems(labels, engines.indexOf(current)) { dialog, which ->
                    prefs.edit().putString("search_engine", engines[which].id).apply()
                    Toast.makeText(this, "Search engine set to ${engines[which].label}", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        row(content, "Homepage", "Set a website to open as your homepage") { editHomepage() }

        section(content, "PRIVACY & SECURITY", "These protections apply to browser sessions.")
        toggle(content, "Tracking protection", "Block known trackers using GeckoView", "tracking_protection_enabled", true)
        toggle(content, "Global Privacy Control", "Ask websites not to sell or share your data", "global_privacy_control_enabled", true)

        section(content, "SITE DATA", "Review browser data and saved website permissions.")
        row(content, "Site permissions", "Manage saved website permissions") {
            Toast.makeText(this, "Open the browser menu → Site permissions to review saved permissions.", Toast.LENGTH_LONG).show()
        }
        row(content, "Clear browsing data", "Remove cookies, site data, cache and history") {
            AlertDialog.Builder(this)
                .setTitle("Clear browsing data?")
                .setMessage("This clears Astra's saved history, cookies, site data and cache. Bookmarks are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    Toast.makeText(this, "For the full clear-data flow, use Clear browsing data in the browser menu.", Toast.LENGTH_LONG).show()
                }
                .show()
        }

        section(content, "ABOUT", "Astra Browser · experimental GeckoView browser")
        row(content, "Version", "0.1.0 · ARM64 debug build") { }
        setContentView(root)
    }

    private fun section(parent: LinearLayout, heading: String, subtitle: String) {
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(20), dp(4), dp(8))
        }
        group.addView(TextView(this).apply {
            text = heading
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setTextColor(accent)
        })
        group.addView(TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(5), 0, dp(8))
        })
        parent.addView(group)
    }

    private fun row(parent: LinearLayout, title: String, summary: String, action: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground()
            if (isEnabled) setOnClickListener { action() }
        }
        item.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
        })
        item.addView(TextView(this).apply {
            text = summary
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(4), 0, 0)
        })
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun toggle(parent: LinearLayout, title: String, summary: String, key: String, default: Boolean) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = roundedBackground()
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(this).apply {
            this.text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
        })
        text.addView(TextView(this).apply {
            this.text = summary
            textSize = 12f
            setTextColor(secondary)
            setPadding(0, dp(4), dp(8), 0)
        })
        item.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                Toast.makeText(this@SettingsActivity, "$title ${if (checked) "enabled" else "disabled"}", Toast.LENGTH_SHORT).show()
            }
        }
        item.addView(control)
        parent.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun editHomepage() {
        val input = EditText(this).apply {
            hint = "https://example.com (blank = Astra start page)"
            setSingleLine(true)
            setText(prefs.getString("custom_homepage", "").orEmpty())
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle("Homepage")
            .setMessage("Enter an HTTP or HTTPS address.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val raw = input.text?.toString()?.trim().orEmpty()
                val valid = raw.isEmpty() || raw.startsWith("https://", true) || raw.startsWith("http://", true)
                if (!valid) {
                    Toast.makeText(this, "Use a valid http:// or https:// address", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString("custom_homepage", raw).apply()
                    Toast.makeText(this, "Homepage saved", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun roundedBackground(): View = View(this).apply { setBackgroundColor(surface) }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
