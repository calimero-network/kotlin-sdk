package com.calimero.mero.sample.mock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer

/**
 * State holder for the deterministic **mock flow** — the Android analog of the Swift sample's
 * `MeroClient` wired to `UITestMockURLProtocol`. It owns a [FakeNode]-backed [MockWebServer] and a
 * [Mero] client pointed at it, so the login → home → rpc → logout journey runs entirely on-device
 * with no live node. Drives [com.calimero.mero.sample.mock.MockLoginScreen] /
 * [com.calimero.mero.sample.mock.MockHomeScreen] and the CI instrumented `LoginFlowTest`.
 */
class MockViewModel : ViewModel() {
    var isAuthenticated by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var nodeUrl by mutableStateOf("http://localhost:4001")
    var username by mutableStateOf("")
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var rpcResult by mutableStateOf<String?>(null)
        private set

    private var server: MockWebServer? = null
    private var mero: Mero? = null

    /**
     * Validate the form, then (lazily) start the in-app [FakeNode] and authenticate against it.
     * Empty username/password surfaces an inline error and performs no network work — matching the
     * Swift `MeroClient.login` guard the UI test asserts.
     */
    fun login(
        node: String,
        user: String,
        password: String,
    ) {
        errorMessage = null
        if (user.isBlank() || password.isBlank()) {
            errorMessage = "Username and password are required."
            return
        }
        isLoading = true
        viewModelScope.launch {
            try {
                // Start the server AND build the base URL on IO: MockWebServer.url() calls
                // getCanonicalHostName(), a reverse-DNS lookup that throws
                // NetworkOnMainThreadException if resumed on the main dispatcher.
                val client =
                    withContext(Dispatchers.IO) {
                        val srv = server ?: FakeNode().start().also { server = it }
                        Mero(MeroConfig(baseUrl = srv.url("/").toString(), tokenStore = MemoryTokenStore()))
                    }
                client.authenticate(Credentials(username = user, password = password))
                mero = client
                nodeUrl = node
                username = user
                isAuthenticated = true
            } catch (e: Exception) {
                android.util.Log.e("MockViewModel", "mock login failed", e)
                isAuthenticated = false
                errorMessage = "Login failed: ${e.message ?: "unknown error"}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Run the demo contract call — the [FakeNode] returns `42` for method `get`. */
    fun runSampleRpc() {
        val client = mero ?: return
        errorMessage = null
        viewModelScope.launch {
            try {
                val value: Int = client.rpc.execute(contextId = "demo-context", method = "get")
                rpcResult = "RPC result: $value"
            } catch (e: Exception) {
                errorMessage = "RPC failed: ${e.message ?: "unknown error"}"
            }
        }
    }

    /** Clear the session and return to login. */
    fun logout() {
        viewModelScope.launch {
            mero?.logout()
            mero = null
            isAuthenticated = false
            username = ""
            rpcResult = null
            errorMessage = null
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { server?.shutdown() }
    }
}
