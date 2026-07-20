package com.calimero.mero.storage

import com.calimero.mero.TokenData

/**
 * In-memory token store — the default when no store is supplied. Tokens are lost on process death
 * and are NOT shared across processes (the cross-process refresh lock is a no-op with this store).
 *
 * Port of mero-js `MemoryTokenStore`.
 */
class MemoryTokenStore : TokenStore {
    @Volatile private var tokens: TokenData? = null

    override fun getTokens(): TokenData? = tokens

    override fun setTokens(data: TokenData) {
        tokens = data
    }

    override fun clear() {
        tokens = null
    }
}
