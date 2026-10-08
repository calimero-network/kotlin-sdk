package com.calimero.mero.account

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.randomBytes
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder

/**
 * Getting this device's key certified by the Calimero wallet, over a redirect.
 *
 * Port of mero-js `src/cloud/enrol-redirect.ts`. The app holds a device keypair and no
 * account; the wallet (a different origin, in a Custom Tab) holds the account root as a
 * passkey. The app opens [deviceEnrolmentUrl], the person approves, and the wallet
 * redirects to the callback with `#credential=…&account=…&device=…&state=…` — or
 * `#error=cancelled`. Nothing in the fragment is trusted until [completeDeviceEnrolment]
 * has checked it.
 *
 * ## Callback URLs
 *
 * Unlike mero-js (`https:` or loopback `http:` only), this accepts **any** absolute
 * callback: an https App Link, or an app scheme such as `mero-sample://enrol`. A native
 * app has no origin; what protects the credential is that it certifies a key that never
 * leaves this process, and the `state` check below. Whether the wallet *redirects* to a
 * custom scheme is the wallet's decision (`safeCallback` in mero-wallet), not this SDK's.
 */
object Enrolment {
    /** The hosted wallet's enrolment page. */
    const val DEFAULT_WALLET_URL = "https://wallet.cloud.calimero.network/account-enroll"

    private const val DEVICE_PARAM = "enrol-device"
    private const val KEM_PARAM = "enrol-kem"
    private const val CALLBACK_PARAM = "callback-url"
    private const val STATE_PARAM = "state"

    /** A fresh opaque `state`: 16 random bytes, hex. */
    fun newState(): String = Hex.encode(randomBytes(16))

    /**
     * The wallet URL to open: `{walletUrl}?enrol-device=<signPk>&enrol-kem=<kemPk>&callback-url=<cb>&state=<state>`.
     *
     * @throws IllegalArgumentException when a key is not 64 lowercase hex, or the callback
     *   is not an absolute URL or already carries a fragment (the wallet appends its own).
     */
    fun deviceEnrolmentUrl(
        signPublicKey: String,
        kemPublicKey: String,
        callbackUrl: String,
        state: String? = null,
        walletUrl: String = DEFAULT_WALLET_URL,
    ): String {
        require(Hex.isHex32(signPublicKey)) { "signPublicKey must be 64 lowercase hex characters" }
        require(Hex.isHex32(kemPublicKey)) { "kemPublicKey must be 64 lowercase hex characters" }
        assertCallback(callbackUrl)
        val builder =
            walletUrl
                .toHttpUrl()
                .newBuilder()
                .setQueryParameter(DEVICE_PARAM, signPublicKey)
                .setQueryParameter(KEM_PARAM, kemPublicKey)
                .setQueryParameter(CALLBACK_PARAM, callbackUrl)
        if (!state.isNullOrEmpty()) builder.setQueryParameter(STATE_PARAM, state)
        return builder.build().toString()
    }

    /**
     * Read an enrolment out of a callback URL's fragment.
     *
     * Returns null when the URL carries no enrolment (an ordinary deep link). Throws
     * [EnrolmentException] when the wallet reports an error — `cancelled`/`denied` set
     * [EnrolmentException.cancelled].
     */
    fun readEnrolmentCallback(url: String): EnrolmentCallback? {
        val fragment = url.substringAfter('#', missingDelimiterValue = "")
        if (fragment.isEmpty()) return null
        val params = parseParams(fragment)
        params["error"]?.takeIf { it.isNotEmpty() }?.let { error ->
            val cancelled = error == "cancelled" || error == "denied"
            throw EnrolmentException(
                if (cancelled) "the device was not approved at the wallet" else "the wallet refused this enrolment: $error",
                reason = error,
                cancelled = cancelled,
            )
        }
        val credential = params["credential"]
        val account = params["account"]
        val device = params["device"]
        if (credential.isNullOrEmpty() || account.isNullOrEmpty() || device.isNullOrEmpty()) return null
        return EnrolmentCallback(credential, account, device, params["state"]?.ifEmpty { null })
    }

    /**
     * Verify what came back, refusing loudly rather than returning a half-checked
     * credential. Checks, in order: the `state` (when [expectState] is given), the
     * certificate itself ([DeviceCert.verify]), that it certifies **this** device's
     * signing and delivery keys, and that the account and device the wallet reported are
     * the ones the certificate names.
     *
     * @throws EnrolmentException naming what disagreed.
     */
    @Suppress("ThrowsCount")
    fun completeDeviceEnrolment(
        callback: EnrolmentCallback,
        signPublicKey: String,
        kemPublicKey: String? = null,
        expectState: String? = null,
    ): EnrolledDevice {
        if (expectState != null && expectState != callback.state) {
            throw EnrolmentException("this enrolment answers a request this app did not make — the state it carries is not the one sent")
        }
        val certificate =
            try {
                DeviceCert.verify(callback.credential)
            } catch (e: IllegalArgumentException) {
                throw EnrolmentException(e.message ?: "the credential did not verify", cause = e)
            }
        val ours = signPublicKey.trim().lowercase()
        if (certificate.signPublicKey != ours) {
            throw EnrolmentException(
                "this credential certifies device key ${certificate.signPublicKey}, but this app holds $ours — " +
                    "it would sign with a key the certificate does not cover",
            )
        }
        if (kemPublicKey != null && certificate.kemPublicKey != kemPublicKey.trim().lowercase()) {
            throw EnrolmentException(
                "this credential names delivery key ${certificate.kemPublicKey}, not the one this app asked about",
            )
        }
        val account = callback.account.trim().lowercase()
        if (certificate.account != account) {
            throw EnrolmentException("the wallet reported account $account, but the credential names ${certificate.account}")
        }
        val device = callback.device.trim().lowercase()
        if (certificate.device != device) {
            throw EnrolmentException("the wallet reported device $device, but the credential names ${certificate.device}")
        }
        return EnrolledDevice(callback.credential, certificate.account, certificate.device, certificate)
    }

    private fun assertCallback(callbackUrl: String) {
        val scheme = callbackUrl.substringBefore(':', missingDelimiterValue = "")
        require(scheme.isNotEmpty() && SCHEME.matches(scheme) && callbackUrl.length > scheme.length + 1) {
            "callbackUrl must be an absolute URL (https:// or an app scheme), got $callbackUrl"
        }
        require('#' !in callbackUrl) { "callbackUrl must not carry a fragment: the wallet appends the credential as one" }
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    private fun parseParams(fragment: String): Map<String, String> =
        fragment
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                decode(key) to decode(value)
            }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}

/** What came back in the fragment, unverified. */
data class EnrolmentCallback(
    /** The `AccountProof<DeviceCert>`, hex borsh. */
    val credential: String,
    /** The account the wallet says certified this device, 64 hex. */
    val account: String,
    /** The device id the wallet minted, 64 hex. */
    val device: String,
    val state: String? = null,
)

/** A device this app may now sign as. */
data class EnrolledDevice(
    /** The credential to present — every warrant's `authorProof`. */
    val credential: String,
    val account: String,
    val device: String,
    val certificate: DeviceCredential,
)

/** The wallet declined or refused, or what came back did not verify. */
class EnrolmentException(
    message: String,
    /** The wallet's `error` value, when it sent one. */
    val reason: String? = null,
    /** True when the person declined at the wallet — an outcome to show, not a failure to report. */
    val cancelled: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/** mero-js-named alias for [Enrolment.deviceEnrolmentUrl]. */
fun deviceEnrolmentUrl(
    signPublicKey: String,
    kemPublicKey: String,
    callbackUrl: String,
    state: String? = null,
    walletUrl: String = Enrolment.DEFAULT_WALLET_URL,
): String = Enrolment.deviceEnrolmentUrl(signPublicKey, kemPublicKey, callbackUrl, state, walletUrl)

/** mero-js-named alias for [Enrolment.readEnrolmentCallback]. */
fun readEnrolmentCallback(url: String): EnrolmentCallback? = Enrolment.readEnrolmentCallback(url)
