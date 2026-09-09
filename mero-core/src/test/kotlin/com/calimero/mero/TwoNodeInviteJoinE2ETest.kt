package com.calimero.mero

import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.InstallDevApplicationRequest
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole invitation journey, through the SDK's own types, across two real nodes.
 *
 * [InvitationRoundTripTest] proves the model re-encodes byte for byte. This proves
 * the thing that actually matters: an invitation minted by
 * `createNamespaceInvitation`, carried through Kotlin objects, and handed back to
 * `joinNamespace` is still accepted. That is the round trip an app makes — mint,
 * put in a share link, paste, join — and it is exactly where dropping a signed
 * field showed up as `500 invalid invitation signature`, three layers from the
 * cause.
 *
 * Needs a SECOND node, so it self-skips unless `MERO_E2E_NODE_B_URL` is set, and
 * that node must be able to reach the first (mDNS is off by default since rc.26 —
 * pass `--boot-nodes /ip4/127.0.0.1/tcp/<swarm>/p2p/<peer_id>`). TESTING.md §4a.
 */
class TwoNodeInviteJoinE2ETest {
    private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

    private fun client(url: String) = Mero(MeroConfig(baseUrl = url, timeoutMs = 180_000, tokenStore = MemoryTokenStore()))

    private suspend fun Mero.login() =
        authenticate(
            Credentials(
                username = env("MERO_E2E_USER") ?: "dev",
                password = env("MERO_E2E_PASS") ?: "dev-password",
            ),
        )

    @Test
    fun `an invitation carried through the SDK is still accepted by the joiner`() =
        runBlocking {
            val urlA = env("MERO_E2E_NODE_URL")
            val urlB = env("MERO_E2E_NODE_B_URL")
            val bundle = env("MERO_E2E_BUNDLE")
            assumeTrue("MERO_E2E_NODE_B_URL / MERO_E2E_BUNDLE not set — skipping two-node e2e", urlB != null && bundle != null)
            assumeTrue("MERO_E2E_NODE_URL not set", urlA != null)

            val a = client(urlA!!)
            val b = client(urlB!!)
            a.login()
            b.login()

            // Both nodes need the app: since core#3652 an http-registry node serves no
            // bytecode to peers, so the joiner cannot get it from the inviter.
            val appA = a.admin.installDevApplication(InstallDevApplicationRequest(bundle!!)).applicationId
            val appB = b.admin.installDevApplication(InstallDevApplicationRequest(bundle)).applicationId
            assertEquals("ApplicationId is package+signer, so both installs agree", appA, appB)

            val namespaceId = a.admin.createNamespace(CreateNamespaceRequest(applicationId = appA, name = "invite-e2e")).namespaceId

            val minted = a.admin.createNamespaceInvitation(namespaceId)
            assertTrue("expected a single invitation", minted is CreateNamespaceInvitationResult.Single)
            val invitation = (minted as CreateNamespaceInvitationResult.Single).data.invitation

            // The field whose loss makes rc.29+ refuse the join.
            assertTrue("rc.29+ mints a non-empty signed admitters list", invitation.invitation.admitters.isNotEmpty())

            // …and the join the app would make, with the invitation as a Kotlin object.
            val joined = b.admin.joinNamespace(namespaceId, JoinNamespaceRequest(invitation = invitation))

            assertEquals(namespaceId, joined.namespaceId)
            assertTrue("the join must yield an account", !joined.memberAccount.isNullOrEmpty())
        }
}
