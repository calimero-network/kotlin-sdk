package com.calimero.mero.account

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
}

/**
 * Keystore-backed [SecureStore] over AndroidX Security's [EncryptedSharedPreferences]:
 * values are encrypted at rest under a hardware-backed master key. The device's signing
 * secret lives here, so this is the store to use on a device.
 *
 * Writes are `commit()`ed, not `apply()`ed: a nonce that is handed out must be on disk
 * before the warrant carrying it leaves, or a crash replays it.
 */
class EncryptedPrefsSecureStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : SecureStore {
    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        val masterKey =
            MasterKey
                .Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(
        key: String,
        value: String,
    ) {
        prefs.edit().putString(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "mero_account"
    }
}
