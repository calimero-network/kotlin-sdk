package com.calimero.mero.compose.state

import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateContextResponseData
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupInNamespaceResponseData
import com.calimero.mero.admin.GetBlobInfoResponseData
import com.calimero.mero.admin.GetContextsResponseData
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.JoinContextResponseData
import com.calimero.mero.admin.LookupAliasResponseData
import com.calimero.mero.admin.MemberCapabilities
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.SetMemberCapabilitiesRequest
import com.calimero.mero.compose.CreatePrivateContextRequest
import com.calimero.mero.compose.MeroCapabilities
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.http.ErrorKind
import com.calimero.mero.http.HttpException
import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.transport.ExecuteResult
import com.calimero.mero.transport.ExecuteTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-react `hooks/index.test.tsx` + `stale-data.test.tsx`: the read and write state holders. */
@OptIn(ExperimentalCoroutinesApi::class)
class StateHoldersTest {
    // ---- AsyncResource: latest request wins ----------------------------------------------

    @Test
    fun `a slow earlier read never overwrites a newer one`() =
        runTest {
            val slow = CompletableDeferred<String>()
            val r = AsyncResource(backgroundScope, "initial")
            r.setFetcher { slow.await() }
            runCurrent()
            assertTrue(r.loading)
            r.setFetcher { "newer" }
            runCurrent()
            assertEquals("newer", r.data)
            slow.complete("older")
            runCurrent()
            assertEquals("newer", r.data)
            assertFalse(r.loading)
        }

    @Test
    fun `a disabled read resets to the initial value and fetches nothing`() =
        runTest {
            var calls = 0
            val r = AsyncResource(backgroundScope, 0)
            r.setFetcher {
                calls++
                7
            }
            runCurrent()
            assertEquals(7, r.data)
            r.setFetcher(null)
            runCurrent()
            assertEquals(0, r.data)
            assertNull(r.error)
            assertEquals(1, calls)
        }

    @Test
    fun `a failed read reports its error and keeps the reset value`() =
        runTest {
            val r = AsyncResource<String?>(backgroundScope, null)
            r.setFetcher { error("boom") }
            runCurrent()
            assertEquals("boom", r.error!!.message)
            assertNull(r.data)
            assertFalse(r.loading)
        }

    @Test
    fun `useGroupInfo - a slow read for the previous session loses to the new session's`() =
        runTest {
            val slow = CompletableDeferred<GroupInfo>()
            val first =
                object : StateFakeAdmin() {
                    override suspend fun getGroupInfo(groupId: String) = slow.await()
                }
            val second =
                object : StateFakeAdmin() {
                    override suspend fun getGroupInfo(groupId: String) = groupInfo("g", appKey = "new")
                }
            val sessions = nodeSession(first)
            val info = MeroReads(sessions, backgroundScope).groupInfo("g")
            runCurrent()
            sessions.value = MeroSession(admin = second)
            runCurrent()
            assertEquals("new", info.data!!.appKey)
            slow.complete(groupInfo("g", appKey = "old"))
            runCurrent()
            assertEquals("new", info.data!!.appKey)
        }

    @Test
    fun `a read with no admin, or no input, fetches nothing`() =
        runTest {
            val reads = MeroReads(MutableStateFlow(MeroSession()), backgroundScope)
            val members = reads.groupMembers("g")
            runCurrent()
            assertEquals(emptyList<Any>(), members.data)
            assertFalse(members.isEnabled)
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getGroupMetadata(groupId: String): MetadataRecord? = error("must not be asked")
                }
            val none = MeroReads(nodeSession(admin), backgroundScope).groupMetadata(null)
            runCurrent()
            assertNull(none.data)
            assertNull(none.error)
        }

    // ---- Contexts -------------------------------------------------------------------------

    @Test
    fun `contexts of an application on a node use the application listing`() =
        runTest {
            val asked = mutableListOf<String>()
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getContextsForApplication(applicationId: String): GetContextsResponseData {
                        asked += applicationId
                        return GetContextsResponseData(listOf(ctx("c1", applicationId)))
                    }
                }
            val contexts = MeroReads(nodeSession(admin), backgroundScope).applicationContexts("app-1")
            runCurrent()
            assertEquals(listOf(ApplicationContextRecord("c1", "app-1")), contexts.data)
            assertEquals(listOf("app-1"), asked)
        }

    @Test
    fun `an account lists its own contexts across relays and filters by application`() =
        runTest {
            val ops = StateTestDelegatedOps(contexts = listOf(ctx("c1", "chat"), ctx("c2", "kv")))
            val sessions = MutableStateFlow(MeroSession(admin = StateFakeAdmin(), isDelegated = true, delegated = ops))
            val reads = MeroReads(sessions, backgroundScope)
            val chat = reads.contexts("chat")
            val all = reads.contexts()
            runCurrent()
            assertEquals(listOf(ApplicationContextRecord("c1", "chat")), chat.data)
            assertEquals(2, all.data.size)
        }

    @Test
    fun `an account's namespaces for an application are filtered from its own`() =
        runTest {
            val ops = StateTestDelegatedOps(namespaces = listOf(namespace("n1", "chat"), namespace("n2", "kv")))
            val sessions = MutableStateFlow(MeroSession(admin = StateFakeAdmin(), isDelegated = true, delegated = ops))
            val ns = MeroReads(sessions, backgroundScope).namespacesForApplication("kv")
            runCurrent()
            assertEquals(listOf("n2"), ns.data.map { it.namespaceId })
        }

    @Test
    fun `blob info labels a 404 as not found, and nothing else`() =
        runTest {
            var status = 404
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getBlobInfo(
                        blobId: String,
                        contextId: String?,
                    ): GetBlobInfoResponseData = throw HttpException(status, "", emptyMap(), "u")
                }
            val reads = MeroReads(nodeSession(admin), backgroundScope)
            val missing = reads.blobInfo("b")
            runCurrent()
            assertTrue(isBlobNotFound(missing.error))
            status = 500
            val broken = reads.blobInfo("b", contextId = "ctx")
            runCurrent()
            assertNotNull(broken.error)
            assertFalse(isBlobNotFound(broken.error))
        }

    // ---- Writes ---------------------------------------------------------------------------

    @Test
    fun `a write with no admin resolves null without an error`() =
        runTest {
            val create = CreateContext(MutableStateFlow(MeroSession()))
            assertNull(create.createContext(CreateContextRequest("app", "g")))
            assertNull(create.error)
        }

    @Test
    fun `a write records its failure, classified, and resolves null`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun joinContext(contextId: String): JoinContextResponseData = throw HttpException(403, "", emptyMap(), "u")
                }
            val join = JoinContext(nodeSession(admin))
            assertNull(join.joinContext("c"))
            assertEquals(ErrorKind.FORBIDDEN, join.failure!!.kind)
            assertFalse(join.loading)
        }

    @Test
    fun `throwOnError rethrows the original error, and the next success clears the failure`() =
        runTest {
            var fail = true
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun joinContext(contextId: String): JoinContextResponseData {
                        if (fail) throw HttpException(409, "", emptyMap(), "u")
                        return JoinContextResponseData("c", "pk")
                    }
                }
            val join = JoinContext(nodeSession(admin), throwOnError = true)
            val thrown = runCatching { join.joinContext("c") }.exceptionOrNull()
            assertEquals(409, (thrown as HttpException).status)
            assertNotNull(join.failure)
            fail = false
            assertNotNull(join.joinContext("c"))
            assertNull(join.failure)
            assertNull(join.error)
        }

    @Test
    fun `a private context on a node is a restricted group, its members, then the context`() =
        runTest {
            val calls = mutableListOf<String>()
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun createGroupInNamespace(
                        namespaceId: String,
                        request: CreateGroupInNamespaceRequest?,
                    ): CreateGroupInNamespaceResponseData {
                        calls += "group:$namespaceId:${request?.visibility}"
                        return CreateGroupInNamespaceResponseData("dm-group")
                    }

                    override suspend fun addGroupMembers(
                        groupId: String,
                        request: AddGroupMembersRequest,
                    ) {
                        calls += "members:$groupId:" + request.members.joinToString { "${it.identity}/${it.role}" }
                    }

                    override suspend fun createContext(request: CreateContextRequest): CreateContextResponseData {
                        calls += "context:${request.groupId}:${request.applicationId}"
                        return CreateContextResponseData("dm-ctx", "pk")
                    }
                }
            val id = CreatePrivateContext(nodeSession(admin)).createPrivateContext(CreatePrivateContextRequest("ns", "chat", listOf("bob")))
            assertEquals("dm-ctx", id)
            assertEquals(listOf("group:ns:restricted", "members:dm-group:bob/Member", "context:dm-group:chat"), calls)
        }

    @Test
    fun `a private context on an account goes through the relay's ops`() =
        runTest {
            val ops = StateTestDelegatedOps()
            val sessions = MutableStateFlow(MeroSession(admin = StateFakeAdmin(), isDelegated = true, delegated = ops))
            assertEquals("private-ctx", CreatePrivateContext(sessions).createPrivateContext(CreatePrivateContextRequest("ns", "chat", listOf("bob"))))
            assertEquals(listOf("bob"), ops.privateRequests.single().members)
        }

    @Test
    fun `redeem keeps the outcome, and resolves null with no client`() =
        runTest {
            assertNull(RedeemInvitation(MutableStateFlow(MeroSession())).redeem("ns", invitation()))
        }

    private fun invitation() =
        com.calimero.mero.admin
            .SignedGroupOpenInvitation(JsonObject(emptyMap()))

    @Test
    fun `group capabilities load, then a set wins over the read`() =
        runTest {
            var stored = 3
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getMemberCapabilities(
                        groupId: String,
                        identity: String,
                    ) = MemberCapabilities(stored)

                    override suspend fun setMemberCapabilities(
                        groupId: String,
                        identity: String,
                        request: SetMemberCapabilitiesRequest,
                    ) {
                        stored = request.capabilities
                    }
                }
            val caps = GroupCapabilitiesState(nodeSession(admin), backgroundScope, "g", "acct")
            runCurrent()
            assertEquals(3, caps.capabilities)
            assertEquals(7, caps.setCapabilities(7))
            assertEquals(7, caps.capabilities)
            val none = GroupCapabilitiesState(nodeSession(admin), backgroundScope, "g", null)
            runCurrent()
            assertNull(none.capabilities)
            caps.close()
            none.close()
        }

    // ---- Execute --------------------------------------------------------------------------

    private class FakeRpc : ExecuteTransport {
        override val kind = "node"
        override val canSubscribe = true

        override suspend fun execute(
            contextId: String,
            method: String,
            argsJson: JsonObject,
        ): JsonElement = JsonPrimitive("$contextId/$method")

        override suspend fun executeWithMetadata(
            contextId: String,
            method: String,
            argsJson: JsonObject,
        ) = ExecuteResult(execute(contextId, method, argsJson), kind)

        override suspend fun migrateMyEntries(contextId: String) = MigrateMyEntriesSummary(1, 0)

        override suspend fun countMyPending(contextId: String) = 2
    }

    @Test
    fun `execute runs through the session's transport`() =
        runTest {
            val exec = ExecuteState(MutableStateFlow(MeroSession(admin = StateFakeAdmin(), rpc = FakeRpc())), "ctx")
            assertEquals(JsonPrimitive("ctx/get"), exec.execute("get"))
        }

    @Test
    fun `execute with nowhere to send says why - an account with no relay is told about invitations`() =
        runTest {
            val noRelay = ExecuteState(MutableStateFlow(MeroSession(admin = StateFakeAdmin(), isDelegated = true)), "ctx")
            assertNull(noRelay.execute("get"))
            assertSame(NotConnectedException.NO_RELAY, noRelay.state.value.error)
            val signedOut = ExecuteState(MutableStateFlow(MeroSession()), "ctx")
            signedOut.execute("get")
            assertEquals(
                "Not connected",
                signedOut.state.value.error!!
                    .message,
            )
            val noContext = ExecuteState(MutableStateFlow(MeroSession(rpc = FakeRpc())), null)
            assertNull(noContext.execute("get"))
        }

    // ---- Context discovery ------------------------------------------------------------------

    @Test
    fun `discovery finds the first application context that is not already known`() =
        runTest {
            var round = 0
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getContextsForApplication(applicationId: String): GetContextsResponseData {
                        round++
                        val ids = if (round < 3) listOf("known") else listOf("known", "fresh")
                        return GetContextsResponseData(ids.map { ctx(it, applicationId) })
                    }
                }
            val discovery =
                ContextDiscoveryState(
                    nodeSession(admin),
                    ContextDiscoveryOptions("app", knownContextIds = setOf("known"), pollIntervalMs = 1_000, timeoutMs = 10_000),
                    clock = { testScheduler.currentTime },
                )
            assertEquals(ApplicationContextRecord("fresh", "app"), discovery.discover())
            assertEquals(3, round)
            assertEquals("fresh", discovery.context.value!!.contextId)
            assertFalse(discovery.loading.value)
        }

    @Test
    fun `discovery tries the alias first`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun lookupContextAlias(name: String) = LookupAliasResponseData("aliased")

                    override suspend fun getContextsForApplication(applicationId: String): GetContextsResponseData = error("not reached")
                }
            val discovery = ContextDiscoveryState(nodeSession(admin), ContextDiscoveryOptions("app", targetAlias = "main"))
            assertEquals(ApplicationContextRecord("aliased", "app"), discovery.discover())
        }

    @Test
    fun `discovery reports a timeout when nothing new appears`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getContextsForApplication(applicationId: String) = GetContextsResponseData(listOf(ctx("known", applicationId)))
                }
            val discovery =
                ContextDiscoveryState(
                    nodeSession(admin),
                    ContextDiscoveryOptions("app", knownContextIds = setOf("known"), pollIntervalMs = 1_000, timeoutMs = 3_000),
                    clock = { testScheduler.currentTime },
                )
            assertNull(discovery.discover())
            assertTrue(
                discovery.error.value!!
                    .message!!
                    .startsWith("Timed out discovering a context for application app"),
            )
            discovery.reset()
            assertNull(discovery.error.value)
        }

    @Test
    fun `discovery without a session is not connected`() =
        runTest {
            val discovery = ContextDiscoveryState(MutableStateFlow(MeroSession()), ContextDiscoveryOptions("app"))
            assertNull(discovery.discover())
            assertEquals("Not connected", discovery.error.value!!.message)
        }

    // ---- Subscription -----------------------------------------------------------------------

    @Test
    fun `a subscription connects, subscribes its ids and hands over only their events`() =
        runTest {
            val events = StateTestEvents()
            val got = mutableListOf<String>()
            val sub =
                SubscriptionState(
                    MutableStateFlow(MeroSession(admin = StateFakeAdmin(), events = events)),
                    backgroundScope,
                    contextIds = listOf("c1"),
                ) { got += (it as com.calimero.mero.sse.ContextEvent).contextId }
            runCurrent()
            assertEquals(1, events.connectCalls)
            assertEquals(listOf("c1"), events.subscribedContexts)
            events.events.emit(
                com.calimero.mero.sse
                    .ContextEvent("c2", "StateMutation", JsonObject(emptyMap())),
            )
            events.events.emit(
                com.calimero.mero.sse
                    .ContextEvent("c1", "StateMutation", JsonObject(emptyMap())),
            )
            runCurrent()
            assertEquals(listOf("c1"), got)
            sub.close()
        }

    @Test
    fun `an AsyncMutation fail records a refusal that never reached the network`() {
        val m = AsyncMutation()
        m.fail(NotConnectedException())
        assertEquals("Not connected", m.error!!.message)
        assertTrue(m.error is IllegalStateException)
        assertEquals(MeroCapabilities.NONE, MeroSession().can)
    }
}
