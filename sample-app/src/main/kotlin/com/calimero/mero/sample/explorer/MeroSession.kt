package com.calimero.mero.sample.explorer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.SsoLauncher
import com.calimero.mero.sample.chat.ChatService
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.launch

/**
 * Holds the authenticated [Mero] client for the explorer, drives credential / SSO login and logout,
 * keeps a diagnostics log, and owns a single [ChatService] for the session. Compose observes its
 * `mutableStateOf`-backed fields directly. Android analog of the Swift sample's `MeroSession`.
 */
class MeroSession : ViewModel() {
    data class LogLine(
        val level: String,
        val text: String,
    )

    var isAuthenticated by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var nodeUrl by mutableStateOf("http://10.0.2.2:2528")
    var username by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var nodeSummary by mutableStateOf("")
        private set
    var chat by mutableStateOf<ChatService?>(null)
        private set

    /**
     * e2e hooks, set from the launch intent by `MainActivity` (the Android analog of the Swift
     * sample's `E2E_USERNAME` / `E2E_JOIN` env vars): a chat display name that overrides the login
     * user, and an invite code the chat screen auto-joins on open.
     */
    var chatDisplayName: String? = null
    var autoJoinInvite: String? = null

    val logs = mutableStateListOf<LogLine>()

    var mero: Mero? = null
        private set

    private var pendingSsoNodeUrl: String? = null
    private val callbackScheme = "mero-sample"

    private fun log(
        level: String,
        text: String,
    ) {
        logs.add(LogLine(level, text))
        if (logs.size > MAX_LOGS) logs.removeRange(0, logs.size - MAX_LOGS)
    }

    fun clearLogs() = logs.clear()

    fun logText(): String = logs.joinToString("\n") { "${it.level} ${it.text}" }

    /** Credential login against [nodeUrlString]. Publishes auth state (and a diagnostics log). */
    fun login(
        nodeUrlString: String,
        user: String,
        password: String,
    ) {
        errorMessage = null
        log("→", "connect $nodeUrlString as \"${user.ifEmpty { "<empty>" }}\"")
        if (user.isBlank() || password.isBlank()) {
            errorMessage = "Username and password are required."
            log("!", "missing username or password — nothing sent")
            return
        }
        isLoading = true
        viewModelScope.launch {
            try {
                val client = Mero(MeroConfig(baseUrl = nodeUrlString, tokenStore = MemoryTokenStore()))
                client.authenticate(Credentials(username = user, password = password))
                mero = client
                nodeUrl = nodeUrlString
                username = user
                isAuthenticated = true
                chat = ChatService(client, user, chatDisplayName)
                log("✓", "authenticated — token acquired")
                refreshSummary()
            } catch (e: Exception) {
                isAuthenticated = false
                errorMessage = friendly(e)
                log("✗", "login failed — ${e.message}")
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * Start hosted-SSO login: open the node's `/auth/login` page in a Custom Tab (admin mode). The
     * callback returns asynchronously via the deep link into [consumeSsoCallback].
     */
    fun connect(
        context: Context,
        nodeUrlString: String,
    ) {
        errorMessage = null
        pendingSsoNodeUrl = nodeUrlString
        log("→", "SSO connect $nodeUrlString")
        val options =
            AuthLoginOptions(
                callbackUrl = "$callbackScheme://auth-callback",
                mode = "admin",
                permissions = listOf("admin"),
            )
        SsoLauncher.launch(context, nodeUrlString, options)
    }

    /** Adopt tokens from an SSO callback deep link. Called by the activity when the redirect lands. */
    fun consumeSsoCallback(url: String) {
        val result = Mero.parseAuthCallback(url)
        if (result == null) {
            errorMessage = "The login page returned no tokens."
            log("✗", "no access_token in callback fragment")
            return
        }
        val base = pendingSsoNodeUrl ?: nodeUrl
        val client = Mero(MeroConfig(baseUrl = base, tokenStore = MemoryTokenStore()))
        client.setTokenData(TokenData(result.accessToken, result.refreshToken, expiresAt = 0L))
        mero = client
        nodeUrl = base
        username = "admin"
        isAuthenticated = true
        chat = ChatService(client, "admin", chatDisplayName)
        log("✓", "authenticated via SSO — tokens adopted")
        viewModelScope.launch { refreshSummary() }
    }

    fun logout() {
        log("→", "logout")
        viewModelScope.launch {
            mero?.logout()
            mero = null
            chat = null
            isAuthenticated = false
            nodeSummary = ""
            log("✓", "signed out")
        }
    }

    private suspend fun refreshSummary() {
        val client = mero ?: return
        try {
            val id = client.auth.getIdentity()
            val peers = runCatching { client.admin.getPeersCount() }.getOrNull()
            var line = "${id.service} · ${id.version} · ${id.authenticationMode}"
            if (peers != null) line += " · ${peers.count} peers"
            nodeSummary = line
            log("•", "identity: $line")
        } catch (e: Exception) {
            nodeSummary = "connected"
            log("!", "identity fetch failed — ${e.message}")
        }
    }

    private fun friendly(error: Exception): String =
        error.message?.let { "Login failed — $it" } ?: "Something went wrong."

    private companion object {
        const val MAX_LOGS = 300
    }
}
