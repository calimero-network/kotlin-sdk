package com.calimero.mero.app

import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.relay.GovernanceMemberRole
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
 *
 * The names, blobs, leaving and members calls have defaults that throw
 * [UnsupportedOperationException] naming the call, so a backend of an app's own, written before
 * them, still compiles. `DelegatedAppBackend` and `InMemoryAppBackend` implement every one.
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

    // ---- names ---------------------------------------------------------------------------

    /** The contexts directly in [groupId], with their names (context metadata): [listContexts] with what a screen shows. */
    suspend fun listContextEntries(groupId: String): List<AppContextEntry> = unsupported("listContextEntries")

    /** A group's metadata (a namespace's or a subgroup's), or null if never set. */
    suspend fun groupMetadata(groupId: String): AppMetadata? = unsupported("groupMetadata")

    /** A context's metadata in its group, or null if never set. */
    suspend fun contextMetadata(
        groupId: String,
        contextId: String,
    ): AppMetadata? = unsupported("contextMetadata")

    /** Rename a namespace or subgroup (`GroupMetadataSet`), keeping its metadata's `data`. */
    suspend fun setGroupName(
        groupId: String,
        name: String,
    ): Unit = unsupported("setGroupName")

    /** Rename a context in its group (`ContextMetadataSet`), keeping its metadata's `data`. */
    suspend fun setContextName(
        groupId: String,
        contextId: String,
        name: String,
    ): Unit = unsupported("setContextName")

    // ---- blobs ---------------------------------------------------------------------------

    /** Store [data] on the relay against [contextId], a context the account is a member of; the relay announces it to the context's other members. */
    suspend fun uploadBlob(
        contextId: String,
        data: ByteArray,
    ): AppBlob = unsupported("uploadBlob")

    /** A blob's bytes, read through [contextId] (the relay checks the account is a member and that the blob belongs to that context). */
    suspend fun getBlob(
        blobId: String,
        contextId: String,
    ): ByteArray = unsupported("getBlob")

    // ---- leaving and deleting ------------------------------------------------------------

    /** Leave a namespace (`MemberLeft` on it): every subgroup and context in it with it. */
    suspend fun leaveNamespace(namespaceId: String): Unit = unsupported("leaveNamespace")

    /**
     * Leave a subgroup (`MemberLeft` on it), and with it the contexts in it. An account is a member
     * of a context through its group, so this is also how it leaves a context.
     */
    suspend fun leaveSubgroup(groupId: String): Unit = unsupported("leaveSubgroup")

    /** Delete a subgroup and what is in it (`GroupDeleted`, a root op on its namespace). The relay refuses it unless the account may. */
    suspend fun deleteSubgroup(groupId: String): Unit = unsupported("deleteSubgroup")

    /** Take a context out of its group (`ContextDetached`): it stops being listed and served there. The relay refuses it unless the account may. */
    suspend fun removeContext(
        groupId: String,
        contextId: String,
    ): Unit = unsupported("removeContext")

    // ---- members -------------------------------------------------------------------------

    /** The members of a namespace or subgroup, with their roles and names (member metadata): a namespace's are the app's display names. */
    suspend fun listMembers(groupId: String): List<AppMember> = unsupported("listMembers")

    /** Set a member's role (`MemberRoleSet`). Admins only. */
    suspend fun setMemberRole(
        groupId: String,
        identity: String,
        role: GovernanceMemberRole,
    ): Unit = unsupported("setMemberRole")

    /** Remove a member (`MemberRemoved`). Admins only. */
    suspend fun removeMember(
        groupId: String,
        identity: String,
    ): Unit = unsupported("removeMember")

    /** Set the account's own name in a namespace or subgroup (`MemberMetadataSet` on itself), keeping its metadata's `data`. */
    suspend fun setMemberName(
        groupId: String,
        name: String,
    ): Unit = unsupported("setMemberName")
}

private fun unsupported(method: String): Nothing = throw UnsupportedOperationException("$method is not supported by this MeroAppBackend")

/** A context in a group, with its name. (Swift `AppContextEntry`.) */
data class AppContextEntry(
    /** The context id. */
    val id: String,
    /** Its name (context metadata), when it has one. */
    val name: String?,
)

/** A group's, member's or context's metadata record. (Swift `AppMetadata`.) */
data class AppMetadata(
    val name: String?,
    /** The record's other fields. Setting a name keeps them. */
    val data: Map<String, String> = emptyMap(),
)

/** A member of a namespace or subgroup. (Swift `AppMember`.) */
data class AppMember(
    /** The member's account, 64 hex. */
    val identity: String,
    /** `Admin`, `Member` or `ReadOnly`; a relay is `RelayTee`. */
    val role: String,
    /** The name the member set (member metadata), when it set one. */
    val name: String? = null,
) {
    val isAdmin: Boolean get() = role == GovernanceMemberRole.Admin.name
}

/** A blob stored through the relay. (Swift `AppBlob`.) */
data class AppBlob(
    val blobId: String,
    val size: Long,
)

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
