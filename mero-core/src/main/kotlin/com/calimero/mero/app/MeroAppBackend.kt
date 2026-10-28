package com.calimero.mero.app

import com.calimero.mero.admin.SignedGroupOpenInvitation
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement

/**
 * Everything an app's screens need from a signed-in Calimero account, and nothing a node would
 * offer: the phone holds no node. The account's relay reads, writes, creates and admits on its
 * behalf. (Swift `MeroKit.MeroAppBackend`.)
 *
 * The words are the protocol's: a *namespace* is what an app calls a workspace, team or space; a
 * *subgroup* is a group under it (a channel's, a board's); a *context* runs the app's contract in
 * a group.
 *
 * mero-compose's `DelegatedAppBackend` is the real one, and `MeroAppSession` builds it from the
 * signed-in client. View models take this interface, so a test hands them mero-testkit's
 * `InMemoryAppBackend` instead.
 */
@Suppress("TooManyFunctions")
interface MeroAppBackend {
    /** The account, 64 hex. */
    val account: String

    /** The contract in any context: relay query for reads, warrants for writes. [NoRelayContextContract] while no relay serves the account. */
    val contract: ContextContract

    /** Whether a relay serves this account. Without one only joining works. */
    val hasRelay: Boolean

    /** Whether [events] streams (the relay's Bearer session is up). When not, poll. */
    val canSubscribe: Boolean

    /** The namespaces this account is in, for this app's application only when the session knows it (an account is used across apps). */
    suspend fun listNamespaces(): List<AppNamespace>

    /** The subgroups of a namespace and the contexts in each. */
    suspend fun listSubgroups(namespaceId: String): List<AppSubgroup>

    /** The contexts directly in [groupId] (a namespace's root group, or a subgroup). */
    suspend fun listContexts(groupId: String): List<String>

    /** Whether the account is a member of the context's group, so its contract calls work. */
    suspend fun isMember(contextId: String): Boolean

    /** Join a context in an open subgroup: `MemberJoinedOpen` on its group, through the relay. */
    suspend fun joinContext(contextId: String)

    /** Found a namespace running this app through the relay, named [name]. */
    suspend fun foundNamespace(name: String): AppFoundedNamespace

    /** Create a subgroup under [namespaceId], open (anyone in the namespace may join) or restricted. Returns its group id. */
    suspend fun createSubgroup(
        namespaceId: String,
        name: String,
        isPublic: Boolean,
    ): String

    /** Create a context running [applicationId] in [groupId], initialised with [initArgs] (the contract's `init` arguments). Returns its context id. */
    suspend fun createContext(
        groupId: String,
        applicationId: String,
        name: String,
        initArgs: JsonElement,
    ): String

    /** Mint an invitation to a namespace, signed with the device key. */
    suspend fun createInvitation(namespaceId: String): SignedGroupOpenInvitation

    /**
     * Redeem an invitation: direct admission through an admitting relay. A relayless account gets
     * that relay; reconnect after (`MeroAppSession.didJoinNamespace()`).
     */
    suspend fun joinNamespace(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): AppJoinedNamespace

    /** Live events for one context. Completes at once when [canSubscribe] is false. */
    fun events(contextId: String): Flow<AppContextEvent>
}

/** A namespace this account is a member of. (Swift `AppNamespace`.) */
data class AppNamespace(
    /** The namespace id, 64 hex. */
    val id: String,
    /** Its name, when it has one. */
    val name: String?,
    /** The application it runs: what a new context in it is created with. */
    val applicationId: String,
    val memberCount: Int = 0,
)

/** A subgroup of a namespace, and the contexts in it. (Swift `AppSubgroup`.) */
data class AppSubgroup(
    val groupId: String,
    val name: String?,
    val contextIds: List<String>,
)

/** What founding a namespace came to. (Swift `AppFoundedNamespace`.) */
data class AppFoundedNamespace(
    val namespaceId: String,
    /** Whether the cloud agreed to host the namespace. Invitees find it only then. */
    val haEnabled: Boolean,
    /** Why [haEnabled] is false, in words a person can act on. */
    val haError: String? = null,
    /** Why the name could not be set, when the namespace exists anyway. */
    val nameError: String? = null,
)

/** What redeeming an invitation came to. (Swift `AppJoinedNamespace`.) */
data class AppJoinedNamespace(
    val namespaceId: String,
    /** NOT "joined": membership lands once peers apply the op. */
    val published: Boolean,
    /** The relay that admitted the account. */
    val relayUrl: String,
)

/** A live event in a context: something changed there. (Swift `AppContextEvent`.) */
data class AppContextEvent(
    val contextId: String,
    /** The node's event kind, e.g. `StateMutation`, `ExecutionEvent`, `Ephemeral`. */
    val kind: String,
)
