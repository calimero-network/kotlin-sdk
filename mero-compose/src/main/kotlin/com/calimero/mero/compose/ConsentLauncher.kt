package com.calimero.mero.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.calimero.mero.CalimeroClient
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentResult
import com.calimero.mero.ConsentStarted
import com.calimero.mero.OpenConsentOptions

/**
 * Opens the node's consent page in a Custom Tab (the system browser), where the account holder
 * approves what the app's package-bound session may not do itself: a release, an upgrade, outbound
 * hosts, a new organization, an invitation minted or accepted. Approvals happen only there, never in
 * the app (Swift `ConsentLauncher`, mero-js `request / begin / resume / wait`):
 *
 * - [request]: ask the node ([CalimeroClient.openConsentPage]) and open the page; show the person
 *   the answer's `code` in `confirm` first.
 * - [resume]: the page sends the person back to the request's `returnTo` (a verified App Link on the
 *   package's frontend origin, or one its signed manifest names) with `?request_id=…&result=…`;
 *   hand the intent's URI here (or to [MeroAppSession.handle]) for the decision, confirmed with the
 *   node.
 * - [CalimeroClient.waitForConsent]: poll instead, when the link does not come back.
 *
 * The decision's [ConsentResult.outcome] is typed by kind ([com.calimero.mero.ConsentOutcome]).
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

    /**
     * [CalimeroClient.openConsentPage] in a Custom Tab. A `code` the node answers that is not the
     * page's ([com.calimero.mero.confirmationCode]) throws `CONSENT_CODE_MISMATCH`. [confirm] runs
     * before the page opens, with the node's answer: show the person its `code` there; answer
     * `false` and the page is not opened.
     */
    suspend fun request(
        context: Context,
        client: CalimeroClient,
        request: ConsentRequest,
        confirm: (suspend (ConsentStarted) -> Boolean)? = null,
    ): ConsentStarted = client.openConsentPage(request, OpenConsentOptions({ open(context, it) }, confirm))

    /**
     * The decision behind the link the consent page sent the person back with (an App Link intent's
     * `data`), confirmed with the node ([CalimeroClient.resumeConsent]), or null when [uri] is not a
     * consent return.
     */
    suspend fun resume(
        client: CalimeroClient,
        uri: Uri,
    ): ConsentResult? = client.resumeConsent(uri.toString())

    /** [resume] with the intent the system handed the activity, or null when it carries no consent return. */
    suspend fun resume(
        client: CalimeroClient,
        intent: Intent?,
    ): ConsentResult? = intent?.data?.let { resume(client, it) }
}
