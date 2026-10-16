package com.calimero.mero

import com.calimero.mero.admin.NamespaceRevokedDevices
import com.calimero.mero.http.HttpException
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `listNamespaceRevokedDevices` (core `GET /admin-api/namespaces/{id}/revoked-devices`) and
 * `getLoginPage` (core `GET /auth/login`), ported from mero-js `admin-client.ts` / `auth-client.ts`.
 */
class AdminAuthStragglersTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore()))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `listNamespaceRevokedDevices reads the top-level body (no data envelope)`() =
        runBlocking {
            // The shape core's handler serializes (camelCase NamespaceRevokedDevicesApiResponse).
            server.enqueue(MockResponse().setBody("""{"revokedDevices":["${"a".repeat(64)}"],"withdrawnSlots":[]}"""))
            assertEquals(
                NamespaceRevokedDevices(revokedDevices = listOf("a".repeat(64)), withdrawnSlots = emptyList()),
                mero.admin.listNamespaceRevokedDevices("ns1"),
            )
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/admin-api/namespaces/ns1/revoked-devices", req.path)
        }

    @Test
    fun `listNamespaceRevokedDevices surfaces core's 403 for a node bound to no account`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"not a member"}"""))
        val e = runCatching { runBlocking { mero.admin.listNamespaceRevokedDevices("ns1") } }.exceptionOrNull()
        assertTrue(e is HttpException && e.status == 403 && e.errorMessage == "not a member")
    }

    @Test
    fun `getLoginPage returns the auth UI as text`() =
        runBlocking {
            val html = "<!doctype html><html><body><div id=\"root\"></div></body></html>"
            server.enqueue(MockResponse().setBody(html).setHeader("Content-Type", "text/html"))
            assertEquals(html, mero.auth.getLoginPage())
            val req = server.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/auth/login", req.path)
            assertEquals("text/html", req.getHeader("Accept"))
        }
}
