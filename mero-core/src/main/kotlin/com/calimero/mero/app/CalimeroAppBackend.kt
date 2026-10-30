package com.calimero.mero.app

import com.calimero.mero.AppAccess
import com.calimero.mero.AppSource
import com.calimero.mero.CalimeroClient
import com.calimero.mero.NotFound
import com.calimero.mero.OrgInfo
import com.calimero.mero.OrgRole
import com.calimero.mero.TeamJoin
import com.calimero.mero.TeamRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The real [MeroAppBackend]: an app's screens over a signed-in [CalimeroClient], for the app
 * published as [packageName]. (Swift `CalimeroAppBackend`; replaces 0.x's `DelegatedAppBackend`.)
 *
 * Namespaces are organizations, subgroups are teams (`"{orgId}/{teamId}"`), contexts are app
 * instances of [packageName]; see [MeroAppBackend] for the whole mapping. Names live where 1.0
 * keeps them: a team's and an organization's `name`, an app instance's `name`, overridden by a
 * `name` in its metadata once renamed; a member's display name is `name` in their member metadata.
 */
@Suppress("TooManyFunctions")
class CalimeroAppBackend(
    val client: CalimeroClient,
    /** The app's package (`com.calimero.chat`): what app instances are created from and listed by. */
    val packageName: String,
    /** The signed-in account's id. */
    override val account: String,
) : MeroAppBackend {
    override val contract: ContextContract = CloudContextContract(client)
    override val hasRelay: Boolean = true
    override val canSubscribe: Boolean = true

    override suspend fun listNamespaces(): List<AppNamespace> =
        client.me().orgs.map { org ->
            val members = runCatching { client.getOrg(org.orgId).members.size }.getOrDefault(0)
            AppNamespace(org.orgId, org.name.ifEmpty { null }, packageName, members)
        }

    override suspend fun listSubgroups(namespaceId: String): List<AppSubgroup> {
        val org = client.getOrg(namespaceId)
        val apps = appsByTeam(namespaceId)
        return org.teams.values.map { team ->
            AppSubgroup(teamGroupId(namespaceId, team.teamId), team.name.ifEmpty { null }, apps[team.teamId].orEmpty().map { it.app }, team.join == TeamJoin.Everyone.wire)
        }
    }

    override suspend fun listContexts(groupId: String): List<String> = listContextEntries(groupId).map { it.id }

    override suspend fun listContextEntries(groupId: String): List<AppContextEntry> {
        val ref = GroupRef.parse(groupId)
        return appsByTeam(ref.org)[ref.team].orEmpty().map { AppContextEntry(it.app, it.name) }
    }

    override suspend fun isMember(contextId: String): Boolean =
        try {
            client.getApp(contextId)
            true
        } catch (_: NotFound) {
            false
        }

    override suspend fun joinContext(contextId: String) {
        client.getApp(contextId)
    }

    override suspend fun foundNamespace(name: String): AppFoundedNamespace = AppFoundedNamespace(client.createOrg(name))

    override suspend fun createSubgroup(
        namespaceId: String,
        name: String,
        isPublic: Boolean,
    ): String = teamGroupId(namespaceId, client.createTeam(namespaceId, name, if (isPublic) TeamJoin.Everyone else TeamJoin.Invite))

    override suspend fun createContext(
        groupId: String,
        applicationId: String,
        name: String,
        initArgs: JsonElement,
    ): String {
        val ref = GroupRef.parse(groupId)
        val pkg = applicationId.substringBefore('#').ifEmpty { packageName }
        val service = applicationId.substringAfter('#', "").ifEmpty { null }
        return client.createApp(
            name,
            AppSource.Package(pkg, service = service),
            org = ref.org,
            access = ref.team?.let { AppAccess.Team(it) } ?: AppAccess.Org,
            initArgs = initArgs,
        )
    }

    override suspend fun createInvitation(namespaceId: String): OrgInvitation {
        val token = client.invite(namespaceId, OrgRole.Member)
        val name = runCatching { client.getOrg(namespaceId).name }.getOrNull()
        return OrgInvitation(namespaceId, token, name = name)
    }

    override suspend fun joinNamespace(
        namespaceId: String,
        invitation: OrgInvitation,
    ): AppJoinedNamespace {
        val answer = client.redeemInvite(invitation.token)
        val org = (answer["org"] as? JsonPrimitive)?.contentOrNull ?: invitation.namespaceId
        return AppJoinedNamespace(org, published = true, relayUrl = client.nodeUrl)
    }

    override fun events(contextId: String): Flow<AppContextEvent> = client.events(contextId).map { AppContextEvent(contextId, it.kind, it.data, it.id) }

    // ---- names ---------------------------------------------------------------------------

    override suspend fun groupMetadata(groupId: String): AppMetadata? {
        val ref = GroupRef.parse(groupId)
        val org = client.getOrg(ref.org)
        return if (ref.team == null) {
            metadataOf(org.name, org.metadata)
        } else {
            org.teams[ref.team]?.let { metadataOf(it.name, it.metadata) }
        }
    }

    override suspend fun contextMetadata(
        groupId: String,
        contextId: String,
    ): AppMetadata? {
        val app = client.getApp(contextId)
        return metadataOf((app["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(), app["metadata"] ?: JsonNull)
    }

    override suspend fun setGroupName(
        groupId: String,
        name: String,
    ) {
        val ref = GroupRef.parse(groupId)
        if (ref.team != null) {
            client.updateTeam(ref.org, ref.team, name = name)
        } else {
            client.setOrgMetadata(ref.org, withName(client.getOrg(ref.org).metadata, name))
        }
    }

    override suspend fun setContextName(
        groupId: String,
        contextId: String,
        name: String,
    ) {
        client.setAppMetadata(contextId, withName(client.getApp(contextId)["metadata"] ?: JsonNull, name))
    }

    // ---- blobs ---------------------------------------------------------------------------

    override suspend fun uploadBlob(
        contextId: String,
        data: ByteArray,
    ): AppBlob = client.uploadBlob(contextId, data).let { AppBlob(it.blobId, it.size) }

    override suspend fun getBlob(
        blobId: String,
        contextId: String,
    ): ByteArray = client.downloadBlob(contextId, blobId)

    // ---- leaving and deleting ------------------------------------------------------------

    override suspend fun leaveNamespace(namespaceId: String) {
        client.removeMember(namespaceId, account)
    }

    override suspend fun leaveSubgroup(groupId: String) {
        val ref = GroupRef.parse(groupId)
        val team = ref.team ?: return leaveNamespace(ref.org)
        client.removeTeamMember(ref.org, team, account)
    }

    override suspend fun deleteSubgroup(groupId: String) {
        val ref = GroupRef.parse(groupId)
        client.deleteTeam(ref.org, ref.team ?: throw IllegalArgumentException("$groupId is an organization, not a team"))
    }

    override suspend fun removeContext(
        groupId: String,
        contextId: String,
    ) {
        client.deleteApp(contextId)
    }

    // ---- members -------------------------------------------------------------------------

    override suspend fun listMembers(groupId: String): List<AppMember> {
        val ref = GroupRef.parse(groupId)
        val org = client.getOrg(ref.org)
        val names = org.memberMetadata.mapValues { (_, v) -> ((v as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
        if (ref.team == null) {
            return org.members.map { (account, role) ->
                AppMember(
                    account,
                    if (role == "admin" || role == "owner") {
                        "Admin"
                    } else if (role == "guest") {
                        "ReadOnly"
                    } else {
                        "Member"
                    },
                    names[account],
                )
            }
        }
        val team = org.teams[ref.team] ?: return emptyList()
        val accounts = if (team.join == TeamJoin.Everyone.wire) org.members.keys + team.members.keys else team.members.keys
        return accounts.map { account ->
            val role =
                when {
                    team.members[account] == true -> "Admin"
                    account in team.readOnly -> "ReadOnly"
                    else -> "Member"
                }
            AppMember(account, role, names[account])
        }
    }

    override suspend fun setMemberRole(
        groupId: String,
        identity: String,
        role: GovernanceMemberRole,
    ) {
        val ref = GroupRef.parse(groupId)
        if (ref.team == null) {
            val orgRole =
                when (role) {
                    GovernanceMemberRole.Admin -> OrgRole.Admin
                    GovernanceMemberRole.Member -> OrgRole.Member
                    GovernanceMemberRole.ReadOnly -> OrgRole.Guest
                }
            client.setMember(ref.org, identity, orgRole)
        } else {
            val teamRole =
                when (role) {
                    GovernanceMemberRole.Admin -> TeamRole.Lead
                    GovernanceMemberRole.Member -> TeamRole.Member
                    GovernanceMemberRole.ReadOnly -> TeamRole.ReadOnly
                }
            client.setTeamMember(ref.org, ref.team, identity, role = teamRole)
        }
    }

    override suspend fun removeMember(
        groupId: String,
        identity: String,
    ) {
        val ref = GroupRef.parse(groupId)
        if (ref.team == null) client.removeMember(ref.org, identity) else client.removeTeamMember(ref.org, ref.team, identity)
    }

    override suspend fun setMemberName(
        groupId: String,
        name: String,
    ) {
        val org = GroupRef.parse(groupId).org
        val current = client.getOrg(org).memberMetadata[account] ?: JsonNull
        client.setMemberMetadata(org, account, withName(current, name))
    }

    // ---- helpers -------------------------------------------------------------------------

    /** This package's apps in [org], by team (null: on no team). */
    private suspend fun appsByTeam(org: String): Map<String?, List<com.calimero.mero.ReportedApp>> =
        client
            .appsReportAll(org)
            .apps
            .filter { it.`package`?.`package` == packageName }
            .groupBy { it.team }

    private fun metadataOf(
        name: String,
        metadata: JsonElement,
    ): AppMetadata? {
        val fields = (metadata as? JsonObject).orEmpty()
        val shown = (fields["name"] as? JsonPrimitive)?.contentOrNull ?: name.ifEmpty { null }
        val data = fields.filterKeys { it != "name" }.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: v.toString() }
        if (shown == null && data.isEmpty()) return null
        return AppMetadata(shown, data)
    }

    private fun withName(
        metadata: JsonElement,
        name: String,
    ): JsonObject = JsonObject((metadata as? JsonObject).orEmpty() + ("name" to JsonPrimitive(name)))

    /** The organization [org] as [OrgInfo], for an app's own screens. */
    suspend fun organization(org: String): OrgInfo = client.getOrg(org)
}
