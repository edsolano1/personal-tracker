package com.edsolano.tracker.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The wallet page, full screen. The WebView is owned by the activity so it survives tab switches. */
@Composable
fun WalletScreen(web: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        factory = {
            (web.parent as? ViewGroup)?.removeView(web)
            web
        },
        modifier = modifier.fillMaxSize(),
    )
}
