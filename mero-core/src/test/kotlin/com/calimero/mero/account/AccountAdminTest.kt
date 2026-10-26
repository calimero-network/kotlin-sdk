package com.calimero.mero.account

import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.ContextGroupResponseData
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.GetContextsResponseData
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.GroupMemberInput
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.ListGroupMembersResponseData
import com.calimero.mero.admin.ListNamespacesResponseData
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.OutboundSelection
import com.calimero.mero.admin.RedeemOutcome
import com.calimero.mero.admin.ReparentGroupRequest
import com.calimero.mero.admin.SetMetadataRequest
import com.calimero.mero.admin.SetOutboundApprovalRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.crypto.Hex
import com.calimero.mero.http.HttpException
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.FoundedNamespace
import com.calimero.mero.relay.GovernResult
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.relay.GovernanceOp
import com.calimero.mero.relay.GovernanceOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `account-admin.test.ts`, with every dependency faked. */
class AccountAdminTest {
    private val acc = "aa".repeat(32)
    private val ns = "11".repeat(32)
    private val sub = "22".repeat(32)
    private val ctx = "33".repeat(32)
    private val relayAcc = "cd".repeat(32)
    private val s = DelegatedSession(acc, "dd".repeat(16), "c0ffee", relayUrl = "https://relay.example")
    private val invitation = SignedGroupOpenInvitation(JsonObject(emptyMap()))

    private inner class Deps : AccountAdminDeps {
        val ops = mutableListOf<Triple<String, String, String>>() // plane, group, op hex
        val known = mutableMapOf<String, String>()
        var refuseJoinOpen: Exception? = null
        var created: CreateDelegatedContextRequest? = null
        var foundWith: Pair<Long?, ApplicationTarget>? = null
        var signedWith: List<String>? = null
        var joins = 0
        var routingNodes: List<String?>? = null

        override suspend fun governGroup(
            session: DelegatedSession,
            groupId: String,
            op: GovernanceOp,
        ): GovernResult {
            ops += Triple("group", groupId, Hex.encode(op.bytes))
            return GovernResult(groupId)
        }

        override suspend fun governRoot(
            session: DelegatedSession,
            groupId: String,
            op: GovernanceOp,
        ): GovernResult {
            refuseJoinOpen?.let { if (Hex.encode(op.bytes).startsWith("07")) throw it }
            ops += Triple("root", groupId, Hex.encode(op.bytes))
            return GovernResult(groupId)
        }

        override suspend fun createContext(
            session: DelegatedSession,
            request: CreateDelegatedContextRequest,
        ): CreatedDelegatedContext {
            created = request
            return CreatedDelegatedContext("44".repeat(32), request.groupId ?: request.namespaceId)
        }

        override suspend fun found(
            session: DelegatedSession,
            defaultCapabilities: Long?,
            application: ApplicationTarget,
        ): FoundedDelegatedNamespace {
            foundWith = defaultCapabilities to application
            return FoundedDelegatedNamespace(FoundedNamespace("55".repeat(32), "66".repeat(32), true), false, "not hosted")
        }

        override suspend fun latestVersion(
            registryUrl: String,
            packageName: String,
        ) = "9.9.9"

        override fun signInvitation(
            session: DelegatedSession,
            namespaceId: String,
            admitters: List<String>,
            members: List<GroupMember>,
            applicationId: String?,
            appKey: String?,
        ): SignedGroupOpenInvitation {
            signedWith = admitters
            return SignedGroupOpenInvitation(JsonObject(emptyMap()))
        }

        override fun namespaceOfGroup(
            session: DelegatedSession,
            groupId: String,
        ): String? = if (groupId == ns) ns else known[groupId]

        override fun rememberGroupNamespace(
            account: String,
            groupId: String,
            namespaceId: String,
        ) {
            known[groupId] = namespaceId
        }

        override val join: (suspend (String, SignedGroupOpenInvitation) -> String) = { n, _ ->
            joins++
            n
        }

        override val routing: (suspend (String) -> List<String?>)? get() = routingNodes?.let { nodes -> { _ -> nodes } }
    }

    private inner class Read(
        val members: List<GroupMember> = listOf(GroupMember(acc, "Admin")),
    ) : AdminSurface by NoRelayAdmin {
        var groupInfoCalls = 0

        override suspend fun getGroupInfo(groupId: String): GroupInfo {
            groupInfoCalls++
            return GroupInfo(groupId, "99".repeat(32), "88".repeat(32), 1, 0, defaultCapabilities = 0, subgroupVisibility = "open", namespaceId = ns)
        }

        override suspend fun listGroupMembers(groupId: String) = ListGroupMembersResponseData(members)

        override suspend fun getContextGroup(contextId: String): ContextGroupResponseData = sub

        override suspend fun listNamespaces(): ListNamespacesResponseData =
            listOf(namespace(ns, "88".repeat(32)), namespace("12".repeat(32), "77".repeat(32)))

        override suspend fun getContexts() = GetContextsResponseData(emptyList())
    }

    private fun namespace(
        id: String,
        app: String,
    ) = Namespace(id, "99".repeat(32), app, 0, memberCount = 1, contextCount = 0, subgroupCount = 0)

    private fun rig(
        read: AdminSurface? = Read(),
        app: AccountApp = AccountApp(packageName = "com.calimero.chat"),
    ): Pair<AdminSurface, Deps> {
        val deps = Deps()
        return createAccountAdmin(s, read, app, deps) to deps
    }

    @Test
    fun `lists its namespaces for an application from its own scoped list`() =
        runBlocking {
            val (admin, _) = rig()
            assertEquals(listOf(ns), admin.listNamespacesForApplication("88".repeat(32)).map { it.namespaceId })
        }

    @Test
    fun `is the account itself when asked who it is`() =
        runBlocking {
            assertEquals(acc, rig().first.getNodeIdentity().accountId)
        }

    @Test
    fun `creates a subgroup Open through the namespace and remembers which namespace it is in`() =
        runBlocking {
            val (admin, deps) = rig()
            val groupId = admin.createGroupInNamespace(ns, CreateGroupInNamespaceRequest(groupName = "general")).groupId
            val (plane, group, op) = deps.ops[0]
            assertEquals("root" to ns, plane to group)
            assertEquals(groupId, op.substring(2, 66))
            assertEquals("00", op.substring(130, 132))
            assertEquals(ns, deps.known[groupId])
            assertEquals(Triple("group", groupId, Hex.encode(GovernanceOps.groupMetadataSetOp(name = "general").bytes)), deps.ops[1])
            admin.createGroupInNamespace(ns, CreateGroupInNamespaceRequest(visibility = CreateGroupInNamespaceRequest.VISIBILITY_RESTRICTED))
            assertEquals("01", deps.ops[2].third.substring(130, 132))
        }

    @Test
    fun `member, metadata, visibility and role writes are group ops on the named group`() =
        runBlocking {
            val (admin, deps) = rig()
            admin.addGroupMembers(ns, AddGroupMembersRequest(listOf(GroupMemberInput("ab".repeat(32), "Admin"))))
            admin.setGroupMetadata(ns, SetMetadataRequest(name = "n"))
            admin.setSubgroupVisibility(ns, SetSubgroupVisibilityRequest("restricted"))
            admin.updateMemberRole(ns, "ab".repeat(32), UpdateMemberRoleRequest("ReadOnly"))
            assertEquals(
                listOf(
                    GovernanceOps.memberAddedOp("ab".repeat(32), GovernanceMemberRole.Admin),
                    GovernanceOps.groupMetadataSetOp(name = "n"),
                    GovernanceOps.subgroupVisibilitySetOp(restricted = true),
                    GovernanceOps.memberRoleSetOp("ab".repeat(32), GovernanceMemberRole.ReadOnly),
                ).map { Triple("group", ns, Hex.encode(it.bytes)) },
                deps.ops,
            )
        }

    @Test
    fun `delete and reparent are root ops, leaving is my own MemberLeft`() =
        runBlocking {
            val (admin, deps) = rig()
            assertTrue(admin.deleteGroup(ns, null).isDeleted)
            assertTrue(admin.reparentGroup(ns, ReparentGroupRequest(sub)).reparented)
            admin.leaveGroup(ns, null)
            assertEquals(listOf("root", "root", "group"), deps.ops.map { it.first })
            assertEquals(Hex.encode(GovernanceOps.memberLeftOp(acc).bytes), deps.ops[2].third)
        }

    @Test
    fun `governs a subgroup it was never told about, asking the relay its namespace once`() =
        runBlocking {
            val read = Read()
            val (admin, deps) = rig(read)
            admin.setGroupMetadata(sub, SetMetadataRequest(name = "x"))
            admin.setGroupMetadata(sub, SetMetadataRequest(name = "y"))
            assertEquals(ns, deps.known[sub])
            assertEquals(1, read.groupInfoCalls)
        }

    @Test
    fun `joins an open channel by its context, and a direct member's join is a no-op`() =
        runBlocking {
            val (admin, deps) = rig()
            assertEquals(acc, admin.joinContext(ctx).memberPublicKey)
            assertEquals("root" to Hex.encode(GovernanceOps.memberJoinedOpenOp(acc, sub, s.credential).bytes), deps.ops[0].first to deps.ops[0].third)
            deps.refuseJoinOpen = HttpException(409, "signer $acc is a direct member; use MemberJoined or add_group_members instead", emptyMap(), "")
            assertFalse(admin.joinSubgroupInheritance(sub).wasInherited)
            deps.refuseJoinOpen = HttpException(403, "nope", emptyMap(), "")
            assertTrue(runCatching { admin.joinContext(ctx) }.exceptionOrNull() is HttpException)
        }

    @Test
    fun `owns its account as the identity of a context whose group it belongs to`() =
        runBlocking {
            assertEquals(listOf(acc), rig().first.getContextIdentitiesOwned(ctx).identities)
            assertEquals(emptyList<String>(), rig(Read(members = emptyList())).first.getContextIdentitiesOwned(ctx).identities)
        }

    @Test
    fun `creates a context through the relay in the group it names, the account its member key`() =
        runBlocking {
            val (admin, deps) = rig()
            val created = admin.createContext(CreateContextRequest("88".repeat(32), sub, serviceName = "docs", contextSeed = "5e".repeat(32)))
            assertEquals(acc, created.memberPublicKey)
            assertEquals(CreateDelegatedContextRequest(ns, "88".repeat(32), sub, "docs", "5e".repeat(32)), deps.created)
        }

    @Test
    fun `founds a namespace with this app's package at the registry's latest version, HA riding along`() =
        runBlocking {
            val (admin, deps) = rig()
            val result = admin.createNamespace(CreateNamespaceRequest("88".repeat(32), name = "Team"))
            assertEquals(231L to ApplicationTarget("88".repeat(32), "com.calimero.chat", "9.9.9"), deps.foundWith)
            assertEquals("55".repeat(32), result.namespaceId)
            assertEquals(false, result.haEnabled)
            assertEquals("not hosted", result.haError)
            assertEquals("group", deps.ops.single().first)
            val noPackage = rig(app = AccountApp()).first
            assertTrue(runCatching { noPackage.createNamespace(CreateNamespaceRequest("88".repeat(32))) }.exceptionOrNull() is NotForAccountException)
        }

    @Test
    fun `refuses outbound approval writes by name, before founding anything`() =
        runBlocking {
            val (admin, deps) = rig()
            val approving = CreateNamespaceRequest("88".repeat(32), approveOutbound = OutboundSelection.Manifest)
            val refused = runCatching { admin.createNamespace(approving) }.exceptionOrNull() as NotForAccountException
            assertEquals("createNamespace with approveOutbound", refused.method)
            assertNull(deps.foundWith)
            val set = SetOutboundApprovalRequest("88".repeat(32), OutboundSelection.Prefixes(emptyList()))
            assertTrue(runCatching { admin.setOutboundApproval(ns, set) }.exceptionOrNull() is NotForAccountException)
            assertTrue(runCatching { rig(read = null).first.setOutboundApproval(ns, set) }.exceptionOrNull() is NoRelayException)
            assertTrue(deps.ops.isEmpty())
        }

    @Test
    fun `names the namespace's relays as an invitation's admitters, else the admins`() =
        runBlocking {
            val (withRelay, d1) = rig(Read(listOf(GroupMember(acc, "Admin"), GroupMember(relayAcc.uppercase(), "RelayTee"))))
            assertTrue(withRelay.createNamespaceInvitation(ns, null) is CreateNamespaceInvitationResult.Single)
            assertEquals(listOf(relayAcc), d1.signedWith)
            val (admins, d2) = rig()
            admins.createNamespaceInvitation(ns, null)
            assertEquals(emptyList<String>(), d2.signedWith)
        }

    @Test
    fun `refuses an invitation nobody could claim, without signing`() =
        runBlocking {
            val (admin, deps) = rig(Read(listOf(GroupMember(relayAcc, "RelayTee"))))
            deps.routingNodes = emptyList()
            val notHosted = runCatching { admin.createNamespaceInvitation(ns, null) }.exceptionOrNull() as InvitationNotClaimableException
            assertEquals(InvitationNotClaimableReason.NOT_HOSTED, notHosted.reason)
            deps.routingNodes = listOf("ff".repeat(32), null)
            val unnamed = runCatching { admin.createNamespaceInvitation(ns, null) }.exceptionOrNull() as InvitationNotClaimableException
            assertEquals(InvitationNotClaimableReason.NO_NAMED_NODE, unnamed.reason)
            assertNull(deps.signedWith)
            deps.routingNodes = listOf(relayAcc)
            admin.createNamespaceInvitation(ns, null)
            assertEquals(listOf(relayAcc), deps.signedWith)
        }

    @Test
    fun `a node-only call is refused by name, and syncs are no-ops`() =
        runBlocking {
            val (admin, deps) = rig()
            listOf<suspend () -> Unit>(
                {
                    admin.upgradeGroup(
                        ns,
                        com.calimero.mero.admin
                            .UpgradeGroupRequest("x"),
                    )
                },
                { admin.deleteContext(ctx, null) },
                { admin.leaveContext(ctx, null) },
                { admin.listContextAliases() },
                { admin.installFromRegistry("p", "1") },
            ).forEach { call -> assertTrue(runCatching { call() }.exceptionOrNull() is NotForAccountException) }
            assertEquals(ns, admin.syncGroup(ns, null).groupId)
            admin.syncContext(ctx)
            assertTrue(deps.ops.isEmpty())
        }

    @Test
    fun `joins a namespace and redeems an invitation through the join handler`() =
        runBlocking {
            val (admin, deps) = rig()
            assertEquals(ns, admin.joinNamespace(ns, JoinNamespaceRequest(invitation)).namespaceId)
            assertEquals(RedeemOutcome.Joined(ns, "Team"), admin.redeemInvitation(ns, invitation, teamName = "Team"))
            assertEquals(2, deps.joins)
        }

    @Test
    fun `with no relay yet it is a member of nothing, knows who it is, joins, and refuses the rest`() =
        runBlocking {
            val (admin, deps) = rig(read = null)
            assertTrue(admin.listNamespaces().isEmpty())
            assertTrue(admin.listNamespacesForApplication("88".repeat(32)).isEmpty())
            assertTrue(admin.getContexts().contexts.isEmpty())
            assertEquals(acc, admin.getNodeIdentity().accountId)
            admin.joinNamespace(ns, JoinNamespaceRequest(invitation))
            assertEquals(1, deps.joins)
            val e = runCatching { admin.getContext(ctx) }.exceptionOrNull() as NoRelayException
            assertEquals("getContext", e.method)
            assertTrue(runCatching { admin.setGroupMetadata(ns, SetMetadataRequest(name = "x")) }.exceptionOrNull() is NoRelayException)
            assertTrue(
                runCatching {
                    admin.upgradeGroup(
                        ns,
                        com.calimero.mero.admin
                            .UpgradeGroupRequest("x"),
                    )
                }.exceptionOrNull() is NoRelayException,
            )
            assertTrue(deps.ops.isEmpty())
        }
}
