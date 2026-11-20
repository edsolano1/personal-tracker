package com.edsolano.tracker.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The in-app chat (his ask, 2026-11-20: "I don't want to leave the app to ask questions").
 * A panel slides up over the app with his Claude Code chats, each in its own tab, shown
 * through Remote Control's own page on claude.ai. Nothing is relayed: it is the real chat.
 *
 * Each tab remembers the last claude.ai page it was on, so the first time he picks the
 * chat from the list and after that the tab opens straight to it. The pages stay loaded
 * while the panel is closed, so reopening is instant and a half-typed message survives.
 */
class ChatPanel(private val activity: Activity) {
    private companion object {
        const val HOME = "https://claude.ai/code"
        val TABS = listOf("money" to "App & money", "shopping" to "Shopping")
        // Sign-in and the chat itself stay inside; any other link (a store, an article) opens outside.
        val INSIDE = listOf("claude.ai", "claude.com", "anthropic.com", "google.com", "gstatic.com", "googleusercontent.com", "apple.com")
        const val BG = 0xFF0E1411.toInt()
        const val SURFACE = 0xFF161F1A.toInt()
        const val FG = 0xFFE5EDE8.toInt()
        const val MUTED = 0xFF90A097.toInt()
        const val CLAUDE = 0xFFD97757.toInt()
    }

    private val prefs = activity.getSharedPreferences("chat-panel", Context.MODE_PRIVATE)
    private val webs = LinkedHashMap<String, WebView>()
    private val tabs = mutableMapOf<String, TextView>()
    private var current = prefs.getString("tab", TABS[0].first) ?: TABS[0].first
    private val host = FrameLayout(activity)
    private val dialog: Dialog by lazy { build() }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun open(tab: String?) {
        select(TABS.firstOrNull { it.first == tab }?.first ?: current)
        dialog.show()
    }

    private fun build(): Dialog {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(BG)
        }
        TABS.forEach { (key, label) ->
            val t = TextView(activity).apply {
                text = label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { select(key) }
            }
            tabs[key] = t
            bar.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
        bar.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(TextView(activity).apply {
            text = "Done"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(CLAUDE)
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener { dialog.hide() }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(host, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Room for the status bar on top, and for the navigation bar or keyboard at the bottom.
        root.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, b.top, 0, b.bottom)
            WindowInsets.CONSUMED
        }

        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
            setContentView(root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(BG))
                setWindowAnimations(android.R.style.Animation_InputMethod)  // slides up from the bottom
                setDecorFitsSystemWindows(false)
            }
            // Back goes back inside the chat first, then closes the panel (the chats stay loaded).
            setOnKeyListener { _, code, ev ->
                if (code != KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
                if (ev.action == KeyEvent.ACTION_UP) {
                    val w = webs[current]
                    if (w != null && w.canGoBack()) w.goBack() else hide()
                }
                true
            }
        }
    }

    private fun select(key: String) {
        current = key
        prefs.edit().putString("tab", key).apply()
        val w = web(key)
        webs.forEach { (k, v) -> v.visibility = if (k == key) View.VISIBLE else View.GONE }
        if (w.parent == null) host.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tabs.forEach { (k, t) ->
            val on = k == key
            t.setTextColor(if (on) FG else MUTED)
            t.background = GradientDrawable().apply {
                cornerRadius = dp(99).toFloat()
                setColor(if (on) SURFACE else Color.TRANSPARENT)
                if (on) setStroke(dp(1), CLAUDE)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun web(key: String): WebView = webs.getOrPut(key) {
        WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // claude.ai and Google sign-in refuse embedded browsers that say so; look like Chrome.
            settings.userAgentString = settings.userAgentString.replace("; wv)", ")")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(BG)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                    val u = req.url
                    val h = u.host ?: return false
                    if (u.scheme == "https" && INSIDE.any { h == it || h.endsWith(".$it") }) return false
                    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    return true
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    if (url.startsWith("https://claude.ai/")) prefs.edit().putString("url-$key", url).apply()
                }
            }
            loadUrl(prefs.getString("url-$key", null) ?: HOME)
        }
    }
}
