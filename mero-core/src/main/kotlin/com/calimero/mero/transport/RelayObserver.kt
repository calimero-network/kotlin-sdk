package com.calimero.mero.transport

import com.calimero.mero.ExperimentalMeroApi
import com.calimero.mero.TokenData
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.expiresAtFromJwt
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.sse.SseConnection
import com.calimero.mero.ws.WsClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * How a relay-transport client observes the relay node — mero-js `RelayObserveConfig`.
 *
 * Every field but [nodeKey] defaults to the [com.calimero.mero.relay.RelayClient] already
 * configured: the author's proof, its signer and the relay's own URL are what a login needs,
 * and a second copy is how two copies come to disagree.
 */
class RelayObserveConfig(
    /**
     * The relay node's ed25519 signing key, hex, **as this client pinned it** — learned out of
     * band or from the relay's attestation, never from the challenge being answered. `null`
     * means "nobody told me": `canSubscribe` is false, and nothing guesses.
     */
    val nodeKey: String?,
    /** The client surface the session is bound to. A native app has no web origin: [Audience.Cli]. */
    val audience: Audience = Audience.Cli,
    /** The node to log in to; defaults to the relay's own URL (they are the same node). */
    val nodeUrl: String? = null,
    /** The author's `AccountProof<DeviceCert>`, hex; defaults to the relay client's. */
    val accountProof: String? = null,
    /** The device signer; defaults to the relay client's (the same device authors and observes). */
    val signer: DeviceSigner? = null,
    val clientName: String = "mero-kotlin-sdk",
    /** Seconds the login statement stays valid. */
    val ttlSeconds: Long = 300,
)

/**
 * A node session over the relay, and the event clients it feeds — port of mero-js
 * `RelayObserver`. A relay is an ordinary node: it serves `/auth/challenge`, `/auth/token`,
 * `/sse` and `/ws`, and the `account_proof` provider grants `context:subscribe`. The one input
 * that cannot be inferred is [nodeKey].
 *
 * The session is minted lazily, on the first thing that needs a token: reading [events] makes
 * no network call. [accessToken] is single-flight — two surfaces asking at once mint one
 * session — and re-mints once the token is within 30 s of expiring, or on every call when its
 * lifetime cannot be read (an unreadable `exp` means "mint a fresh one", never "assume an hour").
 */
class RelayObserver internal constructor(
    nodeUrl: String,
    /** The node key this observer logs in with. */
    val nodeKey: String,
    private val accountProof: String,
    private val signer: suspend () -> DeviceSigner,
    private val login: RelayLogin,
    private val streamClient: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The node logged in to, without a trailing slash. */
    val nodeUrl: String = nodeUrl.trimBase()

    private val mutex = Mutex()
    private var session: TokenData? = null
    private var expiresAtMs: Long? = null

    /** In-flight login, so two surfaces asking at once mint one session, not two. */
    private var pending: CompletableDeferred<TokenData>? = null

    /** A usable access token, logging in when there is none or it is about to lapse. */
    suspend fun accessToken(): String {
        val (inflight, mine) =
            mutex.withLock {
                val current = session
                val exp = expiresAtMs
                if (current != null && exp != null && exp - EXPIRY_SKEW_MS > now()) return current.accessToken
                pending?.let { it to false } ?: (CompletableDeferred<TokenData>().also { pending = it } to true)
            }
        if (mine) {
            try {
                val minted = login.login(nodeUrl, nodeKey, accountProof, signer())
                mutex.withLock {
                    session = minted
                    expiresAtMs = expiresAtFromJwt(minted.accessToken, UNKNOWN).takeIf { it != UNKNOWN }
                    pending = null
                }
                inflight.complete(minted)
            } catch (e: Throwable) {
                mutex.withLock { pending = null }
                inflight.completeExceptionally(e)
            }
        }
        return inflight.await().accessToken
    }

    private val eventsLazy = lazy { SseConnection(this.nodeUrl, { accessToken() }, streamClient) }

    @OptIn(ExperimentalMeroApi::class)
    private val wsLazy = lazy { WsClient(this.nodeUrl, { accessToken() }, streamClient) }

    /** SSE over the relay node — the same [SseConnection] a node client hands out, fed by [accessToken]. */
    val events: SseConnection get() = eventsLazy.value

    /** WebSocket events over the relay node. Experimental, as in mero-js: prefer [events]. */
    @ExperimentalMeroApi
    val ws: WsClient get() = wsLazy.value

    /** Close whatever was opened. Never touches the relay's write path. */
    @OptIn(ExperimentalMeroApi::class)
    fun close() {
        if (eventsLazy.isInitialized()) eventsLazy.value.close()
        if (wsLazy.isInitialized()) wsLazy.value.close()
    }

    companion object {
        /** Re-login this long before the access token expires, so a reconnect is not racing it. */
        const val EXPIRY_SKEW_MS = 30_000L
        private const val UNKNOWN = -1L

        /**
         * An observer for [nodeUrl] signed against [nodeKey], or `null` without a key — the
         * single place that decision is made (mero-js `buildObserver`).
         */
        @Suppress("LongParameterList")
        fun of(
            nodeUrl: String,
            nodeKey: String?,
            accountProof: String,
            signer: suspend () -> DeviceSigner,
            audience: Audience = Audience.Cli,
            clientName: String = "mero-kotlin-sdk",
            ttlSeconds: Long = 300,
            httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
        ): RelayObserver? {
            val key = nodeKey?.trim()?.lowercase()?.ifEmpty { null } ?: return null
            val login = RelayLogin(clientName = clientName, audience = audience, ttlSeconds = ttlSeconds, httpClient = httpClient)
            return RelayObserver(nodeUrl, key, accountProof, signer, login, streamingClient(httpClient))
        }
    }
}
