package com.calimero.mero.sample.mock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.crypto.SeedSigner
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLEncoder

/**
 * A stand-in for the Calimero wallet in mock mode. Nothing opens a browser: the wallet URL
 * the app would open is recorded in [pendingUrl] (the app shows a small "Mock wallet" sheet
 * for it), and [approve] / [decline] build the redirect the real wallet would send.
 *
 * [approve] does exactly what the wallet does: read the device keys named in the URL and
 * certify them with an account root — here a fixed, throwaway one — then put the credential
 * in the callback's fragment.
 */
object MockWallet {
    /** The wallet URL the app last asked to open, or null when no sign-in is pending. */
    var pendingUrl by mutableStateOf<String?>(null)

    private val root = SeedSigner("5c".repeat(32))

    /** The callback a person approving at the wallet would be sent to. */
    fun approve(walletUrl: String = requireNotNull(pendingUrl) { "no mock sign-in pending" }): String {
        val url = walletUrl.toHttpUrl()
        val signPk = requireNotNull(url.queryParameter("enrol-device"))
        val kemPk = requireNotNull(url.queryParameter("enrol-kem"))
        val back = requireNotNull(url.queryParameter("callback-url"))
        val state = url.queryParameter("state").orEmpty()
        val account = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(account, ByteArray(DEVICE_NONCE_BYTES) { 7 })
        val credential = DeviceCert.sign(root, device, signPk, kemPk)
        return "$back#credential=$credential&account=$account&device=$device&state=${URLEncoder.encode(state, "UTF-8")}"
    }

    /** The callback a person declining at the wallet would be sent to. */
    fun decline(walletUrl: String = requireNotNull(pendingUrl) { "no mock sign-in pending" }): String {
        val back = requireNotNull(walletUrl.toHttpUrl().queryParameter("callback-url"))
        return "$back#error=cancelled"
    }

    private const val DEVICE_NONCE_BYTES = 16
}
