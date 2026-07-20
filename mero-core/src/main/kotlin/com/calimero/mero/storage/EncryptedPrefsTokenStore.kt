package com.calimero.mero.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.calimero.mero.TokenData
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Keystore-backed token store using AndroidX Security's [EncryptedSharedPreferences]. Tokens are
 * encrypted at rest with a hardware-backed master key and survive process death, which is what makes
 * the cross-process refresh lock meaningful (§4.1 of the SDK spec).
 *
 * Port of mero-js `LocalStorageTokenStore`, hardened for mobile. The stored JSON uses the same
 * snake_case shape as mero-js so a bundle is portable across SDKs on the same device.
 *
 * @param fileName SharedPreferences file name; use distinct names to isolate multiple nodes.
 */
class EncryptedPrefsTokenStore(
    context: Context,
    fileName: String = DEFAULT_FILE_NAME,
) : TokenStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val prefs by lazy {
        val masterKey =
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getTokens(): TokenData? {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return null
        return try {
            json.decodeFromString<TokenData>(raw)
        } catch (_: Exception) {
            null
        }
    }

    override fun setTokens(data: TokenData) {
        prefs.edit().putString(KEY_TOKENS, json.encodeToString(data)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_TOKENS).apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "mero_tokens"
        const val KEY_TOKENS = "mero-tokens"
    }
}
