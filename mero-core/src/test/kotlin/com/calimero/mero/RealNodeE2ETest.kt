package com.calimero.mero

import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
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
 *   MERO_AUTH_BOOTSTRAP_SECRET first-login setup code (core#3221), optional
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

            // Authenticate (bootstrap secret only matters on a fresh node's first login).
            val creds =
                Credentials(
                    username = env("MERO_E2E_USER") ?: "dev",
                    password = env("MERO_E2E_PASS") ?: "dev-password",
                    bootstrapSecret = env("MERO_AUTH_BOOTSTRAP_SECRET"),
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
}
