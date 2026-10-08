package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.Mero
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.EncryptedPrefsSecureStore
import com.calimero.mero.account.EnrolmentException
import com.calimero.mero.account.JoinResult
import com.calimero.mero.account.RelayConnection
import com.calimero.mero.account.WalletLauncher
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.storage.EncryptedPrefsTokenStore
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Observable Cloud session state — what mero-react's `MeroProvider` exposes for a
 * delegated (account) session.
 *
 * Three signed-in shapes matter to a UI:
 * - [isAuthenticated] with a [relayUrl] and [sessionReady]: fully connected.
 * - [isAuthenticated] with a [relayUrl] but not [sessionReady]: writes work (warrants),
 *   admin reads and live events wait for the relay's node key.
 * - [isAuthenticated] with no [relayUrl]: signed in, but no relay serves the account yet —
 *   normal for a new account until it redeems an invitation ([relayNote] says so).
 */
data class MeroAuthState(
    /** Signed in with Calimero Cloud: this device holds a verified certificate for [account]. */
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    /** The account, 64 hex. */
    val account: String? = null,
    /** This device's id within the account, 64 hex. */
    val device: String? = null,
    /** The relay serving the account, or null when none does yet. */
    val relayUrl: String? = null,
    /** True once a Bearer session on the relay is minted (admin reads + SSE available). */
    val sessionReady: Boolean = false,
    /** Why the relay situation is degraded or empty, in words a person can read. */
    val relayNote: String? = null,
    /** The person declined at the wallet (not an error to report loudly). */
    val cancelled: Boolean = false,
    val error: String? = null,
) {
    /** Signed in, but no relay to write through yet. */
    val signedInWithoutRelay: Boolean get() = isAuthenticated && relayUrl == null
}

/**
 * The Compose-facing Calimero client: **Cloud sign-in only**.
 *
 * The person signs in with their Calimero account in the wallet (a passkey, in a Custom
 * Tab); this device gets a certificate for its own key and talks to the account's hosted
 * relay. There is no node URL, username or password on this path — for a self-hosted node
 * use the core [Mero] client directly (`Mero.authenticate`), which stays available for
 * servers, tests and tools.
 *
 * Flow:
 * 1. [signInWithCloud] opens the wallet.
 * 2. The app's callback intent filter receives the redirect and calls [handleEnrolmentCallback].
 * 3. State flips to authenticated; [relay] (writes, reads) and [mero] (admin + events on the
 *    relay) become available.
 *
 * Call [restore] once at startup to reconnect a persisted session.
 */
class MeroClient(
    val account: CloudAccount,
    private val tokenStore: TokenStore = MemoryTokenStore(),
    private val launchWallet: (Context, String) -> Unit = WalletLauncher::launch,
) {
    private val _state = MutableStateFlow(stateFor())
    val state: StateFlow<MeroAuthState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<RelayConnection?>(null)

    /** The live relay connection, or null when signed out or no relay serves the account. */
    val connection: StateFlow<RelayConnection?> = _connection.asStateFlow()

    /** Warranted writes and reads on the account's relay. */
    val relay: RelayClient? get() = _connection.value?.relay

    /** The admin / events / auth clients, pointed at the relay (Bearer once [MeroAuthState.sessionReady]). */
    val mero: Mero? get() = _connection.value?.mero

    /**
     * Open the Calimero wallet so the person can approve this device. [callbackUrl] must be
     * a URL this app receives — an https App Link or an app scheme such as
     * `myapp://calimero-enrol` registered in an intent filter.
     */
    fun signInWithCloud(
        context: Context,
        callbackUrl: String,
    ) {
        _state.update { it.copy(isLoading = true, error = null, cancelled = false) }
        try {
            launchWallet(context, account.beginEnrolment(callbackUrl))
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Could not open the Calimero wallet") }
        }
    }

    /** The wallet URL [signInWithCloud] would open, for apps that launch it themselves. */
    fun enrolmentUrl(callbackUrl: String): String = account.beginEnrolment(callbackUrl)

    /** Stop showing a spinner if the person closed the wallet without an answer. */
    fun cancelPendingSignIn() {
        _state.update { it.copy(isLoading = false) }
    }

    /**
     * Consume the wallet's redirect. Returns true when [url] was an enrolment answer (even
     * a declined or invalid one, which lands in [MeroAuthState.cancelled] / `error`), false
     * for an unrelated deep link.
     */
    suspend fun handleEnrolmentCallback(url: String): Boolean {
        if (!account.isEnrolmentCallback(url)) return false
        _state.update { it.copy(isLoading = true, error = null, cancelled = false) }
        try {
            account.completeEnrolment(url) ?: run {
                _state.update { it.copy(isLoading = false) }
                return false
            }
            connect(note = account.lastRelayNote)
        } catch (e: EnrolmentException) {
            _state.update {
                stateFor().copy(
                    cancelled = e.cancelled,
                    error = if (e.cancelled) null else e.message ?: "Sign-in failed",
                )
            }
        } catch (e: Exception) {
            _state.update { stateFor().copy(error = e.message ?: "Sign-in failed") }
        }
        return true
    }

    /** Reconnect a persisted session (call at startup). Re-asks the cloud which relay serves the account. */
    suspend fun restore() {
        if (!account.isSignedIn) {
            _state.value = stateFor()
            return
        }
        _state.update { stateFor().copy(isLoading = true) }
        // Re-ask the cloud: the account may have gained a relay (or moved) since last time.
        // A failed lookup keeps the persisted relay.
        val note = runCatching { account.findRelay() }.fold({ account.lastRelayNote }, { null })
        connect(note)
    }

    /**
     * Redeem an invitation as this account. On success the admitting node becomes the
     * account's relay (when it had none) and the client reconnects.
     */
    suspend fun joinWithInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): JoinResult {
        val result = account.joinAsAccount(namespaceId, invitation)
        connect(note = null)
        return result
    }

    /** Sign out: drop the relay session and the persisted account session. */
    suspend fun signOut(forgetDevice: Boolean = false) {
        runCatching { _connection.value?.mero?.clearToken() }
        tokenStore.clear()
        _connection.value = null
        account.signOut(forgetDevice)
        _state.value = MeroAuthState()
    }

    /** Alias of [signOut], for parity with mero-react's `logout`. */
    suspend fun logout() = signOut()

    private suspend fun connect(note: String?) {
        val session = account.session()
        if (session == null) {
            _state.value = stateFor()
            return
        }
        if (session.relayUrl == null) {
            _connection.value = null
            val why = note ?: account.lastRelayNote ?: runCatching { account.chooseRelay(session).note }.getOrNull()
            _state.value = stateFor().copy(relayNote = why)
            return
        }
        val connection = account.connect(session, tokenStore)
        _connection.value = connection
        _state.value = stateFor().copy(isLoading = true, relayNote = note)
        val ready = runCatching { connection.ensureSession() }.getOrDefault(false)
        _state.update {
            it.copy(
                isLoading = false,
                sessionReady = ready,
                relayNote =
                    note ?: if (ready) null else "Writes go through the relay; reads and live updates resume once it is attested.",
            )
        }
    }

    private fun stateFor(): MeroAuthState {
        val s = account.session()
        return MeroAuthState(isAuthenticated = s != null, account = s?.account, device = s?.device, relayUrl = s?.relayUrl)
    }

    companion object {
        /**
         * The on-device client: keys, session and nonces in a Keystore-backed
         * [EncryptedPrefsSecureStore], relay tokens in an [EncryptedPrefsTokenStore].
         */
        fun create(
            context: Context,
            cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
            walletUrl: String = com.calimero.mero.account.Enrolment.DEFAULT_WALLET_URL,
        ): MeroClient {
            val appContext = context.applicationContext
            val account = CloudAccount(EncryptedPrefsSecureStore(appContext), cloudBaseUrl = cloudBaseUrl, walletUrl = walletUrl)
            return MeroClient(account, EncryptedPrefsTokenStore(appContext, fileName = "mero_relay_tokens"))
        }
    }
}
