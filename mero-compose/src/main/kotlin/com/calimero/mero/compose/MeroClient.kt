package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.SsoLauncher
import com.calimero.mero.rpc.RpcClient
import com.calimero.mero.storage.EncryptedPrefsTokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** Observable auth/session state, mirroring the state mero-react's `MeroProvider` exposes. */
data class MeroAuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val nodeUrl: String? = null,
    val applicationId: String? = null,
    val contextId: String? = null,
    val contextIdentity: String? = null,
    val error: String? = null,
)

/**
 * Stateful wrapper around the core [Mero] client that a Compose UI observes. It owns a
 * [StateFlow] of [MeroAuthState] and exposes suspend login/logout plus SSO deep-link handling —
 * the Kotlin analogue of mero-react's `MeroProvider` + `useMero`.
 *
 * Construct via [create] on device (Keystore-backed store + cross-process refresh lock), or pass a
 * pre-built [Mero] directly (handy for tests with an in-memory store).
 */
class MeroClient(
    val mero: Mero,
    private val baseUrl: String,
    private val allowedNodeUrls: List<String> = emptyList(),
) {
    private val _state =
        MutableStateFlow(
            MeroAuthState(isAuthenticated = mero.isAuthenticated, nodeUrl = baseUrl),
        )
    val state: StateFlow<MeroAuthState> = _state.asStateFlow()

    val rpc: RpcClient get() = mero.rpc

    /** Direct credential login. */
    suspend fun login(
        username: String,
        password: String,
        bootstrapSecret: String? = null,
    ) {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            mero.authenticate(Credentials(username, password, bootstrapSecret))
            _state.update { it.copy(isAuthenticated = true, isLoading = false, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Login failed") }
        }
    }

    /** Open the node's hosted SSO login in a Custom Tab; the result arrives via [handleAuthCallback]. */
    fun startSsoLogin(
        context: Context,
        options: AuthLoginOptions,
    ) {
        SsoLauncher.launch(context, baseUrl, options)
    }

    /**
     * Consume an SSO callback deep-link. The callback's `node_url` is attacker-influenceable, so it is
     * trust-checked (§ mero-react `resolveTrustedNodeUrl`) before any token is stored. Returns true
     * when tokens were adopted.
     */
    fun handleAuthCallback(url: String): Boolean {
        val callback = Mero.parseAuthCallback(url) ?: return false
        if (!isTrustedNode(callback.nodeUrl)) {
            _state.update { it.copy(error = "SSO callback node_url is not trusted; tokens ignored") }
            return false
        }
        mero.setTokenData(TokenData(callback.accessToken, callback.refreshToken, expiresAt = 0L))
        _state.update {
            it.copy(
                isAuthenticated = true,
                applicationId = callback.applicationId.ifBlank { null },
                contextId = callback.contextId.ifBlank { null },
                contextIdentity = callback.contextIdentity.ifBlank { null },
                error = null,
            )
        }
        return true
    }

    /** Full logout: best-effort server revocation + local clear + state reset. */
    suspend fun logout(clientId: String? = null) {
        mero.logout(clientId)
        _state.update {
            MeroAuthState(isAuthenticated = false, nodeUrl = baseUrl)
        }
    }

    private fun isTrustedNode(nodeUrl: String): Boolean {
        // Empty node_url → bind to the node login was initiated with (the configured baseUrl).
        if (nodeUrl.isBlank()) return true
        val normalized = nodeUrl.trimEnd('/')
        return normalized == baseUrl.trimEnd('/') || normalized in allowedNodeUrls.map { it.trimEnd('/') }
    }

    companion object {
        fun create(
            context: Context,
            baseUrl: String,
            allowedNodeUrls: List<String> = emptyList(),
        ): MeroClient {
            val appContext = context.applicationContext
            val mero =
                Mero(
                    MeroConfig(
                        baseUrl = baseUrl,
                        tokenStore = EncryptedPrefsTokenStore(appContext),
                        refreshLockFile = File(appContext.filesDir, "mero-refresh.lock"),
                    ),
                )
            return MeroClient(mero, baseUrl, allowedNodeUrls)
        }
    }
}
