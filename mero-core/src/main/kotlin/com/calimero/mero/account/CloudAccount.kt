package com.calimero.mero.account

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.AccountHaRefusedException
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.cloud.RelayChoice
import com.calimero.mero.cloud.RoutingCredential
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.FoundNamespaceInput
import com.calimero.mero.relay.FoundedNamespace
import com.calimero.mero.relay.GovernanceOps
import com.calimero.mero.relay.PersistedNonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayExecutor
import com.calimero.mero.relay.RelayKeyVerifier
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.relay.TlsRelayKeyVerifier
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

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
 * 4. [connect] — learn the relay's node key ([RelayKeyVerifier]), log in to it with the
 *    certificate (Bearer tokens for admin reads and SSE), and hand back a [RelayConnection]
 *    whose [RelayClient] writes through warrants.
 *
 * Everything persistent goes through [store]: on a device use [EncryptedPrefsSecureStore].
 */
@Suppress("LongParameterList")
class CloudAccount(
    private val store: SecureStore,
    val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    val walletUrl: String = Enrolment.DEFAULT_WALLET_URL,
    private val relayKeyVerifier: RelayKeyVerifier = TlsRelayKeyVerifier(),
    private val clientName: String = "mero-kotlin-sdk",
    private val audience: Audience = Audience.Cli,
    private val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val loginMutex = Mutex()

    /** This device's keys. */
    val deviceKeys: DeviceKeyStore = DeviceKeyStore(store)

    private val relayLogin: RelayLogin get() = RelayLogin(relayKeyVerifier, clientName, audience, httpClient = httpClient)

    /** The persisted session, or null when signed out. */
    fun session(): DelegatedSession? =
        store.get(KEY_SESSION)?.let { raw -> runCatching { json.decodeFromString(DelegatedSession.serializer(), raw) }.getOrNull() }

    /** True when a session is persisted. */
    val isSignedIn: Boolean get() = session() != null

    private fun saveSession(session: DelegatedSession) = store.put(KEY_SESSION, json.encodeToString(DelegatedSession.serializer(), session))

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

    /** Ask the cloud manager which relay serves the account, and persist the choice. */
    suspend fun findRelay(session: DelegatedSession = requireSession()): DelegatedSession {
        val choice = chooseRelay(session)
        lastRelayNote = choice.note
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
    fun cloud(session: DelegatedSession = requireSession()): CloudClient =
        CloudClient(cloudBaseUrl, RoutingCredential(session.credential, deviceKeys.loadOrCreate().signer()), httpClient)

    // ---- Relay ---------------------------------------------------------------------------

    /**
     * The relay's node key: pinned from an earlier attestation, or attested now and pinned.
     * Throws when the relay is unreachable or its attestation is refused.
     */
    suspend fun relayNodeKey(relayUrl: String): String {
        val key = KEY_NODE_KEY_PREFIX + relayUrl.trimBase()
        store.get(key)?.takeIf { Hex.isHex32(it) }?.let { return it }
        val nodeKey = relayLogin.attestNodeKey(relayUrl)
        store.put(key, nodeKey)
        return nodeKey
    }

    /** Pin a relay's node key learned out of band (e.g. from its operator). */
    fun pinRelayNodeKey(
        relayUrl: String,
        nodeKey: String,
    ) {
        require(Hex.isHex32(nodeKey.lowercase())) { "nodeKey must be 64 hex characters" }
        store.put(KEY_NODE_KEY_PREFIX + relayUrl.trimBase(), nodeKey.lowercase())
    }

    /** Log in to the session's relay with the device certificate. */
    suspend fun loginToRelay(session: DelegatedSession = requireSession()): TokenData {
        val relayUrl = session.relayUrl ?: error("this account has no relay yet")
        val nodeKey = relayNodeKey(relayUrl)
        return relayLogin.login(relayUrl, nodeKey, session.credential, deviceKeys.loadOrCreate().signer())
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
        val bearer: suspend () -> String? = {
            loginMutex.withLock {
                val current = tokenStore.getTokens()
                if (current != null && current.expiresAt - System.currentTimeMillis() > REFRESH_MARGIN_MS) {
                    current.accessToken
                } else {
                    runCatching { loginToRelay(session) }.getOrNull()?.also { tokenStore.setTokens(it) }?.accessToken
                }
            }
        }
        return RelayConnection(session, mero, relayClient(session, relayUrl, bearer), bearer)
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
     * Found a namespace as this account, through the relay it already uses: the account
     * becomes founder, owner and admin, the relay is seated in it, and contexts can be
     * created in it through the same relay straight away. Then, best-effort, asks the cloud
     * to host it ([CloudClient.enableHaAsAccount]) so invitees with no node can find it.
     *
     * Port of mero-js `foundDelegatedNamespace` (plus `createNamespace`'s naming step).
     *
     * The relay's executor cannot be asked about a namespace that does not exist yet: its
     * account comes from the session (the cloud names it beside the relay) or, failing that,
     * from [knownNamespaceId] (a namespace this account already uses on the relay), and its
     * key is always the relay's attested or pinned node key ([relayNodeKey]).
     *
     * @param defaultCapabilities the namespace root's default member mask (mero-react uses 231).
     * @param application the app the namespace runs; without one no context can be created in it.
     * @param name a display name, set with a delegated `GroupMetadataSet` (best-effort).
     * @throws MeroStateException when no relay or executor is known, or the namespace was
     *   founded but could not be given its [application].
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
        val relayUrl =
            session.relayUrl?.trimBase()
                ?: throw MeroStateException("no relay is known for this account, so there is nowhere to found a namespace")
        val relay = relayClient(session, relayUrl)
        val executor = foundingExecutor(session, relay, knownNamespaceId)
        val founded = relay.foundNamespace(FoundNamespaceInput(executor, defaultCapabilities = defaultCapabilities, application = application))
        if (application != null && founded.applicationSet != true) {
            // Founded, but no context can be created in it yet; the same choice can be retried.
            throw MeroStateException(
                "founded ${founded.namespaceId} but could not give it its application: ${founded.applicationError ?: "unknown reason"}",
            )
        }
        val nameError =
            name?.takeIf { it.isNotEmpty() }?.let { n ->
                runCatching { relay.govern(founded.namespaceId, GovernanceOps.groupMetadataSetOp(name = n)) }.exceptionOrNull()?.message
            }
        // HA's fleet node is admitted by the founding relay, which can vouch for it only once
        // it attested itself as the namespace's first TEE.
        val (haEnabled, haError) =
            when {
                !enableHa -> false to null
                founded.teeEnabled -> enableHaBestEffort(session, founded, relayUrl)
                else ->
                    false to
                        "the relay did not attest the founding, so no fleet node could be admitted for HA" +
                        (founded.teeError?.let { ": $it" } ?: "")
            }
        return FoundedDelegatedNamespace(founded, haEnabled, haError, nameError)
    }

    private suspend fun foundingExecutor(
        session: DelegatedSession,
        relay: RelayClient,
        knownNamespaceId: String?,
    ): RelayExecutor {
        session.executorAccount?.let { return RelayExecutor(it, relayNodeKey(relay.relayUrl)) }
        val known =
            knownNamespaceId
                ?: throw MeroStateException(
                    "the executor account of this relay is not known: join a namespace on it first (and pass it as " +
                        "knownNamespaceId), or sign in again so the cloud names the relay's account",
                )
        val executorKey = relayNodeKey(relay.relayUrl)
        // Discovery is unauthenticated: it may name the account, never the key.
        val described = relay.describeGovernance(known)
        if (described.executorKey?.lowercase() != executorKey) {
            throw MeroStateException(
                "the relay's discovery names signing key ${described.executorKey}, not the relay's node key $executorKey: refusing to found through it",
            )
        }
        return RelayExecutor(described.executorAccount.orEmpty(), executorKey)
    }

    private suspend fun enableHaBestEffort(
        session: DelegatedSession,
        founded: FoundedNamespace,
        relayUrl: String,
    ): Pair<Boolean, String?> =
        try {
            cloud(session).enableHaAsAccount(
                namespaceId = founded.namespaceId,
                salt = founded.salt,
                accountId = session.account,
                credential = session.credential,
                relayUrl = relayUrl,
            )
            true to null
        } catch (e: AccountHaRefusedException) {
            val blocking = if (e.code == AccountHaRefusedException.HA_REQUEST_PENDING) blockingNamespace(e.bodyText) else null
            false to (blocking?.let { "${e.advice} (waiting: $it)" } ?: e.advice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false to (e.message ?: e.toString())
        }

    private fun blockingNamespace(body: String): String? =
        runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            val id = ((o["detail"] as? JsonObject)?.get("namespace_id") ?: o["namespace_id"])?.jsonPrimitive?.contentOrNull
            id?.lowercase()?.takeIf { Hex.isHex32(it) }
        }.getOrNull()

    // ---- Inviting ------------------------------------------------------------------------

    /**
     * Mint an invitation to [namespaceId] signed by this device, for a namespace the account
     * founded or joined. Port of mero-react / mero-js `createAccountAdmin().createNamespaceInvitation`.
     *
     * The relays in the namespace (`RelayTee` members) are named as admitters, because the
     * person who claims it may have no node and only a relay can admit them; with none, the
     * namespace's admins. The members and the group's application are read from the relay
     * over [connection]'s Bearer session; without one, the session relay's own account is
     * named. When the cloud routes the namespace to nobody (not hosted), the invitation
     * carries the relay's URL so the claimant can still find it.
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
        val nodes = runCatching { cloud(session).getNamespaceRouting(ns).nodes }.getOrNull()
        var admitterAddrs = emptyList<String>()
        if (nodes != null) {
            if (nodes.isEmpty()) {
                // Not hosted: the relay that serves this session admits through its own `/admit`.
                val relayUrl = session.relayUrl?.trimBase()
                if (relays.isEmpty() || relayUrl == null) throw InvitationNotClaimableException(ns, notHosted = true)
                admitterAddrs = listOf(relayUrl)
            } else if (nodes.none { n -> n.account?.lowercase() in named }) {
                throw InvitationNotClaimableException(ns, notHosted = false)
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
            admitterAddrs = admitterAddrs,
        )
    }

    // ---- Joining -------------------------------------------------------------------------

    /**
     * Redeem an invitation as this account: find an admitting node the invitation names
     * (via the cloud), sign this device's join and have that node publish it. The node
     * becomes the account's relay when it had none. Mirrors mero-js `joinAsAccount`.
     *
     * @throws JoinException naming the step that failed.
     */
    suspend fun joinAsAccount(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        nodeUrl: String? = null,
    ): JoinResult = bootstrapFromInvitation(namespaceId, invitation, nodeUrl)

    /** [joinAsAccount] under its mero-js name. */
    suspend fun bootstrapFromInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        nodeUrl: String? = null,
    ): JoinResult {
        val session = requireSession()
        val resolved =
            if (nodeUrl != null) {
                null
            } else {
                Join.resolveRelayFromInvitation(cloud(session), namespaceId, invitation)
            }
        val relayUrl = nodeUrl?.trimBase() ?: resolved!!.relayUrl
        val admitUrl = resolved?.admitUrl ?: "$relayUrl/admin-api/namespaces/$namespaceId/admit"
        val nonce = PersistedNonceSource(store, "calimero.delegated.joinnonce.${session.account}").next()
        val published =
            Join.joinWithNode(
                PlainJsonHttp(httpClient), admitUrl, namespaceId, invitation, session.account, session.credential,
                deviceKeys.loadOrCreate().signer(), nonce,
            )
        val updated =
            if (session.relayUrl == null) {
                session.copy(relayUrl = relayUrl, executorAccount = resolved?.admitterAccount?.takeIf { Hex.isHex32(it) })
            } else {
                session
            }
        saveSession(updated)
        return JoinResult(namespaceId, relayUrl, published, updated)
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

    private fun requireSession(): DelegatedSession = session() ?: error("not signed in with Calimero Cloud")

    private companion object {
        const val KEY_SESSION = "calimero.delegated.connection"
        const val KEY_PENDING_STATE = "calimero.enrol.state"
        const val KEY_NODE_KEY_PREFIX = "calimero.delegated.relay-node-key."
        const val REFRESH_MARGIN_MS = 30_000L
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
) {
    val relayUrl: String get() = relay.relayUrl

    /**
     * Mint (or reuse) the relay's Bearer session. Returns false when it cannot be had —
     * the relay's node key is unknown or refused — in which case writes still work and
     * reads fall back to warrants.
     */
    suspend fun ensureSession(): Boolean = bearer() != null
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

/**
 * Nobody the claimant of an invitation could reach would admit them: the namespace is not
 * hosted in the cloud and has no relay in it ([notHosted]), or the cloud routes to none of
 * the accounts the invitation would name.
 */
class InvitationNotClaimableException(
    val namespaceId: String,
    val notHosted: Boolean,
) : Exception(
        if (notHosted) {
            "nobody could claim an invitation to $namespaceId yet: the namespace is not hosted in the cloud and has no relay in it, " +
                "so an invitee with no node has no relay to be admitted through. Link this account to your cloud user in the wallet " +
                "and enable HA for the namespace, then invite."
        } else {
            "nobody could claim an invitation to $namespaceId: the cloud routes to none of the relays or admins it would name as admitters"
        },
    )
