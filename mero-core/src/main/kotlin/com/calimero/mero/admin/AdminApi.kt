package com.calimero.mero.admin

import com.calimero.mero.auth.ApiEnvelope
import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.deleteJson
import com.calimero.mero.http.getJson
import com.calimero.mero.http.postJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Compare two dotted version strings, ascending: negative if `a < b`, positive if
 * `a > b`, `0` if equal. Components compared numerically when both parse as ints
 * (so `1.10.0 > 1.9.0`), else lexically; a missing component is `0`.
 */
fun compareSemver(a: String, b: String): Int {
    val pa = a.split(".")
    val pb = b.split(".")
    val n = maxOf(pa.size, pb.size)
    for (i in 0 until n) {
        val cmp = compareSemverComponent(pa.getOrElse(i) { "0" }, pb.getOrElse(i) { "0" })
        if (cmp != 0) return cmp
    }
    return 0
}

/** Compare one version component: numerically when both parse as ints, else lexically (±1). */
private fun compareSemverComponent(sa: String, sb: String): Int {
    val na = sa.toIntOrNull()
    val nb = sb.toIntOrNull()
    if (na != null && nb != null) return na - nb
    return sa.compareTo(sb).let { if (it == 0) 0 else if (it < 0) -1 else 1 }
}

// ---- Internal wire structs (flat / snake_case tolerances) ------------------

@Serializable
private data class BlobWire(@SerialName("blob_id") val blobId: String, val size: Int)

@Serializable
private data class BlobsWire(val blobs: List<BlobWire>)

@Serializable
private data class DeleteBlobWire(@SerialName("blob_id") val blobId: String, val deleted: Boolean)

@Serializable
private data class ListPackagesWire(val packages: List<String>? = null, val data: ListPackagesResponseData? = null)

@Serializable
private data class ListVersionsWire(val versions: List<String>? = null, val data: ListVersionsResponseData? = null)

@Serializable
private data class NamespaceIdentityWire(
    val namespaceId: String? = null,
    val publicKey: String? = null,
    val data: NamespaceIdentity? = null,
)

@Serializable
private data class ContextsWithExecutorsWrap(
    val contexts: List<ContextWithExecutors>? = null,
    val data: List<ContextWithExecutors>? = null,
)

@Serializable
private data class ListGroupMembersLenient(
    val members: List<GroupMember>? = null,
    val selfIdentity: String? = null,
)

@Serializable
private data class TeeAdmissionEnv(val data: GetTeeAdmissionPolicyResponseData? = null)

@Serializable
private data class MetadataEnv(val data: MetadataRecord? = null)

@Serializable
private data class ReparentEnv(val data: ReparentGroupResponseData? = null, val reparented: Boolean? = null)

@Serializable
private data class SubgroupsWire(val subgroups: List<SubgroupEntry>? = null, val data: List<SubgroupEntry>? = null)

/**
 * Admin API client — ported 1:1 from the Swift MeroKit `AdminApi.swift` (itself a
 * 1:1 port of mero-js `admin-client.ts`).
 *
 * Every method mirrors the source: same HTTP verb, path, query params, request-body
 * shape, and response unwrapping. Most reads unwrap core's `{ data: T }` envelope
 * ([ApiEnvelope]) and throw when the inner payload is missing (or fall back to an
 * empty list for list returns); the handful of endpoints core serializes flat (or
 * that legitimately return `null`) are handled explicitly and documented at each site.
 */
class AdminApi(private val http: HttpClient) {

    // ---- Health and Status (public, no auth) -------------------------------

    suspend fun healthCheck(): HealthStatus =
        http.getJson<ApiEnvelope<HealthStatus>>("/admin-api/health").data ?: error("healthCheck")

    /** NOTE: not unwrapped — returns the whole `{ data: { status } }` envelope. */
    suspend fun isAuthed(): AdminAuthStatus =
        http.getJson("/admin-api/is-authed")

    // ---- Application Management --------------------------------------------

    suspend fun installApplication(request: InstallApplicationRequest): InstallApplicationResponseData =
        http.postJson<InstallApplicationRequest, ApiEnvelope<InstallApplicationResponseData>>(
            "/admin-api/install-application", request,
        ).data ?: error("installApplication")

    /**
     * Resolve a `package@version` to its registry artifact URL and install it.
     * Fetches the bundle manifest from the registry (off-node, via [URL]), derives
     * the `.mpk` artifact URL, then calls [installApplication]. `registryUrl` is the
     * registry origin.
     */
    suspend fun installFromRegistry(
        registryUrl: String,
        packageName: String,
        version: String,
    ): InstallApplicationResponseData {
        val base = origin(registryUrl)
        val manifestUrl = "$base/api/v2/bundles/${encodeComponent(packageName)}/${encodeComponent(version)}"
        val body = fetchExternal(manifestUrl, "registry manifest fetch failed for $packageName@$version")
        val bundle = http.json.decodeFromString<RegistryBundleManifest>(body)
        val pkg = encodeComponent(bundle.packageName)
        val ver = encodeComponent(bundle.appVersion)
        val artifactUrl = "$base/artifacts/$pkg/$ver/$pkg-$ver.mpk"
        return installApplication(
            InstallApplicationRequest(
                url = artifactUrl,
                metadata = emptyList(),
                packageName = bundle.packageName,
                version = bundle.appVersion,
            ),
        )
    }

    /**
     * List a package's published versions from the registry, newest-first by semver.
     * Reads `GET {registry}/api/v2/bundles?package={package}` and takes each bundle's
     * `appVersion`. Registry-side data — distinct from the node's installed-version list.
     */
    suspend fun getRegistryVersions(registryUrl: String, packageName: String): List<String> {
        val base = origin(registryUrl)
        val url = "$base/api/v2/bundles?package=${encodeComponent(packageName)}"
        val body = fetchExternal(url, "registry versions fetch failed for $packageName")
        val bundles = http.json.decodeFromString<List<RegistryBundleManifest>>(body)
        return bundles.map { it.appVersion }.sortedWith { x, y -> -compareSemver(x, y) }
    }

    suspend fun installDevApplication(request: InstallDevApplicationRequest): InstallApplicationResponseData =
        http.postJson<InstallDevApplicationRequest, ApiEnvelope<InstallApplicationResponseData>>(
            "/admin-api/install-dev-application", request,
        ).data ?: error("installDevApplication")

    suspend fun uninstallApplication(appId: String): UninstallApplicationResponseData =
        http.deleteJson<ApiEnvelope<UninstallApplicationResponseData>>("/admin-api/applications/$appId").data
            ?: error("uninstallApplication")

    suspend fun listApplications(): ListApplicationsResponseData =
        http.getJson<ApiEnvelope<ListApplicationsResponseData>>("/admin-api/applications").data
            ?: error("listApplications")

    suspend fun getApplication(appId: String): GetApplicationResponseData =
        http.getJson<ApiEnvelope<GetApplicationResponseData>>("/admin-api/applications/$appId").data
            ?: error("getApplication")

    /** Installed-blob inventory for an application — one entry per locally installed version. */
    suspend fun listApplicationVersions(applicationId: String): List<ApplicationVersionEntry> =
        http.getJson<ApiEnvelope<List<ApplicationVersionEntry>>>("/admin-api/applications/$applicationId/versions").data
            ?: emptyList()

    // ---- Package Management ------------------------------------------------

    suspend fun listPackages(): ListPackagesResponseData {
        // Core returns this flat ({ packages: [...] }), not under `data`; tolerate both.
        val res = http.execute("GET", "/admin-api/packages").ensureSuccessful()
        val wire = http.json.decodeFromString<ListPackagesWire>(res.body)
        return wire.data ?: ListPackagesResponseData(packages = wire.packages ?: emptyList())
    }

    suspend fun listPackageVersions(packageName: String): ListVersionsResponseData {
        // Core returns this flat ({ versions: [...] }), not under `data`; tolerate both.
        val res = http.execute("GET", "/admin-api/packages/${encodeComponent(packageName)}/versions").ensureSuccessful()
        val wire = http.json.decodeFromString<ListVersionsWire>(res.body)
        return wire.data ?: ListVersionsResponseData(versions = wire.versions ?: emptyList())
    }

    suspend fun getLatestPackageVersion(packageName: String): GetLatestVersionResponseData =
        http.getJson("/admin-api/packages/${encodeComponent(packageName)}/latest")

    // ---- Context Management ------------------------------------------------

    suspend fun createContext(request: CreateContextRequest): CreateContextResponseData {
        // Core requires `initializationParams`; default it to an empty byte array.
        val body = if (request.initializationParams == null) request.copy(initializationParams = emptyList()) else request
        return http.postJson<CreateContextRequest, ApiEnvelope<CreateContextResponseData>>(
            "/admin-api/contexts", body,
        ).data ?: error("createContext")
    }

    suspend fun deleteContext(
        contextId: String,
        request: DeleteContextRequest? = null,
    ): DeleteContextResponseData {
        val body = request?.let { http.json.encodeToString(it) }
        val res = http.execute("DELETE", "/admin-api/contexts/$contextId", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteContextResponseData>>(res.body).data
            ?: error("deleteContext")
    }

    suspend fun getContexts(): GetContextsResponseData =
        http.getJson<ApiEnvelope<GetContextsResponseData>>("/admin-api/contexts").data ?: error("getContexts")

    suspend fun getContext(contextId: String): Context =
        http.getJson<ApiEnvelope<Context>>("/admin-api/contexts/$contextId").data ?: error("getContext")

    suspend fun getContextsForApplication(applicationId: String): GetContextsResponseData =
        http.getJson<ApiEnvelope<GetContextsResponseData>>("/admin-api/contexts/for-application/$applicationId").data
            ?: error("getContextsForApplication")

    // ---- Context Identity --------------------------------------------------

    suspend fun generateContextIdentity(): GenerateContextIdentityResponseData =
        http.postJson<JsonObject, ApiEnvelope<GenerateContextIdentityResponseData>>(
            "/admin-api/identity/context", JsonObject(emptyMap()),
        ).data ?: error("generateContextIdentity")

    suspend fun getContextIdentities(contextId: String): GetContextIdentitiesResponseData =
        http.getJson<ApiEnvelope<GetContextIdentitiesResponseData>>("/admin-api/contexts/$contextId/identities").data
            ?: error("getContextIdentities")

    suspend fun getContextIdentitiesOwned(contextId: String): GetContextIdentitiesResponseData =
        http.getJson<ApiEnvelope<GetContextIdentitiesResponseData>>(
            "/admin-api/contexts/$contextId/identities-owned",
        ).data ?: error("getContextIdentitiesOwned")

    // ---- Context join (group membership) -----------------------------------

    suspend fun joinContext(contextId: String): JoinContextResponseData =
        http.postJson<JsonObject, ApiEnvelope<JoinContextResponseData>>(
            "/admin-api/contexts/$contextId/join", JsonObject(emptyMap()),
        ).data ?: error("joinContext")

    // ---- Context group / storage / sync ------------------------------------

    /** Value is `string | null`; returns the optional directly (does NOT throw on null). */
    suspend fun getContextGroup(contextId: String): ContextGroupResponseData =
        http.getJson<ApiEnvelope<String>>("/admin-api/contexts/$contextId/group").data

    suspend fun getContextStorage(contextId: String): ContextStorageResponseData =
        http.getJson<ApiEnvelope<ContextStorageResponseData>>("/admin-api/contexts/$contextId/storage").data
            ?: error("getContextStorage")

    suspend fun syncContext(contextId: String? = null) {
        http.execute("POST", "/admin-api/contexts/sync/${contextId ?: ""}", "{}").ensureSuccessful()
    }

    /**
     * Kick off a full state re-pull for a context (operator recovery for a stranded
     * context). `force` re-pulls even when the node does not flag the context as stranded.
     */
    suspend fun resyncContext(
        contextId: String,
        request: ResyncContextRequest = ResyncContextRequest(),
    ): ResyncContextResponseData {
        // Core's `ResyncContextApiResponse` is a flat payload (no inner `data`), so parse
        // directly. An empty 2xx body means accepted; synthesize a typed result.
        val res = http.execute("POST", "/admin-api/contexts/$contextId/resync", http.json.encodeToString(request))
            .ensureSuccessful()
        return runCatching { http.json.decodeFromString<ResyncContextResponseData>(res.body) }.getOrNull()
            ?: ResyncContextResponseData(contextId = contextId, resyncStarted = true)
    }

    suspend fun inviteSpecializedNode(request: InviteSpecializedNodeRequest): InviteSpecializedNodeResponseData =
        http.postJson<InviteSpecializedNodeRequest, ApiEnvelope<InviteSpecializedNodeResponseData>>(
            "/admin-api/contexts/invite-specialized-node", request,
        ).data ?: error("inviteSpecializedNode")

    suspend fun updateContextApplication(contextId: String, request: UpdateContextApplicationRequest) {
        http.execute("POST", "/admin-api/contexts/$contextId/application", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    suspend fun getContextsWithExecutorsForApplication(applicationId: String): ContextsWithExecutorsResponseData {
        // Core returns this flat as { contexts: [...] }; tolerate a bare array and { data } too.
        val res = http.execute("GET", "/admin-api/contexts/with-executors/for-application/$applicationId")
            .ensureSuccessful()
        runCatching { http.json.decodeFromString<List<ContextWithExecutors>>(res.body) }.getOrNull()?.let { return it }
        val wrap = runCatching { http.json.decodeFromString<ContextsWithExecutorsWrap>(res.body) }.getOrNull()
        return wrap?.contexts ?: wrap?.data ?: emptyList()
    }

    // ---- Blob Management ---------------------------------------------------

    suspend fun uploadBlob(request: UploadBlobRequest): UploadBlobResponseData {
        // Core streams the raw request body into blob storage (no JSON) and takes its
        // params from the query string (`hash`, `context_id` — snake_case).
        val query = buildList {
            request.hash?.let { add("hash=${encodeComponent(it)}") }
            request.contextId?.let { add("context_id=${encodeComponent(it)}") }
        }
        val path = if (query.isEmpty()) "/admin-api/blobs" else "/admin-api/blobs?" + query.joinToString("&")
        // Body streamed verbatim as octet-stream. Core's BlobInfo is snake_case (`blob_id`).
        val res = http.execute(
            "PUT", path, String(request.data, Charsets.ISO_8859_1), contentType = "application/octet-stream",
        ).ensureSuccessful()
        val inner = http.json.decodeFromString<ApiEnvelope<BlobWire>>(res.body).data ?: error("uploadBlob")
        return BlobInfo(blobId = inner.blobId, size = inner.size)
    }

    suspend fun deleteBlob(blobId: String): DeleteBlobResponseData {
        // Core's `BlobDeleteResponse` is flat, snake_case (`{ blob_id, deleted }`).
        val wire = http.deleteJson<DeleteBlobWire>("/admin-api/blobs/$blobId")
        return DeleteBlobResponseData(blobId = wire.blobId, deleted = wire.deleted)
    }

    suspend fun listBlobs(): ListBlobsResponseData {
        // Core's BlobInfo is snake_case (`blob_id`); map to camelCase.
        val inner = http.getJson<ApiEnvelope<BlobsWire>>("/admin-api/blobs").data ?: error("listBlobs")
        return ListBlobsResponseData(blobs = inner.blobs.map { BlobInfo(blobId = it.blobId, size = it.size) })
    }

    /**
     * Download a blob's raw bytes. `GET /admin-api/blobs/:id` streams the blob content
     * (e.g. `application/gzip`), NOT JSON. Use [listBlobs] for `{ blobId, size }` metadata.
     */
    suspend fun getBlob(blobId: String): ByteArray =
        http.execute("GET", "/admin-api/blobs/$blobId").ensureSuccessful().body.toByteArray(Charsets.ISO_8859_1)

    /**
     * Fetch a blob's metadata without downloading it. `HEAD /admin-api/blobs/:id` returns
     * the info in response headers (size via `content-length`, plus `x-blob-*`). Header
     * names are matched case-insensitively.
     */
    suspend fun getBlobInfo(blobId: String): GetBlobInfoResponseData {
        val res = http.execute("HEAD", "/admin-api/blobs/$blobId").ensureSuccessful()
        val headers = res.headers.entries.associate { it.key.lowercase() to it.value }
        return GetBlobInfoResponseData(
            blobId = headers["x-blob-id"] ?: blobId,
            size = headers["content-length"]?.toIntOrNull() ?: 0,
            hash = headers["x-blob-hash"],
            mimeType = headers["x-blob-mime-type"],
        )
    }

    // ---- Alias Management --------------------------------------------------

    suspend fun createContextAlias(request: CreateContextAliasRequest): CreateAliasResponseData =
        http.postJson<CreateContextAliasRequest, ApiEnvelope<CreateAliasResponseData>>(
            "/admin-api/alias/create/context", request,
        ).data ?: error("createContextAlias")

    suspend fun createApplicationAlias(request: CreateApplicationAliasRequest): CreateAliasResponseData =
        http.postJson<CreateApplicationAliasRequest, ApiEnvelope<CreateAliasResponseData>>(
            "/admin-api/alias/create/application", request,
        ).data ?: error("createApplicationAlias")

    suspend fun lookupContextAlias(name: String): LookupAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<LookupAliasResponseData>>(
            "/admin-api/alias/lookup/context/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("lookupContextAlias")

    suspend fun lookupApplicationAlias(name: String): LookupAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<LookupAliasResponseData>>(
            "/admin-api/alias/lookup/application/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("lookupApplicationAlias")

    suspend fun deleteContextAlias(name: String): DeleteAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<DeleteAliasResponseData>>(
            "/admin-api/alias/delete/context/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("deleteContextAlias")

    suspend fun deleteApplicationAlias(name: String): DeleteAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<DeleteAliasResponseData>>(
            "/admin-api/alias/delete/application/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("deleteApplicationAlias")

    suspend fun listContextAliases(): ListAliasesResponseData =
        http.getJson<ApiEnvelope<ListAliasesResponseData>>("/admin-api/alias/list/context").data
            ?: error("listContextAliases")

    suspend fun listApplicationAliases(): ListAliasesResponseData =
        http.getJson<ApiEnvelope<ListAliasesResponseData>>("/admin-api/alias/list/application").data
            ?: error("listApplicationAliases")

    // ---- Context Identity Aliases ------------------------------------------

    suspend fun listContextIdentityAliases(contextId: String): ListContextIdentityAliasesResponseData =
        http.getJson<ApiEnvelope<ListContextIdentityAliasesResponseData>>(
            "/admin-api/alias/list/identity/$contextId",
        ).data ?: error("listContextIdentityAliases")

    suspend fun createContextIdentityAlias(
        contextId: String,
        request: CreateContextIdentityAliasRequest,
    ): CreateContextIdentityAliasResponseData =
        http.postJson<CreateContextIdentityAliasRequest, ApiEnvelope<CreateContextIdentityAliasResponseData>>(
            "/admin-api/alias/create/identity/$contextId", request,
        ).data ?: error("createContextIdentityAlias")

    suspend fun lookupContextIdentityAlias(
        contextId: String,
        name: String,
    ): LookupContextIdentityAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<LookupContextIdentityAliasResponseData>>(
            "/admin-api/alias/lookup/identity/$contextId/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("lookupContextIdentityAlias")

    suspend fun deleteContextIdentityAlias(
        contextId: String,
        name: String,
    ): DeleteContextIdentityAliasResponseData =
        http.postJson<JsonObject, ApiEnvelope<DeleteContextIdentityAliasResponseData>>(
            "/admin-api/alias/delete/identity/$contextId/${encodeComponent(name)}", JsonObject(emptyMap()),
        ).data ?: error("deleteContextIdentityAlias")

    // ---- Namespace Management ----------------------------------------------

    suspend fun listNamespaces(): ListNamespacesResponseData =
        http.getJson<ApiEnvelope<ListNamespacesResponseData>>("/admin-api/namespaces").data ?: emptyList()

    suspend fun getNamespace(namespaceId: String): Namespace =
        http.getJson<ApiEnvelope<Namespace>>("/admin-api/namespaces/$namespaceId").data ?: error("getNamespace")

    suspend fun getNamespaceIdentity(namespaceId: String): NamespaceIdentity {
        // Core returns this endpoint flat ({ namespaceId, publicKey }); tolerate both.
        val res = http.execute("GET", "/admin-api/namespaces/$namespaceId/identity").ensureSuccessful()
        val wire = http.json.decodeFromString<NamespaceIdentityWire>(res.body)
        return wire.data ?: NamespaceIdentity(namespaceId = wire.namespaceId ?: "", publicKey = wire.publicKey ?: "")
    }

    suspend fun listNamespacesForApplication(applicationId: String): ListNamespacesResponseData =
        http.getJson<ApiEnvelope<ListNamespacesResponseData>>(
            "/admin-api/namespaces/for-application/$applicationId",
        ).data ?: emptyList()

    suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData =
        http.postJson<CreateNamespaceRequest, ApiEnvelope<CreateNamespaceResponseData>>(
            "/admin-api/namespaces", request,
        ).data ?: error("createNamespace")

    suspend fun deleteNamespace(
        namespaceId: String,
        request: DeleteNamespaceRequest? = null,
    ): DeleteNamespaceResponseData {
        // Core requires `Content-Type: application/json` even with an empty body.
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("DELETE", "/admin-api/namespaces/$namespaceId", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteNamespaceResponseData>>(res.body).data
            ?: error("deleteNamespace")
    }

    suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest? = null,
    ): CreateNamespaceInvitationResult {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/namespaces/$namespaceId/invite", body).ensureSuccessful()
        val data = http.json.decodeFromString<ApiEnvelope<JsonElement>>(res.body).data
            ?: error("createNamespaceInvitation")
        val isRecursive = (data as? JsonObject)?.containsKey("invitations") == true
        return if (isRecursive) {
            CreateNamespaceInvitationResult.Recursive(http.json.decodeFromJsonElement(data))
        } else {
            CreateNamespaceInvitationResult.Single(http.json.decodeFromJsonElement(data))
        }
    }

    suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData {
        // Join can be slow (network sync); the Swift/TS set a 65s timeout — the OkHttp
        // client's configured timeouts apply here.
        val res = http.execute("POST", "/admin-api/namespaces/$namespaceId/join", http.json.encodeToString(request))
            .ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<JoinNamespaceResponseData>>(res.body).data
            ?: error("joinNamespace")
    }

    suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest? = null,
    ): CreateGroupInNamespaceResponseData {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/namespaces/$namespaceId/groups", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<CreateGroupInNamespaceResponseData>>(res.body).data
            ?: error("createGroupInNamespace")
    }

    suspend fun listNamespaceGroups(namespaceId: String): List<SubgroupEntry> =
        http.getJson<ApiEnvelope<List<SubgroupEntry>>>("/admin-api/namespaces/$namespaceId/groups").data
            ?: emptyList()

    // ---- Group Management --------------------------------------------------

    suspend fun getGroupInfo(groupId: String): GroupInfoResponseData =
        http.getJson<ApiEnvelope<GroupInfoResponseData>>("/admin-api/groups/$groupId").data ?: error("getGroupInfo")

    /** Thin wrapper over [getGroupInfo]: returns the group's `defaultCapabilities` bitmask. */
    suspend fun getDefaultCapabilities(groupId: String): Int =
        getGroupInfo(groupId).defaultCapabilities

    /** Thin wrapper over [getGroupInfo]: returns the group's `subgroupVisibility`. */
    suspend fun getSubgroupVisibility(groupId: String): String =
        getGroupInfo(groupId).subgroupVisibility

    suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest? = null,
    ): DeleteGroupResponseData {
        // Core requires `Content-Type: application/json` even with an empty body.
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("DELETE", "/admin-api/groups/$groupId", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteGroupResponseData>>(res.body).data ?: error("deleteGroup")
    }

    suspend fun listGroupMembers(groupId: String): ListGroupMembersResponseData {
        // Response is un-enveloped ({ members, selfIdentity }). Validate the non-optional
        // `members` field so a contract-violating response surfaces as a clear error.
        val res = http.execute("GET", "/admin-api/groups/$groupId/members").ensureSuccessful()
        val lenient = http.json.decodeFromString<ListGroupMembersLenient>(res.body)
        val members = lenient.members ?: run {
            val safeId = groupId.filterNot { it.isWhitespace() }.take(64)
            error("Invalid listGroupMembers response for group $safeId: missing or non-array `members` field")
        }
        return ListGroupMembersResponseData(members = members, selfIdentity = lenient.selfIdentity)
    }

    suspend fun listGroupContexts(groupId: String): ListGroupContextsResponseData =
        http.getJson<ApiEnvelope<ListGroupContextsResponseData>>("/admin-api/groups/$groupId/contexts").data
            ?: emptyList()

    suspend fun addGroupMembers(groupId: String, request: AddGroupMembersRequest) {
        http.execute("POST", "/admin-api/groups/$groupId/members", http.json.encodeToString(request)).ensureSuccessful()
    }

    suspend fun removeGroupMembers(groupId: String, request: RemoveGroupMembersRequest) {
        http.execute("POST", "/admin-api/groups/$groupId/members/remove", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    suspend fun updateMemberRole(groupId: String, identity: String, request: UpdateMemberRoleRequest) {
        http.execute("PUT", "/admin-api/groups/$groupId/members/$identity/role", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    suspend fun getMemberCapabilities(groupId: String, identity: String): MemberCapabilities =
        http.getJson<ApiEnvelope<MemberCapabilities>>(
            "/admin-api/groups/$groupId/members/$identity/capabilities",
        ).data ?: error("getMemberCapabilities")

    suspend fun setMemberCapabilities(groupId: String, identity: String, request: SetMemberCapabilitiesRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/members/$identity/capabilities", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun setDefaultCapabilities(groupId: String, request: SetDefaultCapabilitiesRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/settings/default-capabilities", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun setSubgroupVisibility(groupId: String, request: SetSubgroupVisibilityRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/settings/subgroup-visibility", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun setTeeAdmissionPolicy(groupId: String, request: SetTeeAdmissionPolicyRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/settings/tee-admission-policy", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun getTeeAdmissionPolicy(groupId: String): GetTeeAdmissionPolicyResponseData {
        // `data` is optional so a flat (un-enveloped) response cleanly falls back.
        val res = http.execute("GET", "/admin-api/groups/$groupId/settings/tee-admission-policy").ensureSuccessful()
        runCatching { http.json.decodeFromString<TeeAdmissionEnv>(res.body).data }.getOrNull()?.let { return it }
        return http.json.decodeFromString(res.body)
    }

    suspend fun updateGroupSettings(groupId: String, request: UpdateGroupSettingsRequest) {
        http.execute("PATCH", "/admin-api/groups/$groupId", http.json.encodeToString(request)).ensureSuccessful()
    }

    // ---- Group / member / context metadata ---------------------------------

    suspend fun setGroupMetadata(groupId: String, request: SetGroupMetadataRequest) {
        http.execute("PUT", "/admin-api/groups/$groupId/metadata", http.json.encodeToString(request)).ensureSuccessful()
    }

    suspend fun getGroupMetadata(groupId: String): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/$groupId/metadata")

    suspend fun setMemberMetadata(groupId: String, identity: String, request: SetMemberMetadataRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/members/$identity/metadata", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun getMemberMetadata(groupId: String, identity: String): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/$groupId/members/$identity/metadata")

    suspend fun setContextMetadata(groupId: String, contextId: String, request: SetContextMetadataRequest) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/contexts/$contextId/metadata", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    suspend fun getContextMetadata(groupId: String, contextId: String): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/$groupId/contexts/$contextId/metadata")

    suspend fun syncGroup(groupId: String, request: SyncGroupRequest? = null): SyncGroupResponseData {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/$groupId/sync", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<SyncGroupResponseData>>(res.body).data ?: error("syncGroup")
    }

    suspend fun registerGroupSigningKey(
        groupId: String,
        request: RegisterGroupSigningKeyRequest,
    ): RegisterGroupSigningKeyResponseData =
        http.postJson<RegisterGroupSigningKeyRequest, ApiEnvelope<RegisterGroupSigningKeyResponseData>>(
            "/admin-api/groups/$groupId/signing-key", request,
        ).data ?: error("registerGroupSigningKey")

    suspend fun upgradeGroup(groupId: String, request: UpgradeGroupRequest): UpgradeGroupResponseData =
        http.postJson<UpgradeGroupRequest, ApiEnvelope<UpgradeGroupResponseData>>(
            "/admin-api/groups/$groupId/upgrade", request,
        ).data ?: error("upgradeGroup")

    /** Value is `GroupUpgradeStatus | null`; returns the optional directly. */
    suspend fun getGroupUpgradeStatus(groupId: String): GroupUpgradeStatusResponseData =
        http.getJson<ApiEnvelope<GroupUpgradeStatus>>("/admin-api/groups/$groupId/upgrade/status").data

    /**
     * The operator-facing "have all peers migrated?" rollup for a namespace. The handler
     * serializes the payload directly, so there is no `{ data }` envelope to unwrap here.
     */
    suspend fun getMigrationStatus(namespaceId: String): MigrationStatus =
        http.getJson("/admin-api/groups/${encodeComponent(namespaceId)}/migration-status")

    /** Per-group cascade-migration snapshots for a namespace. */
    suspend fun getCascadeStatus(namespaceId: String): List<CascadeStatusEntry> =
        http.getJson<ApiEnvelope<List<CascadeStatusEntry>>>(
            "/admin-api/groups/${encodeComponent(namespaceId)}/cascade-status",
        ).data ?: emptyList()

    suspend fun retryGroupUpgrade(
        groupId: String,
        request: RetryGroupUpgradeRequest? = null,
    ): RetryGroupUpgradeResponseData {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/$groupId/upgrade/retry", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<RetryGroupUpgradeResponseData>>(res.body).data
            ?: error("retryGroupUpgrade")
    }

    /** Move `childGroupId` under `request.newParentId`. */
    suspend fun reparentGroup(childGroupId: String, request: ReparentGroupRequest): ReparentGroupResponseData {
        // Core returns this flat ({ reparented }); tolerate the { data } envelope too.
        val res = http.execute("POST", "/admin-api/groups/$childGroupId/reparent", http.json.encodeToString(request))
            .ensureSuccessful()
        val env = http.json.decodeFromString<ReparentEnv>(res.body)
        return env.data ?: ReparentGroupResponseData(reparented = env.reparented ?: false)
    }

    suspend fun listSubgroups(groupId: String): List<SubgroupEntry> {
        val res = http.execute("GET", "/admin-api/groups/$groupId/subgroups").ensureSuccessful()
        val wire = http.json.decodeFromString<SubgroupsWire>(res.body)
        return wire.subgroups ?: wire.data ?: emptyList()
    }

    suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest? = null,
    ) {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        http.execute("POST", "/admin-api/groups/$groupId/contexts/$contextId/remove", body).ensureSuccessful()
    }

    // ---- Group Invitation & Join -------------------------------------------

    suspend fun createGroupInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest? = null,
    ): CreateGroupInvitationResult {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/$groupId/invite", body).ensureSuccessful()
        val data = http.json.decodeFromString<ApiEnvelope<JsonElement>>(res.body).data ?: error("createGroupInvitation")
        val isRecursive = (data as? JsonObject)?.containsKey("invitations") == true
        return if (isRecursive) {
            CreateGroupInvitationResult.Recursive(http.json.decodeFromJsonElement(data))
        } else {
            CreateGroupInvitationResult.Single(http.json.decodeFromJsonElement(data))
        }
    }

    suspend fun joinGroup(request: JoinGroupRequest): JoinGroupResponseData =
        http.postJson<JoinGroupRequest, ApiEnvelope<JoinGroupResponseData>>("/admin-api/groups/join", request).data
            ?: error("joinGroup")

    suspend fun joinSubgroupInheritance(groupId: String): JoinSubgroupInheritanceResponseData =
        http.postJson<JsonObject, ApiEnvelope<JoinSubgroupInheritanceResponseData>>(
            "/admin-api/groups/$groupId/join-via-inheritance", JsonObject(emptyMap()),
        ).data ?: error("joinSubgroupInheritance")

    // ---- TEE ---------------------------------------------------------------

    suspend fun getTeeInfo(): TeeInfoResponseData =
        http.getJson<ApiEnvelope<TeeInfoResponseData>>("/admin-api/tee/info").data ?: error("getTeeInfo")

    suspend fun teeAttest(request: TeeAttestRequest): TeeAttestResponseData =
        http.postJson<TeeAttestRequest, ApiEnvelope<TeeAttestResponseData>>("/admin-api/tee/attest", request).data
            ?: error("teeAttest")

    suspend fun teeVerifyQuote(request: TeeVerifyQuoteRequest): TeeVerifyQuoteResponseData =
        http.postJson<TeeVerifyQuoteRequest, ApiEnvelope<TeeVerifyQuoteResponseData>>(
            "/admin-api/tee/verify-quote", request,
        ).data ?: error("teeVerifyQuote")

    // ---- Network -----------------------------------------------------------

    suspend fun getPeersCount(): PeersCountResponseData =
        http.getJson("/admin-api/peers")

    /** Node network status (GET /admin-api/network/status). */
    suspend fun getNetworkStatus(): JsonElement =
        rawJson("GET", "/admin-api/network/status", null)

    /** Node storage/usage stats (GET /admin-api/usage). */
    suspend fun getUsage(): JsonElement =
        rawJson("GET", "/admin-api/usage", null)

    /** Node TLS certificate, PEM text (GET /admin-api/certificate). */
    suspend fun getCertificate(): String =
        http.execute("GET", "/admin-api/certificate").ensureSuccessful().body

    // ---- Group / context / namespace membership ----------------------------

    /** Create a standalone group (POST /admin-api/groups). */
    suspend fun createGroup(request: Map<String, JsonElement>): CreateGroupResponseData =
        http.postJson<Map<String, JsonElement>, ApiEnvelope<CreateGroupResponseData>>("/admin-api/groups", request).data
            ?: error("createGroup")

    /** Leave a group (POST /admin-api/groups/:group_id/leave). */
    suspend fun leaveGroup(groupId: String, request: Map<String, JsonElement>? = null) {
        http.execute("POST", "/admin-api/groups/$groupId/leave", http.json.encodeToString(request ?: emptyMap()))
            .ensureSuccessful()
    }

    /** Leave a context (POST /admin-api/contexts/:context_id/leave). */
    suspend fun leaveContext(contextId: String, request: Map<String, JsonElement>? = null) {
        http.execute("POST", "/admin-api/contexts/$contextId/leave", http.json.encodeToString(request ?: emptyMap()))
            .ensureSuccessful()
    }

    /** Leave a namespace (POST /admin-api/namespaces/:namespace_id/leave). */
    suspend fun leaveNamespace(namespaceId: String, request: Map<String, JsonElement>? = null) {
        http.execute(
            "POST", "/admin-api/namespaces/$namespaceId/leave", http.json.encodeToString(request ?: emptyMap()),
        ).ensureSuccessful()
    }

    /** Issue a group ownership proof (POST /admin-api/groups/:group_id/issue-ownership-proof). */
    suspend fun issueOwnershipProof(groupId: String, request: Map<String, JsonElement>? = null): JsonElement =
        rawJson("POST", "/admin-api/groups/$groupId/issue-ownership-proof", http.json.encodeToString(request ?: emptyMap()))

    /** Issue a namespace ownership proof (POST /admin-api/groups/:group_id/issue-namespace-ownership-proof). */
    suspend fun issueNamespaceOwnershipProof(groupId: String, request: Map<String, JsonElement>? = null): JsonElement =
        rawJson(
            "POST",
            "/admin-api/groups/$groupId/issue-namespace-ownership-proof",
            http.json.encodeToString(request ?: emptyMap()),
        )

    /** Set a member's auto-follow flag (PUT /admin-api/groups/:group_id/members/:identity/auto-follow). */
    suspend fun setMemberAutoFollow(groupId: String, identity: String, request: Map<String, JsonElement>) {
        http.execute(
            "PUT", "/admin-api/groups/$groupId/members/$identity/auto-follow", http.json.encodeToString(request),
        ).ensureSuccessful()
    }

    /** Abort a namespace migration (POST /admin-api/groups/:namespace_id/migration/abort). */
    suspend fun abortMigration(namespaceId: String, request: Map<String, JsonElement>? = null): JsonElement =
        rawJson("POST", "/admin-api/groups/$namespaceId/migration/abort", http.json.encodeToString(request ?: emptyMap()))

    // ---- Private helpers ---------------------------------------------------

    /**
     * Core single-envelopes the record: `{ data: MetadataRecord | null }`. "No record
     * yet" is `{ data: null }` (or a bare null body on older nodes), so resolve to null.
     */
    private suspend fun getMetadataRecord(path: String): MetadataRecord? {
        val res = http.execute("GET", path).ensureSuccessful()
        if (res.body.isBlank()) return null
        return runCatching { http.json.decodeFromString<MetadataEnv>(res.body).data }.getOrNull()
    }

    /** For endpoints typed `unknown`: decode the raw body into a dynamic [JsonElement], tolerating an empty 2xx body. */
    private suspend fun rawJson(method: String, path: String, body: String?): JsonElement {
        val res = http.execute(method, path, body).ensureSuccessful()
        if (res.body.isBlank()) return JsonNull
        return runCatching { http.json.parseToJsonElement(res.body) }.getOrDefault(JsonNull)
    }

    /** Resolve the origin (scheme://host[:port]) of a registry URL. */
    private fun origin(urlString: String): String {
        val uri = URI(urlString)
        val scheme = uri.scheme ?: throw MeroStateException("invalid registry URL: $urlString")
        val host = uri.host ?: throw MeroStateException("invalid registry URL: $urlString")
        return if (uri.port != -1) "$scheme://$host:${uri.port}" else "$scheme://$host"
    }

    /** Plain external GET (registry endpoints live off-node, not behind [HttpClient]). Returns the raw body. */
    private suspend fun fetchExternal(url: String, failure: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw MeroStateException("$failure ($code)")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        /** RFC-3986 unreserved set: ALPHA / DIGIT / `-` / `.` / `_` / `~`. */
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** Percent-encode a string for use as a query value or a path segment. */
        fun encodeComponent(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt() and 0xFF
                if (c.toChar() in UNRESERVED) {
                    append(c.toChar())
                } else {
                    append('%')
                    append("0123456789ABCDEF"[c shr 4])
                    append("0123456789ABCDEF"[c and 0x0F])
                }
            }
        }
    }
}
