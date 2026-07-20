package com.calimero.mero

import com.calimero.mero.storage.TokenStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
 */
data class MeroConfig(
    val baseUrl: String,
    val credentials: Credentials? = null,
    val timeoutMs: Long = 10_000,
    val tokenStore: TokenStore? = null,
    val refreshLockFile: File? = null,
)

/**
 * Login credentials.
 *
 * @property bootstrapSecret first-login setup code (core#3221 / rc.14). A fresh node only mints its
 *   first root key when the login carries this out-of-band secret — `merod` prints it at startup and
 *   stores it in `config.toml`. Ignored once an account exists, so it is always safe to include.
 */
data class Credentials(
    val username: String,
    val password: String,
    val bootstrapSecret: String? = null,
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
)
