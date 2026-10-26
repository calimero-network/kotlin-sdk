package com.calimero.mero.relay

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.http.MeroException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.tee.DEFAULT_RELEASE_MIRROR
import com.calimero.mero.tee.Dcap
import com.calimero.mero.tee.DcapCollateral
import com.calimero.mero.tee.DcapVerify
import com.calimero.mero.tee.PRODUCTION_PROFILE
import com.calimero.mero.tee.QuoteVerifier
import com.calimero.mero.tee.SignedNodeRelease
import com.calimero.mero.tee.SignedReleaseVerifierOptions
import com.calimero.mero.tee.TransportQuote
import com.calimero.mero.tee.VerifyTransportQuote
import com.calimero.mero.tee.cloudNodeReleaseUrl
import com.calimero.mero.tee.createSignedReleaseVerifier
import com.calimero.mero.tee.fetchNodeRelease
import com.calimero.mero.tee.fetchNodeReleaseVersion
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/*
 * Learning a relay's node signing key from its TEE attestation — port of mero-js
 * `src/relay-attestation/attest-node-key.ts`, plus the quote verifier mero-js's delegated
 * session builds for a hosted relay (`relayQuoteVerifier` in `src/account/session.ts`).
 *
 * `login()` binds a session to the node key the client names, and that key must never be
 * taken from the node being logged in to on its word. A TEE quote is different: the node
 * cannot put a key into it that the TEE did not bind. With `bindNodeKey`, core commits the
 * node's signing key into the quote's report data:
 *
 *   report_data = nonce (32) ‖ SHA-256("calimero.tee-attest.key-binding.v1" ‖ 0^32 ‖ key)
 *
 * So the client sends a fresh nonce, reads the report data out of the RAW quote bytes — never
 * the server's parsed rendering of them — and accepts the returned key only if both halves
 * match. Whether the quote comes from a genuine TDX guest running a trusted image is its
 * signature and measurements: [DcapRelayKeyVerifier] checks those on the device.
 */

/** Core's `attest_key_binding(None, key)`: `SHA-256(domain ‖ 0^32 ‖ nodeKey)`, the second half of the report data. */
fun attestKeyBinding(nodeKey: ByteArray): ByteArray = RelayAttest.keyBinding(nodeKey)

/** The 64 report-data bytes of a quote, read from the quote itself, and whether it is a mock. */
data class QuoteReportData(
    val reportData: ByteArray,
    val mock: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is QuoteReportData && other.mock == mock && other.reportData.contentEquals(reportData)

    override fun hashCode(): Int = reportData.contentHashCode() * 31 + mock.hashCode()
}

/**
 * The report data of [quote]: after core's `MOCK_TDX_QUOTE_V1` header for a mock quote, at
 * TD-report offset 520 of a TDX v4 quote otherwise. Throws [RelayKeyRefusedException] for a
 * quote too short to carry it.
 */
fun reportDataOf(quote: ByteArray): QuoteReportData = RelayAttest.reportDataOf(quote).let { (data, mock) -> QuoteReportData(data, mock) }

/** The relay's node key, as a quote binds it. */
data class AttestedNodeKey(
    /** The relay's node signing key, hex (32 bytes). */
    val nodeKey: String,
    /** True when the quote was a mock — no TEE vouched for anything. */
    val mock: Boolean,
)

/**
 * Ask a relay for a quote binding its node key to a fresh nonce, check the binding, and return
 * the key — mero-js `attestRelayNodeKey`.
 *
 * @param allowMock accept a mock quote. DEV/TEST ONLY: a mock quote carries no signature.
 * @param verify verify a real quote as a sealed transport does: a [VerifyTransportQuote] such as
 *   [com.calimero.mero.tee.createSignedReleaseVerifier], called with our nonce and the node-key
 *   binding as the expected report data, and with the relay's collateral when it asks for it.
 *   Takes precedence over [verifyQuote].
 * @param verifyQuote verify a real quote's signature chain and measurements; throw to refuse.
 * @throws RelayKeyRefusedException when the answer does not hold; a real quote with neither
 *   verifier is refused, since the binding would be checked against unauthenticated bytes.
 */
@Suppress("ThrowsCount")
suspend fun attestRelayNodeKey(
    relayUrl: String,
    allowMock: Boolean = false,
    verify: VerifyTransportQuote? = null,
    verifyQuote: (suspend (ByteArray) -> Unit)? = null,
    httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
): AttestedNodeKey {
    val nonce = randomBytes(32)
    val body =
        buildJsonObject {
            put("nonce", Hex.encode(nonce))
            put("bindNodeKey", true)
            if (verify?.includeCollateral == true) put("includeCollateral", true)
        }
    val response = PlainJsonHttp(httpClient).execute("POST", "${relayUrl.trimBase()}/admin-api/tee/attest", body.toString())
    if (!response.isSuccessful) {
        throw RelayKeyRefusedException("the relay would not attest (HTTP ${response.status})${response.body.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}")
    }
    val parsed = Json.parseToJsonElement(response.body) as? JsonObject ?: throw RelayKeyRefusedException("the relay returned no quote")
    val data = parsed["data"] as? JsonObject ?: parsed
    val attestation =
        RelayAttestation(
            quoteB64 = (data["quoteB64"] as? JsonPrimitive)?.contentOrNull ?: throw RelayKeyRefusedException("the relay returned no quote"),
            boundPublicKey = (data["boundPublicKey"] as? JsonPrimitive)?.contentOrNull,
            raw = data,
        )
    return checkAttestation(nonce, attestation, allowMock, verify, verifyQuote)
}

/** The checks [attestRelayNodeKey] and [DcapRelayKeyVerifier] share, in mero-js's order. */
@Suppress("ThrowsCount")
internal suspend fun checkAttestation(
    nonce: ByteArray,
    attestation: RelayAttestation,
    allowMock: Boolean,
    verify: VerifyTransportQuote?,
    verifyQuote: (suspend (ByteArray) -> Unit)?,
): AttestedNodeKey {
    val named = attestation.boundPublicKey?.trim()
    if (!Hex.isHex32(named?.lowercase())) throw RelayKeyRefusedException("boundPublicKey must be 64 hex characters")
    val nodeKey = Hex.decode(named!!, "boundPublicKey", 32)
    val quote =
        try {
            fromBase64(attestation.quoteB64)
        } catch (e: Exception) {
            throw RelayKeyRefusedException("the relay's quote is not base64: ${e.message}")
        }
    val (reportData, mock) = reportDataOf(quote)
    when {
        mock -> if (!allowMock) throw RelayKeyRefusedException("the relay answered with a MOCK quote, which proves nothing about the hardware")
        verify != null -> {
            val collateral = (attestation.raw["collateral"] as? JsonObject)?.let(DcapCollateral::fromJson)
            val accepted = verify.verify(TransportQuote(attestation.quoteB64, Hex.encode(nonce), Hex.encode(attestKeyBinding(nodeKey)), collateral))
            if (!accepted) throw RelayKeyRefusedException("the relay's quote did not verify: not a trusted image, or not bound to this request")
        }
        verifyQuote != null -> verifyQuote(quote)
        else -> throw RelayKeyRefusedException("a real TDX quote needs a verify (or verifyQuote) to check its signature and measurements")
    }
    if (!reportData.copyOfRange(0, 32).contentEquals(nonce)) {
        throw RelayKeyRefusedException("the quote does not carry our nonce: it was not made for this request")
    }
    if (!reportData.copyOfRange(32, 64).contentEquals(attestKeyBinding(nodeKey))) {
        throw RelayKeyRefusedException("the quote does not bind the key the relay named: refusing it")
    }
    return AttestedNodeKey(Hex.encode(nodeKey), mock)
}

/**
 * Something on the way to a relay's key did not answer — reading the release the relay names,
 * fetching that release, reaching the relay. It says nothing about the relay's image, so the
 * attempt is worth making again ([com.calimero.mero.account.RelayNodeKeyAttempt.Unavailable]).
 * mero-js `RelayKeyTransportError`; [com.calimero.mero.http.classifyError] reads it as `UNAVAILABLE`.
 */
class RelayKeyTransportException(
    /** What was being done, e.g. `fetching the release the relay runs`. */
    val step: String,
    cause: Throwable,
) : MeroException("$step: ${cause.message ?: cause}", cause)

/**
 * A [RelayKeyVerifier] that can also verify the relay's quote for a sealed transport — so a
 * delegated session seals its relay with the same trust it learns the relay's key with
 * ([com.calimero.mero.account.relayTransportClient]). [DcapRelayKeyVerifier] is one.
 */
interface AttestingRelayKeyVerifier : RelayKeyVerifier {
    /** The verifier of [relayUrl]'s transport quote. */
    fun quoteVerifier(relayUrl: String): VerifyTransportQuote
}

/**
 * The oldest relay release trusted: the first that serves delegated context creation and
 * governance at the namespace-op schema this client signs (mero-js `MIN_RELAY_RELEASE`).
 */
const val MIN_RELAY_RELEASE: String = "2.3.99"

/**
 * The [RelayKeyVerifier] that checks the relay's TDX quote on the device before trusting the
 * key it binds — the verification mero-js's delegated session runs (`attemptRelayNodeKey` with
 * `relayQuoteVerifier`):
 *
 * - the quote is verified with DCAP ([Dcap] by default) against Intel's root, with the
 *   collateral the relay sends with it;
 * - its image must be, in all five registers, the [profile] (`locked-read-only`) of the signed
 *   mero-tee release the relay says it runs (read from its `/admin-api/tee/info`), at or above
 *   [minReleaseVersion] — that release fetched from [releaseMirror] (or [releaseSource]) and
 *   trusted only for its Sigstore signature;
 * - its report data must be our nonce and the binding of the key the relay named.
 *
 * A mock quote (a dev rig's) is refused unless [allowMock], from a loopback relay too: another app
 * on the device can listen on `127.0.0.1`. When a
 * step that only moves bytes fails, [verify] throws [RelayKeyTransportException] (try again);
 * any other failure is the verification saying no ([RelayKeyRefusedException] or
 * [com.calimero.mero.tee.TeeTrustException]), which retrying cannot change.
 *
 * [quoteVerifier] is the same check as a [VerifyTransportQuote], which the delegated session also
 * seals its relay transport with.
 */
@Suppress("LongParameterList")
class DcapRelayKeyVerifier(
    val minReleaseVersion: String = MIN_RELAY_RELEASE,
    val profile: String = PRODUCTION_PROFILE,
    val releaseMirror: String = DEFAULT_RELEASE_MIRROR,
    /** Accept mock quotes, from any relay. DEV/TEST ONLY: a mock quote carries no signature. */
    val allowMock: Boolean = false,
    private val dcapVerify: DcapVerify = Dcap,
    /** Carries `/tee/info` and the release fetch. */
    private val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    /** Get the release some other way, given the relay and the version it names. Untrusted: it is verified. */
    private val releaseSource: (suspend (relayUrl: String, version: String) -> SignedNodeRelease)? = null,
    /** Trust a profile other than `locked-read-only`. Testing only. */
    private val allowDebugProfile: Boolean = false,
    /** The current time in milliseconds, for the quote's collateral. For tests. */
    private val now: () -> Long = System::currentTimeMillis,
) : AttestingRelayKeyVerifier {
    /** [signedReleaseVerifier], as the transport quote verifier for [relayUrl]. */
    override fun quoteVerifier(relayUrl: String): QuoteVerifier = signedReleaseVerifier(relayUrl)

    /**
     * The quote verifier for [relayUrl]: its signed release ([profile], at least
     * [minReleaseVersion]), Intel's chain, every register of the release's image. (Swift
     * `DcapRelayKeyVerifier.signedReleaseVerifier`.)
     */
    fun signedReleaseVerifier(relayUrl: String): QuoteVerifier {
        val base = relayUrl.trimBase()
        return createSignedReleaseVerifier(
            SignedReleaseVerifierOptions(
                release = {
                    // Fetched only: its signature, its version and the quote's registers are all
                    // checked after this returns, and none of that is a transport question.
                    transport("fetching the release the relay runs") {
                        val version = fetchNodeReleaseVersion(base, httpClient)
                        releaseSource?.invoke(base, version) ?: fetchNodeRelease(cloudNodeReleaseUrl(releaseMirror, version), httpClient)
                    }
                },
                minReleaseVersion = minReleaseVersion,
                profile = profile,
                allowDebugProfile = allowDebugProfile,
                dcapVerify = dcapVerify,
                now = now,
            ),
        )
    }

    override suspend fun verify(
        relayUrl: String,
        nonce: ByteArray,
        attestation: RelayAttestation,
    ): String =
        checkAttestation(
            nonce = nonce,
            attestation = attestation,
            allowMock = allowMock,
            verify = quoteVerifier(relayUrl),
            verifyQuote = null,
        ).nodeKey

    private suspend fun <T> transport(
        step: String,
        run: suspend () -> T,
    ): T =
        try {
            run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayKeyTransportException) {
            throw e
        } catch (e: Exception) {
            throw RelayKeyTransportException(step, e)
        }
}
