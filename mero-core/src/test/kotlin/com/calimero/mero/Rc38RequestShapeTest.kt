package com.calimero.mero

import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceInvitationRequest
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.GroupMemberInput
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SyncGroupRequest
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What core moved between 0.11.0-rc.32 and rc.38.
 *
 * rc.32 → rc.38 added `deny_unknown_fields` to **37** admin request structs
 * that had been permissive. Nothing about this SDK changed; what changed is
 * that any extra key it sends — tolerated and ignored for releases — became a
 * 400 or 422 for the whole call.
 *
 * So every assertion here is on the **exact key set** of the body. That is the
 * only shape of assertion that catches this: the rest of the suite checks that
 * the keys it cares about are *present*, which passes just as happily with a
 * fatal one beside them. `requester` sat on eighteen request types for exactly
 * that reason — core never had such a field, at rc.32 or at rc.38, so it was
 * silently dropped rather than flagged, and no test looked at what else was on
 * the wire.
 */
class Rc38RequestShapeTest {
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

    private fun capture(block: suspend () -> Unit): RecordedRequest {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    /** Every key actually written to the wire, in the order-independent form worth asserting. */
    private fun keysOf(req: RecordedRequest): Set<String> {
        val body = req.body.readUtf8()
        if (body.isBlank()) return emptySet()
        return Json.parseToJsonElement(body).jsonObject.keys
    }

    @Test
    fun `createNamespace sends applicationId and name only`() {
        val req = capture { mero.admin.createNamespace(CreateNamespaceRequest(applicationId = "app-1", name = "ws")) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces", req.path)
        assertEquals(setOf("applicationId", "name"), keysOf(req))
    }

    /**
     * The subgroup route reads `groupName` and `visibility` — it is not the
     * group-create body. Measured against a live rc.38: `name` and `groupId`
     * are both `422 unknown field, expected 'groupName' or 'visibility'`, which
     * made naming a subgroup fail outright while the empty body kept working.
     */
    @Test
    fun `createGroupInNamespace sends groupName not name`() {
        val req =
            capture {
                mero.admin.createGroupInNamespace(
                    "ns-1",
                    CreateGroupInNamespaceRequest(groupName = "room", visibility = "open"),
                )
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/namespaces/ns-1/groups", req.path)
        assertEquals(setOf("groupName", "visibility"), keysOf(req))
    }

    /** Passing nothing must stay an empty object — the one shape that always worked. */
    @Test
    fun `createGroupInNamespace with no request sends an empty body`() {
        val req = capture { mero.admin.createGroupInNamespace("ns-1") }
        assertEquals(emptySet<String>(), keysOf(req))
    }

    /**
     * `requester` is gone from every request type.
     *
     * It was never a field core accepted — not at rc.32, not at rc.38. It rode
     * along on eighteen bodies, omitted whenever it was null (this SDK sets
     * `explicitNulls = false`), which is why it never showed up: harmless until
     * a caller set it, then a 400 for the whole call on a node that had just
     * closed its bodies.
     */
    @Test
    fun `no request body carries a requester key`() {
        assertEquals(
            setOf("subgroupVisibility"),
            keysOf(capture { mero.admin.setSubgroupVisibility("g-1", SetSubgroupVisibilityRequest(subgroupVisibility = "open")) }),
        )
        assertEquals(
            setOf("members"),
            keysOf(capture { mero.admin.addGroupMembers("g-1", AddGroupMembersRequest(members = listOf(GroupMemberInput(identity = "m-1", role = "member")))) }),
        )
        assertEquals(
            emptySet<String>(),
            keysOf(capture { mero.admin.syncGroup("g-1", SyncGroupRequest()) }),
        )
        assertEquals(
            emptySet<String>(),
            keysOf(capture { mero.admin.createNamespaceInvitation("ns-1", CreateNamespaceInvitationRequest()) }),
        )
    }
}
