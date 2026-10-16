package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.Capabilities
import com.calimero.mero.Credentials
import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
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
import com.calimero.mero.auth.AuthLoginOptions
import com.calimero.mero.auth.PendingAuthStates
import com.calimero.mero.auth.buildAuthLoginUrl
import com.calimero.mero.auth.parseAuthCallback
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.http.ErrorKind
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
    /** The person declined at the wallet (not an error to report loudly). */
    val cancelled: Boolean = false,
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
        _state.update { it.copy(isLoading = true, error = null, cancelled = false) }
        try {
            launchWallet(context, account.beginEnrolment(callbackUrl))
        } catch (e: Exception) {
            _state.update { it.copy(isLoading = false, error = e.message ?: "Could not open the Calimero wallet") }
        }
    }

    /** The wallet URL [signInWithCloud] would open, for apps that launch it themselves. */
    fun enrolmentUrl(callbackUrl: String): String = account.beginEnrolment(callbackUrl)

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
     * its behalf (mero-react `connectWithAccount`). Persisted first, so [restore] finds it. Used
     * after a join moved the account onto the relay that admitted it.
     */
    suspend fun connectWithAccount(session: DelegatedSession) {
        account.saveSession(session)
        connect(note = null)
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
     * `connectToNode`). The page redirects to [callbackUrl] — an app scheme or App Link this app
     * receives — which the app hands to [handleDeepLink].
     */
    fun connectToNode(
        context: Context,
        nodeUrl: String,
        callbackUrl: String,
    ) {
        if (nodeUrl.trim().toHttpUrlOrNull() == null) {
            _state.update { it.copy(error = "Enter a valid node URL (e.g. http://10.0.2.2:2428).") }
            return
        }
        _state.update { it.copy(isLoading = true, error = null, cancelled = false) }
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
     * so, and the session is then validated with `/auth/validate`.
     */
    suspend fun handleAuthCallback(url: String): Boolean {
        val callback = parseAuthCallback(url, pending = pendingAuth) ?: return false
        if (account.isSignedIn) {
            _state.update { it.copy(error = "Signed in with Calimero Cloud: sign out before connecting to a node.") }
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
        _state.update { it.copy(isLoading = true, error = null) }
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
        if (checkAuth(instance)) {
            installNode(instance, nodeUrl, username = null)
        } else {
            _state.value = MeroAuthState(error = "The node did not accept this login. Try again.")
        }
        return true
    }

    /** Bring back the node session [MeroAppStorage.nodeUrl] names, if its tokens still validate. */
    suspend fun restoreNodeSession(): Boolean {
        val saved = storage.nodeUrl
        if (saved == null) {
            _state.value = stateFor()
            return false
        }
        _state.update { it.copy(isLoading = true) }
        val instance = newNode(saved)
        if (checkAuth(instance)) {
            installNode(instance, saved, username = null)
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
     * The tokens go to this client's [TokenStore], bound to [nodeUrl]. A development session
     * is not brought back by [restore]; log in again.
     *
     * @return true when signed in; on failure the reason is in [MeroAuthState.error].
     */
    suspend fun login(
        nodeUrl: String,
        username: String,
        password: String,
    ): Boolean {
        val base = nodeUrl.trim().trimEnd('/')
        val invalid =
            when {
                base.toHttpUrlOrNull() == null -> "Enter a valid node URL (e.g. http://10.0.2.2:2428)."
                username.isEmpty() || password.isEmpty() -> "Username and password are required."
                else -> null
            }
        if (invalid != null) {
            _state.update { it.copy(isLoading = false, error = invalid) }
            return false
        }
        _state.update { it.copy(isLoading = true, error = null, cancelled = false) }
        val client = newNode(base)
        return try {
            client.authenticate(Credentials(username, password))
            installNode(client, base, username)
            true
        } catch (e: CancellationException) {
            _state.update { it.copy(isLoading = false) }
            throw e
        } catch (e: Exception) {
            node.value = null
            _state.value = MeroAuthState(error = devLoginMessage(e, base))
            false
        }
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
     * node's tokens, drop every token, the persisted account session and everything in
     * [storage] — mero-react `logout`, which clears it all.
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
        storage.clearAll()
        _session.value = MeroSession()
        _state.value = MeroAuthState()
    }

    /** Alias of [signOut], for parity with mero-react's `logout`. */
    suspend fun logout() = signOut()

    // ---- Wiring ---------------------------------------------------------------------------

    private fun newNode(baseUrl: String): Mero = Mero(MeroConfig(baseUrl = baseUrl, tokenStore = tokenStore, timeoutMs = config.timeoutMs))

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
        monitor =
            OnlineMonitor(
                events = events,
                delegated = false,
                setOnline = { online -> _state.update { if (it.isNodeSession) it.copy(isOnline = online) else it } },
                recover = { node.value?.let { checkAuth(it) } ?: false },
                logout = { scope.launch { signOut() } },
            ).start(scope)
    }

    private suspend fun connect(note: String?) {
        monitor?.cancel()
        val session = account.session()
        if (session == null) {
            _state.value = stateFor()
            _session.value = MeroSession()
            return
        }
        if (session.relayUrl == null) {
            _connection.value = null
            val why = note ?: account.lastRelayNote ?: runCatching { account.chooseRelay(session).note }.getOrNull()
            _state.value = stateFor().copy(relayNote = why)
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
                relayNote =
                    note ?: if (ready) null else "Writes go through the relay; reads and live updates resume once it is attested.",
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
            val apps =
                _session.value.delegated
                    ?.listContexts()
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

    private fun devLoginMessage(
        e: Exception,
        nodeUrl: String,
    ): String {
        val c = classifyError(e)
        return when (c.kind) {
            ErrorKind.UNAUTHORIZED, ErrorKind.FORBIDDEN, ErrorKind.INVALID -> "Login failed — check the username and password."
            ErrorKind.UNREACHABLE -> "Can't reach the node at $nodeUrl: ${c.message}"
            else -> c.message
        }
    }

    private fun stateFor(): MeroAuthState {
        val s = account.session()
        return MeroAuthState(isAuthenticated = s != null, account = s?.account, device = s?.device, relayUrl = s?.relayUrl)
    }

    companion object {
        /**
         * The default member mask mero-react founds a space with: create contexts, invite,
         * join open subgroups, create and delete subgroups and manage their visibility
         * (231, [Capabilities.Presets.COLLABORATOR]).
         */
        const val DEFAULT_SPACE_CAPABILITIES: Long = Capabilities.Presets.COLLABORATOR

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
            val account = CloudAccount(EncryptedPrefsSecureStore(appContext), cloudBaseUrl = cloudBaseUrl, walletUrl = walletUrl)
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
