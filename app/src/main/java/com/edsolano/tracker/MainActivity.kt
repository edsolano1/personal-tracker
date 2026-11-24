package com.edsolano.tracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.edsolano.tracker.ui.TrackerTheme
import kotlinx.coroutines.launch

/** The web app: the wallet full screen plus the Claude button (finance only since 0.13.0). */
private const val WEB_APP = "https://edsolano1.github.io/personal-tracker/"

class MainActivity : ComponentActivity() {
    private var lastUpdateCheck = 0L

    /** Kept loaded so the money alerts stay fresh; the page reports what it shows through `Tracker`. */
    private val walletWeb by lazy {
        com.edsolano.tracker.wallet.Wallet.newWebView(this).also {
            it.loadUrl(com.edsolano.tracker.wallet.Wallet.url(this@MainActivity))
        }
    }

    /**
     * The screen itself is a web page (edsolano1.github.io/personal-tracker), so a change to it
     * reaches the phone without an install. It shows the wallet and the Claude button.
     */
    private val appWeb by lazy {
        android.webkit.WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            setBackgroundColor(android.graphics.Color.parseColor("#0E1411"))
            addJavascriptInterface(AppBridge(), "App")
            webViewClient = android.webkit.WebViewClient()
            webChromeClient = android.webkit.WebChromeClient()
            loadUrl(WEB_APP)
        }
    }

    private val chat by lazy { com.edsolano.tracker.chat.ChatPanel(this) }

    private inner class AppBridge {
        @android.webkit.JavascriptInterface
        fun walletUrl(): String = com.edsolano.tracker.wallet.Wallet.url(this@MainActivity)

        @android.webkit.JavascriptInterface
        fun version(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

        /** The floating Claude button: the in-app chat panel, on a tab ("money", "shopping") or the last one used. */
        @android.webkit.JavascriptInterface
        fun openChat(tab: String) { runOnUiThread { chat.open(tab.ifBlank { null }) } }

        /**
         * Hands a claude.ai link to Android, which opens it in the Claude app (or the browser).
         * Only https claude.ai links, since the wallet iframe can reach this bridge too.
         */
        @android.webkit.JavascriptInterface
        fun openUrl(url: String) {
            val uri = android.net.Uri.parse(url)
            if (uri.scheme != "https" || (uri.host != "claude.ai" && uri.host?.endsWith(".claude.ai") != true)) return
            runOnUiThread {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        holdSplash()
        enableEdgeToEdge()
        // Money alerts are notifications.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askPermissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { TrackerTheme { Root() } }
    }

    /**
     * Lets the splash's ring animation finish (it's short) instead of being cut off by the
     * first frame, then zooms the splash away into the app.
     */
    private fun holdSplash() {
        val until = android.os.SystemClock.uptimeMillis() + 750
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (android.os.SystemClock.uptimeMillis() < until) { content.postInvalidateDelayed(16); return false }
                content.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        splashScreen.setOnExitAnimationListener { view ->
            view.animate().alpha(0f).scaleX(1.25f).scaleY(1.25f).setDuration(260)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { view.remove() }.start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Look for a new version every time the app comes forward, not only on a cold start
        // (Android keeps the app alive in the background, so onCreate rarely runs).
        if (System.currentTimeMillis() - lastUpdateCheck > 5 * 60_000) {
            lastUpdateCheck = System.currentTimeMillis()
            lifecycleScope.launch { com.edsolano.tracker.update.Updater.check(this@MainActivity) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        // Back walks the wallet page's own history, then leaves.
        BackHandler { if (appWeb.canGoBack()) appWeb.goBack() else finish() }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
            androidx.compose.runtime.LaunchedEffect(Unit) { walletWeb }  // keeps the money alerts fresh
            com.edsolano.tracker.ui.WalletScreen(appWeb, Modifier.fillMaxSize().padding(inner))
        }
    }
}
