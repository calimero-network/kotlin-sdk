package com.calimero.mero.compose

import com.calimero.mero.admin.AdminSurface
import com.calimero.mero.admin.BlobInfo
import com.calimero.mero.admin.DeleteGroupRequest
import com.calimero.mero.admin.DeleteGroupResponseData
import com.calimero.mero.admin.DetachContextFromGroupRequest
import com.calimero.mero.admin.GroupContextEntry
import com.calimero.mero.admin.GroupMember
import com.calimero.mero.admin.ListGroupContextsResponseData
import com.calimero.mero.admin.ListGroupMembersResponseData
import com.calimero.mero.admin.MetadataRecord
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.SetMetadataRequest
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.admin.UploadBlobResponseData
import kotlinx.serialization.json.JsonElement
import java.lang.reflect.Proxy

/** An [AdminSurface] whose every call throws: what [RecordingAdmin] delegates the calls it does not script to. */
private val unscripted: AdminSurface =
    Proxy.newProxyInstance(AdminSurface::class.java.classLoader, arrayOf(AdminSurface::class.java)) { _, method, _ ->
        throw UnsupportedOperationException("unscripted ${method.name}")
    } as AdminSurface

/**
 * An [AdminSurface] that answers the calls [DelegatedAppBackend]'s names, blobs, leaving and
 * members make, and records them. (Swift `RecordingAdmin` in MeroKitUITests.)
 */
@Suppress("TooManyFunctions")
class RecordingAdmin : AdminSurface by unscripted {
    val calls = mutableListOf<String>()
    var metadata: Map<String, MetadataRecord> = emptyMap()
    var members: List<GroupMember> = emptyList()
    var contexts: List<GroupContextEntry> = emptyList()

    private fun describe(request: SetMetadataRequest) =
        "${request.name ?: "-"} ${request.data.orEmpty().toSortedMap().map { "${it.key}=${it.value}" }}"

    override suspend fun listGroupContexts(groupId: String): ListGroupContextsResponseData = contexts.also { calls += "listGroupContexts $groupId" }

    override suspend fun getGroupMetadata(groupId: String): MetadataRecord? = metadata[groupId].also { calls += "getGroupMetadata $groupId" }

    override suspend fun getContextMetadata(
        groupId: String,
        contextId: String,
    ): MetadataRecord? = metadata[contextId].also { calls += "getContextMetadata $groupId $contextId" }

    override suspend fun getMemberMetadata(
        groupId: String,
        identity: String,
    ): MetadataRecord? = metadata[identity].also { calls += "getMemberMetadata $groupId $identity" }

    override suspend fun setGroupMetadata(
        groupId: String,
        request: SetMetadataRequest,
    ) {
        calls += "setGroupMetadata $groupId ${describe(request)}"
    }

    override suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetMetadataRequest,
    ) {
        calls += "setContextMetadata $groupId $contextId ${describe(request)}"
    }

    override suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMetadataRequest,
    ) {
        calls += "setMemberMetadata $groupId $identity ${describe(request)}"
    }

    override suspend fun uploadBlob(request: UploadBlobRequest): UploadBlobResponseData {
        calls += "uploadBlob ${request.contextId} ${request.data.size}"
        return BlobInfo("blob1", request.data.size.toLong())
    }

    override suspend fun getBlob(
        blobId: String,
        contextId: String?,
    ): ByteArray {
        calls += "getBlob $blobId $contextId"
        return "bytes".toByteArray()
    }

    override suspend fun leaveNamespace(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ) {
        calls += "leaveNamespace $namespaceId"
    }

    override suspend fun leaveGroup(
        groupId: String,
        request: Map<String, JsonElement>?,
    ) {
        calls += "leaveGroup $groupId"
    }

    override suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest?,
    ): DeleteGroupResponseData {
        calls += "deleteGroup $groupId"
        return DeleteGroupResponseData(isDeleted = true)
    }

    override suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest?,
    ) {
        calls += "detachContextFromGroup $groupId $contextId"
    }

    override suspend fun listGroupMembers(groupId: String): ListGroupMembersResponseData {
        calls += "listGroupMembers $groupId"
        return ListGroupMembersResponseData(members)
    }

    override suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    ) {
        calls += "updateMemberRole $groupId $identity ${request.role}"
    }

    override suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    ) {
        calls += "removeGroupMembers $groupId ${request.members}"
    }
}
