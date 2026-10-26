package com.calimero.mero.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.calimero.mero.storage.EncryptedPrefs
import java.util.concurrent.ConcurrentHashMap

/**
 * Small string key/value persistence for the account layer: device keys, the
 * enrolment's pending `state`, the delegated session, warrant nonces and pinned relay
 * node keys. The native counterpart of the `localStorage` keys mero-js uses.
 *
 * Implementations must be safe to call from any thread, and must survive the round
 * trip to the wallet and back (the process can die while the Custom Tab is open).
 */
interface SecureStore {
    fun get(key: String): String?

    fun put(
        key: String,
        value: String,
    )

    fun remove(key: String)

    /**
     * Remove every key starting with [prefix] — what [CloudAccount.signOut] with `forgetDevice`
     * uses for sequences it cannot name one by one (governance nonces, per group). A store that
     * cannot list its keys keeps them (the default); the ones [CloudAccount.signOut] can name
     * are removed regardless.
     */
    fun removeAll(prefix: String) {}
}

/** In-memory [SecureStore], for tests and for an app that wants nothing on disk. */
class MemorySecureStore : SecureStore {
    private val map = ConcurrentHashMap<String, String>()

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

    override fun removeAll(prefix: String) {
        map.keys.removeIf { it.startsWith(prefix) }
    }
}

/**
 * Keystore-backed [SecureStore] over AndroidX Security's [EncryptedSharedPreferences]:
 * values are encrypted at rest under a hardware-backed master key. The device's signing
 * secret lives here, so this is the store to use on a device.
 *
 * Writes are `commit()`ed, not `apply()`ed: a nonce that is handed out must be on disk
 * before the warrant carrying it leaves, or a crash replays it.
 *
 * A file whose keyset no longer decrypts (restored from a backup onto a device without its
 * Keystore master key) is reset rather than crashing the app ([EncryptedPrefs]): the device
 * keys and session are gone and the person signs in again.
 */
class EncryptedPrefsSecureStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : SecureStore {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { EncryptedPrefs.open(appContext, fileName) }

    // A value that does not decrypt reads as absent, as an unreadable one does elsewhere.
    override fun get(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun put(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun removeAll(prefix: String) {
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        val edit = prefs.edit()
        keys.forEach(edit::remove)
        edit.commit()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "mero_account"
    }
}
