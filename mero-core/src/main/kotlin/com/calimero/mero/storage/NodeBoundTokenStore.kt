package com.calimero.mero.storage

import com.calimero.mero.TokenData

/**
 * The view of a [TokenStore] one [com.calimero.mero.Mero] reads and writes through: bundles
 * are tagged with [nodeOrigin] on the way in, and a stored bundle tagged for another node (or
 * untagged — a legacy entry) reads as empty, so it is never sent to this node.
 *
 * Mirrors mero-js `persistTokens` / `readStoredTokens`. Like mero-js, which keeps an in-memory
 * copy beside the store, a store that another node's client overwrote after this one signed
 * in does not sign this one out: it keeps using the bundle it last held (and its next write
 * takes the slot back). An **empty** store is a sign-out wherever it came from.
 */
internal class NodeBoundTokenStore(
    private val inner: TokenStore,
    val nodeOrigin: String,
) : TokenStore {
    @Volatile private var own: TokenData? = null

    override fun getTokens(): TokenData? {
        val stored = inner.getTokens()
        val mine =
            when {
                stored == null -> null
                stored.nodeOrigin == nodeOrigin -> stored.copy(nodeOrigin = null)
                else -> own
            }
        own = mine
        return mine
    }

    override fun setTokens(data: TokenData) {
        own = data.copy(nodeOrigin = null)
        inner.setTokens(data.copy(nodeOrigin = nodeOrigin))
    }

    /** Drop this node's bundle. Another node's bundle in the same store is left alone. */
    override fun clear() {
        own = null
        val stored = inner.getTokens() ?: return
        if (stored.nodeOrigin == nodeOrigin) inner.clear()
    }
}
