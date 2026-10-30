package com.calimero.mero.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.calimero.mero.CalimeroClient
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentStarted

/**
 * Opens the node's consent page in a Custom Tab (the system browser), where the account holder
 * approves a release, an upgrade or outbound hosts with their passkey. Approvals happen only
 * there, never in the app: the app asks ([CalimeroClient.requestConsent]), the page decides, and
 * sends the person back to the request's `returnTo` with `?request_id=…&result=…`, which the app
 * hands to [MeroAppSession.handle] (or reads with [com.calimero.mero.consentReturn]) and confirms
 * with [CalimeroClient.consentResult]. (Swift `ConsentLauncher`.)
 */
object ConsentLauncher {
    /** Open [url] in a Custom Tab. */
    fun open(
        context: Context,
        url: String,
    ) {
        val intent = CustomTabsIntent.Builder().setShowTitle(true).build()
        if (context !is android.app.Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(url))
    }

    /** [CalimeroClient.openConsentPage] in a Custom Tab. */
    suspend fun request(
        context: Context,
        client: CalimeroClient,
        request: ConsentRequest,
    ): ConsentStarted = client.openConsentPage(request) { open(context, it) }
}
