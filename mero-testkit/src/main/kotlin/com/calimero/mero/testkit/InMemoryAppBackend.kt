package com.calimero.mero.testkit

import com.calimero.mero.account.NoRelayException
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.app.AppContextEvent
import com.calimero.mero.app.AppFoundedNamespace
import com.calimero.mero.app.AppJoinedNamespace
import com.calimero.mero.app.AppNamespace
import com.calimero.mero.app.AppSubgroup
import com.calimero.mero.app.ContextContract
import com.calimero.mero.app.MeroAppBackend
import com.calimero.mero.app.NoRelayContextContract
import com.calimero.mero.crypto.Hex
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// In-memory stand-ins for an app's delegated session, so an app's view models are unit-testable
// with no relay, no cloud and no wallet. (Swift MeroKitTestSupport `InMemoryAppBackend` /
// `RecordingContextContract`.)

/** One contract call a [RecordingContextContract] saw. */
data class RecordedContractCall(
    val path: Path,
    val contextId: String,
    val method: String,
    val args: JsonElement,
) {
    enum class Path { QUERY, EXECUTE }
}

/**
 * A [ContextContract] that records every call and answers from [handler] (`null` by default).
 * Script the app's contract in the handler, by method.
 */
class RecordingContextContract(
    @Volatile var handler: (RecordedContractCall) -> JsonElement? = { JsonNull },
) : ContextContract {
    private val recorded = mutableListOf<RecordedContractCall>()

    /** Every call so far, in order. */
    val calls: List<RecordedContractCall> get() = synchronized(recorded) { recorded.toList() }

    /** The calls to [method]. */
    fun calls(method: String): List<RecordedContractCall> = calls.filter { it.method == method }

    fun reset() = synchronized(recorded) { recorded.clear() }

    override suspend fun query(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = record(RecordedContractCall(RecordedContractCall.Path.QUERY, contextId, method, args))

    override suspend fun execute(
        contextId: String,
        method: String,
        args: JsonElement,
    ): JsonElement? = record(RecordedContractCall(RecordedContractCall.Path.EXECUTE, contextId, method, args))

    private fun record(call: RecordedContractCall): JsonElement? {
        synchronized(recorded) { recorded += call }
        return handler(call)
    }
}

/**
 * A [MeroAppBackend] that keeps its namespaces, subgroups, contexts and memberships in memory,
 * and whose contract is a [RecordingContextContract].
 *
 * It behaves like the relay as far as a view model can tell: founding adds a namespace, creating a
 * subgroup or a context adds it (the creator is a member of a context it creates), joining a
 * context makes the account a member, and redeeming an invitation adds the namespace and gives a
 * relayless account its relay. Seed state with [namespaces], [subgroups] and [members]; make every
 * admin-side call fail with [failure]; push live events with [emit] (to streams being collected:
 * [subscriberCount] says how many). [log] lists what was asked, in order.
 */
@Suppress("TooManyFunctions")
class InMemoryAppBackend(
    override val account: String = "ab".repeat(32),
    val recording: RecordingContextContract = RecordingContextContract(),
) : MeroAppBackend {
    private val lock = Any()
    private var next = 0
    private val streams = mutableListOf<Pair<String, SendChannel<AppContextEvent>>>()
    private val asked = mutableListOf<String>()
    private val subgroupsCreatedOpen = mutableSetOf<String>()
    private val contextInitArgs = mutableMapOf<String, JsonElement>()

    @Volatile override var hasRelay: Boolean = true

    @Volatile override var canSubscribe: Boolean = true

    /** The application a founded or joined namespace runs. */
    @Volatile var applicationId: String = "ap".repeat(32)

    /** Thrown by every call but [events] and the contract while set. */
    @Volatile var failure: Throwable? = null

    /** What [foundNamespace] reports about hosting. */
    @Volatile var haEnabled: Boolean = true

    /** The relay [joinNamespace] reports. */
    @Volatile var joinRelayUrl: String = "https://relay.test"

    /** The namespaces the account is in. */
    var namespaces: List<AppNamespace> = emptyList()
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    /** Subgroups by namespace id. */
    var subgroups: Map<String, List<AppSubgroup>> = emptyMap()
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    /** Contexts directly in a group that is not a listed subgroup (a namespace's root), by group id. */
    var rootContexts: Map<String, List<String>> = emptyMap()
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    /** The contexts the account is a member of. */
    var members: Set<String> = emptySet()
        get() = synchronized(lock) { field }
        set(value) = synchronized(lock) { field = value }

    /** The subgroups created open. */
    val publicSubgroups: Set<String> get() = synchronized(lock) { subgroupsCreatedOpen.toSet() }

    /** The `initArgs` each created context was given, by context id. */
    val initArgs: Map<String, JsonElement> get() = synchronized(lock) { contextInitArgs.toMap() }

    /** What was asked, in order: `"foundNamespace Team"`, `"joinContext <id>"`, ... */
    val log: List<String> get() = synchronized(lock) { asked.toList() }

    /** How many [events] streams are being collected. */
    val subscriberCount: Int get() = synchronized(lock) { streams.size }

    /** [recording], or [NoRelayContextContract] while [hasRelay] is false. */
    override val contract: ContextContract get() = if (hasRelay) recording else NoRelayContextContract

    // ---- MeroAppBackend ------------------------------------------------------------------

    override suspend fun listNamespaces(): List<AppNamespace> = step("listNamespaces") { namespaces }

    override suspend fun listSubgroups(namespaceId: String): List<AppSubgroup> = step("listSubgroups $namespaceId") { subgroups[namespaceId].orEmpty() }

    override suspend fun listContexts(groupId: String): List<String> =
        step("listContexts $groupId") {
            subgroups.values
                .flatten()
                .firstOrNull { it.groupId == groupId }
                ?.contextIds ?: rootContexts[groupId].orEmpty()
        }

    override suspend fun isMember(contextId: String): Boolean = step("isMember $contextId") { contextId in members }

    override suspend fun joinContext(contextId: String) = step("joinContext $contextId") { members = members + contextId }

    override suspend fun foundNamespace(name: String): AppFoundedNamespace =
        step("foundNamespace $name") {
            if (!hasRelay) throw NoRelayException("foundNamespace")
            val id = mint("aa")
            namespaces = namespaces + AppNamespace(id, name, applicationId, memberCount = 1)
            AppFoundedNamespace(id, haEnabled, if (haEnabled) null else "not hosted (fake)")
        }

    override suspend fun createSubgroup(
        namespaceId: String,
        name: String,
        isPublic: Boolean,
    ): String =
        step("createSubgroup $namespaceId $name ${if (isPublic) "open" else "restricted"}") {
            val id = mint("bb")
            subgroups = subgroups + (namespaceId to subgroups[namespaceId].orEmpty() + AppSubgroup(id, name, emptyList()))
            if (isPublic) subgroupsCreatedOpen += id
            id
        }

    override suspend fun createContext(
        groupId: String,
        applicationId: String,
        name: String,
        initArgs: JsonElement,
    ): String =
        step("createContext $groupId $applicationId $name") {
            val id = mint("cc")
            var placed = false
            subgroups =
                subgroups.mapValues { (_, list) ->
                    list.map { if (it.groupId == groupId) it.copy(contextIds = it.contextIds + id).also { placed = true } else it }
                }
            if (!placed) rootContexts = rootContexts + (groupId to rootContexts[groupId].orEmpty() + id)
            members = members + id
            contextInitArgs[id] = initArgs
            id
        }

    override suspend fun createInvitation(namespaceId: String): SignedGroupOpenInvitation = step("createInvitation $namespaceId") { invitation(namespaceId) }

    override suspend fun joinNamespace(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
    ): AppJoinedNamespace =
        step("joinNamespace $namespaceId") {
            if (namespaces.none { it.id == namespaceId }) namespaces = namespaces + AppNamespace(namespaceId, null, applicationId)
            hasRelay = true
            AppJoinedNamespace(namespaceId, published = true, relayUrl = joinRelayUrl)
        }

    override fun events(contextId: String): Flow<AppContextEvent> {
        if (!canSubscribe) return emptyFlow()
        return callbackFlow {
            val entry = contextId to channel
            synchronized(lock) { streams += entry }
            awaitClose { synchronized(lock) { streams -= entry } }
        }
    }

    // ---- driving it ----------------------------------------------------------------------

    /** Deliver [event] to every [events] stream being collected for its context. */
    fun emit(event: AppContextEvent) {
        val targets = synchronized(lock) { streams.filter { it.first == event.contextId }.map { it.second } }
        targets.forEach { it.trySend(event) }
    }

    /** End every [events] stream being collected, with [error] when given. */
    fun finishEvents(error: Throwable? = null) {
        val targets = synchronized(lock) { streams.map { it.second } }
        targets.forEach { it.close(error) }
    }

    // ---- plumbing --------------------------------------------------------------------------

    private fun mint(prefix: String): String {
        next += 1
        val digits = next.toString(HEX_RADIX)
        return prefix + "0".repeat(ID_LENGTH - prefix.length - digits.length) + digits
    }

    private fun <T> step(
        entry: String,
        body: () -> T,
    ): T =
        synchronized(lock) {
            asked += entry
            failure?.let { throw it }
            body()
        }

    companion object {
        private const val HEX_RADIX = 16
        private const val ID_LENGTH = 64
        private const val NAMESPACE_BYTES = 32
        private const val NEVER_EXPIRES = 4_102_444_800L

        /** An invitation to [namespaceId] shaped like a real one (its signature is not). */
        fun invitation(namespaceId: String): SignedGroupOpenInvitation {
            val group =
                runCatching { Hex.decode(namespaceId, "namespaceId", NAMESPACE_BYTES).map { it.toInt() and 0xff } }
                    .getOrDefault(List(NAMESPACE_BYTES) { 0 })

            fun bytes(values: List<Int>) = JsonArray(values.map { JsonPrimitive(it) })
            return SignedGroupOpenInvitation(
                buildJsonObject {
                    put(
                        "invitation",
                        buildJsonObject {
                            put("inviter_identity", bytes(List(NAMESPACE_BYTES) { 1 }))
                            put("group_id", bytes(group))
                            put("expiration_timestamp", NEVER_EXPIRES)
                            put("secret_salt", bytes(List(NAMESPACE_BYTES) { 2 }))
                        },
                    )
                    put("inviter_signature", "00".repeat(64))
                },
            )
        }
    }
}
