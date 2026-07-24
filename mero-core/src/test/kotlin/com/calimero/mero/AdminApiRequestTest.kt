package com.calimero.mero

import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.GroupInvitationFromAdmin
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.UpgradePolicy
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Port of the Swift `AdminApiRequestTests`: asserts that a representative set of [com.calimero.mero.admin.AdminApi]
 * methods issue the exact HTTP verb, path, and JSON body the core admin API expects.
 *
 * Each call is driven against a plain [MockWebServer] with a canned, benign envelope enqueued.
 * Individual methods may fail to decode that benign body — that's fine; these tests assert the
 * *request*, not the response, so the decode error is swallowed and the recorded request inspected.
 */
class AdminApiRequestTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mero =
            Mero(
                MeroConfig(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    tokenStore = MemoryTokenStore(),
                    timeoutMs = 5_000,
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Enqueue a benign response, run the admin call (ignoring any decode error), return the request. */
    private fun capture(
        response: String = "{}",
        block: suspend () -> Unit,
    ): RecordedRequest {
        server.enqueue(MockResponse().setBody(response).setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    @Test
    fun `context endpoints use the right verb and path`() {
        var req = capture { mero.admin.getContexts() }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/contexts", req.path)

        req = capture { mero.admin.getContext("ctx-1") }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/contexts/ctx-1", req.path)

        req = capture { mero.admin.deleteContext("ctx-1") }
        assertEquals("DELETE", req.method)
        assertEquals("/admin-api/contexts/ctx-1", req.path)

        req = capture { mero.admin.getContextIdentities("ctx-1") }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/contexts/ctx-1/identities", req.path)

        req = capture { mero.admin.joinContext("ctx-1") }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/contexts/ctx-1/join", req.path)
    }

    @Test
    fun `createContext posts the expected body`() {
        val request = CreateContextRequest(applicationId = "app-1", groupId = "grp-1", name = "My Context")
        val req = capture { mero.admin.createContext(request) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/contexts", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"applicationId\":\"app-1\""))
        assertTrue(body.contains("\"groupId\":\"grp-1\""))
        assertTrue(body.contains("\"name\":\"My Context\""))
    }

    @Test
    fun `application and blob endpoints use the right verb and path`() {
        var req = capture { mero.admin.listApplications() }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/applications", req.path)

        req = capture { mero.admin.listBlobs() }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/blobs", req.path)

        req = capture { mero.admin.getBlobInfo("blob-1") }
        assertEquals("HEAD", req.method)
        assertEquals("/admin-api/blobs/blob-1", req.path)
    }

    @Test
    fun `lookupContextAlias posts to the alias path`() {
        val req = capture { mero.admin.lookupContextAlias("my-alias") }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/alias/lookup/context/my-alias", req.path)
    }

    @Test
    fun `namespace list and create use the right verb, path, and body`() {
        var req = capture { mero.admin.listNamespaces() }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/namespaces", req.path)

        req =
            capture {
                mero.admin.createNamespace(
                    CreateNamespaceRequest(applicationId = "app-1", upgradePolicy = UpgradePolicy.AUTOMATIC, name = "ns"),
                )
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"applicationId\":\"app-1\""))
        assertTrue(body.contains("\"upgradePolicy\":\"Automatic\""))
    }

    @Test
    fun `createGroupInNamespace posts under the namespace`() {
        val req =
            capture {
                mero.admin.createGroupInNamespace("ns-1", CreateGroupInNamespaceRequest(name = "grp"))
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces/ns-1/groups", req.path)
        assertTrue(req.body.readUtf8().contains("\"name\":\"grp\""))
    }

    @Test
    fun `setSubgroupVisibility puts the settings body`() {
        val req =
            capture {
                mero.admin.setSubgroupVisibility("grp-1", SetSubgroupVisibilityRequest(subgroupVisibility = "visible"))
            }
        assertEquals("PUT", req.method)
        assertEquals("/admin-api/groups/grp-1/settings/subgroup-visibility", req.path)
        assertTrue(req.body.readUtf8().contains("\"subgroupVisibility\":\"visible\""))
    }

    @Test
    fun `joinNamespace posts the invitation to the namespace join path`() {
        val invitation =
            SignedGroupOpenInvitation(
                invitation =
                    GroupInvitationFromAdmin(
                        inviterIdentity = emptyList(),
                        groupId = emptyList(),
                        expirationTimestamp = 0,
                        secretSalt = emptyList(),
                    ),
                inviterSignature = "sig",
            )
        val req =
            capture {
                mero.admin.joinNamespace("ns-1", JoinNamespaceRequest(invitation = invitation, groupName = "g"))
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces/ns-1/join", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"invitation\""))
        assertTrue(body.contains("\"inviterSignature\":\"sig\""))
    }

    @Test
    fun `createNamespaceInvitation posts an empty body to the invite path`() {
        val req = capture { mero.admin.createNamespaceInvitation("ns-1") }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces/ns-1/invite", req.path)
        assertEquals("{}", req.body.readUtf8())
    }
}
