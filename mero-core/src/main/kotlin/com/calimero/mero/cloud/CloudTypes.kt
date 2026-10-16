package com.calimero.mero.cloud

import com.calimero.mero.account.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// The shapes of the cloud manager's session routes (`/api/auth/*`, `/api/cloud/me/*`), as
// mero-js `src/cloud/cloud-client.ts` maps them. Wire names are snake_case; these are the
// SDK's camelCase views, with absent fields read the way mero-js reads them.

/** Who a cloud session belongs to. */
@Serializable
data class CloudUser(
    val email: String,
    val name: String? = null,
    val picture: String? = null,
)

/**
 * A Calimero Cloud (MDMA) login session: mero-js `CloudSession` (Swift `CloudLoginSession`).
 *
 * Not the [com.calimero.mero.account.DelegatedSession] a device holds: that says which
 * account this device signs for, this says which cloud **login** is signed in.
 */
@Serializable
data class CloudLoginSession(
    val sessionToken: String,
    /**
     * Epoch **seconds**, as MDMA reports it — not milliseconds. `0` for a token handed in
     * without one (a stored token): "do not reason about it locally", never "expired".
     */
    val expiresAt: Long,
    val user: CloudUser,
) {
    override fun toString(): String = "CloudLoginSession(expiresAt=$expiresAt, user=$user)"
}

/**
 * Persists the [CloudLoginSession] a [CloudClient] holds, so a launch can resume it (sessions
 * are 7-day tokens with a rolling refresh). Back it with
 * [com.calimero.mero.account.EncryptedPrefsSecureStore] on a device: the token is a
 * credential for everything the login can do.
 */
class CloudSessionStore(
    private val store: SecureStore,
    private val key: String = DEFAULT_KEY,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The stored session, or null when none (or unreadable). */
    fun load(): CloudLoginSession? =
        runCatching { store.get(key) }.getOrNull()?.let { raw ->
            runCatching { json.decodeFromString(CloudLoginSession.serializer(), raw) }.getOrNull()
        }

    /** Store [session], or forget the stored one when null. */
    fun save(session: CloudLoginSession?) {
        if (session == null) store.remove(key) else store.put(key, json.encodeToString(CloudLoginSession.serializer(), session))
    }

    companion object {
        const val DEFAULT_KEY = "calimero.cloud.session"
    }
}

/** One namespace the signed-in user owns — mero-js `CloudNamespace`. */
data class CloudNamespace(
    val namespaceId: String,
    /** Always equal to [namespaceId]; MDMA keeps it for wire compatibility. */
    val groupId: String,
    /** `enabled`, `disabled` or `none` (HA never enabled). */
    val haStatus: String,
    val haEnabledAt: String? = null,
    val contexts: List<String> = emptyList(),
    /** `{current, limit}` as the cloud sent it, verbatim. */
    val fleetReplicas: JsonElement? = null,
)

/**
 * One attested node the cloud assigned to a namespace, described as a relay — mero-js
 * `CloudRelay`. [canExecute] is the cloud's own verdict (a `RelayTee` relays, a
 * `ReadOnlyTee` never does); only against a cloud too old to send it is it derived from
 * [authorshipReady], the URL and the executor account.
 */
data class CloudRelay(
    val peerId: String,
    /** Base URL to present intents to, or null when the cloud knows none yet. */
    val relayUrl: String?,
    /** The account a warrant for this relay must name as its `executor`, hex. */
    val executorAccount: String?,
    /**
     * The relay node's device signing key, hex — null today: the cloud does not publish it
     * yet (mdma #312). Read from `node_key` or `node_public_key`. `peerId` is never a
     * fallback: it is a libp2p identity, a different key.
     */
    val nodeKey: String?,
    /** `assigned` (admitted pending confirm) or `active`. */
    val status: String,
    /** Whether the relay holds `CAN_AUTHOR_ON_BEHALF` on the namespace. */
    val authorshipReady: Boolean,
    /** `RelayTee`, `ReadOnlyTee`, or null for a node too old to report one. */
    val teeRole: String?,
    val canExecute: Boolean,
    val lastSeenAt: String? = null,
    val confirmedAt: String? = null,
    /** `confirmed`, `pending`, `refused`, `absent` — or null from an older cloud. Passed through. */
    val admission: String? = null,
    /** The cloud's one-line explanation of [admission]. */
    val admissionHint: String? = null,
    /** The relay's own report of its latest attempt to join, or null when it reported none. */
    val join: CloudRelayJoin? = null,
)

/** A fleet node's account of its latest attempt to join a namespace — mero-js `CloudRelayJoin`. */
data class CloudRelayJoin(
    /** `admitted`, `refused`, `waiting` or `error`, as sent. */
    val state: String,
    val attempts: Int,
    /** Each directly-asked admitter that declined, in its own words — text to show, never to parse. */
    val refusals: List<CloudRelayRefusal>,
    val error: String?,
    /** ISO 8601 (UTC), or null. */
    val reportedAt: String?,
)

/** One admitter's refusal of a relay's join. */
data class CloudRelayRefusal(
    /** The admitter's libp2p peer id. */
    val peer: String,
    val reason: String,
)

/** One namespace of the caller's that a machine serves — mero-js `CloudMachineNamespace`. */
data class CloudMachineNamespace(
    val namespaceId: String,
    val status: String,
    val authorshipReady: Boolean,
    val teeRole: String?,
    /** Whether the machine's heartbeat for this namespace is recent. */
    val fresh: Boolean,
    val confirmedAt: String? = null,
    val lastSeenAt: String? = null,
)

/** One attested machine serving this account — mero-js `CloudMachine`. */
data class CloudMachine(
    val peerId: String,
    val relayUrl: String?,
    val executorAccount: String?,
    /** Whether it can write for the caller somewhere (the cloud's `can_execute`). */
    val canExecute: Boolean,
    /** Only the caller's own namespaces, even on a machine shared with others. */
    val namespaces: List<CloudMachineNamespace>,
)

/** A Calimero account linked to this cloud login. */
data class CloudLinkedAccount(
    /** 64 hex. */
    val accountId: String,
    val linkedAt: String? = null,
    /** Whether a recovery envelope is stored for it. */
    val hasRecoveryEnvelope: Boolean = false,
)

/** What [CloudClient.getMyAccounts] reports. */
data class CloudLinkedAccounts(
    val accounts: List<CloudLinkedAccount>,
    /** How many the plan allows; null means no limit. */
    val limit: Int?,
)

/** A single-use link challenge to sign with an account root. */
data class AccountLinkChallenge(
    /** Sign verbatim: an opaque sealed string. */
    val nonce: String,
    /** Epoch milliseconds, or null when the cloud did not say. */
    val expiresAtMs: Long?,
)

/** A link proof assembled by whoever holds the root, for [CloudClient.submitAccountLink]. */
data class AccountLinkProof(
    /** The account being claimed, 64 hex — the one [rootPublicKey] names. */
    val accountId: String,
    val rootPublicKey: String,
    val nonce: String,
    /** [com.calimero.mero.account.signAccountLink]'s output, standard base64. */
    val signature: String,
)

/** Where to send someone so a cloud login can consent to linking an account. */
data class AccountLinkHandoff(
    /** The portal URL to open (a Custom Tab, or the system browser). */
    val url: String,
    /** The account the consent will be for — show it to the person. */
    val accountId: String,
)

/** What came back on a handoff callback: a grant, a refusal, or neither. */
data class AccountLinkCallback(
    /** The grant to spend with [CloudClient.linkAccountWithGrant]. */
    val grant: String? = null,
    /** The account the grant is for, as the portal echoed it. */
    val accountId: String? = null,
    /** `denied` when the person cancelled; `unexpected_callback` for a grant this app never asked for. */
    val error: String? = null,
)

/** A single-use challenge for an account root to sign itself in with. */
data class AccountLoginChallenge(
    /** Sign verbatim; nonces live about two minutes. */
    val nonce: String,
    /** Epoch milliseconds. */
    val expiresAtMs: Long,
)

/**
 * A login proof assembled by whoever holds the root, for [CloudClient.submitAccountLogin].
 * No account id: the cloud derives the account from [rootPublicKey].
 */
data class AccountLoginProof(
    val rootPublicKey: String,
    val nonce: String,
    /** [com.calimero.mero.account.signAccountLogin]'s output, standard base64. */
    val signature: String,
)

/** The outcome of linking an account. */
data class CloudAccountLink(
    val accountId: String,
    val linkedAt: String? = null,
    /** True when this account was already linked to this login: a 200 no-op, not an error. */
    val alreadyLinked: Boolean = false,
)

/** What the cloud's `/enable-ha` and `/disable-ha` pages are opened for — mero-js `EnableHAOptions`. */
data class HaPageOptions(
    val groupId: String,
    val contextId: String,
    /** Where the page sends the person back; set it to a URL this app receives. */
    val redirectUrl: String? = null,
)
