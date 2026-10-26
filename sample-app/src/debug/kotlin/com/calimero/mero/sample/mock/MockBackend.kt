package com.calimero.mero.sample.mock

import androidx.compose.runtime.Composable
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.sample.explorer.MockWalletCard
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-app mock backend, for `run-app.sh --mock` and the instrumented `LoginFlowTest`: a
 * [FakeNode] plays both the cloud manager and the relay, and the wallet is [MockWallet], so the
 * whole sign-in runs with no network.
 *
 * Debug builds only. The release variant of this object has no mock, so neither mero-testkit
 * nor the mock wallet's throwaway account root ship in a release APK.
 */
object MockBackend {
    const val AVAILABLE = true

    suspend fun start(): MockSession {
        // MockWebServer.url() does a reverse-DNS lookup: never on the main thread.
        val server = withContext(Dispatchers.IO) { FakeNode().start() }
        val base = withContext(Dispatchers.IO) { server.url("/").toString().trimEnd('/') }
        val account =
            CloudAccount(
                MemorySecureStore(),
                cloudBaseUrl = base,
                walletUrl = MOCK_WALLET_URL,
                relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true),
            )
        val client = MeroClient(account, MemoryTokenStore(), launchWallet = { _, url -> MockWallet.pendingUrl = url })
        return MockSession(client) { Thread { runCatching { server.shutdown() } }.start() }
    }

    /** The in-app "Mock wallet" sheet; [deliver] routes the redirect it builds back to the app. */
    fun walletCard(deliver: (String) -> Unit): (@Composable () -> Unit)? =
        {
            MockWalletCard(
                pendingUrl = MockWallet.pendingUrl,
                onApprove = { deliver(MockWallet.approve()) },
                onDecline = { deliver(MockWallet.decline()) },
            )
        }

    /** A real redirect arrived: the mock wallet has nothing pending any more. */
    fun clearPending() {
        MockWallet.pendingUrl = null
    }

    private const val MOCK_WALLET_URL = "https://wallet.mock/account-enroll"
}
