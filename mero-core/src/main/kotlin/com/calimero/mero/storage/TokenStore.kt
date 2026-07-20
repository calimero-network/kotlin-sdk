package com.calimero.mero.storage

import com.calimero.mero.TokenData

/**
 * Persistence for the token bundle. The store is the **source of truth** for refresh: the refresh
 * coordinator re-reads it inside the cross-process lock so a bundle another process already rotated
 * is adopted rather than replayed.
 *
 * Port of mero-js's `TokenStore` interface. Implementations must be safe to call from any thread.
 */
interface TokenStore {
    fun getTokens(): TokenData?
    fun setTokens(data: TokenData)
    fun clear()
}
