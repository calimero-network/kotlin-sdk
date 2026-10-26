package com.calimero.mero

import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceResponseData
import com.calimero.mero.admin.OutboundApproval
import com.calimero.mero.admin.OutboundSelection
import com.calimero.mero.admin.SetOutboundApprovalRequest
import com.calimero.mero.admin.UpgradeGroupRequest
import com.calimero.mero.admin.UpgradeGroupResponseData
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Outbound approval (calimero-network/core#4690): an app's outbound HTTPS request needs the
 * namespace's approval for the app's publisher. Two existing request bodies gained an optional
 * `approveOutbound`, their responses an optional `outboundApproval`, and one route arrived with a
 * `GET` and a `PUT`.
 *
 * Every body core reads here is `deny_unknown_fields`, so request assertions are on the **exact
 * key set**. `OutboundSelection` is hand-serialized on core's side — the string `"manifest"` or a
 * bare array, no tag — and the SDK must emit exactly those two forms.
 */
class OutboundApprovalWireShapeTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    // What Mero's own HttpClient uses, so a body encoded here is the body sent.
    private val wire =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }

    private val approval = OutboundApproval("did:key:z6Mkpublisher", listOf("https://api.stripe.com/v1/"), listOf("https://api.stripe.com/v1/", "https://hooks.example.com/"))

    private val approvalJson =
        """{"signer":"did:key:z6Mkpublisher","prefixes":["https://api.stripe.com/v1/"],""" +
            """"reviewed":["https://api.stripe.com/v1/","https://hooks.example.com/"]}"""

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

    private fun capture(
        response: String = "{}",
        block: suspend () -> Unit,
    ): RecordedRequest {
        server.enqueue(MockResponse().setBody(response).setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    private fun bodyOf(req: RecordedRequest): JsonObject = Json.parseToJsonElement(req.body.readUtf8()).jsonObject

    private fun respond(body: String) = server.enqueue(MockResponse().setBody(body).setHeader("Content-Type", "application/json"))

    // ---- OutboundSelection: exactly two forms ------------------------------

    @Test
    fun `Manifest is the bare string manifest`() {
        assertEquals("\"manifest\"", wire.encodeToString(OutboundSelection.serializer(), OutboundSelection.Manifest))
    }

    @Test
    fun `Prefixes is a bare array of strings, empty included`() {
        val two = OutboundSelection.Prefixes(listOf("https://api.stripe.com/v1/", "https://hooks.example.com/"))
        assertEquals("""["https://api.stripe.com/v1/","https://hooks.example.com/"]""", wire.encodeToString(OutboundSelection.serializer(), two))
        assertEquals("[]", wire.encodeToString(OutboundSelection.serializer(), OutboundSelection.Prefixes(emptyList())))
    }

    @Test
    fun `both forms decode back, and anything else is refused`() {
        assertEquals(OutboundSelection.Manifest, wire.decodeFromString(OutboundSelection.serializer(), "\"manifest\""))
        assertEquals(
            OutboundSelection.Prefixes(listOf("https://a.example/")),
            wire.decodeFromString(OutboundSelection.serializer(), """["https://a.example/"]"""),
        )
        listOf("\"all\"", "{\"prefixes\":[]}", "null", "1").forEach { bad ->
            val error = runCatching { wire.decodeFromString(OutboundSelection.serializer(), bad) }.exceptionOrNull()
            assertTrue("$bad decoded", error is IllegalArgumentException || error is SerializationException)
        }
    }

    // ---- createNamespace / upgradeGroup gain approveOutbound ---------------

    @Test
    fun `createNamespace omits approveOutbound when null`() {
        val req = capture { mero.admin.createNamespace(CreateNamespaceRequest(applicationId = "app-1")) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces", req.path)
        assertEquals(setOf("applicationId"), bodyOf(req).keys)
    }

    @Test
    fun `createNamespace sends approveOutbound as manifest or a list`() {
        var body =
            bodyOf(capture { mero.admin.createNamespace(CreateNamespaceRequest("app-1", approveOutbound = OutboundSelection.Manifest)) })
        assertEquals(setOf("applicationId", "approveOutbound"), body.keys)
        assertEquals(JsonPrimitive("manifest"), body.getValue("approveOutbound"))

        body =
            bodyOf(
                capture {
                    mero.admin.createNamespace(
                        CreateNamespaceRequest("app-1", approveOutbound = OutboundSelection.Prefixes(listOf("https://api.stripe.com/v1/"))),
                    )
                },
            )
        assertEquals(JsonArray(listOf(JsonPrimitive("https://api.stripe.com/v1/"))), body.getValue("approveOutbound"))
    }

    @Test
    fun `upgradeGroup sends approveOutbound only when set`() {
        var req = capture { mero.admin.upgradeGroup("grp-1", UpgradeGroupRequest(targetApplicationId = "app-2")) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/groups/grp-1/upgrade", req.path)
        assertEquals(setOf("targetApplicationId"), bodyOf(req).keys)

        req =
            capture {
                mero.admin.upgradeGroup(
                    "grp-1",
                    UpgradeGroupRequest("app-2", approveOutbound = OutboundSelection.Prefixes(listOf("https://api.stripe.com/v1/"))),
                )
            }
        val body = bodyOf(req)
        assertEquals(setOf("targetApplicationId", "approveOutbound"), body.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("https://api.stripe.com/v1/"))), body.getValue("approveOutbound"))
    }

    @Test
    fun `createNamespace and upgradeGroup decode outboundApproval, and its absence`() {
        respond("""{"data":{"namespaceId":"ns-1","outboundApproval":$approvalJson}}""")
        val created = runBlocking { mero.admin.createNamespace(CreateNamespaceRequest("app-1", approveOutbound = OutboundSelection.Manifest)) }
        assertEquals(approval, created.outboundApproval)

        respond("""{"data":{"namespaceId":"ns-2"}}""")
        assertNull(runBlocking { mero.admin.createNamespace(CreateNamespaceRequest("app-1")) }.outboundApproval)

        respond("""{"data":{"groupId":"grp-1","status":"in_progress","contextsTrialled":0,"outboundApproval":$approvalJson}}""")
        val upgraded = runBlocking { mero.admin.upgradeGroup("grp-1", UpgradeGroupRequest("app-2", approveOutbound = OutboundSelection.Manifest)) }
        assertEquals(approval, upgraded.outboundApproval)

        assertNull(wire.decodeFromString(UpgradeGroupResponseData.serializer(), """{"groupId":"g","status":"s"}""").outboundApproval)
        assertNull(wire.decodeFromString(CreateNamespaceResponseData.serializer(), """{"namespaceId":"n"}""").outboundApproval)
    }

    // ---- GET / PUT /namespaces/{id}/outbound-approvals ---------------------

    @Test
    fun `listOutboundApprovals GETs the namespace's list, one entry per publisher`() {
        respond("""{"data":[$approvalJson,{"signer":"did:key:z6Mkother","prefixes":[],"reviewed":[]}]}""")
        val list = runBlocking { mero.admin.listOutboundApprovals("ns-1") }
        val req = server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
        assertEquals("GET", req.method)
        assertEquals("/admin-api/namespaces/ns-1/outbound-approvals", req.path)
        assertEquals(listOf(approval, OutboundApproval("did:key:z6Mkother", emptyList(), emptyList())), list)
    }

    @Test
    fun `setOutboundApproval PUTs applicationId and approve and nothing else`() {
        val req =
            capture {
                mero.admin.setOutboundApproval("ns-1", SetOutboundApprovalRequest("app-1", OutboundSelection.Manifest))
            }
        assertEquals("PUT", req.method)
        assertEquals("/admin-api/namespaces/ns-1/outbound-approvals", req.path)
        val body = bodyOf(req)
        assertEquals(setOf("applicationId", "approve"), body.keys)
        assertEquals(JsonPrimitive("manifest"), body.getValue("approve"))
    }

    @Test
    fun `an empty list revokes, and goes on the wire as an empty array`() {
        val req =
            capture {
                mero.admin.setOutboundApproval("ns-1", SetOutboundApprovalRequest("app-1", OutboundSelection.Prefixes(emptyList())))
            }
        assertEquals(JsonArray(emptyList()), bodyOf(req).getValue("approve"))
    }

    @Test
    fun `setOutboundApproval decodes core's flattened result`() {
        respond(
            """{"data":{"namespaceId":"ns-1","signer":"did:key:z6Mkpublisher","prefixes":["https://api.stripe.com/v1/"],""" +
                """"reviewed":["https://api.stripe.com/v1/","https://hooks.example.com/"],"published":true}}""",
        )
        val set = runBlocking { mero.admin.setOutboundApproval("ns-1", SetOutboundApprovalRequest("app-1", OutboundSelection.Manifest)) }
        assertEquals("ns-1", set.namespaceId)
        assertTrue(set.published)
        assertEquals(approval, set.approval)

        respond("""{"data":{"namespaceId":"ns-1","signer":"did:key:z6Mkpublisher","prefixes":[],"reviewed":[],"published":false}}""")
        val unchanged = runBlocking { mero.admin.setOutboundApproval("ns-1", SetOutboundApprovalRequest("app-1", OutboundSelection.Prefixes(emptyList()))) }
        assertFalse(unchanged.published)
        assertEquals(emptyList<String>(), unchanged.prefixes)
    }
}
