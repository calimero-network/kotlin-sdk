package com.calimero.mero.compose

import android.content.Context
import com.calimero.mero.CalimeroClient
import com.calimero.mero.CalimeroClientOptions
import com.calimero.mero.CalimeroError
import com.calimero.mero.CloudPolicy
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentResult
import com.calimero.mero.ConsentStarted
import com.calimero.mero.LoginRequired
import com.calimero.mero.PasskeyProvider
import com.calimero.mero.app.CalimeroAppBackend
import com.calimero.mero.app.ContextContract
import com.calimero.mero.app.MeroAppBackend
import com.calimero.mero.app.OrgInvitation
import com.calimero.mero.app.SignedOutContextContract
import com.calimero.mero.consentReturn
import com.calimero.mero.invite.InviteLink
import com.calimero.mero.storage.EncryptedSessionStore
import com.calimero.mero.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Where an app's [MeroAppSession] signs in. (Swift `MeroAppConfig`.)
 *
 * @property nodeUrl one node of the app's cluster (the URL the Calimero Cloud console shows for
 *   it, or `http://10.0.2.2:2501` for `merod local` from the emulator).
 * @property nodes the cluster's other nodes, to move to on `421 WRONG_NODE`.
 * @property policy what the node must attest to: [CloudPolicy.production] (real TDX quotes from a
 *   signed mero-tee release) unless you run `merod local` ([CloudPolicy.development]).
 */
data class MeroAppConfig(
    val nodeUrl: String,
    val nodes: List<String> = emptyList(),
    val policy: CloudPolicy = CloudPolicy.production,
    /** The app's package (`com.example.board`); [MeroAppSession.create] sets it. */
    val packageName: String? = null,
    /** Carries the attestation and the sealed requests; add interceptors (logging, pinning) here. */
    val httpClient: OkHttpClient? = null,
)

/** How a [MeroAppSession] treats links. (Swift `MeroAppSessionOptions`.) */
data class MeroAppSessionOptions(
    /**
     * Whether a link the system handed the app is one of this app's invitations. Null takes
     * [MeroAppSession.isInvitationLink]: a link carrying `invitation=…` whose path names the app's
     * package.
     */
    val isInvitation: ((String) -> Boolean)? = null,
)

/**
 * A Calimero app's signed-in account on Calimero 1.0: a passkey session on the app's cluster
 * (`v2.…` session token, `rt_…` refresh token, kept encrypted), and the [MeroAppBackend] built over
 * it. Keep one for the app's life (in an `AndroidViewModel`, with its `viewModelScope`). (Swift
 * `MeroKitUI.MeroAppSession`.)
 *
 * ```kotlin
 * val session = MeroAppSession.create(app, "com.example.board", "board://consent", MeroAppConfig(nodeUrl), scope = viewModelScope)
 * viewModelScope.launch { session.restore() }
 * // the sign-in button: session.signIn(activity)
 * // every deep link: viewModelScope.launch { session.handle(url) }
 * ```
 *
 * Screens collect [backend] (and re-read when [revision] moves); view models take it as a
 * [MeroAppBackend], so a test hands them mero-testkit's `InMemoryAppBackend`. A generated
 * contract client calls `session.contract.bound(appId)`.
 *
 * @param callbackUrl where the node's consent page sends the person back (an App Link or an app
 *   scheme the app receives): the `returnTo` of [requestConsent]. 0.x: the wallet's callback.
 */
@Suppress("TooManyFunctions")
class MeroAppSession(
    val client: CalimeroClient,
    /** The app's package: what app instances are created from and invitation links carry. */
    val packageName: String,
    val callbackUrl: String,
    val store: SessionStore,
    val options: MeroAppSessionOptions = MeroAppSessionOptions(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _backend = MutableStateFlow<MeroAppBackend?>(null)

    /** The signed-in account's backend, or null when signed out. */
    val backend: StateFlow<MeroAppBackend?> = _backend.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Bumped whenever [backend] is rebuilt, so screens re-read. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _isRestoring = MutableStateFlow(false)

    /** [restore] is running. */
    val isRestoring: StateFlow<Boolean> = _isRestoring.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)

    /** A passkey sign-in or sign-up is running. */
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    private val _lastError = MutableStateFlow<Throwable?>(null)

    /** Why the last sign-in, sign-up or restore failed; cleared by the next attempt. */
    val lastError: StateFlow<Throwable?> = _lastError.asStateFlow()

    private val _pendingInvitation = MutableStateFlow<String?>(null)

    /** An invitation link that arrived ([handle]) and waits to be redeemed. Clear it with [dismissPendingInvitation]. */
    val pendingInvitation: StateFlow<String?> = _pendingInvitation.asStateFlow()

    private val _consentResult = MutableStateFlow<ConsentResult?>(null)

    /** The decision the consent page sent the person back with ([handle]), confirmed with the node. */
    val consentResult: StateFlow<ConsentResult?> = _consentResult.asStateFlow()

    private val restoring = Mutex()
    private var signing: Job? = null
    private var knownAccount: String? = null

    init {
        client.onSessionChange { session ->
            store.save(session)
            if (session == null || session.accountId != knownAccount) {
                knownAccount = session?.accountId
                rebuild()
            }
        }
    }

    // ---- state ---------------------------------------------------------------------------

    /** Signed in. */
    val isSignedIn: Boolean get() = client.session != null

    /** The account id, or null. */
    val account: String? get() = client.session?.accountId

    /** The app's package: what 0.x called its application. */
    val applicationId: String get() = packageName

    /** Whether the account reaches the cluster: `true` while signed in (0.x: "a relay serves it"). */
    val hasRelay: Boolean get() = _backend.value?.hasRelay ?: false

    /** Whether [MeroAppBackend.events] streams: `true` while signed in. */
    val canSubscribe: Boolean get() = _backend.value?.canSubscribe ?: false

    /** The contract in any app instance: [SignedOutContextContract] while signed out. */
    val contract: ContextContract get() = _backend.value?.contract ?: SignedOutContextContract

    // ---- sign-in -------------------------------------------------------------------------

    /**
     * Reconnect the stored session, if there is one, and check it with the node (`GET /auth/me`;
     * an expired session token is refreshed by itself). A refused refresh forgets it; a node that
     * cannot be reached keeps it, so an offline launch stays signed in.
     */
    suspend fun restore() {
        restoring.withLock {
            _isRestoring.value = true
            try {
                val saved = store.load() ?: return@withLock
                client.useSession(saved)
                knownAccount = saved.accountId
                rebuild()
                try {
                    client.me()
                } catch (e: LoginRequired) {
                    client.forget()
                    _lastError.value = e
                } catch (e: IOException) {
                    _lastError.value = e
                }
            } finally {
                _isRestoring.value = false
            }
        }
    }

    /** Sign in with the device's passkey (Credential Manager over [activity]). Watch [isSigningIn] and [lastError]. */
    fun signIn(activity: Context) = launchSignIn { signIn(PlatformPasskey(activity)) }

    /** Create an account named [name] with a new passkey on the device, and sign in. */
    fun signUp(
        activity: Context,
        name: String,
    ) = launchSignIn { signUp(PlatformPasskey(activity), name) }

    /** Sign in with [passkey] (a [com.calimero.mero.SoftPasskey] on a development cluster). */
    suspend fun signIn(passkey: PasskeyProvider) {
        client.signIn(passkey)
        rebuild()
    }

    /** Create an account with [passkey], and sign in. */
    suspend fun signUp(
        passkey: PasskeyProvider,
        name: String,
    ) {
        client.signUp(passkey, name)
        rebuild()
    }

    /** Stop a sign-in that is waiting (the person left the passkey sheet). */
    fun cancelSignIn() {
        signing?.cancel()
        _isSigningIn.value = false
    }

    /** Sign out: end the session on the node, and forget it here. [forgetDevice] also clears every stored value. */
    suspend fun signOut(forgetDevice: Boolean = false) {
        try {
            client.signOut()
        } catch (_: CalimeroError) {
            client.forget()
        } catch (_: IOException) {
            client.forget()
        }
        if (forgetDevice) store.clear()
        rebuild()
    }

    /**
     * A link the system handed the app: the consent page sending the person back
     * (`callbackUrl?request_id=…&result=…`, confirmed with the node into [consentResult]), or one of
     * this app's invitation links (held in [pendingInvitation] for the person to confirm). Returns
     * whether it was either.
     */
    suspend fun handle(url: String): Boolean {
        restoring.withLock { }
        consentReturn(url)?.let { back ->
            _consentResult.value = runCatching { client.consentResult(back.requestId) }.getOrNull()
            return true
        }
        val recognised = options.isInvitation?.invoke(url) ?: isInvitationLink(url, packageName)
        if (!recognised) return false
        _pendingInvitation.value = url
        return true
    }

    /** [pendingInvitation] decoded, or null. */
    val pendingOrgInvitation: OrgInvitation? get() = _pendingInvitation.value?.let { OrgInvitation.parse(it, packageName) }

    /** Forget [pendingInvitation]: redeemed, or declined. */
    fun dismissPendingInvitation() {
        _pendingInvitation.value = null
    }

    /** Redeem [pendingInvitation] (`POST /invites/{token}/redeem`) and forget it. Answers the organization joined. */
    suspend fun redeemPendingInvitation(): String? {
        val backend =
            _backend.value ?: throw com.calimero.mero.app
                .SignedOutException("redeemPendingInvitation")
        val invitation = pendingOrgInvitation ?: return null
        val joined = backend.joinNamespace(invitation.namespaceId, invitation)
        dismissPendingInvitation()
        didJoinNamespace()
        return joined.namespaceId
    }

    /** After redeeming an invitation: the account is a member at once on 1.0, so screens only re-read. */
    suspend fun didJoinNamespace() {
        _revision.value += 1
    }

    /**
     * Ask the account holder to approve [request] on the node's consent page, opened in a Custom
     * Tab ([ConsentLauncher]). The page sends them back to [callbackUrl]: hand that link to
     * [handle], and read [consentResult]. Show the answer's [ConsentStarted.code] first: the
     * page shows the same confirmation code.
     */
    suspend fun requestConsent(
        context: Context,
        request: ConsentRequest,
    ): ConsentStarted {
        _consentResult.value = null
        return ConsentLauncher.request(context, client, request)
    }

    /** Rebuild [backend] from the client's session. A no-op when the account did not change. */
    fun rebuild() {
        val session = client.session
        val next = session?.let { CalimeroAppBackend(client, packageName, it.accountId) }
        val current = _backend.value as? CalimeroAppBackend
        if (current?.account == next?.account && (current == null) == (next == null)) return
        _backend.value = next
        _revision.value += 1
    }

    private fun launchSignIn(block: suspend () -> Unit) {
        signing?.cancel()
        _lastError.value = null
        _isSigningIn.value = true
        signing =
            scope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    _lastError.value = e
                } finally {
                    _isSigningIn.value = false
                }
            }
    }

    companion object {
        /**
         * A session with its own [CalimeroClient] on [config]'s cluster, its session kept in
         * Keystore-backed encrypted preferences ([EncryptedSessionStore]).
         *
         * @param packageName the app's registry package (`com.example.board`).
         * @param callbackUrl where the consent page sends the person back.
         */
        @Suppress("LongParameterList")
        fun create(
            context: Context,
            packageName: String,
            callbackUrl: String,
            config: MeroAppConfig,
            options: MeroAppSessionOptions = MeroAppSessionOptions(),
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            store: SessionStore = EncryptedSessionStore(context, "calimero_session_$packageName"),
        ): MeroAppSession {
            val client =
                CalimeroClient(
                    CalimeroClientOptions(
                        baseUrl = config.nodeUrl,
                        nodes = config.nodes,
                        policy = config.policy,
                        httpClient = config.httpClient ?: CalimeroClientOptions.defaultHttpClient(),
                    ),
                )
            return MeroAppSession(client, packageName, callbackUrl, store, options, scope)
        }

        /**
         * Whether [raw] is an invitation to the app with [packageName]: it carries `invitation=…`,
         * and its path names the package. Without a package any invitation link is.
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
