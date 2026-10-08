package com.calimero.mero.sample.chat

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.invite.InviteCodec
import com.calimero.mero.invite.InviteLink
import com.calimero.mero.relay.CreateContextInput
import com.calimero.mero.sse.ContextEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

data class ChatChannel(
    val contextId: String,
    val groupId: String?,
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

// ---- ChatService -----------------------------------------------------------

/**
 * A mero-chat frontend over a Calimero Cloud session: every read and write goes through the
 * account's relay. Reads use [com.calimero.mero.relay.RelayClient.call] (a session query,
 * falling back to a warrant when the method is not a view); writes are warrants the device
 * signs. Channels are the contexts the relay lists for this account (caller-scoped), and a
 * space is joined by redeeming an invitation as the account
 * ([MeroClient.joinWithInvitation]). Same `com.calimero.chat` contract as mero-chat.
 */
class ChatService(
    private val client: MeroClient,
    displayName: String? = null,
) {
    var channels by mutableStateOf<List<ChatChannel>>(emptyList())
        private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set

    /** The display name registered with `set_profile`. */
    val username: String = displayName?.takeIf { it.isNotEmpty() } ?: "mobile"

    /** Live events for a channel, once the relay session (Bearer) is up; nothing otherwise. */
    fun eventStream(channel: ChatChannel): Flow<ContextEvent> =
        client.mero?.takeIf { client.state.value.sessionReady }?.events(listOf(channel.contextId)) ?: emptyFlow()

    // ---- channels ----------------------------------------------------------

    /** The contexts this account belongs to on its relay, named by the contract's `get_info`. */
    suspend fun loadChannels() =
        runStep("Loading channels") {
            val mero = client.mero ?: return@runStep
            val contexts = mero.admin.getContexts().contexts
            channels =
                contexts.mapNotNull { ctx ->
                    val info = runCatching { call<ChatContextInfo>(ctx.id, "get_info") }.getOrNull()
                    if (info?.contextType == "Dm") return@mapNotNull null
                    ChatChannel(ctx.id, ctx.groupId, info?.name ?: "channel", info?.contextType ?: "Channel")
                }
            status = if (channels.isEmpty()) "" else "${channels.size} channel(s)"
        }

    /** Create a channel in a group the relay may create in on the account's behalf. */
    suspend fun createChannel(
        groupId: String,
        applicationId: String,
        name: String,
    ) = runStep("Creating #$name") {
        val relay = client.relay ?: error("no relay")
        val created =
            relay.createContext(
                CreateContextInput(
                    groupId = groupId,
                    applicationId = applicationId,
                    name = name,
                    initArgs =
                        buildJsonObject {
                            put("name", name)
                            put("context_type", "Channel")
                            put("description", "")
                            put("created_at", System.currentTimeMillis() / MILLIS_PER_SECOND)
                            put("creator_username", username)
                        },
                ),
            )
        registerProfile(created.contextId)
        status = "Channel #$name created"
        loadChannels()
    }

    // ---- messages ----------------------------------------------------------

    suspend fun loadMessages(channel: ChatChannel) {
        try {
            val args =
                buildJsonObject {
                    put("parent_message", JsonNull)
                    put("limit", MESSAGE_PAGE)
                    put("offset", 0)
                    put("search_term", JsonNull)
                }
            val page = call<ChatMessagePage>(channel.contextId, "get_messages", args)
            messages = page.messages.filter { it.deleted != true }
        } catch (e: Exception) {
            status = "Couldn't load messages: ${short(e)}"
        }
    }

    suspend fun sendMessage(
        channel: ChatChannel,
        text: String,
    ) {
        if (text.isEmpty()) return
        val relay = client.relay ?: return
        try {
            val args =
                buildJsonObject {
                    put("message", text)
                    put("mentions", buildJsonArray { })
                    put("mentions_usernames", buildJsonArray { })
                    put("parent_message", JsonNull)
                    put("timestamp", System.currentTimeMillis())
                    put("files", JsonNull)
                    put("images", JsonNull)
                }
            // A write: signed as a warrant and spent by the relay as this account.
            relay.execute(channel.contextId, "send_message", args)
            loadMessages(channel)
        } catch (e: Exception) {
            status = "Couldn't send: ${short(e)}"
        }
    }

    /** Register our display name in a channel (`set_profile`, a warranted write). */
    suspend fun registerProfile(contextId: String) {
        val relay = client.relay ?: return
        runCatching {
            relay.execute(
                contextId,
                "set_profile",
                buildJsonObject {
                    put("username", username)
                    put("avatar", JsonNull)
                },
            )
        }
    }

    // ---- join --------------------------------------------------------------

    /** Redeem an invite code as this account: the admitting node becomes (or stays) its relay. */
    suspend fun joinSpace(inviteCode: String) =
        runStep("Reading invite") {
            val invite = ChatInvite.decode(inviteCode)
            if (invite == null) {
                status = "That invite code could not be read."
                return@runStep
            }
            status = "Joining \"${invite.spaceName}\""
            val joined = client.joinWithInvitation(invite.namespaceId, invite.invitation)
            status =
                if (joined.published) {
                    "Joined \"${invite.spaceName}\". Channels appear once the relay has synced them."
                } else {
                    "The relay accepted the join; it will publish shortly."
                }
            loadChannels()
        }

    // ---- helpers -----------------------------------------------------------

    private suspend inline fun <reified T> call(
        contextId: String,
        method: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): T {
        val relay = client.relay ?: error("no relay")
        val out = relay.call(contextId, method, args) ?: JsonNull
        return chatJson.decodeFromJsonElement(out)
    }

    private suspend fun runStep(
        message: String,
        body: suspend () -> Unit,
    ) {
        busy = true
        status = "$message…"
        try {
            body()
        } catch (e: Exception) {
            status = "$message failed: ${short(e)}"
        } finally {
            busy = false
        }
    }

    private fun short(error: Exception): String = error.message ?: error.toString()

    companion object {
        /** The chat app's package: what invite links name. */
        const val PACKAGE_NAME = "com.calimero.chat"
        private const val MESSAGE_PAGE = 50
        private const val MILLIS_PER_SECOND = 1000
    }
}
