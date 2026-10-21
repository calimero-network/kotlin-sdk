package com.calimero.mero.account

import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay connection's login (2026-10-21 incident): a stale pinned node key is re-attested
 * rather than trusted forever, and a login that fails says why ([RelayConnection.sessionError])
 * instead of leaving the next read to go out without a token.
 */
class RelayLoginRecoveryTest {
    private val rig = DelegatedRig()
    private val account = rig.account
    private val cloud = rig.node.cloud

    @After
    fun tearDown() = rig.close()

    private fun attests() = cloud.recorded("/admin-api/tee/attest").size

    @Test
    fun `a login refused under a stale pinned key re-attests and logs in under the relay's key now`() =
        runBlocking {
            // Pinned before the relay was rebuilt with a new key.
            account.pinRelayNodeKey(rig.base, "cd".repeat(32))
            rig.node.refuseLoginsNamingOtherNodeKey = true

            val connection = account.connect(rig.session, MemoryTokenStore())
            assertTrue(connection.ensureSession())
            assertNull(connection.sessionError)
            assertEquals(1, attests())
            assertEquals(cloud.nodeKey, account.readPinnedRelayNodeKey(rig.base))
        }

    @Test
    fun `a login under a key just attested is not retried`() =
        runBlocking {
            rig.node.refuseLoginsNamingOtherNodeKey = true
            cloud.nodeKey = "ef".repeat(32)
            val connection = account.connect(rig.session, MemoryTokenStore())
            assertTrue(connection.ensureSession())
            assertEquals(1, attests())
        }

    @Test
    fun `a failed login is kept as the session error`() =
        runBlocking {
            cloud.attestStatus = 503
            val connection = account.connect(rig.session, MemoryTokenStore())
            assertFalse(connection.ensureSession())
            assertNotNull(connection.sessionError)
            assertNull(connection.mero.getTokenData())

            // The relay answers again: the next login succeeds and clears it.
            cloud.attestStatus = null
            assertTrue(connection.ensureSession())
            assertNull(connection.sessionError)
        }

    @Test
    fun `a dead relay with a pinned key fails at login, and says so`() =
        runBlocking {
            val dead = rig.session.copy(relayUrl = "http://127.0.0.1:1")
            account.pinRelayNodeKey("http://127.0.0.1:1", cloud.nodeKey)
            val connection = account.connect(dead, MemoryTokenStore())
            assertFalse(connection.ensureSession())
            assertNotNull(connection.sessionError)
        }
}
