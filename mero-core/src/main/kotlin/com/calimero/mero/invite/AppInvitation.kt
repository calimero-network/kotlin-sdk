package com.calimero.mero.invite

import com.calimero.mero.admin.SignedGroupOpenInvitation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * An invitation as the Calimero web apps share it, so a native app redeems a link minted on the
 * web and mints one the web redeems. (Swift `MeroKit.AppInvitation`.)
 *
 * The token is [InviteCodec]'s `base58(deflate(JSON))`; the JSON is the signed invitation wrapped
 * with the namespace's name, under a key each web app picks:
 *
 * - mero-design: `{"invitation": <signed>, "__teamName": "…"}` (the node's
 *   `createNamespaceInvitation` data, plus the name)
 * - mero-chat and the others: `{"invitation": <signed>, "groupName" | "groupAlias": "…"}`
 *
 * Decoding takes every shape they send: the wrapped payload or a bare signed invitation, under an
 * optional `data` envelope, with the signature under `inviter_signature` or `inviterSignature`
 * (normalized to core's `inviter_signature`), and the name under any of [NAME_KEYS]. A link, a
 * `calimero://` link or a bare token all work ([parse]).
 */
class AppInvitation(
    /** The signed invitation: what `MeroAppBackend.joinNamespace` redeems. */
    val invitation: SignedGroupOpenInvitation,
    /** The namespace's name, as the inviter shared it. */
    val name: String? = null,
) {
    /** The namespace the invitation admits to: the hex of its signed `group_id`. */
    val namespaceId: String
        get() = invitation.invitation.groupId.joinToString("") { "%02x".format(it and BYTE_MASK) }

    /** The shareable token: `base58(deflate({"invitation": …, nameKey: name}))`. Pass the key the app's web build reads (`__teamName` for mero-design). */
    fun token(nameKey: String = DEFAULT_NAME_KEY): String =
        InviteCodec.encode(
            buildJsonObject {
                put("invitation", invitation.raw)
                name?.let { put(nameKey, JsonPrimitive(it)) }
            }.toString(),
        )

    /** The shareable link for [token] ([InviteLink.invitation]). */
    fun link(
        slug: String,
        nameKey: String = DEFAULT_NAME_KEY,
        host: String = InviteLink.DEFAULT_HOST,
    ): String = InviteLink.invitation(token(nameKey), slug, host)

    override fun equals(other: Any?): Boolean = other is AppInvitation && other.invitation == invitation && other.name == name

    override fun hashCode(): Int = 31 * invitation.hashCode() + (name?.hashCode() ?: 0)

    override fun toString(): String = "AppInvitation(namespaceId=$namespaceId, name=$name)"

    companion object {
        /** What the name is read from, first match wins. */
        val NAME_KEYS: List<String> = listOf("groupName", "groupAlias", "__teamName", "teamName", "spaceName", "name")

        /** What [token] writes the name under unless told otherwise. */
        const val DEFAULT_NAME_KEY = "groupName"

        private const val BYTE_MASK = 0xff
        private const val ID_BYTES = 32
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parse a pasted or opened link, a `calimero://` link or a bare token. Null when nothing in
         * it is an invitation, or when it is a link for an app other than [expectedSlug] (redeeming
         * it would join that app's namespace).
         */
        @JvmStatic
        @JvmOverloads
        fun parse(
            input: String,
            expectedSlug: String? = null,
        ): AppInvitation? = InviteLink.tokenFromPasted(input, expectedSlug)?.let { decode(it) }

        /** Decode a token (any form [InviteCodec.decode] reads). */
        @JvmStatic
        fun decode(token: String): AppInvitation? = InviteCodec.decode(token)?.let { decodeJson(it) }

        /** Decode the payload JSON. */
        @JvmStatic
        fun decodeJson(payload: String): AppInvitation? {
            val root = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
            val inner = root["data"] as? JsonObject ?: root
            val wrapped = inner["invitation"] as? JsonObject
            if (wrapped != null && isSigned(wrapped)) {
                val signed = signed(wrapped) ?: return null
                val name = NAME_KEYS.firstNotNullOfOrNull { key -> inner.string(key)?.trim()?.ifEmpty { null } }
                return AppInvitation(signed, name)
            }
            if (isSigned(inner)) return signed(inner)?.let { AppInvitation(it) }
            return null
        }

        /** A signature and an inner invitation object (the web apps' `isSignedGroupOpenInvitation`). */
        private fun isSigned(obj: JsonObject): Boolean =
            obj["invitation"] is JsonObject && (obj.string("inviter_signature") != null || obj.string("inviterSignature") != null)

        private fun signed(obj: JsonObject): SignedGroupOpenInvitation? {
            val normalized =
                if (obj["inviter_signature"] == null && obj["inviterSignature"] != null) {
                    JsonObject(
                        obj.entries.associate { (key, value) -> (if (key == "inviterSignature") "inviter_signature" else key) to value },
                    )
                } else {
                    obj
                }
            val signed = SignedGroupOpenInvitation(normalized)
            val group = signed.invitation.raw["group_id"] as? JsonArray ?: return null
            val bytes = group.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            return signed.takeIf { bytes.size == ID_BYTES && bytes.size == group.size }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
