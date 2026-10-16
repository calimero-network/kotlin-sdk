package com.calimero.mero

import com.calimero.mero.request.ProofSigner
import com.calimero.mero.storage.TokenStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.File

/**
 * Configuration for a [Mero] instance.
 *
 * Mirrors `MeroJsConfig` from `@calimero-network/mero-js` (v7.0.1).
 *
 * @property baseUrl base URL of the remote Calimero node (e.g. `https://node.example.com`).
 * @property credentials optional initial credentials used by [Mero.authenticate].
 * @property timeoutMs per-request timeout in milliseconds (connect/read/write).
 * @property tokenStore where tokens are persisted. Defaults to an in-memory store; supply an
 *   [com.calimero.mero.storage.EncryptedPrefsTokenStore] on device so tokens survive process death
 *   and the cross-process refresh lock (§4.1 of the SDK spec) has a stable backing store.
 * @property refreshLockFile file backing the cross-process refresh lock. Pass a file in app-internal
 *   storage (e.g. `File(context.filesDir, "mero-refresh.lock")`) when a second process/WorkManager
 *   job can refresh concurrently. Null (default) → in-process single-flight only.
 * @property requestProof sign every request with a device key, instead of or alongside a session —
 *   mero-js `MeroJsConfig.requestProof`. Each request's method, path and query, and body are
 *   signed at the moment of the call and sent as `X-Calimero-Proof`
 *   ([com.calimero.mero.request.REQUEST_PROOF_HEADER]). Build one with
 *   [com.calimero.mero.request.createProofSigner] (from `ProofSignerOptions` or a
 *   `ProofCredential`), or pass your own when the key must not reach the SDK. Returning `null`
 *   sends no proof for that request. With a proof configured the HTTP clients **do not follow
 *   redirects**: a 3xx answering a proof-carrying request fails with
 *   [com.calimero.mero.http.ProofRedirectRefusedException]. The node consults a proof only when
 *   the request has no token, and serves other accounts' proofs only with `--delegated-access`
 *   (otherwise `403`; a bad proof is a `401`, `x-auth-error: invalid_proof`). SSE ([Mero.sse])
 *   and the WebSocket ([Mero.ws]) present the session token when there is one and the proof
 *   otherwise.
 */
data class MeroConfig(
    val baseUrl: String,
    val credentials: Credentials? = null,
    val timeoutMs: Long = 10_000,
    val tokenStore: TokenStore? = null,
    val refreshLockFile: File? = null,
    val requestProof: ProofSigner? = null,
    /**
     * The client every request is carried on — mero-js `MeroJsConfig.fetch`. [Mero] builds its
     * own clients from it (its timeouts, and its auth, proof and refresh handling in front of
     * this client's interceptors), so a sealed transport's client
     * ([com.calimero.mero.tee.SealedTransport.client]) seals every request, SSE included.
     * Null: a fresh client.
     */
    val httpClient: OkHttpClient? = null,
)

/**
 * Login credentials.
 *
 * There is no `bootstrapSecret` any more. The first-login setup code (core#3221)
 * stopped being how a node gets its first admin: since rc.17 `merod init` creates
 * the admin account, and rc.29 parses `bootstrap_secret` only to discard it
 * (`crates/auth/src/providers/impls/user_password.rs`). Sending it did nothing.
 */
data class Credentials(
    val username: String,
    val password: String,
)

/**
 * A token bundle. `expiresAt` is an epoch-millisecond timestamp computed from the access token's
 * JWT `exp` claim (see [expiresAtFromJwt]), falling back to now + 1h.
 *
 * Serializable so it can be persisted verbatim by a [TokenStore]. The wire/storage names stay
 * snake_case to match the mero-js `localStorage` payload, so a token bundle written by one SDK is
 * readable by the other on the same device.
 */
@Serializable
data class TokenData(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    /**
     * Origin and path prefix of the node that issued the bundle ([nodeOriginOf]), as mero-js
     * stores it. [Mero] tags what it persists and ignores a stored bundle for another node — or
     * one with no tag, written before this field existed — so tokens are never sent to a node
     * that did not issue them. Optional, so older bundles still decode. [Mero.getTokenData]
     * returns bundles without it.
     */
    @SerialName("node_origin") val nodeOrigin: String? = null,
)

/**
 * The key a token bundle is bound to: the node's origin (`scheme://host[:port]`, default port
 * omitted, host lower-cased) plus its path prefix without trailing slashes — so two nodes
 * behind one host (`https://host/node1`, `https://host/node2`) stay apart, and
 * `https://host/node1/` is the same node as `https://host/node1`. A URL that is not http(s)
 * keys as itself. Port of mero-js `nodeKeyOf`.
 */
fun nodeOriginOf(baseUrl: String): String {
    val url = baseUrl.toHttpUrlOrNull() ?: return baseUrl
    val host = if (url.host.contains(':')) "[${url.host}]" else url.host
    val port = if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"
    return "${url.scheme}://$host$port${url.encodedPath.trimEnd('/')}"
}
