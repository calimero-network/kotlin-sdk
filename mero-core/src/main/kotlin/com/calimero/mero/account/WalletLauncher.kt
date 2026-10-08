package com.calimero.mero.account

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the Calimero wallet's enrolment page in a Chrome Custom Tab: the system browser,
 * with its passkeys and the wallet origin's storage, never an embedded WebView (which
 * could draw over the consent screen and has no passkey access).
 *
 * The wallet redirects to the callback URL when the person approves or declines; the app
 * receives it through an intent filter (an App Link or an app scheme) and passes it to
 * [CloudAccount.completeEnrolment].
 */
object WalletLauncher {
    fun launch(
        context: Context,
        walletUrl: String,
    ) {
        val intent =
            CustomTabsIntent
                .Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
        if (context !is android.app.Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(walletUrl))
    }
}
