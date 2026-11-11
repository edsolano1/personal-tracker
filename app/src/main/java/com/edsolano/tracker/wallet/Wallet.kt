package com.edsolano.tracker.wallet

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.edsolano.tracker.MainActivity
import com.edsolano.tracker.R
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * The Money tab is the wallet page on GitHub Pages, rebuilt hourly from Plaid by the
 * "wallet" repo. Its numbers are encrypted; the key lives only in this app's private
 * prefs ("wallet-link"/"key", set over adb) and travels in the link's #fragment.
 * The app only reads what the page shows (the big number, the status pill, the newest
 * charge) so it can send notifications.
 */
object Wallet {
    const val PAGE = "https://edsolano1.github.io/wallet/"

    fun url(context: Context): String =
        PAGE + (context.getSharedPreferences("wallet-link", Context.MODE_PRIVATE)
            .getString("key", null)?.let { "#k=$it" } ?: "")
    const val CHANNEL = "wallet"

    /**
     * Runs in every frame (the wallet lives in an iframe on claude.ai) and reports what
     * the page shows every few seconds once it has loaded.
     */
    private const val WATCH = """
        (function(){
          if (window.__trackerWatch) return; window.__trackerWatch = true;
          setInterval(function(){
            var big = document.getElementById('big');
            if (!big || !window.Tracker) return;
            if (big.classList.contains('skel')) return;
            var t = function(id){ var e = document.getElementById(id); return e && !e.hidden ? e.textContent.trim() : ''; };
            var row = document.querySelector('#list .row');
            var first = row ? Array.prototype.map.call(row.children, function(c){ return c.textContent.trim(); }).join(' | ') : '';
            window.Tracker.report(big.textContent.trim(), t('pill'), t('eyebrow'), first, t('updated'));
          }, 3000);
        })();
    """

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Spending money", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun newWebView(context: Context, onReport: ((String) -> Unit)? = null): WebView {
        val web = WebView(context)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.javaScriptCanOpenWindowsAutomatically = true
        // Google refuses sign-in inside an embedded browser, so look like Chrome; the
        // email code sign-in on claude.ai works either way.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv)", ")")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        WebView.setWebContentsDebuggingEnabled(true)
        web.settings.setSupportMultipleWindows(true)
        web.webViewClient = WebViewClient()
        web.webChromeClient = Popups(context)
        web.addJavascriptInterface(Bridge(context.applicationContext, onReport), "Tracker")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, WATCH, setOf("*"))
        }
        return web
    }

    /**
     * Google's sign-in opens a popup window. A WebView drops popups unless the app makes
     * one, so show it full screen in a dialog and close it when the page says so.
     */
    private class Popups(val context: Context) : WebChromeClient() {
        private var dialog: android.app.Dialog? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            val popup = WebView(view.context)
            popup.settings.javaScriptEnabled = true
            popup.settings.domStorageEnabled = true
            popup.settings.javaScriptCanOpenWindowsAutomatically = true
            popup.settings.userAgentString = view.settings.userAgentString
            CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)
            popup.webViewClient = WebViewClient()
            popup.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView) {
                    dialog?.dismiss()
                }
            }
            dialog = android.app.Dialog(view.context, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                setContentView(popup)
                setOnDismissListener { popup.destroy(); view.reload() }
                show()
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            dialog?.dismiss()
        }
    }

    private class Bridge(val context: Context, val onReport: ((String) -> Unit)?) {
        @JavascriptInterface
        fun report(big: String, pill: String, eyebrow: String, first: String, updated: String) {
            Alerts.seen(context, big, pill, eyebrow, first)
            onReport?.invoke(updated)
        }
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "wallet-check", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WalletCheckWorker>(1, TimeUnit.HOURS).build(),
        )
    }
}

/** Decides when what the page shows is worth a notification. */
object Alerts {
    private fun amount(s: String): Double? =
        s.replace("−", "-").replace(Regex("[^0-9.-]"), "").toDoubleOrNull()

    @Synchronized
    fun seen(context: Context, big: String, pill: String, eyebrow: String, first: String) {
        val p = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
        val lastPill = p.getString("pill", null)
        val lastFirst = p.getString("first", null)
        val lastBig = p.getString("big", null)
        p.edit().putString("pill", pill).putString("first", first).putString("big", big)
            .putLong("seenAt", System.currentTimeMillis()).apply()
        if (lastBig == null) return // first look ever: nothing to compare against

        val headline = if (eyebrow.startsWith("Out")) "Out of spending money" else "$big ${eyebrow.lowercase()}"
        when {
            pill.isNotBlank() && pill != lastPill -> notify(context, 1, pill, headline)
            first.isNotBlank() && first != lastFirst && !first.contains("+") ->
                notify(context, 2, first.substringBefore(" | ") + " " + first.split(" | ").getOrElse(1) { "" }, headline)
            amount(big) != null && amount(lastBig) != null && amount(big)!! < amount(lastBig)!! - 0.5 ->
                notify(context, 2, "Spending money now $big", headline)
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).putExtra("tab", 2),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, Wallet.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title.trim())
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(4000 + id, n) }
    }
}

/**
 * Opens the wallet page off screen about once an hour so a new charge can notify even
 * when the app is closed. Best effort: Android may run it later than asked.
 */
class WalletCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val main = Handler(Looper.getMainLooper())
        var web: WebView? = null
        main.post {
            web = Wallet.newWebView(applicationContext).also {
                it.layout(0, 0, 1080, 2400)
                it.loadUrl(Wallet.url(applicationContext))
            }
        }
        delay(45_000) // long enough for the page to sign in, ask Monarch and report twice
        main.post { web?.destroy() }
        return Result.success()
    }
}
