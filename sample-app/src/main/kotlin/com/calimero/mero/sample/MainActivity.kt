package com.calimero.mero.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.calimero.mero.sample.app.SampleViewModel
import com.calimero.mero.sample.explorer.ExplorerApp
import com.calimero.mero.sample.explorer.ExplorerOptions
import com.calimero.mero.sample.explorer.MockWalletCard
import com.calimero.mero.sample.mock.MockWallet
import com.calimero.mero.sample.ui.Cal
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Entry point. Two run modes, selected by intent extras:
 *  - `mock = true`: an in-app FakeNode plays the cloud manager and the relay, and the wallet is
 *    [MockWallet]. Drives the CI instrumented `LoginFlowTest`.
 *  - default: the hosted Calimero wallet, cloud manager and relay.
 *
 * Sign-in is Calimero Cloud only. The wallet redirects back to [callbackUrl]
 * (`mero-sample://enrol#credential=…` by default, see the manifest's intent filter); the redirect
 * arrives on the launch intent or through [onNewIntent] and goes to
 * [com.calimero.mero.compose.MeroClient.handleEnrolmentCallback].
 */
class MainActivity : ComponentActivity() {
    private val vm: SampleViewModel by viewModels()
    private var pendingCallback: String? = null

    /** Where the wallet sends the person back. Overridable with the `callbackUrl` extra (e.g. an App Link). */
    private val callbackUrl: String
        get() = intent?.getStringExtra(EXTRA_CALLBACK_URL) ?: DEFAULT_CALLBACK_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mock = intent?.getBooleanExtra(EXTRA_MOCK, false) ?: false
        vm.start(mock)
        val options =
            ExplorerOptions(
                callbackUrl = callbackUrl,
                initialContextId = intent?.getStringExtra(EXTRA_CONTEXT) ?: if (mock) MOCK_CONTEXT else "",
                chatDisplayName = intent?.getStringExtra(EXTRA_CHAT_USER),
                autoJoinInvite = intent?.getStringExtra(EXTRA_INVITE),
                mockWallet =
                    if (mock) {
                        {
                            MockWalletCard(
                                pendingUrl = MockWallet.pendingUrl,
                                onApprove = { deliverCallback(MockWallet.approve()) },
                                onDecline = { deliverCallback(MockWallet.decline()) },
                            )
                        }
                    } else {
                        null
                    },
            )
        consumeCallbackIntent(intent)

        // A redirect that arrived before the client existed (cold start from the wallet) is
        // handed over as soon as it does.
        lifecycleScope.launch {
            val client = snapshotFlow { vm.client }.filterNotNull().first()
            pendingCallback?.let { url ->
                pendingCallback = null
                client.handleEnrolmentCallback(url)
            }
        }

        setContent {
            val client = vm.client
            if (client == null) {
                Box(Modifier.fillMaxSize().background(Cal.bg), contentAlignment = Alignment.Center) {
                    val error = vm.startError
                    if (error != null) Text(error, color = Cal.error) else CircularProgressIndicator(color = Cal.accentInk)
                }
            } else {
                ExplorerApp(client, options)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeCallbackIntent(intent)
    }

    /** Route a wallet redirect through the same intent path a real one takes. */
    private fun deliverCallback(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(packageName))
    }

    private fun consumeCallbackIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith(callbackUrl) || data.startsWith(DEFAULT_CALLBACK_URL)) {
            val client = vm.client
            if (client != null) {
                MockWallet.pendingUrl = null
                lifecycleScope.launch { client.handleEnrolmentCallback(data) }
            } else {
                pendingCallback = data
            }
        }
    }

    companion object {
        const val EXTRA_MOCK = "mock"
        const val EXTRA_CALLBACK_URL = "callbackUrl"
        const val EXTRA_CONTEXT = "contextId"
        const val EXTRA_CHAT_USER = "chatUser"
        const val EXTRA_INVITE = "invite"

        /** Must match the `<data android:scheme="mero-sample" android:host="enrol" />` intent filter. */
        const val DEFAULT_CALLBACK_URL = "mero-sample://enrol"
        const val MOCK_CONTEXT = "demo-context"
    }
}
