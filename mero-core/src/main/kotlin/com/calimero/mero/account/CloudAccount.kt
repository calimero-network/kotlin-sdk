package com.calimero.mero.account

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.TokenData
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.cloud.CloudClient
import com.calimero.mero.cloud.RelayChoice
import com.calimero.mero.cloud.RoutingCredential
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.PersistedNonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayKeyVerifier
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.relay.TlsRelayKeyVerifier
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
        val relay =
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
        return RelayConnection(session, mero, relay, bearer)
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
