package com.calimero.mero.cloud

import com.calimero.mero.Mero
import com.calimero.mero.MeroConfig
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.RelayConnection
import com.calimero.mero.account.SecureStore
import com.calimero.mero.account.resolveRootPair
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.crypto.resolveSigner
import com.calimero.mero.http.PlainJsonHttp
import com.calimero.mero.http.trimBase
import com.calimero.mero.relay.Audience
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.relay.IntentResult
import com.calimero.mero.relay.MemoryNonceSource
import com.calimero.mero.relay.NonceSource
import com.calimero.mero.relay.PersistedNonceSource
import com.calimero.mero.relay.RelayClient
import com.calimero.mero.relay.RelayLogin
import com.calimero.mero.relay.isLoopbackRelay
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.storage.TokenStore
import com.calimero.mero.tee.VerifyTransportQuote
import com.calimero.mero.tee.createAttestedSealedFetch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

/**
 * What [connectCloud] needs — mero-js `ConnectCloudOptions`.
 *
 * The cloud login: [sessionToken] (a stored one), [googleIdToken] (exchanged for one), or a
 * session already in [sessionStore]. The author: [authorAccount], its certificate
 * [authorProof] and the device key as [deviceSecret] **or** [signer] (exactly one).
 *
 * [seal] seals every call to the relay to its attested TEE (mero-js `seal`).
 */
@Suppress("LongParameterList")
class ConnectCloudOptions(
    /** The author's account, hex — whose writes these will be. */
    val authorAccount: String,
    /** The author's `AccountProof<DeviceCert>`, hex. */
    val authorProof: String,
    /** The author device's Ed25519 secret, hex. Never transmitted. */
    val deviceSecret: String? = null,
    /** The author device's signer — use instead of [deviceSecret]. */
    val signer: DeviceSigner? = null,
    val sessionToken: String? = null,
    val googleIdToken: String? = null,
    val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    /** Told about every new or rolling-refreshed session; see [CloudClient]. */
    val onSession: ((CloudLoginSession?) -> Unit)? = null,
    val sessionStore: CloudSessionStore? = null,
    /** Which namespace to write in. Optional only when the login owns exactly one. */
    val namespaceId: String? = null,
    /** Where nonces come from. Defaults to [nonceStore], else memory. */
    val nonces: NonceSource? = null,
    /**
     * Persists the default nonce sequence under `mero-js:warrant-nonce:<device public key>`
     * (mero-js's key), so a restart continues it instead of replaying it.
     */
    val nonceStore: SecureStore? = null,
    /** Seconds a minted warrant stays presentable. */
    val ttlSeconds: Long = 300,
    /**
     * Seal every call to the relay to its attested TEE, verified with this — for example
     * [com.calimero.mero.relay.DcapRelayKeyVerifier.quoteVerifier] (the relay's signed release),
     * or [com.calimero.mero.tee.createQuoteVerifier] with the images you trust. The warrant and the
     * method's arguments then reach only the relay's TD, not whatever terminates TLS in front of
     * it, and the relay is one this client checked itself rather than one the cloud vouched for.
     * Calls to the cloud are not sealed; they carry no intent. Without it, [sealRelays] decides.
     */
    val seal: VerifyTransportQuote? = null,
    /**
     * With no [seal], seal the relay anyway, to its signed release
     * ([com.calimero.mero.relay.DcapRelayKeyVerifier.signedReleaseVerifier]), as a delegated
     * session does: on by default (Swift `sealRelays`). A loopback relay is never sealed this way,
     * and a logged-in call the TD answers `sealed_route_unguarded` goes out plain. Pass false for
     * a plain test rig.
     */
    val sealRelays: Boolean = true,
    /**
     * The chosen relay node's device signing key, hex, if known out of band. With it,
     * [CloudConnection.client] can log in to the relay and observe (SSE, admin reads); left
     * unset, the key the cloud reported is used — null until mdma #312, so
     * [CloudConnection.canSubscribe] is false and writes still work.
     */
    val relayNodeKey: String? = null,
    /** Where the relay session's tokens live. */
    val tokenStore: TokenStore = MemoryTokenStore(),
    val clientName: String = "mero-kotlin-sdk",
    /** The audience the observing relay session is bound to. */
    val audience: Audience = Audience.Cli,
    val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
)

/**
 * What [connectCloudWithAccount] needs: [ConnectCloudOptions] without a cloud login, plus the
 * account root as [rootSecret] **or** [root]. [authorAccount] defaults to the account the
 * root derives — the only account it can open a session as.
 */
@Suppress("LongParameterList")
class ConnectCloudWithAccountOptions(
    val authorProof: String,
    val rootSecret: String? = null,
    val root: DeviceSigner? = null,
    val authorAccount: String? = null,
    val deviceSecret: String? = null,
    val signer: DeviceSigner? = null,
    val cloudBaseUrl: String = CloudClient.DEFAULT_BASE_URL,
    val onSession: ((CloudLoginSession?) -> Unit)? = null,
    val sessionStore: CloudSessionStore? = null,
    val namespaceId: String? = null,
    val nonces: NonceSource? = null,
    val nonceStore: SecureStore? = null,
    val ttlSeconds: Long = 300,
    /** See [ConnectCloudOptions.seal]. */
    val seal: VerifyTransportQuote? = null,
    /** See [ConnectCloudOptions.sealRelays]. */
    val sealRelays: Boolean = true,
    val relayNodeKey: String? = null,
    val tokenStore: TokenStore = MemoryTokenStore(),
    val clientName: String = "mero-kotlin-sdk",
    val audience: Audience = Audience.Cli,
    val httpClient: OkHttpClient = PlainJsonHttp.defaultClient(),
)

/**
 * A signed-in cloud login with a relay ready to write through — mero-js `CloudConnection`.
 *
 * [client] is the same [RelayConnection] a device sign-in ([com.calimero.mero.account.CloudAccount.connect])
 * hands back, built over the same [relay] (one nonce sequence, one warrant signer): its
 * `mero` reads and streams once the relay session is minted, which needs the relay's node
 * key ([canSubscribe]).
 */
class CloudConnection internal constructor(
    /** The cloud API, for namespaces, HA, linking and sign-out. */
    val cloud: CloudClient,
    /** The relay chosen for this namespace. */
    val relay: RelayClient,
    /** The namespace writes land in. */
    val namespaceId: String,
    val namespace: CloudNamespace,
    /** What the cloud reported about the chosen relay. */
    val relayInfo: CloudRelay,
    /** The transport-neutral connection over [relay]. */
    val client: RelayConnection,
    /** The relay node key the observing session is signed against, or null. */
    val relayNodeKey: String?,
) {
    /** Whether [client] can log in to the relay to observe; false while its node key is unknown. */
    val canSubscribe: Boolean get() = relayNodeKey != null

    /** Run one method in one context, on the author's behalf. */
    suspend fun execute(
        contextId: String,
        method: String,
        argsJson: JsonElement = JsonObject(emptyMap()),
    ): IntentResult = relay.execute(contextId, method, argsJson)
}

/**
 * Sign in to the cloud and return a connection that can write — mero-js `connectCloud`.
 *
 * Reads the login's namespaces, picks [ConnectCloudOptions.namespaceId] (or the only one),
 * reads its relays once and takes the first that can execute ([CloudClient.pickExecutingRelay]).
 * Intents then go straight to the relay; the cloud never sees a method or its arguments.
 *
 * @throws IllegalArgumentException with no cloud login to use, or not exactly one device key.
 * @throws CloudNotReadyException naming what the person must do: claim a namespace, choose
 *   between several, enable HA, wait for a relay, or have an admin admit relays (TEE admission
 *   policy `mode: relay`) or grant authorship.
 */
suspend fun connectCloud(options: ConnectCloudOptions): CloudConnection {
    require(
        !options.sessionToken.isNullOrEmpty() || !options.googleIdToken.isNullOrEmpty() || options.sessionStore?.load() != null,
    ) { "connectCloud needs either a sessionToken or a googleIdToken" }
    // Resolved before any request: finding out after signing in leaves a session minted for
    // a connection that was never going to be built.
    val signer = resolveSigner(options.deviceSecret, options.signer, "deviceSecret")
    val cloud =
        CloudClient(
            options.cloudBaseUrl,
            httpClient = options.httpClient,
            sessionToken = options.sessionToken,
            onSession = options.onSession,
            sessionStore = options.sessionStore,
        )
    val googleIdToken = options.googleIdToken
    if (options.sessionToken.isNullOrEmpty() && !googleIdToken.isNullOrEmpty()) cloud.signInWithGoogle(googleIdToken)

    val namespace = pickNamespace(cloud.getMyNamespaces(), options.namespaceId)
    // One read, for both the choice and the diagnostic: a second could disagree with the first.
    val relays = cloud.getNamespaceRelays(namespace.namespaceId)
    val relayInfo = CloudClient.pickExecutingRelay(relays) ?: throw CloudNotReadyException(explainNoRelay(namespace, relays))
    // Checked by pickExecutingRelay, the only thing that returns a relay.
    val relayUrl = checkNotNull(relayInfo.relayUrl).trimBase()

    val mero = Mero(MeroConfig(baseUrl = relayUrl, tokenStore = options.tokenStore))
    val nodeKey = (options.relayNodeKey ?: relayInfo.nodeKey)?.lowercase()
    val login = RelayLogin(clientName = options.clientName, audience = options.audience, httpClient = options.httpClient)
    val bearer = RelayConnection.bearerSource(mero, Mutex()) { nodeKey?.let { login.login(relayUrl, it, options.authorProof, signer) } }
    val relay =
        RelayClient(
            relayUrl = relayUrl,
            authorAccount = options.authorAccount,
            authorProof = options.authorProof,
            signer = signer,
            nonces = options.nonces ?: defaultNonceSource(options.nonceStore, signer.publicKey),
            ttlSeconds = options.ttlSeconds,
            executorAccount = relayInfo.executorAccount,
            session = bearer,
            // Only the relay is sealed: a sealed transport is bound to one node and refuses any
            // other URL, and the cloud calls above carry no intent.
            httpClient = relayHttpClient(options, relayUrl),
        )
    val session =
        DelegatedSession(
            account = options.authorAccount,
            device = runCatching { DeviceCert.parse(options.authorProof).device }.getOrDefault(""),
            credential = options.authorProof,
            relayUrl = relayUrl,
            executorAccount = relayInfo.executorAccount,
        )
    return CloudConnection(
        cloud = cloud,
        relay = relay,
        namespaceId = namespace.namespaceId,
        namespace = namespace,
        relayInfo = relayInfo,
        client = RelayConnection(session, mero, relay, bearer),
        relayNodeKey = nodeKey,
    )
}

/**
 * The same connection, opened by signing in **as the account** with its root instead of a
 * cloud login — mero-js `connectCloudWithAccount`. The session is minted here
 * ([CloudClient.signInWithAccount]) and everything after it is [connectCloud], unchanged; the
 * author defaults to the account the root derives.
 *
 * @throws IllegalArgumentException with not exactly one root, or not exactly one device key
 *   — the root is not a device key.
 */
suspend fun connectCloudWithAccount(options: ConnectCloudWithAccountOptions): CloudConnection {
    val signer = resolveSigner(options.deviceSecret, options.signer, "deviceSecret")
    val root = resolveRootPair(options.rootSecret, options.root)
    val cloud =
        CloudClient(
            options.cloudBaseUrl,
            httpClient = options.httpClient,
            onSession = options.onSession,
            sessionStore = options.sessionStore,
        )
    val session = cloud.signInWithAccount(root.signer)
    return connectCloud(
        ConnectCloudOptions(
            authorAccount = options.authorAccount ?: root.accountId,
            authorProof = options.authorProof,
            signer = signer,
            sessionToken = session.sessionToken,
            cloudBaseUrl = options.cloudBaseUrl,
            onSession = options.onSession,
            sessionStore = options.sessionStore,
            namespaceId = options.namespaceId,
            nonces = options.nonces,
            nonceStore = options.nonceStore,
            ttlSeconds = options.ttlSeconds,
            seal = options.seal,
            sealRelays = options.sealRelays,
            relayNodeKey = options.relayNodeKey,
            tokenStore = options.tokenStore,
            clientName = options.clientName,
            audience = options.audience,
            httpClient = options.httpClient,
        ),
    )
}

/** The client [connectCloud]'s relay writes go through: sealed with [ConnectCloudOptions.seal], else by default. */
internal fun relayHttpClient(
    options: ConnectCloudOptions,
    relayUrl: String,
): OkHttpClient {
    options.seal?.let { return createAttestedSealedFetch(relayUrl, it, httpClient = options.httpClient).client }
    if (!options.sealRelays || isLoopbackRelay(relayUrl)) return options.httpClient
    val verifier = DcapRelayKeyVerifier(httpClient = options.httpClient).signedReleaseVerifier(relayUrl)
    return createAttestedSealedFetch(relayUrl, verifier, httpClient = options.httpClient).also { it.plainWhenUnguarded = true }.client
}

private fun defaultNonceSource(
    store: SecureStore?,
    devicePublicKey: String,
): NonceSource = store?.let { PersistedNonceSource(it, "mero-js:warrant-nonce:$devicePublicKey") } ?: MemoryNonceSource(1u)

/** The one namespace to write in, or a [CloudNotReadyException] naming the actual ambiguity. */
internal fun pickNamespace(
    namespaces: List<CloudNamespace>,
    requested: String?,
): CloudNamespace {
    val ids = namespaces.joinToString(", ") { it.namespaceId }
    val problem =
        when {
            !requested.isNullOrEmpty() ->
                namespaces.firstOrNull { it.namespaceId == requested }?.let { return it }
                    ?: "this account owns no namespace $requested; it owns: ${ids.ifEmpty { "(none)" }}"
            namespaces.isEmpty() ->
                "this account owns no namespaces in the cloud: claim one first, then enable HA on it so a relay is assigned"
            namespaces.size > 1 -> "this account owns ${namespaces.size} namespaces — pass namespaceId to say which: $ids"
            else -> return namespaces[0]
        }
    throw CloudNotReadyException(problem)
}

/** Why no relay can write here — the states kept apart, because each is a different ask (mero-js `explainNoRelay`). */
internal fun explainNoRelay(
    namespace: CloudNamespace,
    relays: List<CloudRelay>,
): String {
    val ns = namespace.namespaceId
    if (relays.isEmpty()) {
        return if (namespace.haStatus == "enabled") {
            "namespace $ns has HA enabled but no relay has been assigned to it yet — retry shortly"
        } else {
            "namespace $ns has no relays: enable HA on it in the cloud to have an attested node assigned"
        }
    }
    val replicas = relays.filter { it.teeRole != null && it.teeRole != "RelayTee" }
    if (replicas.size == relays.size) {
        return "every node on namespace $ns is a read-only TEE replica (ReadOnlyTee), " +
            "which never relays delegated writes: an admin of the namespace must set its TEE admission " +
            "policy with mode: 'relay' — AdminClient.setTeeAdmissionPolicy(namespaceId, { ..., mode: 'relay' }) " +
            "— so fleet nodes are admitted as RelayTee"
    }
    val waitingOnGrant = relays.filter { it.teeRole == null && !it.authorshipReady }
    if (waitingOnGrant.isNotEmpty() && waitingOnGrant.size + replicas.size == relays.size) {
        val who = waitingOnGrant.joinToString(", ") { it.executorAccount ?: it.peerId }.ifEmpty { "the relay account" }
        return "every relay on namespace $ns is waiting for the authorship grant: " +
            "an admin of the namespace must grant CAN_AUTHOR_ON_BEHALF to $who — with AdminClient.grantAuthorship(namespaceId, account) " +
            "for these relays, and AdminClient.openToDelegatedExecution(namespaceId) so relays assigned later need no further op"
    }
    return "namespace $ns has ${relays.size} relay(s), but none is ready to relay yet (a URL, an executor account and a recent heartbeat) — retry shortly"
}
