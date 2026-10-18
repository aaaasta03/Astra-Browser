package org.aaaasta03.astrabrowser

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

class MainActivity : Activity() {
    private lateinit var session: GeckoSession
    private lateinit var addressBar: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        addressBar = EditText(this).apply {
            hint = "Search or enter address"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            textSize = 16f
            setText("https://duckduckgo.com/")
            setSelectAllOnFocus(false)
        }

        val goButton = Button(this).apply {
            text = "Go"
            contentDescription = "Go to address"
            setOnClickListener { loadAddress() }
        }

        toolbar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        toolbar.addView(
            goButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val geckoView = GeckoView(this)
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            geckoView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        val runtime = GeckoRuntime.create(applicationContext)
        session = GeckoSession()
        session.open(runtime)
        geckoView.setSession(session)
        session.loadUri("https://duckduckgo.com/")

        addressBar.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                loadAddress()
                true
            } else {
                false
            }
        }
    }

    private fun loadAddress() {
        val rawInput = addressBar.text?.toString().orEmpty()
        val destination = UrlNormalizer.normalize(rawInput)
        addressBar.setText(destination)
        if (destination.isBlank()) {
            Toast.makeText(this, "Enter a web address or search term", Toast.LENGTH_SHORT).show()
            return
        }
        session.loadUri(destination)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::session.isInitialized) {
            session.close()
        }
        super.onDestroy()
    }
}
