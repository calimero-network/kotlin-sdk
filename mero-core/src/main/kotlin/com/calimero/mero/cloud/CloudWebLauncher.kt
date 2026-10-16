package com.calimero.mero.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/**
 * Opens the Calimero Cloud pages a person completes by hand in a Chrome Custom Tab — the
 * system browser, with the cloud login's cookies, never an embedded WebView. mero-js opens
 * them with `window.open` / `window.location`; here the page redirects back to a URL this
 * app receives (an App Link or an app scheme), and the deep-link Activity reads it:
 *
 * | launch | read the return with |
 * |---|---|
 * | [enableHA] / [disableHA] | [readHaPageCallback] |
 * | [beginCloudLink] | [CloudLink.readCloudLinkCallback], then [CloudLink.completeCloudLink] |
 * | [openAccountLinkHandoff] | [CloudClient.readAccountLinkCallback], then [CloudClient.linkAccountWithGrant] |
 *
 * For the HA API calls (no person involved) use [CloudClient.enableNamespaceHa] /
 * [CloudClient.disableNamespaceHa] instead.
 */
object CloudWebLauncher {
    /**
     * Open the cloud's `/enable-ha` page ([CloudClient.enableHaUrl]). mero-js `CloudClient.enableHA`.
     * Returns the URL opened.
     */
    fun enableHA(
        context: Context,
        cloud: CloudClient,
        options: HaPageOptions,
    ): String = cloud.enableHaUrl(options).also { open(context, it) }

    /** Open the cloud's `/disable-ha` page ([CloudClient.disableHaUrl]). mero-js `CloudClient.disableHA`. */
    fun disableHA(
        context: Context,
        cloud: CloudClient,
        options: HaPageOptions,
    ): String = cloud.disableHaUrl(options).also { open(context, it) }

    /**
     * Send the person to the cloud to consent to linking [accountId] ([CloudLink.cloudLinkUrl],
     * remembered in [pending]). mero-js `beginCloudLink`, which redirects the page instead.
     * Returns the URL opened.
     */
    fun beginCloudLink(
        context: Context,
        accountId: String,
        returnTo: String,
        cloudWebUrl: String = CloudLink.DEFAULT_CLOUD_WEB_URL,
        pending: PendingCloudLinks = PendingCloudLinks.shared,
    ): String = CloudLink.cloudLinkUrl(accountId, returnTo, cloudWebUrl, pending).also { open(context, it) }

    /** Open a portal handoff built by [CloudClient.accountLinkHandoff]. */
    fun openAccountLinkHandoff(
        context: Context,
        handoff: AccountLinkHandoff,
    ) = open(context, handoff.url)

    /** Open [url] in a Custom Tab. */
    fun open(
        context: Context,
        url: String,
    ) {
        val intent =
            CustomTabsIntent
                .Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .build()
        if (context !is android.app.Activity) intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.launchUrl(context, Uri.parse(url))
    }
}
