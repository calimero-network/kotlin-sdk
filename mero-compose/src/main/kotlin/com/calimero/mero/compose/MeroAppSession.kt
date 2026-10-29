package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.account.ApplicationIds
import com.calimero.mero.account.Enrolment
import com.calimero.mero.app.ContextContract
import com.calimero.mero.app.MeroAppBackend
import com.calimero.mero.app.NoRelayContextContract
import com.calimero.mero.invite.InviteLink
import com.calimero.mero.relay.ApplicationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How a [MeroAppSession] treats links and where it founds and joins. (Swift `MeroAppSessionOptions`.) */
data class MeroAppSessionOptions(
    /**
     * Whether a link the system handed the app is one of this app's invitations. Null takes
     * [MeroAppSession.isInvitationLink]: a link carrying `invitation=…` whose path names the app's
     * package.
     */
    val isInvitation: ((String) -> Boolean)? = null,
    /** What a new namespace is founded with. Null resolves the app's package from the registry ([DelegatedAppBackend.foundingApplication]). */
    val foundingApplication: ApplicationTarget? = null,
    /** Where to send a join. Null asks the Cloud manager for the admitting relay ([DelegatedAppBackend.joinNodeUrl]). */
    val joinNodeUrl: String? = null,
)

/**
 * A Calimero app's signed-in account, delegated only: Calimero Cloud enrolment (the wallet
 * certifies this device's key), the account's relay from the Cloud manager, and the
 * [MeroAppBackend] built over that relay. Keep one for the app's life (in an `AndroidViewModel`,
 * with its `viewModelScope`). (Swift `MeroKitUI.MeroAppSession`.)
 *
 * There is no node login and no node URL: the [MeroClient] is only driven through its Cloud
 * entry points ([MeroClient.signInWithCloud], [MeroClient.restore], [MeroClient.handleEnrolmentCallback],
 * [MeroClient.signOut]).
 *
 * ```kotlin
 * val session = MeroAppSession.create(app, "com.example.board", "https://board.example.com/enrol", scope = viewModelScope)
 * viewModelScope.launch { session.restore() }
 * // every deep link: viewModelScope.launch { session.handle(url) }
 * ```
 *
 * Screens collect [backend] (and re-read when [revision] moves); view models take it as a
 * [MeroAppBackend], so a test hands them mero-testkit's `InMemoryAppBackend`. A generated
 * contract client calls `session.contract.bound(contextId)`.
 *
 * @param scope where the session follows [client]: it rebuilds [backend] whenever the client's
 *   state changes (the application id learned, a relay session minted, a join's reconnect).
 */
class MeroAppSession(
    val client: MeroClient,
    /** Where the wallet sends the person back to: an App Link or an app scheme the app receives. */
    val callbackUrl: String,
    val options: MeroAppSessionOptions = MeroAppSessionOptions(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _backend = MutableStateFlow<MeroAppBackend?>(null)

    /** The signed-in account's backend, or null when signed out. */
    val backend: StateFlow<MeroAppBackend?> = _backend.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /**
     * Bumped whenever [backend] is rebuilt, so screens re-read. Not bumped when nothing about the
     * session changed, so open screens are not reset.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)

    /** [restore] is running. */
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _pendingInvitation = MutableStateFlow<String?>(null)

    /** An invitation link that arrived ([handle]) and waits to be redeemed. Clear it with [dismissPendingInvitation]. */
    val pendingInvitation: StateFlow<String?> = _pendingInvitation.asStateFlow()

    private val restoring = Mutex()
    private val rebuildLock = Any()
    private var lastKey: Any? = null

    init {
        rebuild()
        scope.launch { combine(client.state, client.session, client.connection) { _, _, _ -> }.collect { rebuild() } }
    }

    // ---- state ---------------------------------------------------------------------------

    /** Signed in with a Calimero account. */
    val isSignedIn: Boolean get() = client.state.value.isDelegated

    /** The account, 64 hex, or null. */
    val account: String? get() = client.state.value.account

    /** The app's application id, once resolved from its package. */
    val applicationId: String? get() = client.state.value.applicationId

    /** Whether a relay serves the account. Without one only joining works. */
    val hasRelay: Boolean get() = _backend.value?.hasRelay ?: false

    /** Whether [MeroAppBackend.events] streams; when not, poll. */
    val canSubscribe: Boolean get() = _backend.value?.canSubscribe ?: false

    /** The contract in any context: [NoRelayContextContract] while signed out or relayless. `contract.bound(contextId)` for a generated client. */
    val contract: ContextContract get() = _backend.value?.contract ?: NoRelayContextContract

    // ---- sign-in -------------------------------------------------------------------------

    /**
     * Reconnect the stored Cloud session, if there is one. The Cloud manager is asked again which
     * relay serves the account: a relayless session may have earned one by joining elsewhere. A
     * wallet callback that arrives meanwhile (a cold start from the wallet) waits for this to finish.
     */
    suspend fun restore() {
        restoring.withLock {
            _isRestoring.value = true
            try {
                if (client.account.isSignedIn) client.restore()
            } finally {
                _isRestoring.value = false
            }
        }
        rebuild()
    }

    /** Open the wallet to approve this device. Its answer comes back as a deep link: hand it to [handle]. */
    fun signIn(context: Context) = client.signInWithCloud(context, callbackUrl)

    /** The person came back from the wallet without answering: stop the spinner. */
    fun cancelSignIn() = client.cancelPendingSignIn()

    /** Sign out. The device key stays (the next sign-in reuses it) unless [forgetDevice]. */
    suspend fun signOut(forgetDevice: Boolean = false) {
        client.signOut(forgetDevice)
        rebuild()
    }

    /**
     * A link the system handed the app: a wallet callback that finishes a sign-in, or one of this
     * app's invitation links (held in [pendingInvitation] for the person to confirm). Returns
     * whether it was either.
     */
    suspend fun handle(url: String): Boolean {
        restoring.withLock { }
        if (client.handleEnrolmentCallback(url)) {
            rebuild()
            return true
        }
        val recognised = options.isInvitation?.invoke(url) ?: isInvitationLink(url, client.config.packageName)
        if (!recognised) return false
        _pendingInvitation.value = url
        return true
    }

    /** Forget [pendingInvitation]: redeemed, or declined. */
    fun dismissPendingInvitation() {
        _pendingInvitation.value = null
    }

    /**
     * After redeeming an invitation ([MeroAppBackend.joinNamespace]): a session that had no relay
     * now has the admitting one, so reconnect to it.
     */
    suspend fun didJoinNamespace() {
        if (_backend.value?.hasRelay == true) return
        client.restore()
        rebuild()
    }

    /**
     * Rebuild [backend] from the client's current session. A no-op when nothing about the session
     * changed, so open screens are not reset. Runs after every call here and whenever the client
     * changes; call it after driving the client directly.
     */
    fun rebuild() {
        val state = client.state.value
        val delegated = state.isDelegated && client.admin != null && state.account != null
        val key = listOf(delegated, state.account, state.relayUrl, state.sessionReady, state.applicationId, client.session.value, client.connection.value)
        synchronized(rebuildLock) {
            if (key == lastKey) return
            lastKey = key
            _backend.value =
                if (delegated) {
                    DelegatedAppBackend(
                        client,
                        packageName = client.config.packageName.orEmpty(),
                        registryUrl = client.config.registryUrl ?: ApplicationIds.DEFAULT_REGISTRY_URL,
                        applicationId = state.applicationId,
                        foundingApplication = options.foundingApplication,
                        joinNodeUrl = options.joinNodeUrl,
                    )
                } else {
                    null
                }
            _revision.value += 1
        }
    }

    companion object {
        /**
         * A session with its own [MeroClient] ([MeroClient.create]): keys, session and nonces in
         * the Keystore-backed stores.
         *
         * @param packageName the app's registry package (`com.example.board`): what namespaces are
         *   founded with and invitation links carry. Replaces [config]'s.
         * @param config the registry and Cloud manager.
         * @param walletUrl the wallet's enrolment page.
         */
        @Suppress("LongParameterList")
        fun create(
            context: Context,
            packageName: String,
            callbackUrl: String,
            config: MeroAppConfig = MeroAppConfig(),
            walletUrl: String = Enrolment.DEFAULT_WALLET_URL,
            options: MeroAppSessionOptions = MeroAppSessionOptions(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): MeroAppSession {
            val appConfig = config.copy(packageName = packageName)
            val client = MeroClient.create(context, cloudBaseUrl = appConfig.cloudBaseUrl, walletUrl = walletUrl, config = appConfig)
            return MeroAppSession(client, callbackUrl, options, scope)
        }

        /**
         * Whether [raw] is an invitation to the app with [packageName]: it carries `invitation=…`,
         * and its path names the package (another app's invitation decodes just as well, and
         * redeeming it would join that app's namespace). Without a package any invitation link is.
         * (Swift `MeroAppSession.isInvitationLink`.)
         */
        fun isInvitationLink(
            raw: String,
            packageName: String?,
        ): Boolean {
            val trimmed = raw.trim()
            val query = trimmed.indexOf('?')
            if (query < 0) return false
            val hasToken =
                trimmed
                    .substring(query + 1)
                    .split('&')
                    .any { it.substringBefore('=') == InviteLink.INVITATION_PARAM && it.substringAfter('=', "").isNotEmpty() }
            if (!hasToken) return false
            if (packageName.isNullOrEmpty()) return true
            val path = trimmed.substring(0, query).split('/').map { it.lowercase() }
            return path.contains(packageName.lowercase())
        }
    }
}
