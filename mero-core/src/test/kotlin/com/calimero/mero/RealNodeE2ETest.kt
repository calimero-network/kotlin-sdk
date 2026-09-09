package com.calimero.mero

import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end tests against a **live** Calimero node — the Kotlin port of the Swift `RealNodeE2ETests`.
 *
 * Skipped automatically unless `MERO_E2E_NODE_URL` is set, so they never affect the normal
 * `testDebugUnitTest` run. The class name matches the `*RealNodeE2ETest*` glob the e2e workflow
 * selects. Environment:
 *
 *   MERO_E2E_NODE_URL          e.g. http://localhost:4001
 *   MERO_E2E_USER              default "dev"
 *   MERO_E2E_PASS              default "dev-password"
 */
class RealNodeE2ETest {
    private fun env(key: String): String? = System.getenv(key)?.takeIf { it.isNotBlank() }

    private fun makeClient(): Mero {
        val url = env("MERO_E2E_NODE_URL") ?: error("MERO_E2E_NODE_URL not set")
        return Mero(MeroConfig(baseUrl = url, timeoutMs = 30_000, tokenStore = MemoryTokenStore()))
    }

    @Test
    fun `node is healthy`() =
        runBlocking {
            assumeTrue("MERO_E2E_NODE_URL not set — skipping live-node e2e", env("MERO_E2E_NODE_URL") != null)
            val mero = makeClient()
            assertEquals("alive", mero.auth.getHealth().status)
        }

    @Test
    fun `full auth journey`() =
        runBlocking {
            assumeTrue("MERO_E2E_NODE_URL not set — skipping live-node e2e", env("MERO_E2E_NODE_URL") != null)
            val mero = makeClient()

            // Providers advertise the password auth method.
            assertTrue(mero.auth.getProviders().count > 0)

            // The admin is created by `merod init`; there is no first-login setup code.
            val creds =
                Credentials(
                    username = env("MERO_E2E_USER") ?: "dev",
                    password = env("MERO_E2E_PASS") ?: "dev-password",
                )
            val tokens = mero.authenticate(creds)
            assertTrue(tokens.accessToken.isNotEmpty())
            assertTrue(tokens.refreshToken.isNotEmpty())
            assertTrue(mero.isAuthenticated)

            // The freshly minted token validates.
            assertTrue(mero.auth.validateToken(tokens.accessToken).valid)

            // A protected admin read succeeds with the bearer token.
            assertTrue(
                mero.admin
                    .getContexts()
                    .contexts.size >= 0,
            )

            // Logout clears local state.
            mero.logout()
            assertFalse(mero.isAuthenticated)
        }

    /**
     * The rc.23–rc.32 surface this SDK gained, driven against the real node.
     *
     * Each of these is either a route the SDK never called or one whose shape
     * moved; a mock test would only re-assert what the model already believes.
     * Notably `listAccountDevices` / `listAccountApplications` answer FLAT, which
     * nothing but a real body settles.
     */
    @Test
    fun `the rc32 admin surface answers on a real node`() =
        runBlocking {
            assumeTrue("MERO_E2E_NODE_URL not set — skipping live-node e2e", env("MERO_E2E_NODE_URL") != null)
            val mero = makeClient()
            mero.authenticate(
                Credentials(
                    username = env("MERO_E2E_USER") ?: "dev",
                    password = env("MERO_E2E_PASS") ?: "dev-password",
                ),
            )

            assertEquals("ready", mero.admin.isReady().status)

            // Replaces the deleted GET /namespaces/{id}/identity (core#3522).
            val identity = mero.admin.getNodeIdentity()
            assertEquals(64, identity.accountId.length)
            assertEquals(64, identity.accountRootPublicKey.length)

            // Flat payloads — no `data` envelope.
            mero.admin.listAccountDevices().devices
            mero.admin.listAccountApplications().applications

            // A namespace list must decode at all: `upgradePolicy` is gone (core#3485),
            // and while it was declared required this threw MissingFieldException.
            val namespaces = mero.admin.listNamespaces()
            namespaces.firstOrNull()?.let { ns ->
                // GroupInfo carries a MetadataRecord whose `updatedAt` is u64 MILLIS;
                // as an Int it overflowed on any namespace that had ever been named.
                val info = mero.admin.getGroupInfo(ns.namespaceId)
                assertEquals(ns.namespaceId, info.groupId)
                mero.admin.listMemberDevices(ns.namespaceId).members

                // An invitation must survive the round trip through the SDK's model:
                // core re-encodes it as borsh and checks the signature, so a dropped
                // field reads as "invalid invitation signature".
                val minted = mero.admin.createNamespaceInvitation(ns.namespaceId)
                if (minted is CreateNamespaceInvitationResult.Single) {
                    val invitation = minted.data.invitation
                    assertTrue(
                        "rc.29+ mints a non-empty signed admitters list",
                        invitation.invitation.admitters.isNotEmpty(),
                    )
                    assertEquals(
                        "re-encoding must not change a single key",
                        invitation.raw,
                        Json.parseToJsonElement(
                            Json.encodeToString(SignedGroupOpenInvitation.serializer(), invitation),
                        ),
                    )
                }
            }
            Unit
        }
}
