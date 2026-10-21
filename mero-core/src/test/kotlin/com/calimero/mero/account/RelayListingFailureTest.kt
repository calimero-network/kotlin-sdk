package com.calimero.mero.account

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * mero-js `delegated-listing.test.ts` (calimero-network/mero-js#299): a listing across the
 * account's relays skips one that fails, but throws [RelaysUnavailableException] when **every**
 * relay failed — the 2026-10-21 incident, where a dead relay read as an empty account.
 */
class RelayListingFailureTest {
    private val rig = DelegatedRig()
    private val account = rig.account
    private val cloud = rig.node.cloud

    @After
    fun tearDown() = rig.close()

    @Test
    fun `one relay down leaves the others' answer standing`() =
        runBlocking {
            cloud.listedNamespaces += DelegatedRig.namespaceEntry("n1".padEnd(64, '1'))
            account.rememberRelay(rig.session.account, "http://127.0.0.1:1", namespaceId = "n2".padEnd(64, '2'))
            assertEquals(listOf("n1".padEnd(64, '1')), account.listDelegatedNamespaces(rig.session).map { it.namespace.namespaceId })
        }

    @Test
    fun `every relay down throws instead of listing nothing`() =
        runBlocking {
            val dead = rig.session.copy(relayUrl = "http://127.0.0.1:1")
            // A pinned key passes without the network, as in the incident: the listing itself fails.
            account.pinRelayNodeKey("http://127.0.0.1:1", cloud.nodeKey)
            for (list in listOf<suspend () -> Unit>({ account.listDelegatedNamespaces(dead) }, { account.listDelegatedContexts(dead) })) {
                try {
                    list()
                    fail("expected RelaysUnavailableException")
                } catch (e: RelaysUnavailableException) {
                    assertEquals(listOf("http://127.0.0.1:1"), e.failures.map { it.relayUrl })
                    assertEquals(RelayListingFailure.Kind.UNAVAILABLE, e.failures.single().kind)
                    assertFalse(e.refused)
                }
            }
        }

    @Test
    fun `a relay whose attestation is refused is a refused failure, not a silent skip`() =
        runBlocking {
            cloud.attestBindsWrongKey = true
            try {
                account.listDelegatedNamespaces(rig.session)
                fail("expected RelaysUnavailableException")
            } catch (e: RelaysUnavailableException) {
                assertTrue(e.refused)
                assertTrue(cloud.recorded("/admin-api/namespaces").isEmpty())
            }
        }

    @Test
    fun `a relay that answers with nothing is an empty account`() =
        runBlocking {
            assertTrue(account.listDelegatedNamespaces(rig.session).isEmpty())
            assertTrue(account.listDelegatedContexts(rig.session).isEmpty())
        }
}
