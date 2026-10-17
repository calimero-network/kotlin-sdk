package com.calimero.mero.account

import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.classifyError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `bootstrap-from-invitation.test.ts`, `join-with-node.test.ts` and `join-as-account.test.ts`. */
class BootstrapTest {
    private val rig = DelegatedRig(accountHasRelay = false)
    private val account = rig.account
    private val cloud = rig.node.cloud
    private val ns = DelegatedRig.NS

    @After
    fun tearDown() = rig.close()

    private fun invitation(addrs: List<String> = emptyList()) = DelegatedRig.invitation(cloud.relayAccount, addrs)

    @Test
    fun `joins through the relay the cloud lists, at the admit URL it gave`() =
        runBlocking {
            val outcome = account.bootstrapFromInvitation(ns, invitation()) as BootstrapResult.Success
            assertTrue(outcome.published)
            assertEquals(rig.base, outcome.session.relayUrl)
            assertNull(outcome.session.executorAccount)
            assertEquals(cloud.relayAccount, outcome.relay!!.admitterAccount)
            assertEquals("12D3KooWFakeRelay", outcome.relay!!.peerId)
            assertEquals("/admin-api/namespaces/$ns/admit", cloud.recorded("/admit").single().path)
            // Persists nothing.
            assertNull(account.session()!!.relayUrl)
        }

    @Test
    fun `never joins through an http address the invitation carries when the cloud lists no node`() =
        runBlocking {
            cloud.namespacesRouted = false
            val (other, otherUrl) = rig.extra()
            val outcome = account.bootstrapFromInvitation(ns, invitation(listOf(otherUrl))) as BootstrapFailure
            assertEquals(BootstrapStep.NO_NODES, outcome.step)
            assertEquals("no-nodes", outcome.step.wire)
            assertTrue(other.cloud.requests.isEmpty())
        }

    @Test
    fun `names a node the invitation does not name as not-invited`() =
        runBlocking {
            val outcome = account.bootstrapFromInvitation(ns, DelegatedRig.invitation("12".repeat(32))) as BootstrapResult.Failure
            assertEquals(JoinStep.NOT_INVITED, outcome.step)
            assertTrue(outcome.reason.contains("fresh invitation"))
        }

    @Test
    fun `names the lookup itself when the cloud read fails`() =
        runBlocking {
            val dead = CloudAccount(rig.store, cloudBaseUrl = "http://127.0.0.1:1")
            assertEquals(JoinStep.ADMITTERS_LOOKUP, (dead.bootstrapFromInvitation(ns, invitation()) as BootstrapResult.Failure).step)
        }

    @Test
    fun `reports no-credential when nobody is signed in`() =
        runBlocking {
            val outcome = CloudAccount(MemorySecureStore()).bootstrapFromInvitation(ns, invitation()) as BootstrapResult.Failure
            assertEquals(JoinStep.NO_CREDENTIAL, outcome.step)
        }

    @Test
    fun `skips the cloud entirely when a node URL is given, and keeps an admit refusal's status`() =
        runBlocking {
            val node = MockWebServer()
            node.enqueue(MockResponse().setResponseCode(403).setBody("not an admitter"))
            node.start()
            try {
                val outcome = account.bootstrapFromInvitation(ns, invitation(), nodeUrl = node.url("/").toString()) as BootstrapResult.Failure
                assertEquals(JoinStep.ADMIT, outcome.step)
                assertEquals(403, outcome.status)
                assertTrue(outcome.reason.contains("(403)"))
                assertTrue(cloud.recorded("/admitters").isEmpty())
                assertEquals("/admin-api/namespaces/$ns/admit", node.takeRequest().path)
            } finally {
                node.shutdown()
            }
        }

    @Test
    fun `an unreachable admitter is the admit step, distinct from a refusal`() =
        runBlocking {
            val outcome = account.bootstrapFromInvitation(ns, invitation(), nodeUrl = "http://127.0.0.1:1") as BootstrapResult.Failure
            assertEquals(JoinStep.ADMIT, outcome.step)
            assertNull(outcome.status)
            assertTrue(outcome.reason.contains("could not be reached"))
        }

    @Test
    fun `joinAsAccount remembers the relay and moves the session onto it`() =
        runBlocking {
            var moved: DelegatedSession? = null
            val prev = account.session()!!
            assertEquals(ns, account.joinAsAccount(prev, ns, invitation()) { moved = it })
            assertEquals(rig.base, moved!!.relayUrl)
            assertEquals(rig.base, account.readRelayMap(prev.account).namespaces[ns])
        }

    @Test
    fun `joinAsAccount keeps the executor on the same relay and drops it on another`() =
        runBlocking {
            var moved: DelegatedSession? = null
            val same = account.session()!!.copy(relayUrl = rig.base, executorAccount = "ee".repeat(32))
            account.joinAsAccount(same, ns, invitation()) { moved = it }
            assertEquals("ee".repeat(32), moved!!.executorAccount)
            val elsewhere = same.copy(relayUrl = "https://relay-b.example")
            account.joinAsAccount(elsewhere, ns, invitation()) { moved = it }
            assertNull(moved!!.executorAccount)
        }

    @Test
    fun `a refused joinAsAccount throws its step and status and moves nothing`() =
        runBlocking {
            var moved = false
            val e =
                runCatching { account.joinAsAccount(account.session()!!, ns, DelegatedRig.invitation("12".repeat(32))) { moved = true } }
                    .exceptionOrNull() as JoinException
            assertEquals(JoinStep.NOT_INVITED, e.step)
            assertTrue(!moved)
            assertTrue(account.readRelayMap(account.session()!!.account).namespaces.isEmpty())
        }

    @Test
    fun `an account error classifies like Swift's`() {
        assertEquals(ErrorKind.FORBIDDEN, classifyError(NotForAccountException("upgradeGroup")).kind)
        assertEquals(ErrorKind.UNAVAILABLE, classifyError(NoRelayException("getContext")).kind)
        assertEquals(ErrorKind.UNAVAILABLE, classifyError(InvitationNotClaimableException(ns, InvitationNotClaimableReason.NOT_HOSTED)).kind)
    }
}
