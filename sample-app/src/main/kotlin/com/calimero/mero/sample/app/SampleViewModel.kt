package com.calimero.mero.sample.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.compose.MeroAppConfig
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.sample.mock.MockWallet
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer

/**
 * Owns the app's [MeroClient] for the activity's lifetime.
 *
 * - Real mode: [MeroClient.create] (Keystore-backed keys, session and relay tokens) against the
 *   hosted wallet and cloud manager, then [MeroClient.restore] to reconnect a saved session: the
 *   Cloud account's, or a node sign-in, including a username/password one, while its tokens validate.
 * - Mock mode: an in-app [FakeNode] plays both the cloud manager and the relay, and the
 *   wallet is [MockWallet] — so the whole sign-in runs with no network.
 */
class SampleViewModel(
    app: Application,
) : AndroidViewModel(app) {
    var client by mutableStateOf<MeroClient?>(null)
        private set
    var mock by mutableStateOf(false)
        private set
    var startError by mutableStateOf<String?>(null)
        private set

    private var server: MockWebServer? = null
    private var started = false

    fun start(mock: Boolean) {
        if (started) return
        started = true
        this.mock = mock
        if (!mock) {
            // The sample's callback is the `mero-sample://` scheme. A release app uses a verified
            // https App Link and leaves allowCustomSchemeCallbacks off: any app can claim a scheme.
            val c = MeroClient.create(getApplication(), config = MeroAppConfig(allowCustomSchemeCallbacks = true))
            client = c
            viewModelScope.launch { runCatching { c.restore() } }
            return
        }
        viewModelScope.launch {
            try {
                // MockWebServer.url() does a reverse-DNS lookup: never on the main thread.
                val base =
                    withContext(Dispatchers.IO) {
                        val srv = FakeNode().start().also { server = it }
                        srv.url("/").toString().trimEnd('/')
                    }
                val account =
                    CloudAccount(
                        MemorySecureStore(),
                        cloudBaseUrl = base,
                        walletUrl = MOCK_WALLET_URL,
                        relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true),
                    )
                client = MeroClient(account, MemoryTokenStore(), launchWallet = { _, url -> MockWallet.pendingUrl = url })
            } catch (e: Exception) {
                startError = "Mock backend failed to start: ${e.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val srv = server
        if (srv != null) Thread { runCatching { srv.shutdown() } }.start()
    }

    companion object {
        const val MOCK_WALLET_URL = "https://wallet.mock/account-enroll"
    }
}
