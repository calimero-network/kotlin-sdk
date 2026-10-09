package com.calimero.mero.relay

import com.calimero.mero.TokenData
import com.calimero.mero.crypto.BorshWriter
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.checkedSignature
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.domainHash
import com.calimero.mero.crypto.fromBase64
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.crypto.sha256
import com.calimero.mero.crypto.u64le
import com.calimero.mero.expiresAtFromJwt
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.net.URI

/**
 * Who a login statement is for. Core's `Audience` (`crates/account`): a web origin, a
 * code-signing id, or the CLI. A native app has no web origin; mero-js falls back to
 * [Cli] outside a browser and so does this SDK, unless told otherwise.
 */
sealed class Audience(
    internal val tag: Int,
) {
    data class WebOrigin(
        val origin: String,
    ) : Audience(0)

    data class CodeSigningId(
        val id: String,
    ) : Audience(1)

    data object Cli : Audience(2)

    internal fun body(): ByteArray =
        when (this) {
            is WebOrigin -> origin.encodeToByteArray()
            is CodeSigningId -> id.encodeToByteArray()
            Cli -> ByteArray(0)
        }
}

/**
 * The device's consent to one session on one node (core `LoginStatement`,
 * `calimero.auth.login.v1`). Port of mero-js `src/login/login.ts`.
 */
object LoginStatement {
    private const val DOMAIN = "calimero.auth.login.v1"

    /** Sign a statement binding [sessionKey] to [node] for [audience]. Returns hex. */
    @Suppress("LongParameterList")
    fun sign(
        node: String,
        audience: Audience,
        challenge: String,
        sessionKey: String,
        issuedAt: Long,
        expiresAt: Long,
        signer: DeviceSigner,
    ): String {
        val nodeBytes = Hex.decode(node, "node", 32)
        val challengeBytes = Hex.decode(challenge, "challenge", 32)
        val sessionBytes = Hex.decode(sessionKey, "sessionKey", 32)
        val deviceKey = Hex.decode(signer.publicKey, "signer.publicKey", 32)
        // In the preimage the audience is `tag ‖ body` with no length; on the wire the body
        // is a borsh String, and the Cli variant carries nothing after its tag.
        val audienceSigning = concat(byteArrayOf(audience.tag.toByte()), audience.body())
        val preimage =
            domainHash(
                DOMAIN,
                listOf(nodeBytes, audienceSigning, challengeBytes, sessionBytes, deviceKey, u64le(issuedAt), u64le(expiresAt)),
            )
        val signature = checkedSignature(signer.sign(preimage))
        val wire = BorshWriter().raw(nodeBytes).u8(audience.tag)
        if (audience !is Audience.Cli) wire.bytes(audience.body())
        return Hex.encode(
            wire
                .raw(challengeBytes)
                .raw(sessionBytes)
                .raw(deviceKey)
                .u64(issuedAt)
                .u64(expiresAt)
                .raw(signature)
                .toByteArray(),
        )
    }
}

/**
 * Decides which key a relay's node signs with — the key a login statement names.
 *
 * mero-js learns it from the relay's TEE attestation and **verifies the TDX DCAP quote**
 * against Calimero's signed release before trusting it. That verification is not ported
 * yet, so the default ([TlsRelayKeyVerifier]) trusts the TLS connection to the relay and
 * only checks the answer is well-formed and claims to bind our nonce and the key.
 *
 * TODO(follow-up): DCAP quote verification on mobile — or the cloud manager publishing a
 * signed node key beside `relay_url`. Plug either in by implementing this interface.
 * Writes (warrant intents) do not depend on this key; Bearer reads and SSE do.
 */
fun interface RelayKeyVerifier {
    /**
     * Return the relay's node key (64 hex) if [attestation] is acceptable for [relayUrl],
     * or throw [RelayKeyRefusedException].
     *
     * @param nonce the 32 random bytes this client sent; a quote must carry them.
     */
    suspend fun verify(
        relayUrl: String,
        nonce: ByteArray,
        attestation: RelayAttestation,
    ): String
}

/** What `POST {relay}/admin-api/tee/attest {nonce, bindNodeKey, includeCollateral}` answered. */
data class RelayAttestation(
    val quoteB64: String,
    /** The node key the relay says the quote binds, as sent. */
    val boundPublicKey: String?,
    /** The raw `data` object, for verifiers that need the collateral or more. */
    val raw: JsonObject,
)

/** The relay's attestation was answered and refused. Retrying cannot change the answer. */
class RelayKeyRefusedException(
    message: String,
) : Exception(message)

/**
 * The v1 mobile verifier: trusts TLS to the relay, and checks the attestation is shaped
 * like one made for this request — a well-formed node key, and report data that carries
 * our nonce and `SHA256("calimero.tee-attest.key-binding.v1" ‖ 0^32 ‖ nodeKey)`.
 *
 * **It does not verify the quote's signature or measurements.** A relay that lies about
 * its key over a valid TLS connection is not caught here; see [RelayKeyVerifier].
 *
 * A MOCK quote (dev rigs) is accepted only from a loopback relay unless [allowMock].
 */
class TlsRelayKeyVerifier(
    private val allowMock: Boolean = false,
) : RelayKeyVerifier {
    override suspend fun verify(
        relayUrl: String,
        nonce: ByteArray,
        attestation: RelayAttestation,
    ): String {
        val nodeKey = attestation.boundPublicKey?.trim()?.lowercase()
        if (!Hex.isHex32(nodeKey)) throw RelayKeyRefusedException("the relay named no well-formed node key (boundPublicKey)")
        val quote =
            try {
                fromBase64(attestation.quoteB64)
            } catch (e: Exception) {
                throw RelayKeyRefusedException("the relay's quote is not base64: ${e.message}")
            }
        val (reportData, mock) = RelayAttest.reportDataOf(quote)
        if (mock && !(allowMock || isLoopback(relayUrl))) {
            throw RelayKeyRefusedException("the relay answered with a MOCK quote, which proves nothing about the hardware")
        }
        if (!reportData.copyOfRange(0, 32).contentEquals(nonce)) {
            throw RelayKeyRefusedException("the quote does not carry our nonce: it was not made for this request")
        }
        if (!reportData.copyOfRange(32, 64).contentEquals(RelayAttest.keyBinding(Hex.decode(nodeKey!!, "nodeKey", 32)))) {
            throw RelayKeyRefusedException("the quote does not bind the key the relay named: refusing it")
        }
        return nodeKey
    }

    private fun isLoopback(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.trim('[', ']') ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2"
    }
}

/** TEE attestation byte layouts (mero-js `src/relay-attestation/attest-node-key.ts`). */
object RelayAttest {
    private val KEY_BINDING_DOMAIN = "calimero.tee-attest.key-binding.v1".encodeToByteArray()
    private val MOCK_QUOTE_HEADER = "MOCK_TDX_QUOTE_V1".encodeToByteArray()
    private const val TDX_REPORT_DATA_OFFSET = 48 + 520

    /** `SHA256(domain ‖ 0^32 ‖ nodeKey)`: the second half of the report data. */
    fun keyBinding(nodeKey: ByteArray): ByteArray = sha256(concat(KEY_BINDING_DOMAIN, ByteArray(32), nodeKey))

    /** The 64 report-data bytes of a quote, and whether it is a mock. */
    fun reportDataOf(quote: ByteArray): Pair<ByteArray, Boolean> {
        val mock =
            quote.size >= MOCK_QUOTE_HEADER.size && quote.copyOfRange(0, MOCK_QUOTE_HEADER.size).contentEquals(MOCK_QUOTE_HEADER)
        val at = if (mock) MOCK_QUOTE_HEADER.size else TDX_REPORT_DATA_OFFSET
        if (quote.size < at + 64) throw RelayKeyRefusedException("the quote is too short to carry report data (${quote.size} bytes)")
        return quote.copyOfRange(at, at + 64) to mock
    }

    /** A mock quote binding [nonce] and [nodeKey]: what a dev relay (and the test kit) answers. */
    fun mockQuote(
        nonce: ByteArray,
        nodeKey: ByteArray,
    ): ByteArray = concat(MOCK_QUOTE_HEADER, nonce, keyBinding(nodeKey))
}

/**
 * Logging in to a relay with the device certificate: learn its node key, then
 * `GET /auth/challenge` → `POST /auth/token` (`auth_method: account_proof`) with a signed
 * [LoginStatement]. The result is an ordinary Bearer token pair, so the existing
 * [com.calimero.mero.Mero] admin and SSE clients work against the relay.
 *
 * Port of mero-js `src/login/session.ts` plus `attestRelayNodeKey`.
 */
class RelayLogin internal constructor(
    private val http: PlainJsonHttp,
    private val verifier: RelayKeyVerifier,
    private val clientName: String,
    private val audience: Audience,
    private val ttlSeconds: Long,
) {
    constructor(
        verifier: RelayKeyVerifier = TlsRelayKeyVerifier(),
        clientName: String = "mero-kotlin-sdk",
        audience: Audience = Audience.Cli,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
        httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    ) : this(PlainJsonHttp(httpClient), verifier, clientName, audience, ttlSeconds)

    private val json = Json { ignoreUnknownKeys = true }

    /** Ask the relay to attest with its node key bound, and hand the answer to the [RelayKeyVerifier]. */
    suspend fun attestNodeKey(relayUrl: String): String {
        val nonce = randomBytes(32)
        val body =
            buildJsonObject {
                put("nonce", Hex.encode(nonce))
                put("bindNodeKey", true)
                put("includeCollateral", true)
            }
        val res = http.execute("POST", relayUrl.trimBase() + "/admin-api/tee/attest", body.toString()).ensureSuccessful()
        val parsed = json.parseToJsonElement(res.body).jsonObject
        val data = (parsed["data"] as? JsonObject) ?: parsed
        val quote = (data["quoteB64"] as? JsonPrimitive)?.contentOrNull ?: throw RelayKeyRefusedException("the relay returned no quote")
        val bound = (data["boundPublicKey"] as? JsonPrimitive)?.contentOrNull
        return verifier.verify(relayUrl, nonce, RelayAttestation(quote, bound, data))
    }

    /**
     * Mint a session on [relayUrl] whose node signs with [nodeKey], as the device that
     * [credential] certifies. Returns tokens ready for a [com.calimero.mero.storage.TokenStore].
     */
    suspend fun login(
        relayUrl: String,
        nodeKey: String,
        credential: String,
        signer: DeviceSigner,
    ): TokenData {
        val base = relayUrl.trimBase()
        val challengeBody = json.parseToJsonElement(http.execute("GET", "$base/auth/challenge").ensureSuccessful().body).jsonObject
        val challenge =
            (challengeBody["challenge"] as? JsonPrimitive)?.contentOrNull
                ?: ((challengeBody["data"] as? JsonObject)?.get("challenge") as? JsonPrimitive)?.contentOrNull
                ?: error("the relay issued no challenge")

        // An ephemeral session key: the statement binds it, the token is minted for it.
        val sessionSeed = randomBytes(32)
        val sessionKey = Hex.encode(Ed25519.publicKey(sessionSeed))
        val issuedAt = System.currentTimeMillis() / 1000
        val statement = LoginStatement.sign(nodeKey, audience, challenge, sessionKey, issuedAt, issuedAt + ttlSeconds, signer)
        val body =
            buildJsonObject {
                put("auth_method", "account_proof")
                put("public_key", sessionKey)
                put("client_name", clientName)
                put("timestamp", issuedAt)
                put(
                    "provider_data",
                    buildJsonObject {
                        put("challenge", challenge)
                        put("login_statement", statement)
                        put("account_proof", credential)
                    },
                )
            }
        val tokenBody = json.parseToJsonElement(http.execute("POST", "$base/auth/token", body.toString()).ensureSuccessful().body).jsonObject
        val data = (tokenBody["data"] as? JsonObject) ?: tokenBody
        val access =
            (data["access_token"] as? JsonPrimitive)?.contentOrNull ?: error("the relay minted no session")
        val refresh = (data["refresh_token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return TokenData(access, refresh, expiresAtFromJwt(access, System.currentTimeMillis() + ONE_HOUR_MS))
    }

    private companion object {
        const val DEFAULT_TTL_SECONDS = 300L
        const val ONE_HOUR_MS = 3_600_000L
    }
}
