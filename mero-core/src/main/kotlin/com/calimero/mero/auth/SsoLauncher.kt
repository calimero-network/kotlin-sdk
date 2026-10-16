package com.calimero.mero.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the node's hosted SSO login in a Chrome Custom Tab — the recommended OAuth-style flow on
 * Android (system cookie jar, no embedded WebView). The node redirects back to
 * [AuthLoginOptions.callbackUrl]; a deep-link Activity should receive it and call
 * [parseAuthCallback] with the same [PendingAuthStates], then [com.calimero.mero.Mero.setTokenData].
 *
 * Each launch records a single-use `auth_state` in [pending] (see [buildAuthLoginUrl]); the
 * callback is refused without it.
 */
object SsoLauncher {
    @JvmOverloads
    fun launch(
        context: Context,
        nodeUrl: String,
        options: AuthLoginOptions,
        pending: PendingAuthStates = PendingAuthStates.shared,
    ) {
        val url = buildAuthLoginUrl(nodeUrl, options, pending)
        CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, Uri.parse(url))
    }
}
