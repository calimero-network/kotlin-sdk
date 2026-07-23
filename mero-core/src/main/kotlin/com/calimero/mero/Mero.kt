package com.calimero.mero

import com.calimero.mero.admin.AdminApi
import com.calimero.mero.auth.AuthApi
import com.calimero.mero.auth.AuthCallbackResult
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.RefreshCoordinator
import com.calimero.mero.auth.RefreshTokenRequest
import com.calimero.mero.auth.RevokeTokenRequest
import com.calimero.mero.auth.TokenRequest
import com.calimero.mero.http.AuthInterceptor
import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.OkHttpTransport
import com.calimero.mero.http.TokenAuthenticator
import com.calimero.mero.rpc.RpcClient
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.SseClient
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Top-level SDK object — the Kotlin analogue of mero-js's `MeroJs`. Holds config + token state,
 * a single-flight cross-process refresh coordinator, and the typed sub-clients ([auth], [rpc]).
 *
 * The [TokenStore] is the single source of truth for the current bundle (in-memory by default);
 * every read/write of tokens goes through it, so the reactive 401→refresh path re-reads the store
 * inside its lock and never replays a consumed refresh token.
 */
class Mero(private val config: MeroConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val store: TokenStore = config.tokenStore ?: MemoryTokenStore()

    private val coordinator = RefreshCoordinator(
        tokenStore = store,
        refreshCall = { bundle ->
            val response = auth.refreshToken(
                RefreshTokenRequest(accessToken = bundle.accessToken, refreshToken = bundle.refreshToken),
            )
            val access = response.data.accessToken
            TokenData(
                accessToken = access,
                refreshToken = response.data.refreshToken,
                expiresAt = expiresAtFromJwt(access, System.currentTimeMillis() + ONE_HOUR_MS),
            )
        },
        lockFile = config.refreshLockFile,
    )

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor { store.getTokens()?.accessToken })
        .authenticator(TokenAuthenticator { triggering -> coordinator.refresh(triggering).accessToken })
        .build()

    /** Long-lived streaming client for SSE: no read timeout, no auth interceptor/authenticator. */
    private val sseHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val transport: HttpClient =
        OkHttpTransport(
            baseUrl = config.baseUrl,
            client = okHttpClient,
            json = json,
            // Terminal auth (revoked family) → drop the bundle so the app forces a re-login.
            onAuthRevoked = { store.clear() },
        )

    /** Typed `/auth/…` + key-management client. */
    val auth: AuthApi = AuthApi(transport)

    /** JSON-RPC 2.0 client (`/jsonrpc`). */
    val rpc: RpcClient = RpcClient(transport)

    /** Typed `/admin-api/…` client — contexts, groups, namespaces, invitations, registry install. */
    val admin: AdminApi = AdminApi(transport)

    /** The underlying transport, exposed for building higher-level clients (admin, events). */
    val http: HttpClient get() = transport

    /**
     * Live node events for the given [contextIds], over auto-reconnecting SSE. Cancel the collecting
     * coroutine to close the stream. Powers push updates (new messages, etc.) without polling.
     *
     * Uses a dedicated OkHttp client with no read timeout (the stream is long-lived) and the token
     * in the query string, so it does not share the reactive-refresh authenticator on [okHttpClient].
     * (== mero-swift-sdk `Mero.events`.)
     */
    fun events(contextIds: List<String>): Flow<ContextEvent> =
        SseClient(
            baseUrl = config.baseUrl,
            token = { store.getTokens()?.accessToken },
            client = sseHttpClient,
            json = json,
        ).events(contextIds)

    /**
     * Authenticate with credentials (creates the root key on first use). Builds the exact mero-js
     * request body: `auth_method=user_password`, `client_name=mero-kotlin-sdk`, `permissions=[admin]`.
     * The `bootstrap_secret` (core#3221) is included when supplied on the credentials or via the
     * `MERO_AUTH_BOOTSTRAP_SECRET` env var (test harnesses), and omitted otherwise.
     */
    suspend fun authenticate(credentials: Credentials? = null): TokenData {
        val creds = credentials
            ?: config.credentials
            ?: throw MeroStateException("No credentials provided for authentication")

        val bootstrapSecret =
            creds.bootstrapSecret?.takeIf { it.isNotBlank() }
                ?: System.getenv("MERO_AUTH_BOOTSTRAP_SECRET")?.takeIf { it.isNotBlank() }

        val providerData = buildJsonObject {
            put("username", creds.username)
            put("password", creds.password)
            if (bootstrapSecret != null) put("bootstrap_secret", bootstrapSecret)
        }

        val request = TokenRequest(
            authMethod = "user_password",
            publicKey = creds.username,
            clientName = "mero-kotlin-sdk",
            permissions = listOf("admin"),
            timestamp = System.currentTimeMillis() / 1000,
            providerData = providerData,
        )

        val response = auth.generateTokens(request)
        val access = response.data.accessToken
        val data = TokenData(
            accessToken = access,
            refreshToken = response.data.refreshToken,
            expiresAt = expiresAtFromJwt(access, System.currentTimeMillis() + ONE_HOUR_MS),
        )
        store.setTokens(data)
        return data
    }

    /**
     * Set tokens directly (e.g. from an SSO callback). Recomputes `expiresAt` from the JWT when the
     * supplied value is missing/zero.
     */
    fun setTokenData(data: TokenData) {
        val expiresAt =
            if (data.expiresAt > 0) {
                data.expiresAt
            } else {
                expiresAtFromJwt(data.accessToken, System.currentTimeMillis() + ONE_HOUR_MS)
            }
        store.setTokens(data.copy(expiresAt = expiresAt))
    }

    /** Current token bundle, or null if not authenticated. */
    fun getTokenData(): TokenData? = store.getTokens()

    /** True if a token bundle is present. */
    val isAuthenticated: Boolean get() = store.getTokens() != null

    /** Drop the current tokens (logout). Does not call the network — see [logout] for a full logout. */
    fun clearToken() {
        store.clear()
    }

    /**
     * Full logout: best-effort server-side revocation (when a `client_id` is known) followed by a
     * local token clear. The local clear always runs even if revocation fails, mirroring mero-react's
     * `logout()` (which clears storage unconditionally).
     */
    suspend fun logout(clientId: String? = null) {
        if (clientId != null) {
            try {
                auth.revokeTokens(RevokeTokenRequest(clientId = clientId))
            } catch (_: Exception) {
                // Never let a failed revoke block the local clear.
            }
        }
        store.clear()
    }

    companion object {
        private const val ONE_HOUR_MS = 3_600_000L

        /** Parse an SSO callback URL's hash fragment. */
        @JvmStatic
        fun parseAuthCallback(url: String): AuthCallbackResult? =
            com.calimero.mero.auth.parseAuthCallback(url)

        /** Build the node's SSO login URL. */
        @JvmStatic
        fun buildAuthLoginUrl(nodeUrl: String, options: AuthLoginOptions): String =
            com.calimero.mero.auth.buildAuthLoginUrl(nodeUrl, options)
    }
}
