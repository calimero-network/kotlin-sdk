package com.calimero.mero.relay

import com.calimero.mero.account.MemorySecureStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The local counters are the only record of a sequence (no node serves a lookup), so pin [NonceSource.advanceTo]. */
class NonceSourceTest {
    @Test
    fun `memory source advances to a floor and never lowers`() =
        runBlocking {
            val nonces = MemoryNonceSource()
            assertEquals(1uL, nonces.next())
            nonces.advanceTo(50u)
            assertEquals(50uL, nonces.next())
            nonces.advanceTo(10u)
            assertEquals(51uL, nonces.next())
        }

    @Test
    fun `persisted source advances to a floor, never lowers, and survives a rebuild`() =
        runBlocking {
            val store = MemorySecureStore()
            val nonces = PersistedNonceSource(store, "k")
            assertEquals(1uL, nonces.next())
            nonces.advanceTo(50u)
            nonces.advanceTo(10u)
            assertEquals(50uL, PersistedNonceSource(store, "k").next())
            assertEquals(51uL, nonces.next())
        }
}
