package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.account.Enrolment
import java.net.URLDecoder

/**
 * Whether [url] is the wallet's redirect back — a completed enrolment or a declined one — WITHOUT
 * consuming it: mero-react `isReturningFromWallet`, read from the fragment
 * (`#credential=…&account=…&device=…`, or `#error=…`). For deciding, before anything handles the
 * link, to open the sign-in sheet on its Cloud tab.
 */
fun isReturningFromWallet(url: String?): Boolean {
    val fragment = url?.substringAfter('#', "")?.takeIf { it.isNotEmpty() } ?: return false
    val params =
        fragment.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
    if (!params["error"].isNullOrEmpty()) return true
    return !params["credential"].isNullOrEmpty() && !params["account"].isNullOrEmpty() && !params["device"].isNullOrEmpty()
}

/**
 * The account sign-in flow with no UI — mero-react `useAccountEnrolment`, over [MeroClient]'s own
 * Cloud sign-in (which already holds the device keys, the single-use state and the completion):
 * [goToWallet] opens the wallet, [complete] finishes a redirect, and [note] is what to tell the
 * person about the last attempt — an error, or a connection with nowhere to write yet.
 */
class AccountEnrolment(
    private val client: MeroClient,
    /** The callback the wallet redirects to (an App Link or app scheme the app receives). */
    private val callbackUrl: String,
) {
    /** The wallet enrolment goes to. */
    val walletUrl: String get() = client.account.walletUrl

    /** Whether [walletUrl] is an override rather than the hosted wallet. */
    val customWallet: Boolean get() = walletUrl != Enrolment.DEFAULT_WALLET_URL

    /** What to tell the person about the last enrolment, or null. */
    val note: String?
        get() {
            val s = client.state.value
            return s.error ?: s.relayNote?.takeIf { s.isDelegated }
        }

    /** Leave for the wallet to enrol this device's key. */
    fun goToWallet(context: Context) = client.signInWithCloud(context, callbackUrl)

    /** Whether [url] is the wallet's answer (see [isReturningFromWallet]). */
    fun returning(url: String?): Boolean = isReturningFromWallet(url)

    /** Finish an enrolment the app was sent back with; false for an unrelated link. */
    suspend fun complete(url: String): Boolean = client.handleEnrolmentCallback(url)
}
