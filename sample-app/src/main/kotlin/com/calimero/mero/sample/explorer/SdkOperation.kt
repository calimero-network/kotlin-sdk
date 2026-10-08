package com.calimero.mero.sample.explorer

import com.calimero.mero.Mero
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.admin.AdminApi
import com.calimero.mero.auth.AuthApi
import com.calimero.mero.compose.MeroClient
import com.calimero.mero.relay.RelayClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One input field for an operation form. */
enum class OpFieldKind { LINE, MULTILINE }

data class OpField(
    val id: String,
    val label: String,
    val placeholder: String,
    val kind: OpFieldKind,
) {
    companion object {
        fun line(
            id: String,
            label: String,
            placeholder: String = "",
        ): OpField =
            OpField(id, label, placeholder, OpFieldKind.LINE)

        fun json(
            id: String = "body",
            label: String = "Request JSON",
            placeholder: String = "{}",
        ): OpField =
            OpField(id, label, placeholder, OpFieldKind.MULTILINE)
    }
}

/**
 * What an operation runs against: the signed-in [MeroClient]. [admin] / [auth] are the core
 * clients pointed at the account's relay (Bearer once the relay session is minted); [relay]
 * is the warrant client; [account] is the Cloud account layer.
 */
class OpEnv(
    val client: MeroClient,
) {
    val account: CloudAccount get() = client.account
    val relay: RelayClient get() = client.relay ?: error(NO_RELAY)
    val mero: Mero get() = client.mero ?: error(NO_RELAY)
    val admin: AdminApi get() = mero.admin
    val auth: AuthApi get() = mero.auth

    /** JSON-RPC on the relay. An account session gets 403 here — writes go through [relay]. */
    val rpc: com.calimero.mero.rpc.RpcClient get() = mero.rpc

    private companion object {
        const val NO_RELAY = "No relay serves this account yet — redeem an invitation first."
    }
}

/**
 * A single invokable SDK method: metadata + input fields + an async runner that returns a rendered
 * (pretty-printed) result string. 1:1 with the Swift sample's `SDKOperation`.
 */
class SDKOperation(
    val id: String,
    val category: String,
    val name: String,
    val summary: String,
    val fields: List<OpField>,
    val run: suspend (OpEnv, Map<String, String>) -> String,
)

/** Rendering / decoding helpers shared by the operation catalog. */
object Fmt {
    val pretty: Json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

    /** Pretty-print any serializable value. */
    inline fun <reified T> json(value: T): String = pretty.encodeToString(value)

    /** Decode a user-entered JSON string into a request type (empty → `{}`). */
    inline fun <reified T> decode(raw: String): T {
        val text = raw.trim()
        return pretty.decodeFromString(if (text.isEmpty()) "{}" else text)
    }
}

/** Trimmed value for a field id (empty string if absent). */
fun Map<String, String>.v(key: String): String = (this[key] ?: "").trim()

/** Optional trimmed value (null if empty). */
fun Map<String, String>.opt(key: String): String? = v(key).ifEmpty { null }
