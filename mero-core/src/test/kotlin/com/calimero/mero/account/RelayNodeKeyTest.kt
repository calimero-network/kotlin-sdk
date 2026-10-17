package com.calimero.mero.account

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `relay-node-key.test.ts`, against [com.calimero.mero.testkit.FakeCloud]'s mock attestation. */
class RelayNodeKeyTest {
    private val rig = DelegatedRig()
    private val account = rig.account
    private val cloud = rig.node.cloud

    @After
    fun tearDown() = rig.close()

    private fun attests() = cloud.recorded("/admin-api/tee/attest").size

    @Test
    fun `learns the key from the attestation and pins it`() =
        runBlocking {
            assertEquals(RelayNodeKeyAttempt.Learned(cloud.nodeKey), account.attemptRelayNodeKey(rig.base))
            assertEquals(cloud.nodeKey, account.readPinnedRelayNodeKey(rig.base))
            // Pinned: the next attempt asks nothing; a fresh one attests again.
            account.attemptRelayNodeKey(rig.base)
            assertEquals(1, attests())
            account.attemptRelayNodeKey(rig.base, fresh = true)
            assertEquals(2, attests())
        }

    @Test
    fun `calls a relay answering 503 unavailable and pins nothing`() =
        runBlocking {
            cloud.attestStatus = 503
            assertTrue(account.attemptRelayNodeKey(rig.base) is RelayNodeKeyAttempt.Unavailable)
            assertNull(account.readPinnedRelayNodeKey(rig.base))
            assertNull(account.resolveRelayNodeKey(rig.base))
        }

    @Test
    fun `calls a relay that cannot be reached unavailable`() =
        runBlocking {
            assertTrue(account.attemptRelayNodeKey("http://127.0.0.1:1") is RelayNodeKeyAttempt.Unavailable)
        }

    @Test
    fun `refuses a quote that does not bind the key it names, and pins nothing`() =
        runBlocking {
            cloud.attestBindsWrongKey = true
            assertTrue(account.attemptRelayNodeKey(rig.base) is RelayNodeKeyAttempt.Refused)
            assertNull(account.readPinnedRelayNodeKey(rig.base))
        }

    @Test
    fun `learns the key once the relay answers again, after backing off`() =
        runBlocking {
            cloud.attestStatus = 503
            val learned =
                launch {
                    assertEquals(cloud.nodeKey, account.learnRelayNodeKey(rig.base, retryMs = listOf(20L, 40L)))
                }
            while (attests() < 3) kotlinx.coroutines.delay(10)
            cloud.attestStatus = null
            withTimeout(5_000) { learned.join() }
            assertTrue(attests() >= 4)
        }

    @Test
    fun `never retries a refused quote into acceptance, one attempt then no key`() =
        runBlocking {
            cloud.attestBindsWrongKey = true
            assertNull(account.learnRelayNodeKey(rig.base, retryMs = listOf(1L)))
            assertEquals(1, attests())
        }

    @Test
    fun `the default schedule backs off to 30 s`() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L), RELAY_NODE_KEY_RETRY_MS)
    }

    @Test
    fun `stops waiting when its coroutine is cancelled`() =
        runBlocking {
            cloud.attestStatus = 503
            val job = launch { account.learnRelayNodeKey(rig.base, retryMs = listOf(10_000L)) }
            while (attests() < 1) kotlinx.coroutines.delay(10)
            job.cancel()
            withTimeout(2_000) { job.join() }
            assertEquals(1, attests())
        }
}
