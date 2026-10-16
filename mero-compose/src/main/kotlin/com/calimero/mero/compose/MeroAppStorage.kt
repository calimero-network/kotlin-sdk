package com.calimero.mero.compose

import android.content.Context
import android.content.SharedPreferences

/**
 * A string key-value store — what [MeroAppStorage] and the invitation stores persist through.
 * [SharedPreferencesStore] on a device, [MemoryKeyValueStore] in tests (or for a session that
 * should not survive the process). Implementations must not throw for an ordinary failure.
 */
interface KeyValueStore {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

/** An in-memory [KeyValueStore]. Thread-safe. */
class MemoryKeyValueStore : KeyValueStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun get(key: String): String? = map[key]

    override fun put(
        key: String,
        value: String,
    ) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

/**
 * A [KeyValueStore] on plain [SharedPreferences] — fine for node URLs, ids and pending
 * invitations, none of which is a secret. Tokens and keys never go here: they live in the
 * client's encrypted stores.
 */
class SharedPreferencesStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    constructor(context: Context, name: String = DEFAULT_FILE) :
        this(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

    override fun get(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(
        key: String,
        value: String,
    ) {
        runCatching { prefs.edit().putString(key, value).apply() }
    }

    override fun remove(key: String) {
        runCatching { prefs.edit().remove(key).apply() }
    }

    companion object {
        /** The preferences file mero-compose uses by default. */
        const val DEFAULT_FILE = "mero_app"
    }
}

/**
 * What a [MeroClient] remembers between launches: the node, the node that minted the stored
 * tokens, and the application, context and context identity an SSO login named — port of
 * mero-react's `storage` helpers (localStorage there; the keys are the same). Tokens are not
 * kept here: they live in the client's [com.calimero.mero.storage.TokenStore].
 */
class MeroAppStorage(
    private val store: KeyValueStore = MemoryKeyValueStore(),
) {
    /** On-device storage in the `mero_app` [SharedPreferences] file. */
    constructor(context: Context) : this(SharedPreferencesStore(context))

    /**
     * The node a login was started with ([MeroClient.connectToNode] points it at the *target*
     * node before the login page opens) or the node the session runs on.
     */
    var nodeUrl: String?
        get() = store.get(KEY_NODE_URL)
        set(value) = write(KEY_NODE_URL, value)

    /**
     * The node the token bundle in the token store was issued by. Distinct from [nodeUrl]
     * exactly while switching nodes, which is what lets an SSO callback re-seed on a node change
     * without mistaking another node's bundle for a rotated one ([resolveTokenAdoption]).
     */
    var tokenNodeUrl: String?
        get() = store.get(KEY_TOKEN_NODE_URL)
        set(value) = write(KEY_TOKEN_NODE_URL, value)

    var applicationId: String?
        get() = store.get(KEY_APPLICATION_ID)?.ifEmpty { null }
        set(value) = write(KEY_APPLICATION_ID, value)

    var contextId: String?
        get() = store.get(KEY_CONTEXT_ID)?.ifEmpty { null }
        set(value) = write(KEY_CONTEXT_ID, value)

    var contextIdentity: String?
        get() = store.get(KEY_CONTEXT_IDENTITY)?.ifEmpty { null }
        set(value) = write(KEY_CONTEXT_IDENTITY, value)

    /** Forget everything above — mero-react `clearAllStorage`. */
    fun clearAll() {
        ALL_KEYS.forEach(store::remove)
    }

    private fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) store.remove(key) else store.put(key, value)
    }

    companion object {
        const val KEY_NODE_URL = "mero:node_url"
        const val KEY_TOKEN_NODE_URL = "mero:token_node_url"
        const val KEY_APPLICATION_ID = "mero:application_id"
        const val KEY_CONTEXT_ID = "mero:context_id"
        const val KEY_CONTEXT_IDENTITY = "mero:context_identity"

        /** Every key [clearAll] removes, including mero-react's legacy token keys. */
        val ALL_KEYS =
            listOf(
                "mero:access_token",
                "mero:refresh_token",
                "mero:expires_at",
                KEY_NODE_URL,
                KEY_TOKEN_NODE_URL,
                KEY_APPLICATION_ID,
                KEY_CONTEXT_ID,
                KEY_CONTEXT_IDENTITY,
            )
    }
}
