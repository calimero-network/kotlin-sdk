package com.calimero.mero.compose.state

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.ContextWithGroup
import com.calimero.mero.admin.GetBlobInfoResponseData
import com.calimero.mero.admin.GroupContextEntry
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.GroupUpgradeStatus
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.admin.OutboundApproval
import com.calimero.mero.admin.SubgroupEntry
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.http.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** A context and the application it runs — mero-react `ApplicationContextRecord`. */
data class ApplicationContextRecord(
    val contextId: String,
    val applicationId: String,
)

private fun ContextWithGroup.record() = ApplicationContextRecord(id, applicationId)

private const val HTTP_NOT_FOUND = 404

/**
 * True when [error] is the node saying "nobody has this blob" (HTTP 404) rather than a transport
 * or auth failure — mero-react's `isBlobNotFound`.
 */
fun isBlobNotFound(error: Throwable?): Boolean = (error as? HttpException)?.status == HTTP_NOT_FOUND

/**
 * The read state holders mero-react's read hooks are (`useContexts`, `useGroupInfo`, …): each one
 * an [AsyncResource] bound to [sessions], so it re-reads on every sign-in, sign-out or relay change
 * and resets when its owner gives it a new input (a new holder). A null input is a disabled read.
 *
 * Every holder runs in [scope]; close it ([AsyncResource.close]) when done with it — the
 * `remember*` composables do.
 */
@Suppress("TooManyFunctions")
class MeroReads(
    private val sessions: StateFlow<MeroSession>,
    private val scope: CoroutineScope,
) {
    /** Any read, derived from the session. */
    fun <T> resource(
        initial: T,
        derive: (MeroSession) -> (suspend () -> T)?,
    ): AsyncResource<T> = AsyncResource(scope, initial).bind(sessions, derive)

    /** A read of the session's admin, disabled while [enabled] is false or there is no admin. */
    fun <T> adminResource(
        initial: T,
        enabled: Boolean = true,
        read: suspend (AdminSurface) -> T,
    ): AsyncResource<T> =
        resource(initial) { s ->
            val admin = s.admin
            if (admin == null || !enabled) null else ({ read(admin) })
        }

    /**
     * The contexts of this connection, optionally of one application — mero-react `useContexts`.
     * An account lists its own contexts on every relay it uses and filters them here (the
     * per-application listing is node-wide, which a relay refuses).
     */
    fun contexts(applicationId: String? = null): AsyncResource<List<ApplicationContextRecord>> =
        resource(emptyList()) { s ->
            val admin = s.admin ?: return@resource null
            val delegated = s.delegated
            if (s.isDelegated && delegated != null) {
                { delegated.listContexts().filter { applicationId == null || it.applicationId == applicationId }.map { it.record() } }
            } else {
                {
                    val listed = if (applicationId != null) admin.getContextsForApplication(applicationId) else admin.getContexts()
                    listed.contexts.map { it.record() }
                }
            }
        }

    /** mero-react `useApplicationContexts`: [contexts] of [applicationId]. */
    fun applicationContexts(applicationId: String?): AsyncResource<List<ApplicationContextRecord>> = contexts(applicationId)

    /** The context's group id — mero-react `useContextGroup`. */
    fun contextGroup(contextId: String?): AsyncResource<String?> = adminResource(null, contextId != null) { it.getContextGroup(contextId!!) }

    // ---- Groups -------------------------------------------------------------------------

    fun groupInfo(groupId: String?): AsyncResource<GroupInfo?> = adminResource(null, groupId != null) { it.getGroupInfo(groupId!!) }

    /** The members, keyed by ACCOUNT (64 hex) — what member-addressing calls take. mero-react `useGroupMembers`. */
    fun groupMembers(groupId: String?): AsyncResource<List<GroupMember>> =
        adminResource(emptyList(), groupId != null) { it.listGroupMembers(groupId!!).members }

    fun groupContexts(groupId: String?): AsyncResource<List<GroupContextEntry>> =
        adminResource(emptyList(), groupId != null) { it.listGroupContexts(groupId!!) }

    fun subgroups(groupId: String?): AsyncResource<List<SubgroupEntry>> = adminResource(emptyList(), groupId != null) { it.listSubgroups(groupId!!) }

    fun defaultCapabilities(groupId: String?): AsyncResource<Int?> = adminResource(null, groupId != null) { it.getDefaultCapabilities(groupId!!) }

    /**
     * mero-react `useSubgroupVisibility`. How the group takes members, not whether
     * it is private: draw a lock from [groupInfo]'s `effectiveVisibility`.
     */
    fun subgroupVisibility(groupId: String?): AsyncResource<String?> = adminResource(null, groupId != null) { it.getSubgroupVisibility(groupId!!) }

    /** The group's in-flight upgrade, or null — mero-react `useGroupUpgradeStatus`. */
    fun groupUpgradeStatus(groupId: String?): AsyncResource<GroupUpgradeStatus?> =
        adminResource(null, groupId != null) { it.getGroupUpgradeStatus(groupId!!) }

    // ---- Metadata ----------------------------------------------------------------------

    fun groupMetadata(groupId: String?): AsyncResource<MetadataRecord?> = adminResource(null, groupId != null) { it.getGroupMetadata(groupId!!) }

    /** [identity] is the member's ACCOUNT, not a signing key. mero-react `useMemberMetadata`. */
    fun memberMetadata(
        groupId: String?,
        identity: String?,
    ): AsyncResource<MetadataRecord?> = adminResource(null, groupId != null && identity != null) { it.getMemberMetadata(groupId!!, identity!!) }

    // ---- Namespaces --------------------------------------------------------------------

    fun namespaces(): AsyncResource<List<Namespace>> = adminResource(emptyList()) { it.listNamespaces() }

    fun namespace(namespaceId: String?): AsyncResource<Namespace?> = adminResource(null, namespaceId != null) { it.getNamespace(namespaceId!!) }

    /** What [namespaceId] approves for outbound requests, one entry per publisher. */
    fun outboundApprovals(namespaceId: String?): AsyncResource<List<OutboundApproval>> =
        adminResource(emptyList(), namespaceId != null) { it.listOutboundApprovals(namespaceId!!) }

    /**
     * Who this node is: `accountId` is what member-addressing takes, `publicKey` the signing key
     * that addresses nobody. On an account, the account itself. mero-react `useNodeIdentity`.
     */
    fun nodeIdentity(): AsyncResource<NodeIdentity?> = adminResource(null) { it.getNodeIdentity() }

    /** The namespaces running [applicationId]; an account filters its own on every relay. mero-react `useNamespacesForApplication`. */
    fun namespacesForApplication(applicationId: String?): AsyncResource<List<Namespace>> =
        resource(emptyList()) { s ->
            val admin = s.admin
            val delegated = s.delegated
            when {
                admin == null || applicationId == null -> null
                s.isDelegated && delegated != null -> {
                    { delegated.listNamespaces().filter { it.targetApplicationId == applicationId } }
                }
                else -> {
                    { admin.listNamespacesForApplication(applicationId) }
                }
            }
        }

    fun namespaceGroups(namespaceId: String?): AsyncResource<List<SubgroupEntry>> =
        adminResource(emptyList(), namespaceId != null) { it.listNamespaceGroups(namespaceId!!) }

    // ---- Blobs -------------------------------------------------------------------------

    /**
     * A blob's presence and size without downloading it — mero-react `useBlobInfo`. Without
     * [contextId] a local read; with one the node probes the context's peers (up to core's ~30 s
     * discovery deadline). [isBlobNotFound] on its error tells "no holder" from a failure.
     */
    fun blobInfo(
        blobId: String?,
        contextId: String? = null,
    ): AsyncResource<GetBlobInfoResponseData?> = adminResource(null, blobId != null) { it.getBlobInfo(blobId!!, contextId) }
}
