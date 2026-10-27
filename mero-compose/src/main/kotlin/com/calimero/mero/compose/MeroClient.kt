package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.Capabilities
import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.CloudAccountAdminDeps
import com.calimero.mero.account.CreateDelegatedContextRequest
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.EncryptedPrefsSecureStore
import com.calimero.mero.account.EnrolmentException
import com.calimero.mero.account.FoundedDelegatedNamespace
import com.calimero.mero.account.JoinResult
import com.calimero.mero.account.RelayConnection
import com.calimero.mero.account.WalletLauncher
import com.calimero.mero.account.createAccountAdmin
import com.calimero.mero.account.createDelegatedPrivateContext
import com.calimero.mero.account.createNodeAdmin
import com.calimero.mero.account.joinAsAccount
import com.calimero.mero.account.listDelegatedContexts
import com.calimero.mero.account.listDelegatedNamespaces
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.ContextWithGroup
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.compareSemver
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.GenerateClientKeyRequest
import com.calimero.mero.auth.PendingAuthStates
import com.calimero.mero.auth.buildAuthLoginUrl
import com.calimero.mero.auth.parseAuthCallback
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.compose.invitations.InvitationCapture
import com.calimero.mero.http.ClassifiedError
import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.TransportSecurity
import com.calimero.mero.http.classifyError
import com.calimero.mero.presence.RelayPresenceClient
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.storage.EncryptedPrefsTokenStore
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import com.calimero.mero.transport.NodeTransport
import com.calimero.mero.transport.RelayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Observable session state — what mero-react's `MeroProvider` exposes.
 *
 * Two kinds of session:
 * - **Calimero Cloud** (an account through its relay): [isAuthenticated] with an [account].
 *   With a [relayUrl] and [sessionReady] it is fully connected; with a [relayUrl] but not
 *   [sessionReady], writes work (warrants) while admin reads and live events wait for the relay's
 *   node key; with no [relayUrl] it is signed in but no relay serves the account yet — normal for
 *   a new account until it redeems an invitation ([relayNote] says so).
 * - **A node** (SSO through the node's login page, or a development username/password login):
 *   [isAuthenticated] with a [nodeUrl].
 */
data class MeroAuthState(
    /** Signed in: a Cloud certificate for [account], or a validated token for [nodeUrl]. */
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
    /**
     * The cloud answered that no relay serves this account: it is new, a member of nothing. Unlike
     * [signedInWithoutRelay], false when the lookup failed or the assigned relays have no address
     * yet, so an app can show an empty account only when it is one.
     */
    val confirmedNoRelay: Boolean = false,
    /** The person declined at the wallet (not an error to report loudly). */
    val cancelled: Boolean = false,
    /** What went wrong, in words a person can read. See [errorReason] to branch on it. */
    val error: String? = null,
    /** The node this client is signed in to ([MeroClient.connectToNode] or [MeroClient.login]), or null. */
    val nodeUrl: String? = null,
    /** The username of a development [MeroClient.login] session. */
    val username: String? = null,
    /**
     * Whether the node's event stream is up (mero-react `isOnline`). Goes false while it
     * reconnects; always true for a Cloud session, whose stream reconnects by itself.
     */
    val isOnline: Boolean = true,
    /** The application this session is for: the SSO callback's, or derived for an account. */
    val applicationId: String? = null,
    /** The context an SSO login named, or the one the app chose ([MeroClient.selectContext]). */
    val contextId: String? = null,
    /** The executor identity in [contextId]. */
    val contextIdentity: String? = null,
    /**
     * Why [error] is set, for an app to branch on instead of matching [error]'s words: a bad
     * password, a lockout, a node that is not there. Set by [MeroClient.login] (and by
     * [MeroClient.connectToNode] for a malformed URL). Null when there is no error, and for an
     * error this does not classify (a Cloud sign-in failure, say).
     */
    val errorReason: MeroErrorReason? = null,
    /** The HTTP status behind [error], when the node answered one; `0` when no response arrived. */
    val errorStatus: Int? = null,
) {
    /** Signed in, but no relay to write through yet. */
    val signedInWithoutRelay: Boolean get() = isAuthenticated && relayUrl == null && nodeUrl == null

    /** Signed in to a node rather than through a relay. */
    val isNodeSession: Boolean get() = isAuthenticated && nodeUrl != null

    /** Signed in to a development node with a username and password ([MeroClient.login]). */
    val isDevNodeSession: Boolean get() = isNodeSession && username != null

    /** An account on a relay rather than a node login (mero-react `isDelegated`). */
    val isDelegated: Boolean get() = isAuthenticated && nodeUrl == null
}

/** Why a sign-in failed — [MeroAuthState.errorReason]. */
enum class MeroErrorReason {
    /** The input was refused before any request: a malformed node URL, or no username or password. */
    INVALID_INPUT,

    /** The node refused the username and password (`401`, `403` or `400`). */
    INVALID_CREDENTIALS,

    /** The node asked the client to slow down (`429`): too many failed login attempts. */
    RATE_LIMITED,

    /** No response: the node is not running, the URL is wrong, the network is down, or a timeout. */
    UNREACHABLE,

    /** The node answered with a server error (`5xx`). */
    NODE_ERROR,

    /** The password was accepted, but the node would not mint a key with the requested permissions. */
    PERMISSIONS_REFUSED,

    /** Anything else. [MeroAuthState.error] has the detail. */
    UNKNOWN,
}

/**
 * The Compose-facing Calimero client — mero-react's `MeroProvider` and `useMero()`, as a
 * StateFlow-backed object an app creates once and hands to [MeroProvider].
 *
 * On a phone people sign in with their Calimero account ([signInWithCloud]): the wallet
 * certifies this device's key and the client writes through the account's hosted relay. A node
 * can be connected to as well: [connectToNode] opens its login page (SSO, in a Custom Tab), and
 * [handleAuthCallback] adopts what it sends back — only from a node the login was started with
 * or [MeroAppConfig.allowedNodeUrls] allows ([resolveTrustedNodeUrl]), and never over a token
 * bundle that is newer ([resolveTokenAdoption]). [login] is a development-only username/password
 * login to a node you run yourself.
 *
 * Whatever the session, [session] carries the clients the state holders use — [MeroSession.admin]
 * is a node's admin or the account admin, with the same calls on both — and [can] says what the
 * connection may do. Call [restore] once at startup; hand every deep link to [handleDeepLink].
 */
@Suppress("LongParameterList", "TooManyFunctions")
class MeroClient(
    val account: CloudAccount,
    private val tokenStore: TokenStore = MemoryTokenStore(),
    private val launchWallet: (Context, String) -> Unit = WalletLauncher::launch,
    /** The mode, package and trust settings (mero-react's provider props). */
    val config: MeroAppConfig = MeroAppConfig(cloudBaseUrl = account.cloudBaseUrl),
    /** What is remembered between launches: node URL, application, context. */
    val storage: MeroAppStorage = MeroAppStorage(),
    /** Opens a node's login page; a Custom Tab by default. */
    private val launchLogin: (Context, String) -> Unit = WalletLauncher::launch,
    /** Where [connectToNode] records each login, for [handleAuthCallback] to check the answer against. */
    val pendingAuth: PendingAuthStates = PendingAuthStates.shared,
    /** Where background work (the online check, application id lookup) runs. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** How an account learns its application id from the app's package (mero-js `resolveApplicationIdFromRegistry`). */
    private val resolveApplicationId: suspend (packageName: String, registryUrl: String) -> String = { pkg, registry ->
        com.calimero.mero.account.ApplicationIds
            .resolveFromRegistry(pkg, registry)
            .applicationId
    },
) {
    private val _state = MutableStateFlow(stateFor())
    val state: StateFlow<MeroAuthState> = _state.asStateFlow()

    private val _connection = MutableStateFlow<RelayConnection?>(null)

    /** The live relay connection, or null when signed out or no relay serves the account. */
    val connection: StateFlow<RelayConnection?> = _connection.asStateFlow()

    private val _session = MutableStateFlow(MeroSession())

    /** The clients the state holders read; republished on every connection change. */
    val session: StateFlow<MeroSession> = _session.asStateFlow()

    /** Warranted writes and reads on the account's relay. */
    val relay: RelayClient? get() = _connection.value?.relay

    /**
     * The account's presence through its relay (device-signed, resent every 2.5 s while set),
     * or null without a relay connection. Stopped by [signOut].
     */
    val presence: RelayPresenceClient? get() = _connection.value?.presence

    private val node = MutableStateFlow<Mero?>(null)
    private var monitor: Job? = null

    /** A session [connectWithAccount] adopted for this process, ahead of the persisted one. */
    @Volatile private var adoptedSession: DelegatedSession? = null
    private var appIdJob: Job? = null

    /**
     * The admin / events / auth clients: pointed at the relay for a Cloud session (Bearer once
     * [MeroAuthState.sessionReady]), or at the node of a node session.
     */
    val mero: Mero? get() = _connection.value?.mero ?: node.value

    /** The admin API to write against, whatever the session (mero-react `admin`). */
    val admin: AdminSurface? get() = _session.value.admin

    /** What this connection may do (mero-react `can`). */
    val can: MeroCapabilities get() = _session.value.can

    /** An account on a relay rather than a node login (mero-react `isDelegated`). */
    val isDelegated: Boolean get() = _state.value.isDelegated

    /** The account session in use: one [connectWithAccount] adopted, else the persisted one. Null when not signed in. */
    val delegatedSession: DelegatedSession? get() = adoptedSession ?: account.session()

    // ---- Calimero Cloud ------------------------------------------------------------------

    /**
     * Open the Calimero wallet so the person can approve this device. [callbackUrl] must be
     * a URL this app receives — an https App Link or an app scheme such as
     * `myapp://calimero-enrol` registered in an intent filter.
     */
    fun signInWithCloud(
        context: Context,
        callbackUrl: String,
    ) {
        _state.update { it.copy(isLoading = true, error = null, errorReason = null, errorStatus = null, cancelled = false) }
        try {
            launchWallet(context, account.beginEnrolment(callbackUrl))
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Could not open the wallet") }
        }
    }

    /** The wallet URL [signInWithCloud] would open, for apps that launch it themselves. */
    fun enrolmentUrl(callbackUrl: String): String = account.beginEnrolment(callbackUrl)

    /** Forget the last sign-in error (the sign-in screen does this when switching between Cloud and a node). */
    fun clearError() {
        _state.update { it.copy(error = null, errorReason = null, errorStatus = null, cancelled = false) }
    }

    /** Stop showing a spinner if the person closed the wallet (or login page) without an answer. */
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
        _state.update { it.copy(isLoading = true, error = null, errorReason = null, errorStatus = null, cancelled = false) }
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

    /**
     * Hand over any deep link the app received: a wallet redirect ([handleEnrolmentCallback]) or a
     * node's SSO callback ([handleAuthCallback]). False when it was neither.
     */
    suspend fun handleDeepLink(url: String): Boolean = handleEnrolmentCallback(url) || handleAuthCallback(url)

    /**
     * Reconnect a persisted session (call at startup): the Cloud account's (re-asking the cloud
     * which relay serves it), else the node session [MeroAppStorage.nodeUrl] names, validated with
     * `/auth/validate`. Returns whether a session came back.
     */
    suspend fun restore(): Boolean {
        adoptedSession = null
        if (!account.isSignedIn) return restoreNodeSession()
        _state.update { stateFor().copy(isLoading = true) }
        // Re-ask the cloud: the account may have gained a relay (or moved) since last time.
        // A failed lookup keeps the persisted relay.
        val note = runCatching { account.findRelay() }.fold({ account.lastRelayNote }, { null })
        connect(note)
        return true
    }

    /**
     * Adopt a delegated session — an account, a device it certified, and a relay that writes on
     * its behalf (mero-react `connectWithAccount`). Used after a join moved the account onto the
     * relay that admitted it. Held in memory only: what persists stays [CloudAccount]'s (a join
     * through it saves the moved session itself), so [restore] reconnects from there.
     */
    suspend fun connectWithAccount(session: DelegatedSession) {
        connect(note = null, adopted = session)
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

    /**
     * Found a namespace (a "space") as this account through its relay, give it [application]
     * and [defaultCapabilities], name it, and ask the cloud to host it so invitees can find
     * it. See [CloudAccount.foundNamespace].
     */
    suspend fun foundNamespace(
        application: ApplicationTarget? = null,
        defaultCapabilities: Long? = DEFAULT_SPACE_CAPABILITIES,
        name: String? = null,
    ): FoundedDelegatedNamespace = account.foundNamespace(defaultCapabilities = defaultCapabilities, application = application, name = name)

    /** Mint an invitation to [namespaceId], signed by this device. See [CloudAccount.createNamespaceInvitation]. */
    suspend fun createNamespaceInvitation(namespaceId: String): SignedGroupOpenInvitation =
        account.createNamespaceInvitation(namespaceId, _connection.value)

    // ---- A node (SSO) --------------------------------------------------------------------

    /**
     * The node's login URL for this app (mode, permissions, package), with a fresh single-use
     * `auth_state` recorded in [pendingAuth]. Records [nodeUrl] as the node the login was started
     * with — the trust anchor [handleAuthCallback] checks the answer against.
     */
    fun nodeLoginUrl(
        nodeUrl: String,
        callbackUrl: String,
    ): String {
        val base = nodeUrl.trim().trimEnd('/')
        storage.nodeUrl = base
        val options =
            AuthLoginOptions(
                callbackUrl = callbackUrl,
                mode = config.mode.wire,
                permissions = permissionsFor(config.mode),
                packageName = config.packageName,
                packageVersion = config.packageVersion,
                registryUrl = config.registryUrl,
            )
        return buildAuthLoginUrl(base, options, pendingAuth)
    }

    /**
     * Connect to a node: open its login page so the person signs in there (mero-react
     * `connectToNode`). The page redirects to [callbackUrl] — a verified `https` App Link this app
     * receives (an app scheme only with [MeroAppConfig.allowCustomSchemeCallbacks]) — which the
     * app hands to [handleDeepLink]. A node over plain http that is not local is refused
     * ([INSECURE_NODE_URL]).
     */
    fun connectToNode(
        context: Context,
        nodeUrl: String,
        callbackUrl: String,
    ) {
        if (nodeUrl.trim().toHttpUrlOrNull() == null) {
            _state.update { it.copy(error = INVALID_NODE_URL, errorReason = MeroErrorReason.INVALID_INPUT, errorStatus = null) }
            return
        }
        if (!mayCarryCredentials(nodeUrl.trim())) {
            _state.update { it.copy(error = INSECURE_NODE_URL, errorReason = MeroErrorReason.INVALID_INPUT, errorStatus = null) }
            return
        }
        if (!config.allowCustomSchemeCallbacks && !callbackUrl.trim().startsWith("https://", ignoreCase = true)) {
            // The redirect would be refused anyway ([handleAuthCallback]); say why before the login page opens.
            _state.update { it.copy(error = CUSTOM_SCHEME_CALLBACK, errorReason = MeroErrorReason.INVALID_INPUT, errorStatus = null) }
            return
        }
        _state.update { it.copy(isLoading = true, error = null, errorReason = null, errorStatus = null, cancelled = false) }
        try {
            launchLogin(context, nodeLoginUrl(nodeUrl, callbackUrl))
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Could not open the node's login page") }
        }
    }

    /**
     * Consume a node's SSO callback (mero-react `MeroProvider`'s initialiser). Returns false when
     * [url] is not an answer to a login started here ([nodeLoginUrl]).
     *
     * The callback's `node_url` is checked first ([resolveTrustedNodeUrl]): an untrusted one
     * stores nothing and restores any existing session instead, so a tampered link cannot log a
     * person out. A trusted callback's tokens are written only when [resolveTokenAdoption] says
     * so, and the session is then validated with `/auth/validate`. The callback must be an `https`
     * App Link unless [MeroAppConfig.allowCustomSchemeCallbacks]: any app can claim a custom scheme.
     */
    suspend fun handleAuthCallback(url: String): Boolean {
        val callback = parseAuthCallback(url, pending = pendingAuth, allowCustomScheme = config.allowCustomSchemeCallbacks) ?: return false
        if (account.isSignedIn) {
            _state.update { it.copy(error = "Signed in with a cloud account: sign out before connecting to a node.") }
            return true
        }
        val initiated = storage.nodeUrl
        val tokenNode = storage.tokenNodeUrl ?: initiated
        val trust = resolveTrustedNodeUrl(callback.nodeUrl.ifEmpty { null }, initiated, config.allowedNodeUrls)
        if (trust.rejected) {
            restoreNodeSession()
            _state.update { it.copy(error = UNTRUSTED_CALLBACK) }
            return true
        }
        val nodeUrl = trust.url?.trimEnd('/') ?: return true.also { _state.update { it.copy(isLoading = false) } }
        _state.update { it.copy(isLoading = true, error = null, errorReason = null, errorStatus = null) }
        val instance = newNode(nodeUrl)
        val decision = resolveTokenAdoption(callback.accessToken, callback.refreshToken, nodeUrl, instance.getTokenData(), tokenNode)
        decision.tokens?.let {
            instance.setTokenData(it)
            storage.tokenNodeUrl = nodeUrl
        }
        callback.applicationId.ifEmpty { null }?.let { storage.applicationId = it }
        callback.contextId.ifEmpty { null }?.let { storage.contextId = it }
        callback.contextIdentity.ifEmpty { null }?.let { storage.contextIdentity = it }
        storage.nodeUrl = nodeUrl
        // An SSO session, not a password one: restore must not bring it back as one.
        storage.username = null
        if (checkAuth(instance)) {
            installNode(instance, nodeUrl, username = null)
        } else {
            _state.value = MeroAuthState(error = "The node did not accept this login. Try again.")
        }
        return true
    }

    /**
     * Bring back the node session [MeroAppStorage.nodeUrl] names, if its tokens still validate:
     * an SSO session, or a password session [login] persisted.
     */
    suspend fun restoreNodeSession(): Boolean {
        val saved = storage.nodeUrl
        if (saved == null) {
            _state.value = stateFor()
            return false
        }
        _state.update { it.copy(isLoading = true) }
        val instance = newNode(saved)
        if (checkAuth(instance)) {
            // A password session [login] persisted comes back as one (not health-monitored).
            installNode(instance, saved, username = storage.username)
            return true
        }
        _state.value = stateFor()
        return false
    }

    /**
     * **Development only.** Log in to a node you run yourself (`merod` on your machine) with
     * the username and password its admin was created with — the Swift MeroKitUI
     * `MeroClient.login(nodeURL:username:password:)` path. On success [mero] talks to that
     * node and the state is authenticated with [MeroAuthState.nodeUrl] set.
     *
     * Not for a shipped app: it puts a node admin's password into the app, and a dev node
     * usually speaks cleartext `http://` (which needs a network security config exception).
     * People sign in with [signInWithCloud], or through the node's own page ([connectToNode]).
     * From the Android emulator, the development machine's `localhost` is `10.0.2.2` (see
     * `com.calimero.mero.discovery`, which can find running local nodes).
     *
     * The tokens go to this client's [TokenStore], bound to [nodeUrl], and the node URL and
     * username to [storage], so [restore] brings the session back on the next launch while its
     * tokens still validate. [signOut] forgets both.
     *
     * A password login on its own yields the node's **admin** token. Pass [permissions] to sign
     * in with a narrower key instead: the admin token is used once, to mint a client key with
     * just those grants (`POST /admin/client-key`), and only the client key's tokens are kept.
     * [permissionsFor] gives the grants an [AppMode] asks for. Core refuses `keys:*` on a client
     * key, and checks each grant against the admin's own ([MeroErrorReason.PERMISSIONS_REFUSED]).
     *
     * [packageName] is the application to look up on the node (`listApplications`, by package)
     * for [MeroAuthState.applicationId]; [MeroAppConfig.packageName] when null. With neither, or
     * no installed application of that package, the application id is left as it was.
     *
     * @return true when signed in; on failure the reason is in [MeroAuthState.error], and what
     *   kind of failure it was in [MeroAuthState.errorReason].
     */
    suspend fun login(
        nodeUrl: String,
        username: String,
        password: String,
        permissions: List<String>? = null,
        packageName: String? = null,
    ): Boolean {
        val base = nodeUrl.trim().trimEnd('/')
        val invalid =
            when {
                base.toHttpUrlOrNull() == null -> INVALID_NODE_URL
                username.isEmpty() || password.isEmpty() -> "Username and password are required."
                !mayCarryCredentials(base) -> INSECURE_NODE_URL
                else -> null
            }
        if (invalid != null) {
            _state.update { it.copy(isLoading = false, error = invalid, errorReason = MeroErrorReason.INVALID_INPUT, errorStatus = null) }
            return false
        }
        _state.update { it.copy(isLoading = true, error = null, errorReason = null, errorStatus = null, cancelled = false) }
        val client = newNode(base)
        var minting = false
        return try {
            client.authenticate(Credentials(username, password))
            if (permissions != null) {
                minting = true
                val key = client.auth.generateClientKey(GenerateClientKeyRequest(permissions = permissions)).data
                client.setTokenData(TokenData(accessToken = key.accessToken, refreshToken = key.refreshToken, expiresAt = 0))
            }
            storage.nodeUrl = base
            storage.tokenNodeUrl = base
            storage.username = username
            installNode(client, base, username)
            (packageName ?: config.packageName)?.takeIf { it.isNotEmpty() }?.let { resolveNodeApplication(it, client) }
            true
        } catch (e: CancellationException) {
            _state.update { it.copy(isLoading = false) }
            throw e
        } catch (e: Exception) {
            node.value = null
            // Neither a half-made session (the admin token, when the client key was refused) nor one
            // from an earlier login on this node is left for restore to pick up. Another node's
            // bundle is left alone (clearToken only drops this node's).
            client.clearToken()
            val c = classifyError(e)
            _state.value =
                MeroAuthState(
                    error = devLoginMessage(c, base, minting),
                    errorReason = if (minting) MeroErrorReason.PERMISSIONS_REFUSED else reasonFor(c.kind),
                    errorStatus = c.status,
                )
            false
        }
    }

    /**
     * The installed application of [pkg], for [MeroAuthState.applicationId]: the one already
     * remembered if it is still installed, else the latest version.
     */
    private suspend fun resolveNodeApplication(
        pkg: String,
        client: Mero,
    ) {
        val apps =
            try {
                client.admin.listApplications().apps
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return
            }
        if (node.value !== client) return
        val matching = apps.filter { it.packageName == pkg }
        val current = _state.value.applicationId
        if (current != null && matching.any { it.id == current }) return
        val latest = matching.maxWithOrNull { a, b -> compareSemver(a.version, b.version) } ?: return
        storage.applicationId = latest.id
        _state.update { it.copy(applicationId = latest.id) }
    }

    /**
     * Choose the context the app works in (mero-react's `contextId` / `contextIdentity`, which
     * an app's own picker sets). Persisted; null clears it.
     */
    fun selectContext(
        contextId: String?,
        contextIdentity: String? = null,
    ) {
        storage.contextId = contextId
        storage.contextIdentity = contextIdentity
        _state.update { it.copy(contextId = contextId, contextIdentity = contextIdentity) }
    }

    // ---- Signing out ---------------------------------------------------------------------

    /**
     * Sign out: retire the relay session on the relay (best-effort `POST /auth/logout`) or the
     * node's tokens, drop every token, the persisted account session with its relay map and
     * pinned relay keys ([CloudAccount.signOut]), everything in [storage] and the invitation
     * attempt ledger — mero-react `logout`, which clears it all.
     *
     * The device key stays, so signing back in is one approval, unless [forgetDevice] — pass it
     * on a shared or handed-over device, so the next account is not handed a key already
     * certified to this one.
     */
    suspend fun signOut(forgetDevice: Boolean = false) {
        monitor?.cancel()
        appIdJob?.cancel()
        runCatching { _connection.value?.logout() }
        node.value?.let {
            runCatching { it.sse.close() }
            runCatching { it.logout() }
        }
        node.value = null
        tokenStore.clear()
        _connection.value = null
        account.signOut(forgetDevice)
        adoptedSession = null
        storage.clearAll()
        InvitationCapture.shared.clearAttempts()
        _session.value = MeroSession()
        _state.value = MeroAuthState()
    }

    /** Alias of [signOut], for parity with mero-react's `logout`. */
    suspend fun logout(forgetDevice: Boolean = false) = signOut(forgetDevice)

    // ---- Wiring ---------------------------------------------------------------------------

    private fun newNode(baseUrl: String): Mero =
        Mero(MeroConfig(baseUrl = baseUrl, tokenStore = tokenStore, timeoutMs = config.timeoutMs, httpClient = config.httpClient))

    /**
     * Is [instance]'s session alive? (mero-react `checkAuth`.) Asks `/auth/validate` — no
     * permission requirement, unlike probing an admin route. A failed check that rotated the
     * stored token underneath (the transport renewed an expired access token on the way) is judged
     * again on the token actually held; this never calls `/auth/refresh` itself.
     */
    internal suspend fun checkAuth(instance: Mero): Boolean {
        val token = instance.getTokenData()?.accessToken ?: return false
        if (validates(instance, token)) return true
        val rotated = instance.getTokenData()?.accessToken
        if (rotated == null || rotated == token) return false
        return validates(instance, rotated)
    }

    private suspend fun validates(
        instance: Mero,
        token: String,
    ): Boolean =
        try {
            instance.auth.validateToken(token).valid
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private fun installNode(
        instance: Mero,
        nodeUrl: String,
        username: String?,
    ) {
        monitor?.cancel()
        node.value = instance
        _state.value =
            MeroAuthState(
                isAuthenticated = true,
                nodeUrl = nodeUrl,
                username = username,
                applicationId = storage.applicationId,
                contextId = storage.contextId,
                contextIdentity = storage.contextIdentity,
            )
        val events = instance.sse.asMeroEvents()
        _session.value =
            MeroSession(
                admin = createNodeAdmin(instance.admin, config.app),
                rawAdmin = instance.admin,
                rpc = NodeTransport(instance.rpc),
                events = events,
                presence = instance.ephemeral.asPresenceSurface(),
                isDelegated = false,
                can = MeroCapabilities.NODE,
            )
        // A development username/password session is not health-monitored (Swift parity).
        if (username != null) return
        monitor =
            OnlineMonitor(
                events = events,
                delegated = false,
                setOnline = { online -> _state.update { if (it.isNodeSession) it.copy(isOnline = online) else it } },
                recover = { node.value?.let { checkAuth(it) } ?: false },
                logout = { scope.launch { signOut() } },
            ).start(scope)
    }

    private suspend fun connect(
        note: String?,
        adopted: DelegatedSession? = null,
    ) {
        monitor?.cancel()
        if (adopted != null) adoptedSession = adopted
        val session = adoptedSession ?: account.session()
        if (session == null) {
            _state.value = stateFor()
            _session.value = MeroSession()
            return
        }
        if (session.relayUrl == null) {
            _connection.value = null
            // No note from the lookup that ran (it failed, or none ran): ask once more, and let that
            // answer decide both the note and whether the account is confirmed to have no relay.
            val known = note ?: account.lastRelayNote
            val fallback = if (known == null) runCatching { account.chooseRelay(session) }.getOrNull() else null
            val confirmed = adopted == null && (account.lastRelayNone || fallback?.none == true)
            _state.value = stateFor().copy(relayNote = known ?: fallback?.note, confirmedNoRelay = confirmed)
            publishDelegated(session, connection = null, ready = false)
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
                relayNote = note ?: if (ready) null else notReadyNote(connection.sessionError),
            )
        }
        publishDelegated(session, connection, ready)
    }

    /** The account's [MeroSession]: the account admin over the relay's reads, warrants for writes. */
    private fun publishDelegated(
        session: DelegatedSession,
        connection: RelayConnection?,
        ready: Boolean,
    ) {
        val deps =
            CloudAccountAdminDeps(
                account,
                join = { namespaceId, invitation ->
                    var moved: DelegatedSession? = null
                    val joined = account.joinAsAccount(session, namespaceId, invitation, config.cloudBaseUrl) { moved = it }
                    moved?.let { connectWithAccount(it) }
                    joined
                },
            )
        val read = connection?.mero?.admin?.takeIf { ready }
        _session.value =
            MeroSession(
                admin = createAccountAdmin(session, read, config.app, deps),
                rawAdmin = read,
                rpc = connection?.let { RelayTransport(it.relay) },
                events =
                    connection
                        ?.mero
                        ?.sse
                        ?.takeIf { ready }
                        ?.asMeroEvents(),
                presence = connection?.presence?.takeIf { ready }?.asPresenceSurface(),
                isDelegated = true,
                delegated = AccountDelegatedOps(account, session),
                can = MeroCapabilities.account(hasPackage = !config.packageName.isNullOrEmpty()),
            )
        appIdJob?.cancel()
        appIdJob = scope.launch { learnApplicationId() }
    }

    /**
     * Which application this account's session is for, asked rather than told (mero-react's
     * delegated effect). The app's [MeroAppConfig.packageName] decides when it has one — an
     * account is used across apps, so its contexts may well be another app's. Without one (or with
     * a registry that does not answer) the contexts decide, and only when they name exactly one.
     */
    private suspend fun learnApplicationId() {
        val pkg = config.packageName
        if (!pkg.isNullOrEmpty()) {
            try {
                val id = resolveApplicationId(pkg, config.app.registryUrl)
                _state.update { it.copy(applicationId = id) }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to the contexts below.
            }
        }
        if (_connection.value == null) return
        try {
            // Read over the connection already open when it is attested. Listing through
            // `delegated` builds a client per relay, each with its own relay login — a second
            // login for this device, which a relay keeping one session per device answers by
            // retiring the live connection's token (the sample's emulator test caught it).
            val live = _session.value.rawAdmin
            val contexts = live?.getContexts()?.contexts ?: _session.value.delegated?.listContexts()
            val apps =
                contexts
                    .orEmpty()
                    .map { it.applicationId }
                    .filter { it.isNotEmpty() }
                    .distinct()
            if (apps.size == 1) _state.update { it.copy(applicationId = apps.single()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Left null: a read that did not answer must not unwind a connection that did.
        }
    }

    /**
     * Why reads are off on a relay connection whose session could not be minted, naming the
     * login's own failure when there is one: an expired credential, a refused attestation or
     * an unreachable relay each read differently, where a bare "not attested" hid all three.
     */
    private fun notReadyNote(error: Exception?): String {
        if (error == null) return "Writes go through the relay; reads and live updates resume once it is attested."
        val c = classifyError(error)
        val why =
            when (c.kind) {
                ErrorKind.UNAUTHORIZED, ErrorKind.FORBIDDEN -> "the relay refused this device's sign-in; sign in again"
                ErrorKind.UNREACHABLE -> "the relay can't be reached (${c.message})"
                else -> "the relay login failed (${c.message})"
            }
        return "Writes go through the relay, but reads and live updates are off: $why."
    }

    private fun devLoginMessage(
        c: ClassifiedError,
        nodeUrl: String,
        minting: Boolean,
    ): String =
        when {
            minting && c.kind != ErrorKind.UNREACHABLE -> "The node did not grant the requested permissions: ${c.message}"
            c.kind == ErrorKind.UNAUTHORIZED || c.kind == ErrorKind.FORBIDDEN || c.kind == ErrorKind.INVALID ->
                "Login failed — check the username and password."
            c.kind == ErrorKind.UNREACHABLE -> "Can't reach the node at $nodeUrl: ${c.message}"
            else -> c.message
        }

    private fun reasonFor(kind: ErrorKind): MeroErrorReason =
        when (kind) {
            ErrorKind.UNAUTHORIZED, ErrorKind.FORBIDDEN, ErrorKind.INVALID -> MeroErrorReason.INVALID_CREDENTIALS
            ErrorKind.RATE_LIMITED -> MeroErrorReason.RATE_LIMITED
            ErrorKind.UNREACHABLE -> MeroErrorReason.UNREACHABLE
            ErrorKind.SERVER, ErrorKind.UNAVAILABLE -> MeroErrorReason.NODE_ERROR
            else -> MeroErrorReason.UNKNOWN
        }

    private fun stateFor(): MeroAuthState {
        val s = adoptedSession ?: account.session()
        return MeroAuthState(isAuthenticated = s != null, account = s?.account, device = s?.device, relayUrl = s?.relayUrl)
    }

    companion object {
        /**
         * The default member mask mero-react founds a space with: create contexts, invite,
         * join open subgroups, create and delete subgroups and manage their visibility
         * (231, [Capabilities.Presets.COLLABORATOR]).
         */
        const val DEFAULT_SPACE_CAPABILITIES: Long = Capabilities.Presets.COLLABORATOR

        /**
         * What [MeroAuthState.error] says for a node URL that is plain http to a host that is not
         * local ([TransportSecurity]). (Swift `MeroClient.insecureNodeURLMessage`.)
         */
        const val INSECURE_NODE_URL =
            "This node is not on https or a local network, so its password and tokens would cross the network in the clear. Use an https URL."

        /** What [MeroAuthState.error] says when a node login's callback is a custom scheme the config does not allow. */
        const val CUSTOM_SCHEME_CALLBACK =
            "This app's login callback is not an https App Link, so another app could receive the tokens. Use an App Link."

        /** https, a local address, or [TransportSecurity.allowInsecureHttp]. */
        internal fun mayCarryCredentials(url: String): Boolean = TransportSecurity.allowInsecureHttp || TransportSecurity.isSecureOrLocal(url)

        /** What [MeroAuthState.error] says for a node URL that does not parse. */
        const val INVALID_NODE_URL = "Enter a valid node URL (e.g. http://10.0.2.2:2428)."

        /** What [MeroAuthState.error] says when an SSO callback named a node this app does not trust. */
        const val UNTRUSTED_CALLBACK =
            "The login came back from a node this app did not start it with, so it was ignored and no tokens were stored."

        /**
         * The on-device client: keys, session and nonces in a Keystore-backed
         * [EncryptedPrefsSecureStore], relay and node tokens in an [EncryptedPrefsTokenStore],
         * the node URL and ids in [MeroAppStorage], and pending node logins in an encrypted
         * [PendingAuthStates] (a login survives the process dying while the Custom Tab is open).
         */
        fun create(
            context: Context,
            cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
            walletUrl: String = com.calimero.mero.account.Enrolment.DEFAULT_WALLET_URL,
            config: MeroAppConfig = MeroAppConfig(cloudBaseUrl = cloudBaseUrl),
        ): MeroClient {
            val appContext = context.applicationContext
            val secure = EncryptedPrefsSecureStore(appContext)
            val account =
                config.httpClient?.let { CloudAccount(secure, cloudBaseUrl = cloudBaseUrl, walletUrl = walletUrl, httpClient = it) }
                    ?: CloudAccount(secure, cloudBaseUrl = cloudBaseUrl, walletUrl = walletUrl)
            return MeroClient(
                account,
                EncryptedPrefsTokenStore(appContext, fileName = "mero_relay_tokens"),
                config = config,
                storage = MeroAppStorage(appContext),
                pendingAuth = PendingAuthStates(EncryptedPrefsSecureStore(appContext, "mero_auth_state")),
            )
        }
    }
}

/** [DelegatedOps] for [session], over [account]'s relay map. */
private class AccountDelegatedOps(
    private val account: CloudAccount,
    private val session: DelegatedSession,
) : DelegatedOps {
    override suspend fun listContexts(): List<ContextWithGroup> = account.listDelegatedContexts(session).map { it.context }

    override suspend fun listNamespaces(): List<Namespace> = account.listDelegatedNamespaces(session).map { it.namespace }

    override suspend fun createPrivateContext(request: CreatePrivateContextRequest): String =
        account
            .createDelegatedPrivateContext(
                session,
                CreateDelegatedContextRequest(
                    namespaceId = request.namespaceId,
                    applicationId = request.applicationId,
                    initializationParams = request.initializationParams,
                ),
                request.members,
            ).contextId
}
