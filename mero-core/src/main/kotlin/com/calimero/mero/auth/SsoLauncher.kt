package com.calimero.mero.auth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the node's hosted SSO login in a Chrome Custom Tab — the recommended OAuth-style flow on
 * Android (system cookie jar, no embedded WebView). The node redirects back to
 * [AuthLoginOptions.callbackUrl]; a deep-link Activity should receive it and call
 * [parseAuthCallback] with the same [PendingAuthStates], then [com.calimero.mero.Mero.setTokenData].
 *
 * The tab is pinned to the default browser that serves Custom Tabs ([browserPackage]), so another
 * app that handles `https` links cannot be chosen to show the login page.
 *
 * Each launch records a single-use `auth_state` in [pending] (see [buildAuthLoginUrl]); the
 * callback is refused without it. Use a verified `https` App Link as the callback: a custom
 * scheme can be declared by any installed app, which then receives the tokens, so
 * [parseAuthCallback] refuses one unless `allowCustomScheme`.
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
        val intent =
            CustomTabsIntent
                .Builder()
                .setShowTitle(true)
                .build()
        browserPackage(context)?.let { intent.intent.setPackage(it) }
        intent.launchUrl(context, Uri.parse(url))
    }

    /**
     * The package of the browser that serves Custom Tabs (the default one when it does), or null
     * when none does — then the system picks, as for any link.
     */
    @JvmStatic
    fun browserPackage(context: Context): String? = runCatching { CustomTabsClient.getPackageName(context, null) }.getOrNull()
}
