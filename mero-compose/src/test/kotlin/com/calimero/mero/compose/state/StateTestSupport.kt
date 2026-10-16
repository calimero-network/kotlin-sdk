package com.calimero.mero.compose.state

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.ContextWithGroup
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.Namespace
import com.calimero.mero.compose.DelegatedOps
import com.calimero.mero.compose.MeroCapabilities
import com.calimero.mero.compose.MeroEvents
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.sse.NodeEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy

/** An [AdminSurface] whose every call throws: the base [StateFakeAdmin] overrides what a test needs. */
fun stubAdminSurface(): AdminSurface =
    Proxy.newProxyInstance(AdminSurface::class.java.classLoader, arrayOf(AdminSurface::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("not faked: ${method.name}")
    } as AdminSurface

/** A test admin: override the calls a test drives; everything else throws. */
open class StateFakeAdmin : AdminSurface by stubAdminSurface()

/** A node session over [admin]. */
fun nodeSession(admin: AdminSurface?): MutableStateFlow<MeroSession> =
    MutableStateFlow(MeroSession(admin = admin, rawAdmin = admin, can = MeroCapabilities.NODE))

/** An in-memory event stream a test pushes frames into. */
class StateTestEvents : MeroEvents {
    override val events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 64)
    override val connects = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
    override val failure: StateFlow<Throwable?> = MutableStateFlow(null)
    override var isConnected: Boolean = true
    val subscribedGroups = mutableListOf<String>()
    val subscribedContexts = mutableListOf<String>()
    var connectCalls = 0
    var subscribeFails: Throwable? = null

    override fun connect() {
        connectCalls++
    }

    override suspend fun subscribe(
        contextIds: Collection<String>,
        groupIds: Collection<String>,
    ) {
        subscribeFails?.let { throw it }
        subscribedContexts += contextIds
        subscribedGroups += groupIds
    }
}

/** A fixed [DelegatedOps]. */
class StateTestDelegatedOps(
    private val contexts: List<ContextWithGroup> = emptyList(),
    private val namespaces: List<Namespace> = emptyList(),
) : DelegatedOps {
    val privateRequests = mutableListOf<com.calimero.mero.compose.CreatePrivateContextRequest>()

    override suspend fun listContexts() = contexts

    override suspend fun listNamespaces() = namespaces

    override suspend fun createPrivateContext(request: com.calimero.mero.compose.CreatePrivateContextRequest): String {
        privateRequests += request
        return "private-ctx"
    }
}

fun ctx(
    id: String,
    applicationId: String,
    groupId: String? = null,
) = ContextWithGroup(id, applicationId, contextStateHash = "h", dagHeads = emptyList(), groupId = groupId)

fun namespace(
    id: String,
    applicationId: String = "app",
    appKey: String = "00",
) = Namespace(id, appKey, applicationId, createdAt = 0, memberCount = 1, contextCount = 0, subgroupCount = 0)

fun groupInfo(
    id: String,
    appKey: String = "00",
    applicationId: String = "app",
) = GroupInfo(id, appKey, applicationId, memberCount = 1, contextCount = 0, defaultCapabilities = 0, subgroupVisibility = "open")
