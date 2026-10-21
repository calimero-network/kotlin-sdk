package com.calimero.mero.account

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.cloud.RelayChoice
import com.calimero.mero.cloud.RoutingCredential
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.presence.RelayPresenceClient
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.relay.FoundedNamespace
import com.calimero.mero.relay.GovernanceOps
import com.calimero.mero.relay.PersistedNonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayKeyRefusedException
import com.calimero.mero.relay.RelayKeyTransportException
import com.calimero.mero.relay.RelayKeyVerifier
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * A signed-in Cloud account on this device: who it is, the certificate that proves this
 * device belongs to it, and the relay that serves it.
 *
 * The device's signing secret is not in here — it stays in the [DeviceKeyStore].
 * [relayUrl] is null for an account no relay serves yet (a new account is a member of
 * nothing); that is still signed in.
 */
@Serializable
data class DelegatedSession(
    val account: String,
    val device: String,
    /** `AccountProof<DeviceCert>`, hex: every warrant's `authorProof`. */
    val credential: String,
    val relayUrl: String? = null,
    /** The relay's own account (the executor warrants name), when the cloud said. */
    val executorAccount: String? = null,
)

/**
 * The whole Cloud sign-in on a phone, end to end. Mirrors mero-react's
 * `useAccountEnrolment` + `connectWithAccount` without a browser:
 *
 * 1. [beginEnrolment] — make (or load) this device's keys, remember a fresh `state`, and
 *    return the wallet URL to open in a Custom Tab.
 * 2. The person approves with their passkey at the wallet, which redirects to the
 *    callback with the certificate in the fragment.
 * 3. [completeEnrolment] — verify that certificate against **this** device's keys and the
 *    `state`, ask the cloud manager which relay serves the account ([CloudClient.chooseRelay])
 *    and persist the [DelegatedSession].
 * 4. [connect] — learn the relay's node key ([RelayKeyVerifier]; by default its TDX quote is
 *    verified on the device, [DcapRelayKeyVerifier]), log in to it with the
 *    certificate (Bearer tokens for admin reads and SSE), and hand back a [RelayConnection]
 *    whose [RelayClient] writes through warrants.
 *
 * Everything persistent goes through [store]: on a device use [EncryptedPrefsSecureStore].
 */
@Suppress("LongParameterList")
class CloudAccount(
    internal val store: SecureStore,
    val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    val walletUrl: String = Enrolment.DEFAULT_WALLET_URL,
    internal val relayKeyVerifier: RelayKeyVerifier = DcapRelayKeyVerifier(),
    internal val clientName: String = "mero-kotlin-sdk",
    internal val audience: Audience = Audience.Cli,
    internal val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
    /**
     * Seal every request a delegated session sends a hosted relay — intents, admin reads,
     * queries, the login and events — to the relay's attested TD ([relayTransportClient]), as
     * mero-js always does. A loopback relay (a dev rig) is never sealed.
     */
    internal val sealRelays: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loginMutex = Mutex()

    /** Guards read-modify-writes of the relay map and group-namespace records ([RelayMap]). */
    internal val relayMapLock = Any()

    /** Group → namespace pairs learned in this process, per account, whatever the store kept. */
    internal val learnedGroupNamespaces = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    /** This device's keys. */
    val deviceKeys: DeviceKeyStore = DeviceKeyStore(store)

    internal val relayLogin: RelayLogin get() = RelayLogin(relayKeyVerifier, clientName, audience, httpClient = httpClient)

    /** The persisted session, or null when signed out. */
    fun session(): DelegatedSession? =
        store.get(KEY_SESSION)?.let { raw -> runCatching { json.decodeFromString(DelegatedSession.serializer(), raw) }.getOrNull() }

    /** True when a session is persisted. */
    val isSignedIn: Boolean get() = session() != null

    internal fun saveSession(session: DelegatedSession) = store.put(KEY_SESSION, json.encodeToString(DelegatedSession.serializer(), session))

    // ---- Enrolment -----------------------------------------------------------------------

    /**
     * Start enrolment: returns the wallet URL to open. [callbackUrl] is where the wallet
     * sends the person back — any absolute URL this app receives (an https App Link or an
     * app scheme such as `mero-sample://enrol`). The `state` sent is persisted so the
     * check in [completeEnrolment] survives process death while the wallet is open.
     */
    fun beginEnrolment(callbackUrl: String): String {
        val keys = deviceKeys.loadOrCreate()
        val state = Enrolment.newState()
        store.put(KEY_PENDING_STATE, state)
        return Enrolment.deviceEnrolmentUrl(keys.signPublicKey, keys.kemPublicKey, callbackUrl, state, walletUrl)
    }

    /** True if [url] looks like an enrolment answer (credential or error in its fragment). */
    fun isEnrolmentCallback(url: String): Boolean {
        val fragment = url.substringAfter('#', "")
        return fragment.contains("credential=") || fragment.contains("error=")
    }

    /**
     * Finish enrolment from the callback URL the app received. Verifies the certificate
     * against this device's keys and the pending `state`, finds the account's relay and
     * persists the session.
     *
     * @return the session, or null when [url] carries no enrolment.
     * @throws EnrolmentException when the person declined, or the answer did not verify.
     */
    suspend fun completeEnrolment(url: String): DelegatedSession? {
        val callback =
            try {
                Enrolment.readEnrolmentCallback(url) ?: return null
            } catch (e: EnrolmentException) {
                store.remove(KEY_PENDING_STATE)
                throw e
            }
        val keys = deviceKeys.load() ?: throw EnrolmentException("this device has no keys to match the credential against")
        val expected = store.get(KEY_PENDING_STATE)
        val enrolled =
            Enrolment.completeDeviceEnrolment(callback, keys.signPublicKey, keys.kemPublicKey, expectState = expected)
        store.remove(KEY_PENDING_STATE)
        val base = DelegatedSession(enrolled.account, enrolled.device, enrolled.credential)
        saveSession(base)
        // Finding the relay is best-effort: a cloud blip must not undo a verified sign-in.
        return runCatching { findRelay(base) }.getOrDefault(base)
    }

    /**
     * Why the last relay lookup ended degraded or empty ([CloudClient.chooseRelay]'s note),
     * or null when it found a fresh relay. In memory only.
     */
    @Volatile
    var lastRelayNote: String? = null
        private set

    /**
     * True when the last relay lookup ANSWERED that no relay serves the account
     * ([RelayChoice.none]). False before any lookup, after one that failed, and when relays are
     * assigned without an address: none of those says the account is a member of nothing.
     * In memory only.
     */
    @Volatile
    var lastRelayNone: Boolean = false
        private set

    /** Ask the cloud manager which relay serves the account, and persist the choice. */
    suspend fun findRelay(session: DelegatedSession = requireSession()): DelegatedSession {
        lastRelayNone = false
        val choice = chooseRelay(session)
        lastRelayNote = choice.note
        lastRelayNone = choice.none
        val updated =
            session.copy(
                relayUrl = choice.relayUrl?.trimBase() ?: session.relayUrl,
                executorAccount = choice.executorAccount ?: session.executorAccount,
            )
        saveSession(updated)
        return updated
    }

    /** The cloud's relay list for the session's account, reduced by [CloudClient.chooseRelay]. */
    suspend fun chooseRelay(session: DelegatedSession = requireSession()): RelayChoice =
        CloudClient.chooseRelay(cloud(session).getAccountRelays(session.account))

    /** A [CloudClient] proving reads with this device's certificate. */
    fun cloud(session: DelegatedSession = requireSession()): CloudClient = cloud(session, cloudBaseUrl)

    internal fun cloud(
        session: DelegatedSession,
        baseUrl: String,
    ): CloudClient = CloudClient(baseUrl, RoutingCredential(session.credential, deviceKeys.loadOrCreate().signer()), httpClient)

    // ---- Relay ---------------------------------------------------------------------------

    /**
     * The relay's node key: pinned from an earlier attestation, or attested now and pinned.
     * Throws when the relay is unreachable or its attestation is refused; [attemptRelayNodeKey]
     * says which, and [learnRelayNodeKey] waits an unreachable one out.
     */
    suspend fun relayNodeKey(relayUrl: String): String =
        when (val attempt = attemptRelayNodeKey(relayUrl)) {
            is RelayNodeKeyAttempt.Learned -> attempt.nodeKey
            is RelayNodeKeyAttempt.Unavailable -> throw attempt.error
            is RelayNodeKeyAttempt.Refused -> throw attempt.error
        }

    /** Pin a relay's node key learned out of band (e.g. from its operator). */
    fun pinRelayNodeKey(
        relayUrl: String,
        nodeKey: String,
    ) {
        require(Hex.isHex32(nodeKey.trim().lowercase())) { "nodeKey must be 64 hex characters" }
        store.put(KEY_NODE_KEY_PREFIX + relayUrl.trimBase(), nodeKey.trim().lowercase())
    }

    /** The key pinned for [relayUrl], or null when none (or a malformed one) is. mero-js `readPinnedRelayNodeKey`. */
    fun readPinnedRelayNodeKey(relayUrl: String): String? =
        runCatching { store.get(KEY_NODE_KEY_PREFIX + relayUrl.trimBase()) }.getOrNull()?.takeIf { Hex.isHex32(it) }

    /**
     * One attempt at [relayUrl]'s node key, and what became of it — mero-js
     * `attemptRelayNodeKey`: the pinned key, or else learned from the relay's attestation (judged
     * by this account's [RelayKeyVerifier]) and pinned. [RelayNodeKeyAttempt.Unavailable] when
     * something on the way did not answer (the relay unreachable, or answering non-2xx);
     * [RelayNodeKeyAttempt.Refused] when the relay answered and its proof did not hold.
     *
     * [fresh] skips the pin and attests now, for a step that hands the relay something it keeps
     * (founding a namespace through it): a pin was checked on some earlier day.
     */
    suspend fun attemptRelayNodeKey(
        relayUrl: String,
        fresh: Boolean = false,
    ): RelayNodeKeyAttempt {
        if (!fresh) readPinnedRelayNodeKey(relayUrl)?.let { return RelayNodeKeyAttempt.Learned(it) }
        return try {
            val nodeKey = relayLogin.attestNodeKey(relayUrl).trim().lowercase()
            if (!Hex.isHex32(nodeKey)) throw RelayKeyRefusedException("the verifier returned no well-formed node key")
            runCatching { store.put(KEY_NODE_KEY_PREFIX + relayUrl.trimBase(), nodeKey) }
            RelayNodeKeyAttempt.Learned(nodeKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayKeyTransportException) {
            RelayNodeKeyAttempt.Unavailable(e)
        } catch (e: NetworkException) {
            RelayNodeKeyAttempt.Unavailable(e)
        } catch (e: HttpException) {
            RelayNodeKeyAttempt.Unavailable(e)
        } catch (e: Exception) {
            RelayNodeKeyAttempt.Refused(e)
        }
    }

    /**
     * [relayUrl]'s node key from one attempt, or null when it did not learn it, for whatever
     * reason (mero-js `resolveRelayNodeKey`). Callers that must end up with it use [learnRelayNodeKey].
     */
    suspend fun resolveRelayNodeKey(relayUrl: String): String? = (attemptRelayNodeKey(relayUrl) as? RelayNodeKeyAttempt.Learned)?.nodeKey

    /**
     * [relayUrl]'s node key, however long the way to it is down — mero-js `learnRelayNodeKey`.
     * An unavailable attempt is retried after each of [retryMs] in turn (then every last one —
     * [RELAY_NODE_KEY_RETRY_MS] settles at 30 s) until the key is learned or the calling coroutine
     * is cancelled. A refusal ends it at once with `null`: a quote that did not verify is an
     * answer, and asking again is not how it becomes a different one.
     */
    suspend fun learnRelayNodeKey(
        relayUrl: String,
        retryMs: List<Long> = RELAY_NODE_KEY_RETRY_MS,
    ): String? {
        require(retryMs.isNotEmpty()) { "retryMs needs at least one wait" }
        var failures = 0
        while (true) {
            when (val attempt = attemptRelayNodeKey(relayUrl)) {
                is RelayNodeKeyAttempt.Learned -> return attempt.nodeKey
                is RelayNodeKeyAttempt.Refused -> return null
                is RelayNodeKeyAttempt.Unavailable -> delay(retryMs[minOf(failures, retryMs.size - 1)])
            }
            failures++
        }
    }

    /**
     * Log in to the session's relay with the device certificate.
     *
     * A login under a **pinned** node key that fails is tried once more under a key attested
     * now: the relay may have been rebuilt since the pin (a new TD, a new key), and a pin is
     * otherwise never checked against the network again. When the relay cannot be attested
     * either, the login's own error is what is thrown.
     */
    suspend fun loginToRelay(session: DelegatedSession = requireSession()): TokenData {
        val relayUrl = session.relayUrl ?: error("this account has no relay yet")
        val pinned = readPinnedRelayNodeKey(relayUrl)
        val nodeKey = relayNodeKey(relayUrl)
        val signer = deviceKeys.loadOrCreate().signer()
        return try {
            relayLogin.login(relayUrl, nodeKey, session.credential, signer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fresh = pinned?.let { reattestAfterFailedLogin(relayUrl, nodeKey, e) } ?: throw e
            relayLogin.login(relayUrl, fresh, session.credential, signer)
        }
    }

    /**
     * The relay's key attested now, after a login under the pinned [nodeKey] failed with
     * [loginError]: null when it is the same key (the pin was not the problem), and the
     * attestation's refusal thrown when the relay no longer proves itself.
     */
    private suspend fun reattestAfterFailedLogin(
        relayUrl: String,
        nodeKey: String,
        loginError: Exception,
    ): String? =
        when (val attempt = attemptRelayNodeKey(relayUrl, fresh = true)) {
            is RelayNodeKeyAttempt.Learned -> attempt.nodeKey.takeIf { it != nodeKey }
            is RelayNodeKeyAttempt.Unavailable -> null.also { loginError.addSuppressed(attempt.error) }
            is RelayNodeKeyAttempt.Refused -> throw attempt.error
        }

    /**
     * Everything an app needs to talk to the account's relay. The Bearer session is minted
     * lazily and re-minted when it nears expiry; if the relay's node key cannot be learned
     * the connection still writes (warrants carry their own authority) and [RelayClient.call]
     * falls back from query to warrant for reads.
     *
     * @param tokenStore where the relay session's tokens live (in memory by default).
     */
    fun connect(
        session: DelegatedSession = requireSession(),
        tokenStore: TokenStore = MemoryTokenStore(),
    ): RelayConnection {
        val relayUrl = session.relayUrl ?: error("this account has no relay yet")
        val mero = Mero(MeroConfig(baseUrl = relayUrl, tokenStore = tokenStore))
        val loginError = AtomicReference<Exception?>(null)
        val bearer = RelayConnection.bearerSource(mero, loginMutex, onLoginError = loginError::set) { loginToRelay(session) }
        return RelayConnection(session, mero, relayClient(session, relayUrl, bearer), bearer, loginError::get)
    }

    /** A warrant client on [relayUrl] for [session], with this device's persisted nonces. */
    private fun relayClient(
        session: DelegatedSession,
        relayUrl: String,
        bearer: (suspend () -> String?)? = null,
    ): RelayClient =
        RelayClient(
            relayUrl = relayUrl,
            authorAccount = session.account,
            authorProof = session.credential,
            signer = deviceKeys.loadOrCreate().signer(),
            nonces = PersistedNonceSource.forRelay(store, relayUrl),
            executorAccount = session.executorAccount,
            session = bearer,
            httpClient = httpClient,
        )

    // ---- Founding ------------------------------------------------------------------------

    /**
     * Found a namespace as this account, through the relay it already uses, and give it a
     * display [name] — [foundDelegatedNamespace] plus `createNamespace`'s naming step: the
     * account becomes founder, owner and admin, the relay is seated in it, and contexts can be
     * created in it through the same relay straight away. Then, best-effort, the cloud is asked
     * to host it so invitees with no node can find it.
     *
     * @param defaultCapabilities the namespace root's default member mask (mero-react uses 231).
     * @param application the app the namespace runs; without one no context can be created in it.
     * @param name a display name, set with a delegated `GroupMetadataSet` (best-effort).
     * @param knownNamespaceId a namespace this account already uses on the relay, to learn the
     *   relay's executor account from when the session does not name it.
     * @throws MeroStateException when no relay or executor is known, the relay's attestation
     *   failed, or the namespace was founded but could not be given its [application].
     */
    @Suppress("LongParameterList")
    suspend fun foundNamespace(
        defaultCapabilities: Long? = null,
        application: ApplicationTarget? = null,
        name: String? = null,
        knownNamespaceId: String? = null,
        enableHa: Boolean = true,
        session: DelegatedSession = requireSession(),
    ): FoundedDelegatedNamespace {
        val founded = foundDelegatedNamespace(session, defaultCapabilities, application, knownNamespaceId, enableHa)
        val nameError =
            name?.takeIf { it.isNotEmpty() }?.let { n ->
                runCatching {
                    delegatedGovernance(session, founded.namespaceId, founded.namespaceId, GovernanceOps.groupMetadataSetOp(name = n))
                }.exceptionOrNull()?.message
            }
        return founded.copy(nameError = nameError)
    }

    // ---- Inviting ------------------------------------------------------------------------

    /**
     * Mint an invitation to [namespaceId] signed by this device, for a namespace the account
     * founded or joined. Port of mero-react / mero-js `createAccountAdmin().createNamespaceInvitation`.
     *
     * The relays in the namespace (`RelayTee` members) are named as admitters, because the
     * person who claims it may have no node and only a relay can admit them; with none, the
     * namespace's admins. The members and the group's application are read from the relay
     * over [connection]'s Bearer session; without one, the session relay's own account is
     * named. The invitation carries no address: a claimant finds its admitter through the
     * cloud's routing only, so a namespace the cloud lists no node for cannot be claimed and
     * is refused here rather than failing in the claimant's hands.
     *
     * @throws InvitationNotClaimableException when nobody the claimant could reach would admit them.
     */
    @Suppress("ThrowsCount")
    suspend fun createNamespaceInvitation(
        namespaceId: String,
        connection: RelayConnection? = null,
        validForSecs: Long = Invitations.MAX_INVITATION_VALIDITY_SECS,
        session: DelegatedSession = requireSession(),
    ): SignedGroupOpenInvitation {
        val ns = Hex.encode(Hex.decode(namespaceId, "namespaceId", 32))
        val admin = connection?.takeIf { it.ensureSession() }?.mero?.admin
        val members = admin?.let { a -> runCatching { a.listGroupMembers(ns).members }.getOrNull() }
        val info = admin?.let { a -> runCatching { a.getGroupInfo(ns) }.getOrNull() }
        val relays =
            members?.filter { it.role == ROLE_RELAY_TEE }?.map { it.identity.lowercase() }
                ?: listOfNotNull(session.executorAccount?.lowercase())
        if (members == null && relays.isEmpty()) {
            throw MeroStateException(
                "cannot read the namespace's members (the relay session is not ready) and the relay's account is unknown, " +
                    "so there is nobody to name as admitter",
            )
        }
        val named = relays.ifEmpty { Invitations.defaultAdmitters(members.orEmpty()) }.toSet()
        // A lookup that fails does not block minting: the claimant's own lookup will say what is wrong.
        val nodes = runCatching { cloud(session).getNamespaceRouting(ns).nodes }.getOrNull()
        if (nodes != null) {
            // An address on the invitation is unsigned, so a relay the cloud does not list cannot stand in.
            if (nodes.isEmpty()) throw InvitationNotClaimableException(ns, InvitationNotClaimableReason.NOT_HOSTED)
            if (nodes.none { n -> n.account?.lowercase() in named }) {
                throw InvitationNotClaimableException(ns, InvitationNotClaimableReason.NO_NAMED_NODE)
            }
        }
        return Invitations.signGroupInvitation(
            groupId = ns,
            inviterAccount = session.account,
            signer = deviceKeys.loadOrCreate().signer(),
            admitters = relays,
            members = members,
            validForSecs = validForSecs,
            applicationId = info?.targetApplicationId?.lowercase()?.takeIf { Hex.isHex32(it) },
            appKey = info?.appKey?.lowercase()?.takeIf { Hex.isHex32(it) },
        )
    }

    // ---- Joining -------------------------------------------------------------------------

    /**
     * Redeem an invitation as this account: find an admitting node the invitation names
     * (via the cloud), sign this device's join and have that node publish it. The node is
     * remembered as the namespace's relay ([rememberRelay]), and becomes the account's relay
     * when it had none. For the step-typed outcome use [bootstrapFromInvitation]; to move the
     * session onto the joined relay as mero-js does, use the `joinAsAccount(session, …, onJoined)`
     * extension.
     *
     * @throws JoinException naming the step that failed, with its HTTP status when it had one.
     */
    suspend fun joinAsAccount(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        nodeUrl: String? = null,
    ): JoinResult {
        val session = requireSession()
        return when (val outcome = bootstrapFromInvitation(namespaceId, invitation, nodeUrl, session)) {
            is BootstrapResult.Failure -> throw JoinException(outcome.step, outcome.reason, outcome.status)
            is BootstrapResult.Success -> {
                val relayUrl = checkNotNull(outcome.session.relayUrl)
                rememberRelay(session.account, relayUrl, namespaceId = namespaceId)
                val updated =
                    if (session.relayUrl == null) {
                        session.copy(relayUrl = relayUrl, executorAccount = outcome.relay?.admitterAccount?.takeIf { Hex.isHex32(it) })
                    } else {
                        session
                    }
                saveSession(updated)
                JoinResult(namespaceId, relayUrl, outcome.published, updated)
            }
        }
    }

    /**
     * Resolve a node from the invitation, present the signed join to it, and return the
     * session to connect with — mero-js `bootstrapFromInvitation`. Persists nothing.
     *
     * The node is [nodeUrl] when given (one the caller knows: an operator's, a local rig —
     * never one read off the invitation), else the cloud's routing for the namespace
     * intersected with the invitation's signed admitters ([Join.resolveRelayFromInvitation]).
     *
     * Never throws for an expected failure: [BootstrapResult.Failure] names the step —
     * [JoinStep.NO_CREDENTIAL] when no account is signed in on this device.
     */
    suspend fun bootstrapFromInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        nodeUrl: String? = null,
        session: DelegatedSession? = session(),
        cloudBaseUrl: String = this.cloudBaseUrl,
    ): BootstrapResult {
        if (session == null) {
            return BootstrapResult.Failure(
                JoinStep.NO_CREDENTIAL,
                "This device holds no enrolled account, so there is nothing to admit: sign in with a cloud account first.",
            )
        }
        return try {
            val resolved = if (nodeUrl != null) null else Join.resolveRelayFromInvitation(cloud(session, cloudBaseUrl), namespaceId, invitation)
            val relayUrl = nodeUrl?.trimBase() ?: checkNotNull(resolved).relayUrl
            val admitUrl = resolved?.admitUrl ?: Join.admitUrlOn(relayUrl, namespaceId)
            val nonce = PersistedNonceSource(store, "calimero.delegated.joinnonce.${session.account}").next()
            val published =
                Join.joinWithNode(
                    PlainJsonHttp(httpClient), admitUrl, namespaceId, invitation, session.account, session.credential,
                    deviceKeys.loadOrCreate().signer(), nonce,
                )
            BootstrapResult.Success(
                session = DelegatedSession(session.account, session.device, session.credential, relayUrl = relayUrl),
                relay = resolved,
                published = published,
            )
        } catch (e: JoinException) {
            BootstrapResult.Failure(e.step, e.message.orEmpty(), e.status)
        }
    }

    // ---- Sign out ------------------------------------------------------------------------

    /**
     * Forget the session (and any pinned relay keys stay pinned). With [forgetDevice] the
     * device keys go too, so the next sign-in certifies a fresh key.
     */
    fun signOut(forgetDevice: Boolean = false) {
        store.remove(KEY_SESSION)
        store.remove(KEY_PENDING_STATE)
        if (forgetDevice) deviceKeys.clear()
    }

    internal fun requireSession(): DelegatedSession = session() ?: error("not signed in with a cloud account")

    internal companion object {
        const val KEY_SESSION = "calimero.delegated.connection"
        const val KEY_PENDING_STATE = "calimero.enrol.state"
        const val KEY_NODE_KEY_PREFIX = "calimero.delegated.relay-node-key."
        const val ROLE_RELAY_TEE = "RelayTee"
    }
}

/**
 * A live connection to the account's relay.
 *
 * - [relay]: warranted writes, reads by query, context creation, governance.
 * - [mero]: the existing admin / SSE / auth clients, pointed at the relay. Its token
 *   store is filled by [ensureSession], after which `mero.admin` reads are caller-scoped
 *   to the account and `mero.events(...)` streams over Bearer.
 */
class RelayConnection internal constructor(
    val session: DelegatedSession,
    val mero: Mero,
    val relay: RelayClient,
    private val bearer: suspend () -> String?,
    private val loginError: () -> Exception? = { null },
) {
    val relayUrl: String get() = relay.relayUrl

    /**
     * Why the last relay login failed — the relay unreachable, its attestation refused, the
     * credential refused — or null once a session is minted (or before any login). A failed
     * [ensureSession] leaves the cause here rather than swallowing it, so an app can say
     * "sign in again" or "the relay is down" instead of a vague `401` on the next read.
     */
    val sessionError: Exception? get() = loginError()

    /**
     * This account's ephemeral presence through the relay: device-signed statements posted
     * to `presence-intents` and resent every 2.5 s while set; read back off [mero]'s
     * shared event stream (`mero.sse`, which needs [ensureSession]). Close it with
     * [RelayPresenceClient.close] — [logout] does.
     */
    val presence: RelayPresenceClient by lazy { RelayPresenceClient(relay, events = { mero.sse }) }

    /**
     * Mint (or reuse) the relay's Bearer session. Returns false when it cannot be had —
     * the relay's node key is unknown or refused — in which case writes still work and
     * reads fall back to warrants.
     */
    suspend fun ensureSession(): Boolean = bearer() != null

    /**
     * End the relay session: retire its refresh token on the relay (`POST /auth/logout`,
     * best-effort) and drop its tokens. Warranted writes keep working — they never needed
     * the session — and the next [ensureSession] logs in again. See [Mero.logout].
     */
    suspend fun logout() {
        presence.close()
        mero.sse.close()
        mero.logout()
    }

    internal companion object {
        private const val REFRESH_MARGIN_MS = 30_000L

        /**
         * The Bearer token for [mero]'s relay: the stored one while it has more than 30 s
         * left, else a fresh [login] (null when it fails), single-flight under [mutex].
         * [onLoginError] hears each login's outcome: its error, or null once one succeeds.
         */
        fun bearerSource(
            mero: Mero,
            mutex: Mutex,
            onLoginError: (Exception?) -> Unit = {},
            login: suspend () -> TokenData?,
        ): suspend () -> String? =
            {
                mutex.withLock {
                    // Through `mero`, not the raw store: the bundle is bound to the relay's origin.
                    val current = mero.getTokenData()
                    if (current != null && current.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
                        current.accessToken
                    } else {
                        try {
                            login()
                                ?.also {
                                    mero.setTokenData(it)
                                    onLoginError(null)
                                }?.accessToken
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            onLoginError(e)
                            null
                        }
                    }
                }
            }
    }
}

/** What [CloudAccount.foundNamespace] returns. */
data class FoundedDelegatedNamespace(
    /** What the relay founded: id, salt, TEE and follow-up outcomes. */
    val founded: FoundedNamespace,
    /** Whether the cloud agreed to host the namespace right after founding. */
    val haEnabled: Boolean,
    /** Why [haEnabled] is false, in words a person can act on. */
    val haError: String? = null,
    /** Why the display name could not be set, when one was asked for and failed. */
    val nameError: String? = null,
) {
    val namespaceId: String get() = founded.namespaceId
}

/** Why [InvitationNotClaimableException] refused — mero-js `InvitationNotClaimableError.reason`. */
enum class InvitationNotClaimableReason(
    /** mero-js's spelling. */
    val wire: String,
) {
    /** The cloud lists no node holding the namespace. A relay it does not list cannot stand in: an address on the invitation is unsigned. */
    NOT_HOSTED("not-hosted"),

    /** The cloud's nodes are not among the admitters the invitation would name. */
    NO_NAMED_NODE("no-named-node"),
}

/**
 * Nobody the claimant of an invitation could reach would admit them — mero-js
 * `InvitationNotClaimableError`. A claimant with no node finds its admitter through the cloud's
 * routing intersected with the invitation's signed admitters, so an invitation the cloud routes
 * to none of them can only fail in their hands.
 */
class InvitationNotClaimableException(
    val namespaceId: String,
    val reason: InvitationNotClaimableReason,
) : Exception(
        when (reason) {
            InvitationNotClaimableReason.NOT_HOSTED ->
                "nobody could claim an invitation to $namespaceId yet: the cloud lists no node holding the namespace, so an " +
                    "invitee with no node of their own has no relay to be admitted through."
            InvitationNotClaimableReason.NO_NAMED_NODE ->
                "nobody could claim an invitation to $namespaceId: the cloud routes to none of the relays or admins it would " +
                    "name as admitters"
        },
    ) {
    /** The earlier boolean form of [reason]. */
    constructor(namespaceId: String, notHosted: Boolean) :
        this(namespaceId, if (notHosted) InvitationNotClaimableReason.NOT_HOSTED else InvitationNotClaimableReason.NO_NAMED_NODE)

    /** Whether the reason is [InvitationNotClaimableReason.NOT_HOSTED]. */
    val notHosted: Boolean get() = reason == InvitationNotClaimableReason.NOT_HOSTED
}
