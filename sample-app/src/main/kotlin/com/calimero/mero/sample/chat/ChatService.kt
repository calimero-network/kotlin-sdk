package com.calimero.mero.sample.chat

import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calimero.mero.Mero
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.SubgroupEntry
import com.calimero.mero.admin.UpgradePolicy
import com.calimero.mero.sse.ContextEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.zip.Deflater
import java.util.zip.Inflater

private val chatJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

// ---- Wire models (curb contract, snake_case) -------------------------------

@Serializable
data class ChatMessage(
    val id: String,
    val text: String,
    @SerialName("sender_username") val senderUsername: String = "",
    val sender: String = "",
    val timestamp: Long = 0,
    val deleted: Boolean? = null,
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

data class ChatSpace(
    val id: String,
    val name: String,
)

data class ChatChannel(
    val id: String,
    val groupId: String,
    val contextId: String,
    val executorId: String,
    val name: String,
    val kind: String,
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
    /** Compact single-line invite code — zlib-compressed JSON, base64'd — for easy copy-paste. */
    fun encoded(): String {
        val bytes = chatJson.encodeToString(this).toByteArray()
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArray(bytes.size * 2 + BUFFER_PAD)
        val size = deflater.deflate(out)
        deflater.end()
        return Base64.encodeToString(out.copyOf(size), Base64.NO_WRAP)
    }

    companion object {
        private const val BUFFER_PAD = 64

        /** Decode an invite code. Tries the compact (zlib+base64) form first, then raw JSON. */
        fun decode(code: String): ChatInvite? {
            val trimmed = code.trim()
            runCatching {
                val compressed = Base64.decode(trimmed, Base64.DEFAULT)
                val inflater = Inflater()
                inflater.setInput(compressed)
                val buffer = ByteArray(compressed.size * INFLATE_FACTOR + BUFFER_PAD)
                val size = inflater.inflate(buffer)
                inflater.end()
                return chatJson.decodeFromString<ChatInvite>(String(buffer.copyOf(size)))
            }
            return runCatching { chatJson.decodeFromString<ChatInvite>(trimmed) }.getOrNull()
        }

        private const val INFLATE_FACTOR = 8
    }
}

// ---- ChatService -----------------------------------------------------------

/**
 * A native curb (mero-chat) frontend over the authenticated [Mero] client: install the app,
 * create/list spaces (namespaces) and channels (subgroup + context), send/read messages
 * (contract RPC), invite, and join. Same logic as mero-chat, in Kotlin, on the same WASM contract.
 * Compose observes its `mutableStateOf` fields directly.
 */
class ChatService(
    private val mero: Mero,
    username: String,
    displayNameOverride: String? = null,
) {
    var appId by mutableStateOf<String?>(null)
        private set
    var spaces by mutableStateOf<List<ChatSpace>>(emptyList())
        private set
    var channels by mutableStateOf<List<ChatChannel>>(emptyList())
        private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    /**
     * The chat display name. [displayNameOverride] (the `chatUser` intent extra, set by run-app-2.sh)
     * wins over the login user — the login user is always the admin "dev", so the harness passes
     * dev1/dev2 to keep the two emulators distinguishable in the room.
     */
    val username: String = displayNameOverride?.takeIf { it.isNotEmpty() } ?: username.ifEmpty { "dev" }

    // ---- setup / install ---------------------------------------------------

    suspend fun setup() =
        runStep("installing $PACKAGE_NAME…") {
            val versions = mero.admin.getRegistryVersions(REGISTRY_URL, PACKAGE_NAME)
            val version = versions.firstOrNull()
            if (version == null) {
                status = "no registry versions found"
                return@runStep
            }
            val resp = mero.admin.installFromRegistry(REGISTRY_URL, PACKAGE_NAME, version)
            appId = resp.applicationId
            status = "installed $PACKAGE_NAME@$version"
            loadSpaces()
        }

    /** Adopt curb's app id if it is already installed, skipping the install gate. */
    suspend fun detectInstalled() {
        if (appId != null) return
        val apps = runCatching { mero.admin.listApplications() }.getOrNull() ?: return
        val curb = apps.apps.firstOrNull { it.packageName == PACKAGE_NAME } ?: return
        appId = curb.id
        status = "$PACKAGE_NAME already installed"
        loadSpaces()
    }

    /** Live SSE event stream for a channel's context (new messages, etc.). */
    fun eventStream(channel: ChatChannel): Flow<ContextEvent> = mero.events(listOf(channel.contextId))

    // ---- spaces ------------------------------------------------------------

    suspend fun loadSpaces() {
        try {
            // Show every namespace this node belongs to (created OR joined). We used to filter to our
            // own curb app id — but an *invited* space targets the inviter's app id, which can differ,
            // so that hid joined spaces entirely (they showed on the admin dashboard but not here).
            spaces = mero.admin.listNamespaces().map { ChatSpace(it.namespaceId, it.name ?: "space") }
        } catch (e: Exception) {
            status = "load spaces failed: ${short(e)}"
        }
    }

    suspend fun createSpace(name: String) {
        val app = appId
        if (app == null) {
            status = "install the app first"
            return
        }
        runStep("creating space \"$name\"…") {
            val resp =
                mero.admin.createNamespace(
                    CreateNamespaceRequest(applicationId = app, upgradePolicy = UpgradePolicy.AUTOMATIC, name = name),
                )
            status = "space created: ${resp.namespaceId}"
            loadSpaces()
        }
    }

    // ---- channels ----------------------------------------------------------

    suspend fun loadChannels(space: ChatSpace) {
        try {
            channels = emptyList()
            val out = mero.admin.listNamespaceGroups(space.id).mapNotNull { buildChannel(it) }
            channels = out
            if (out.isEmpty()) diagnoseEmptySpace()
        } catch (e: Exception) {
            status = "load channels failed: ${short(e)}"
        }
    }

    /**
     * When a joined space shows no channels, say WHY. A context executes against the group's
     * bytecode-derived app_key, so what matters is (a) that curb is installed here at all and (b) that
     * this node has a peer to sync the context state from — a joined-but-uninitialized context
     * (hash 1111…) is almost always "0 peers", not an app-id mismatch.
     */
    private suspend fun diagnoseEmptySpace() {
        val hasCurb =
            runCatching { mero.admin.listApplications().apps }
                .getOrNull()
                .orEmpty()
                .any { it.packageName == PACKAGE_NAME }
        val peers = runCatching { mero.admin.getPeersCount().count }.getOrNull()
        status =
            when {
                !hasCurb -> "curb isn't installed on this node — install it, then rejoin."
                peers == 0 ->
                    "Joined, but this node has 0 peers — it can't sync the channel from the inviter. " +
                        "Make sure this node is networked to the inviter's node (swarm/bootstrap peers)."
                else -> "No channels yet — still syncing from the inviter (${peers ?: "?"} peer(s)). Pull to refresh."
            }
    }

    /** Resolve a subgroup to a display channel (curb `get_info` for name/kind); null to skip (DMs). */
    private suspend fun buildChannel(sg: SubgroupEntry): ChatChannel? {
        val ctx = mero.admin.listGroupContexts(sg.groupId).firstOrNull() ?: return null
        var executor = ownedIdentity(ctx.contextId)
        if (executor.isEmpty()) {
            // Joined space whose context we haven't joined yet — join it so we get a member identity
            // (otherwise it looks uninitialized), and trigger a state pull so the store root syncs.
            runCatching { mero.admin.joinContext(ctx.contextId) }
            runCatching { mero.admin.syncContext(ctx.contextId) }
            executor = ownedIdentity(ctx.contextId)
        }
        var name = sg.name ?: ctx.name ?: "channel"
        var kind = "Channel"
        if (executor.isNotEmpty()) {
            val info =
                runCatching {
                    mero.rpc.execute<ChatContextInfo>(ctx.contextId, "get_info", JsonObject(emptyMap()), executor)
                }.getOrNull()
            if (info != null) {
                name = info.name
                kind = info.contextType
            }
        }
        if (kind == "Dm") return null
        return ChatChannel(ctx.contextId, sg.groupId, ctx.contextId, executor, name, kind)
    }

    private suspend fun ownedIdentity(contextId: String): String =
        runCatching {
            mero.admin
                .getContextIdentitiesOwned(contextId)
                .identities
                .firstOrNull()
        }.getOrNull()
            .orEmpty()

    suspend fun createChannel(
        space: ChatSpace,
        name: String,
        open: Boolean,
    ) {
        val app = appId
        if (app == null) {
            status = "install the app first"
            return
        }
        runStep("creating channel #$name…") {
            val sg = mero.admin.createGroupInNamespace(space.id, CreateGroupInNamespaceRequest(name = name))
            mero.admin.setSubgroupVisibility(
                sg.groupId,
                SetSubgroupVisibilityRequest(subgroupVisibility = if (open) "open" else "restricted"),
            )
            val ctx =
                mero.admin.createContext(
                    CreateContextRequest(
                        applicationId = app,
                        groupId = sg.groupId,
                        initializationParams = initParams(name),
                        name = name,
                    ),
                )
            runCatching {
                mero.rpc.execute<JsonElement>(
                    ctx.contextId,
                    "set_profile",
                    buildJsonObject {
                        put("username", username)
                        put("avatar", JsonNull)
                    },
                    ctx.memberPublicKey,
                )
            }
            status = "channel #$name created"
            loadChannels(space)
        }
    }

    // ---- messages ----------------------------------------------------------

    suspend fun loadMessages(channel: ChatChannel) {
        if (channel.executorId.isEmpty()) return
        try {
            val args =
                buildJsonObject {
                    put("parent_message", JsonNull)
                    put("limit", MESSAGE_PAGE)
                    put("offset", 0)
                    put("search_term", JsonNull)
                }
            val page = mero.rpc.execute<ChatMessagePage>(channel.contextId, "get_messages", args, channel.executorId)
            messages = page.messages.filter { it.deleted != true }
        } catch (e: Exception) {
            status = "load messages failed: ${short(e)}"
        }
    }

    suspend fun sendMessage(
        channel: ChatChannel,
        text: String,
    ) {
        if (text.isEmpty() || channel.executorId.isEmpty()) return
        val ts = System.currentTimeMillis()
        try {
            val args =
                buildJsonObject {
                    put("message", text)
                    put("mentions", buildJsonArray { })
                    put("mentions_usernames", buildJsonArray { })
                    put("parent_message", JsonNull)
                    put("timestamp", ts)
                    put("sender_username", username)
                    put("files", JsonNull)
                    put("images", JsonNull)
                }
            mero.rpc.execute<ChatMessage>(channel.contextId, "send_message", args, channel.executorId)
            loadMessages(channel)
        } catch (e: Exception) {
            status = "send failed: ${short(e)}"
        }
    }

    // ---- invite / join -----------------------------------------------------

    suspend fun makeInvite(space: ChatSpace): String? {
        return try {
            val signed =
                when (val result = mero.admin.createNamespaceInvitation(space.id)) {
                    is CreateNamespaceInvitationResult.Single -> result.data.invitation
                    is CreateNamespaceInvitationResult.Recursive ->
                        result.data.invitations
                            .firstOrNull()
                            ?.invitation
                }
            if (signed == null) {
                status = "invite: node returned no invitations"
                return null
            }
            val code = ChatInvite(space.id, space.name, signed).encoded()
            // Also grabbable from logcat — the two-emulator harness scrapes it from there.
            Log.i(TAG, "invite code for \"${space.name}\": $code")
            status = "invite ready — Copy or Share it"
            code
        } catch (e: Exception) {
            status = "invite failed: ${short(e)}"
            Log.w(TAG, "invite failed", e)
            null
        }
    }

    suspend fun joinSpace(inviteCode: String) {
        busy = true
        status = "Reading invite…"
        try {
            val invite = ChatInvite.decode(inviteCode)
            if (invite == null) {
                status = "✗ Invalid invite code"
                return
            }
            try {
                status = "Joining \"${invite.spaceName}\"…"
                val joined =
                    mero.admin.joinNamespace(
                        invite.namespaceId,
                        JoinNamespaceRequest(invitation = invite.invitation, groupName = invite.spaceName),
                    )
                val synced = syncJoinedSpace(invite, joined.groupId)
                loadSpaces()
                status =
                    if (synced) {
                        "✓ Joined \"${invite.spaceName}\" — ${channels.size} channel(s)"
                    } else {
                        "Joined \"${invite.spaceName}\". Channels still syncing — open it and pull to refresh."
                    }
            } catch (e: Exception) {
                status = "✗ Join failed: ${short(e)}"
            }
        } finally {
            busy = false
        }
    }

    /**
     * Cross-node sync is async — pull the group, then JOIN each channel's context so it is initialized
     * on this node (the step mero-chat does, and the reason a joined space looked "uninitialized"
     * before). Retries until the contexts arrive + join, showing progress the whole way.
     */
    private suspend fun syncJoinedSpace(
        invite: ChatInvite,
        groupId: String,
    ): Boolean {
        for (attempt in 1..JOIN_ATTEMPTS) {
            status = "Syncing \"${invite.spaceName}\" from the inviter… ($attempt/$JOIN_ATTEMPTS)"
            // SDK convenience: join + state-pull every context in the group so it initializes
            // instead of staying uninitialized (1111…).
            val contexts = runCatching { mero.admin.syncGroupContexts(groupId) }.getOrNull().orEmpty()
            for (ctx in contexts) registerProfile(ctx.contextId)
            loadSpaces()
            spaces.firstOrNull { it.id == invite.namespaceId }?.let { space ->
                loadChannels(space)
                if (channels.isNotEmpty()) return true
            }
            delay(JOIN_RETRY_MS)
        }
        return false
    }

    /** Register our display name in an initialized context (curb `set_profile`). */
    private suspend fun registerProfile(contextId: String) {
        val executor = ownedIdentity(contextId)
        if (executor.isEmpty()) return
        runCatching {
            mero.rpc.execute<JsonElement>(
                contextId,
                "set_profile",
                buildJsonObject {
                    put("username", username)
                    put("avatar", JsonNull)
                },
                executor,
            )
        }
    }

    /** Manually re-sync a space's channels (for when cross-node sync lags). */
    suspend fun resync(space: ChatSpace) =
        runStep("Syncing \"${space.name}\"…") {
            // SDK convenience: join + state-pull every context in the group.
            runCatching { mero.admin.syncGroupContexts(space.id) }
            loadChannels(space)
            status = if (channels.isEmpty()) "No channels yet — still syncing" else "✓ ${channels.size} channel(s)"
        }

    // ---- helpers -----------------------------------------------------------

    private fun initParams(name: String): List<Int> {
        val obj =
            buildJsonObject {
                put("name", name)
                put("context_type", "Channel")
                put("description", "")
                put("created_at", System.currentTimeMillis() / MILLIS_PER_SECOND)
                put("creator_username", username)
            }
        return chatJson.encodeToString(JsonObject.serializer(), obj).toByteArray().map { it.toInt() and 0xFF }
    }

    private suspend fun runStep(
        message: String,
        body: suspend () -> Unit,
    ) {
        busy = true
        status = message
        try {
            body()
        } catch (e: Exception) {
            status = "${message.replace("…", "")} failed: ${short(e)}"
        } finally {
            busy = false
        }
    }

    private fun short(error: Exception): String = error.message ?: error.toString()

    companion object {
        const val REGISTRY_URL = "https://apps.calimero.network"
        const val PACKAGE_NAME = "com.calimero.curb"
        private const val MESSAGE_PAGE = 50
        private const val MILLIS_PER_SECOND = 1000
        private const val JOIN_ATTEMPTS = 6
        private const val JOIN_RETRY_MS = 2_000L
        private const val TAG = "MeroChat"
    }
}
