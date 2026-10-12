package com.gamch1k.lifeos.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Dashboard for everything the app does not do itself, in a Chrome
 * Custom Tab: Chrome has the Access login and ECH. Every Dashboard link in
 * the app (Home, Settings, notifications) goes through [open], the one place
 * to change if the Dashboard moves into an in-app WebView.
 */
object Dashboard {
    fun open(context: Context, scope: CoroutineScope, path: String = "/") {
        scope.launch {
            val base = withContext(Dispatchers.IO) { context.graph.tokens.serverUrl() }
            val uri = Uri.parse(base.trimEnd('/') + path)
            try {
                CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
            } catch (_: android.content.ActivityNotFoundException) {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
