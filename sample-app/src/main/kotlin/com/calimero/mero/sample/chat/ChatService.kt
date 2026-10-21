package com.calimero.mero.sample.chat

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calimero.mero.Mero
import com.calimero.mero.account.ApplicationIds
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.compose.MeroAuthState
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.invite.InviteCodec
import com.calimero.mero.invite.InviteLink
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.CreateContextInput
import com.calimero.mero.sample.app.SampleLog
import com.calimero.mero.sse.ContextEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.util.zip.Inflater

private val chatJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

// ---- Wire models (com.calimero.chat contract, snake_case) ------------------

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    @SerialName("sender_username") val senderUsername: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
    val deleted: Boolean? = null,
)

/** One entry of `get_profiles`: the name a member registered with `set_profile`. */
@Serializable
data class ChatProfile(
    val identity: String,
    val username: String = "",
)

@Serializable
data class ChatMessagePage(
    @SerialName("total_count") val totalCount: Int = 0,
    val messages: List<ChatMessage> = emptyList(),
    @SerialName("start_position") val startPosition: Int = 0,
)

@Serializable
data class ChatContextInfo(
    val name: String,
    @SerialName("context_type") val contextType: String,
    val description: String = "",
)

// ---- View models -----------------------------------------------------------

/** A space: a namespace running mero-chat. */
data class ChatSpace(
    val id: String,
    val name: String,
    /** The namespace's target application (what a new channel runs). */
    val applicationId: String = "",
)

/** A channel: a context in a space. */
data class ChatChannel(
    val contextId: String,
    val groupId: String?,
    val name: String,
    val kind: String,
    /** The member identity this node executes as (node backend only; a relay writes as the account). */
    val executorId: String = "",
)

/**
 * Shareable invitation payload — bundles the namespaceId so joining needs no base58 decode of the
 * invitation's raw group-id bytes. Mirrors the Swift sample's `ChatInvite`.
 */
@Serializable
data class ChatInvite(
    val namespaceId: String,
    val spaceName: String,
    val invitation: SignedGroupOpenInvitation,
) {
    /**
     * Compact single-line invite code, in the format the rest of the fleet uses:
     * `base58(deflate(JSON))` via [InviteCodec].
     *
     * This used to be `Deflater()` + base64. Two problems with that: `Deflater()`
     * defaults to a **zlib-wrapped** stream rather than raw DEFLATE, and base58
     * is what mero-chat, mero-blocks, merraria, mero-stream and the web apps
     * actually emit — so a code from here could not be redeemed in any of them,
     * which is the one thing an example demonstrating "invite and join" has to
     * get right. It also deflated in a single call into a fixed-size buffer,
     * which silently truncates a payload larger than the guess.
     */
    fun encoded(): String = InviteCodec.encode(chatJson.encodeToString(this))

    /**
     * The shareable link for this invite — what you would actually send someone.
     * Opens the desktop app where installed and the web build otherwise.
     */
    fun shareableLink(): String = InviteLink.invitation(encoded(), ChatService.PACKAGE_NAME)

    companion object {
        /**
         * Decode an invite code, or a link containing one.
         *
         * Accepts the shared format, the legacy zlib+base64 form this example
         * emitted before, and raw JSON — so codes already copied out of a running
         * build keep working.
         */
        fun decode(code: String): ChatInvite? {
            val token = InviteLink.tokenFromPasted(code) ?: return null

            InviteCodec.decode(token)?.let { json ->
                runCatching { return chatJson.decodeFromString<ChatInvite>(json) }
            }

            // Legacy: zlib-wrapped deflate + base64, as this example used to emit.
            runCatching {
                val compressed = Base64.decode(token, Base64.DEFAULT)
                val inflater = Inflater()
                inflater.setInput(compressed)
                val buffer = ByteArray(compressed.size * INFLATE_FACTOR + BUFFER_PAD)
                val size = inflater.inflate(buffer)
                inflater.end()
                return chatJson.decodeFromString<ChatInvite>(String(buffer.copyOf(size)))
            }
            return runCatching { chatJson.decodeFromString<ChatInvite>(token) }.getOrNull()
        }

        private const val BUFFER_PAD = 64
        private const val INFLATE_FACTOR = 8
    }
}

/** Where [ChatService.spaces] stands. Mirrors the Swift sample's `SpacesLoad`. */
enum class SpacesLoad {
    /** Not listed yet: the first listing, or the session it waits for, is on its way. */
    PENDING,

    /** Listed: [ChatService.spaces] is the answer, empty or not. */
    LOADED,

    /** The listing failed, or the session it needs could not be had. */
    FAILED,
}

// ---- ChatService -----------------------------------------------------------

/**
 * A native mero-chat frontend: spaces (namespaces), channels (contexts), messages (contract
 * calls), invite and join — on the same `com.calimero.chat` contract as mero-chat. Mirrors the
 * Swift sample's `ChatService`.
 *
 * Two backends, picked from the session:
 * - **relay** (a Calimero Cloud session): reads go through the relay's query route, writes are
 *   warrants this device signs; admin listings use the relay's Bearer session; a space is founded
 *   ([MeroClient.foundNamespace]) and joined ([MeroClient.joinWithInvitation]) as the account.
 * - **node** (signed in to a node directly, e.g. a local `merod`): mero-chat is installed from
 *   the app registry, and everything runs over the node's admin API and JSON-RPC.
 */
@Suppress("TooManyFunctions")
class ChatService(
    private val client: MeroClient,
    displayName: String? = null,
) {
    /** Signed in to a node directly rather than through a relay. */
    val isNode: Boolean = client.state.value.isNodeSession
    val isRelay: Boolean get() = !isNode

    /** mero-chat's application id on the node; for a relay the namespace carries it, so "relay". */
    var appId by mutableStateOf<String?>(if (isNode) null else "relay")
        private set
    var spaces by mutableStateOf<List<ChatSpace>>(emptyList())
        private set

    /**
     * Where the spaces listing stands. Only [SpacesLoad.LOADED] with no spaces means "no spaces
     * yet": a listing still waiting for the session, or one that failed, is not an empty one,
     * and offering to create a space then would make a duplicate of one that exists.
     */
    var spacesLoad by mutableStateOf(SpacesLoad.PENDING)
        private set

    /** Bumped by every [loadSpaces]: only the newest one's answer is applied. */
    private var spacesGeneration = 0

    /** The failure [loadSpaces] last reported, cleared from [status] once a listing answers. */
    private var spacesError: String? = null
    var channels by mutableStateOf<List<ChatChannel>>(emptyList())
        private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set

    /** Whether [status] reports a failure (drives its colour). */
    var statusIsError by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set

    /** Why the last send failed, shown above the composer; cleared by the next successful send. */
    var sendError by mutableStateOf<String?>(null)
        private set

    /** Where the Chat tab is: kept here so switching tabs and back lands in the same place. */
    var openSpace by mutableStateOf<ChatSpace?>(null)
    var openChannel by mutableStateOf<ChatChannel?>(null)

    private var autoJoinClaimed = false

    /** True the first time only: the launch-time invite (the e2e hook) is redeemed once. */
    fun claimAutoJoin(): Boolean = !autoJoinClaimed.also { autoJoinClaimed = true }

    /** The display name registered with `set_profile`. */
    val username: String =
        displayName?.takeIf { it.isNotEmpty() }
            ?: client.state.value.username
            ?: client.state.value.account
                ?.take(ACCOUNT_PREFIX)
                ?.let { "Account $it" }
            ?: "mobile"

    private val mero: Mero? get() = client.mero

    /** The session the chat runs on; the Chat tab re-lists spaces when it changes. */
    val session: StateFlow<MeroAuthState> get() = client.state

    /** Live events for a channel's context, or nothing while reads are off. */
    fun eventStream(channel: ChatChannel): Flow<ContextEvent> {
        val m = mero ?: return emptyFlow()
        if (isRelay && !client.state.value.sessionReady) return emptyFlow()
        return m.events(listOf(channel.contextId))
    }

    // ---- install (node backend) --------------------------------------------

    /** Install mero-chat's newest registry version on the node. */
    suspend fun setup() {
        val m = mero
        if (!isNode || m == null) return
        run("Installing $PACKAGE_NAME") {
            val versions = m.admin.getRegistryVersions(REGISTRY_URL, PACKAGE_NAME)
            val version = versions.firstOrNull()
            if (version == null) {
                say("No registry versions found for $PACKAGE_NAME", error = true)
                return@run
            }
            appId = m.admin.installFromRegistry(PACKAGE_NAME, version).applicationId
            say("Installed $PACKAGE_NAME@$version")
            loadSpaces()
        }
    }

    /** Adopt mero-chat's app id if it is already installed on the node. */
    suspend fun detectInstalled() {
        val m = mero
        if (appId != null || !isNode || m == null) return
        val installed = runCatching { m.admin.listApplications().apps }.getOrNull().orEmpty()
        installed.firstOrNull { it.packageName == PACKAGE_NAME }?.let {
            appId = it.id
            loadSpaces()
        }
    }

    // ---- spaces ------------------------------------------------------------

    /**
     * List the spaces. A relay lists only once its session is minted: before that the relay's
     * admin API would be asked without a token. While the session is still connecting the
     * listing stays [SpacesLoad.PENDING] (the Chat tab calls this again when the session
     * changes); a session that could not be minted, or a listing that failed, is
     * [SpacesLoad.FAILED], never an empty list. An account no relay serves yet is new: it is
     * a member of nothing, so that is an empty listing, not a failed one.
     */
    suspend fun loadSpaces() {
        val generation = ++spacesGeneration
        val m = mero
        val auth = client.state.value
        if (auth.signedInWithoutRelay && !auth.isLoading) {
            spaces = emptyList()
            spacesLoad = SpacesLoad.LOADED
            spacesError = null
            return
        }
        if (m == null || (isRelay && !auth.sessionReady)) {
            if (isRelay && auth.isLoading) {
                if (spaces.isEmpty()) spacesLoad = SpacesLoad.PENDING
                return
            }
            failSpaces(auth.relayNote ?: "Reads are off until the relay session is established. Writes still work.")
            return
        }
        try {
            val listed = m.admin.listNamespaces().map { ChatSpace(it.namespaceId, it.name ?: "Space", it.targetApplicationId) }
            if (generation != spacesGeneration) return
            spaces = listed
            spacesLoad = SpacesLoad.LOADED
            if (spacesError != null && status == spacesError) say("")
            spacesError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != spacesGeneration) return
            // The spaces already shown stay; the failure is reported, not read as "none".
            failSpaces("Couldn't load spaces: ${short(e)}")
        }
    }

    private fun failSpaces(message: String) {
        spacesLoad = SpacesLoad.FAILED
        spacesError = message
        say(message, error = true)
    }

    /** A node creates spaces; a Cloud account founds them through its relay. */
    val canCreateSpaces: Boolean get() = isNode || client.relay != null

    /** Offer "New space" only once the listing answered, so a failed one can't lead to a duplicate. */
    val offerNewSpace: Boolean get() = canCreateSpaces && spacesLoad == SpacesLoad.LOADED

    suspend fun createSpace(name: String) {
        if (isRelay) {
            run("Creating space $name") {
                // Founds the namespace as the account: mero-chat from the registry,
                // mero-react's default capabilities, the name, then HA.
                val app = ApplicationIds.resolveFromRegistry(PACKAGE_NAME)
                val founded =
                    client.foundNamespace(
                        application = ApplicationTarget(app.applicationId, app.packageName, app.version),
                        name = name,
                    )
                say(
                    if (founded.haEnabled) {
                        "Space created: ${founded.namespaceId.take(ID_PREFIX)}"
                    } else {
                        "Space created: ${founded.namespaceId.take(ID_PREFIX)}. Not hosted in the cloud yet: " +
                            (founded.haError ?: "unknown reason")
                    },
                )
                loadSpaces()
            }
            return
        }
        val m = mero ?: return
        val app = appId ?: return
        run("Creating space $name") {
            val resp = m.admin.createNamespace(CreateNamespaceRequest(applicationId = app, name = name))
            say("Space created: ${resp.namespaceId.take(ID_PREFIX)}")
            loadSpaces()
        }
    }

    // ---- channels ----------------------------------------------------------

    suspend fun loadChannels(space: ChatSpace) {
        val m = mero ?: return
        channels = emptyList()
        val out =
            try {
                m.admin.listNamespaceGroups(space.id).mapNotNull { sg ->
                    m.admin
                        .listGroupContexts(sg.groupId)
                        .firstOrNull()
                        ?.let { ctx -> channel(ctx.contextId, sg.groupId, sg.name ?: ctx.name) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isRelay) {
                    relayChannels(m, space)
                } else {
                    say("Couldn't load channels: ${short(e)}", error = true)
                    emptyList()
                }
            }
        channels = out.filter { it.kind != "Dm" }
        if (channels.isEmpty() && isNode) diagnoseEmptySpace()
    }

    /** An account session lists its own contexts; take the ones in this space. */
    private suspend fun relayChannels(
        m: Mero,
        space: ChatSpace,
    ): List<ChatChannel> =
        runCatching { m.admin.getContexts().contexts }
            .getOrNull()
            .orEmpty()
            .filter { it.groupId == null || it.groupId == space.id || it.applicationId == space.applicationId }
            .mapNotNull { channel(it.id, it.groupId ?: space.id, null) }

    private suspend fun channel(
        contextId: String,
        groupId: String,
        fallback: String?,
    ): ChatChannel? {
        var executor = ""
        val m = mero
        if (isNode && m != null) {
            executor = ownedIdentity(m, contextId)
            if (executor.isEmpty()) {
                runCatching { m.admin.joinContext(contextId) }
                runCatching { m.admin.syncContext(contextId) }
                executor = ownedIdentity(m, contextId)
            }
        }
        val info = runCatching { read<ChatContextInfo>(contextId, "get_info") }.getOrNull()
        return ChatChannel(contextId, groupId, info?.name ?: fallback ?: "channel", info?.contextType ?: "Channel", executor)
    }

    private suspend fun ownedIdentity(
        m: Mero,
        contextId: String,
    ): String =
        runCatching {
            m.admin
                .getContextIdentitiesOwned(contextId)
                .identities
                .firstOrNull()
        }.getOrNull().orEmpty()

    /** Why a joined space shows no channels (node backend). */
    private suspend fun diagnoseEmptySpace() {
        val peers = runCatching { mero?.admin?.getPeersCount()?.count }.getOrNull()
        if (peers == 0) {
            say("Joined, but this node has no peers, so it can't sync the channel from the inviter.", error = true)
        } else {
            say("No channels yet. They may still be syncing; pull to refresh.")
        }
    }

    suspend fun createChannel(
        space: ChatSpace,
        name: String,
        open: Boolean = true,
    ) {
        if (isRelay) {
            val relay = client.relay
            if (relay == null) {
                say("No relay serves this account yet.", error = true)
                return
            }
            run("Creating #$name") {
                val created =
                    relay.createContext(
                        CreateContextInput(groupId = space.id, applicationId = space.applicationId, name = name, initArgs = initArgs(name)),
                    )
                runCatching { relay.execute(created.contextId, "set_profile", profileArgs()) }
                say("Channel #$name created")
                loadChannels(space)
            }
            return
        }
        val m = mero ?: return
        val app = appId
        if (app == null) {
            say("Install the app first.", error = true)
            return
        }
        run("Creating #$name") {
            val sg = m.admin.createGroupInNamespace(space.id, CreateGroupInNamespaceRequest(groupName = name))
            m.admin.setSubgroupVisibility(sg.groupId, SetSubgroupVisibilityRequest(if (open) "open" else "restricted"))
            val bytes = chatJson.encodeToString(JsonObject.serializer(), initArgs(name)).encodeToByteArray()
            val ctx =
                m.admin.createContext(
                    CreateContextRequest(
                        applicationId = app,
                        groupId = sg.groupId,
                        initializationParams = bytes.map { it.toInt() and BYTE_MASK },
                        name = name,
                    ),
                )
            runCatching { write(ctx.contextId, "set_profile", profileArgs()) }
            say("Channel #$name created")
            loadChannels(space)
        }
    }

    // ---- messages ----------------------------------------------------------

    suspend fun loadMessages(channel: ChatChannel) {
        if (isNode && channel.executorId.isEmpty()) return
        try {
            val args =
                buildJsonObject {
                    put("parent_message", JsonNull)
                    put("limit", MESSAGE_PAGE)
                    put("offset", 0)
                    put("search_term", JsonNull)
                }
            val page = read<ChatMessagePage>(channel.contextId, "get_messages", args)
            // Messages carry the sender's identity only; names come from `get_profiles`.
            val names =
                runCatching { read<List<ChatProfile>>(channel.contextId, "get_profiles") }
                    .getOrNull()
                    .orEmpty()
                    .associate { it.identity to it.username }
            messages =
                page.messages
                    .filter { it.deleted != true }
                    .map { m -> if (m.senderUsername.isEmpty()) m.copy(senderUsername = names[m.sender].orEmpty()) else m }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Couldn't load messages: ${short(e)}", error = true)
        }
    }

    suspend fun sendMessage(
        channel: ChatChannel,
        text: String,
    ) {
        if (text.isEmpty() || (isNode && channel.executorId.isEmpty())) return
        try {
            val args =
                buildJsonObject {
                    put("message", text)
                    put("mentions", buildJsonArray { })
                    put("mentions_usernames", buildJsonArray { })
                    put("parent_message", JsonNull)
                    // mero-chat (3.1.29+) stores message timestamps in seconds.
                    put("timestamp", System.currentTimeMillis() / MILLIS_PER_SECOND)
                    put("files", JsonNull)
                    put("images", JsonNull)
                }
            write(channel.contextId, "send_message", args)
            sendError = null
            loadMessages(channel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendError = "Couldn't send: ${short(e)}"
            say("Couldn't send: ${short(e)}", error = true)
        }
    }

    // ---- invite / join -----------------------------------------------------

    /** Both backends invite: a node signs with its namespace key, an account with its device key. */
    val canInvite: Boolean get() = isNode || client.mero != null

    /** Mint an invite code to [space], or null (the reason is in [status]). */
    suspend fun makeInvite(space: ChatSpace): String? =
        try {
            val signed: SignedGroupOpenInvitation =
                if (isRelay) {
                    client.createNamespaceInvitation(space.id)
                } else {
                    when (val result = mero!!.admin.createNamespaceInvitation(space.id)) {
                        is CreateNamespaceInvitationResult.Single -> result.data.invitation
                        is CreateNamespaceInvitationResult.Recursive ->
                            result.data.invitations
                                .firstOrNull()
                                ?.invitation
                                ?: error("The node returned no invitations.")
                    }
                }
            val code = ChatInvite(space.id, space.name, signed).encoded()
            SampleLog.log(SampleLog.Level.INFO, "invite code for ${space.name}: $code")
            say("Invite ready. Copy or share it.")
            code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Couldn't create an invite: ${short(e)}", error = true)
            null
        }

    /** Join a space from an invite code. Returns whether it joined. */
    suspend fun joinSpace(inviteCode: String): Boolean {
        busy = true
        try {
            say("Reading invite…")
            val invite = ChatInvite.decode(inviteCode)
            if (invite == null) {
                say("That isn't a valid invite code.", error = true)
                return false
            }
            if (isRelay) {
                return try {
                    say("Joining ${invite.spaceName}…")
                    val joined = client.joinWithInvitation(invite.namespaceId, invite.invitation)
                    say(
                        if (joined.published) {
                            "Joined ${invite.spaceName}. Channels appear as the relay syncs."
                        } else {
                            "The join was sent, but not yet published. Try again shortly."
                        },
                        error = !joined.published,
                    )
                    loadSpaces()
                    joined.published
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("Couldn't join: ${short(e)}", error = true)
                    false
                }
            }
            val m = mero ?: return false
            return joinOnNode(m, invite)
        } finally {
            busy = false
        }
    }

    private suspend fun joinOnNode(
        m: Mero,
        invite: ChatInvite,
    ): Boolean =
        try {
            say("Joining ${invite.spaceName}…")
            val joined = m.admin.joinNamespace(invite.namespaceId, JoinNamespaceRequest(invite.invitation, invite.spaceName))
            var synced = false
            for (attempt in 1..JOIN_SYNC_ATTEMPTS) {
                say("Syncing ${invite.spaceName} from the inviter ($attempt/$JOIN_SYNC_ATTEMPTS)…")
                syncAndRegister(m, joined.namespaceId)
                if (hasChannels(invite.namespaceId)) {
                    synced = true
                    break
                }
                delay(JOIN_SYNC_DELAY_MS)
            }
            loadSpaces()
            say(
                if (synced) {
                    "Joined ${invite.spaceName}: ${channels.size} channel(s)"
                } else {
                    "Joined ${invite.spaceName}. Channels are still syncing; pull to refresh."
                },
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("Couldn't join: ${short(e)}", error = true)
            false
        }

    /** Pull the space's contexts from the inviter and register our name in each we are a member of. */
    private suspend fun syncAndRegister(
        m: Mero,
        namespaceId: String,
    ) {
        val contexts = runCatching { m.admin.syncGroupContexts(namespaceId) }.getOrNull().orEmpty()
        contexts
            .filter { ownedIdentity(m, it.contextId).isNotEmpty() }
            .forEach { runCatching { write(it.contextId, "set_profile", profileArgs()) } }
    }

    /** Reload the spaces, then [namespaceId]'s channels; true once it has any. */
    private suspend fun hasChannels(namespaceId: String): Boolean {
        loadSpaces()
        val space = spaces.firstOrNull { it.id == namespaceId } ?: return false
        loadChannels(space)
        return channels.isNotEmpty()
    }

    /** Re-sync a space's channels. */
    suspend fun resync(space: ChatSpace) =
        run("Syncing ${space.name}") {
            if (isNode) runCatching { mero?.admin?.syncGroupContexts(space.id) }
            loadChannels(space)
            say(if (channels.isEmpty()) "No channels yet." else "${channels.size} channel(s)")
        }

    // ---- helpers -----------------------------------------------------------

    private fun initArgs(name: String): JsonObject =
        buildJsonObject {
            put("name", name)
            put("context_type", "Channel")
            put("description", "")
            put("created_at", System.currentTimeMillis() / MILLIS_PER_SECOND)
            put("creator_username", username)
        }

    private fun profileArgs(): JsonObject =
        buildJsonObject {
            put("username", username)
            put("avatar", JsonNull)
        }

    /** A read: the relay's query (warrant fallback), or JSON-RPC on a node. */
    private suspend inline fun <reified T> read(
        contextId: String,
        method: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): T {
        val out: JsonElement =
            if (isRelay) {
                (client.relay ?: error("no relay")).call(contextId, method, args) ?: JsonNull
            } else {
                (mero ?: error("not signed in")).rpc.executeRaw(contextId, method, args)
            }
        return chatJson.decodeFromJsonElement(out)
    }

    /** A write: a warrant through the relay, or JSON-RPC on a node. */
    private suspend fun write(
        contextId: String,
        method: String,
        args: JsonObject,
    ) {
        if (isRelay) {
            (client.relay ?: error("no relay")).execute(contextId, method, args)
        } else {
            (mero ?: error("not signed in")).rpc.executeRaw(contextId, method, args)
        }
    }

    private fun say(
        message: String,
        error: Boolean = false,
    ) {
        status = message
        statusIsError = error
        SampleLog.log(if (error) SampleLog.Level.ERR else SampleLog.Level.INFO, "chat: $message")
    }

    private suspend fun run(
        message: String,
        body: suspend () -> Unit,
    ) {
        busy = true
        say("$message…")
        try {
            body()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            say("$message failed: ${short(e)}", error = true)
        } finally {
            busy = false
        }
    }

    private fun short(error: Exception): String = error.message ?: error.toString()

    companion object {
        /** mero-chat (`apps/mero-chat`), as the app registry publishes it. Invite links name it too. */
        const val PACKAGE_NAME = "com.calimero.chat"
        const val REGISTRY_URL = "https://apps.calimero.network"
        private const val MESSAGE_PAGE = 50
        private const val MILLIS_PER_SECOND = 1000
        private const val ID_PREFIX = 8
        private const val ACCOUNT_PREFIX = 6
        private const val BYTE_MASK = 0xFF
        private const val JOIN_SYNC_ATTEMPTS = 6
        private const val JOIN_SYNC_DELAY_MS = 2_000L
    }
}
