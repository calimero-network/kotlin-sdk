package com.calimero.mero.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.calimero.mero.sample.explorer.ExplorerApp
import com.calimero.mero.sample.explorer.MeroSession
import com.calimero.mero.sample.mock.MockRoot
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Entry point. Two run modes, selected by intent extras:
 *  - `mock = true` → the deterministic mock flow (in-app FakeNode, no live node). Drives the CI
 *    instrumented `LoginFlowTest`.
 *  - `mock = false` (default) → the real-node Explorer.
 *
 * Also consumes SSO callback deep links (`mero-sample://auth-callback#...`), delivered on the launch
 * intent or via [onNewIntent], and forwards them into the explorer's [MeroSession].
 */
class MainActivity : ComponentActivity() {
    private val ssoCallbacks = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mock = intent?.getBooleanExtra(EXTRA_MOCK, false) ?: false
        val nodeUrl = intent?.getStringExtra(EXTRA_NODE_URL)
        val chatUser = intent?.getStringExtra(EXTRA_CHAT_USER)
        val invite = intent?.getStringExtra(EXTRA_INVITE)
        consumeCallbackIntent(intent)

        setContent {
            if (mock) {
                MockRoot(initialNodeUrl = nodeUrl ?: DEFAULT_NODE_URL)
            } else {
                val session: MeroSession = viewModel()
                LaunchedEffect(nodeUrl) { if (!nodeUrl.isNullOrBlank()) session.nodeUrl = nodeUrl }
                LaunchedEffect(chatUser, invite) {
                    // e2e hooks: a chat display name and an invite to auto-join (see MeroSession).
                    if (!chatUser.isNullOrBlank()) session.chatDisplayName = chatUser
                    if (!invite.isNullOrBlank()) session.autoJoinInvite = invite
                }
                LaunchedEffect(Unit) {
                    ssoCallbacks.collect { url -> url?.let { session.consumeSsoCallback(it) } }
                }
                ExplorerApp(session)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallbackIntent(intent)
    }

    private fun consumeCallbackIntent(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("$CALLBACK_SCHEME://")) ssoCallbacks.value = data
    }

    private companion object {
        const val EXTRA_MOCK = "mock"
        const val EXTRA_NODE_URL = "nodeUrl"
        const val EXTRA_CHAT_USER = "chatUser"
        const val EXTRA_INVITE = "invite"
        const val DEFAULT_NODE_URL = "http://localhost:4001"
        const val CALLBACK_SCHEME = "mero-sample"
    }
}
