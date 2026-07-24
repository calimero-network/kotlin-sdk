package com.calimero.mero.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the node's hosted SSO login in a Chrome Custom Tab — the recommended OAuth-style flow on
 * Android (system cookie jar, no embedded WebView). The node redirects back to
 * [AuthLoginOptions.callbackUrl]; a deep-link Activity should receive it and call
 * [parseAuthCallback], then [com.calimero.mero.Mero.setTokenData].
 */
object SsoLauncher {
    fun launch(
        context: Context,
        nodeUrl: String,
        options: AuthLoginOptions,
    ) {
        val url = buildAuthLoginUrl(nodeUrl, options)
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    }
}
