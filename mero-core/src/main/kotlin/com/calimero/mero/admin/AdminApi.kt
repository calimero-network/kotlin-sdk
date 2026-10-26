package com.calimero.mero.admin

import com.calimero.mero.Capabilities
import com.calimero.mero.account.ApplicationIds
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.DeviceScopeInput
import com.calimero.mero.account.signDeviceScope
import com.calimero.mero.auth.ApiEnvelope
import com.calimero.mero.crypto.DeviceSigner
import com.calimero.mero.http.HttpClient
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.deleteJson
import com.calimero.mero.http.getJson
import com.calimero.mero.http.postJson
import com.calimero.mero.http.putJson
import com.calimero.mero.ownerop.adminChangedOp
import com.calimero.mero.ownerop.groupDeleteOp
import com.calimero.mero.ownerop.mintRootProof
import com.calimero.mero.ownerop.teeAdmissionPolicyOpOf
import com.calimero.mero.ownerop.teeAuthoringPolicyOp
import com.calimero.mero.ownerop.transferOwnershipOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Compare two dotted version strings, ascending: negative if `a < b`, positive if
 * `a > b`, `0` if equal. Components compared numerically when both parse as ints
 * (so `1.10.0 > 1.9.0`), else lexically; a missing component is `0`.
 */
fun compareSemver(
    a: String,
    b: String,
): Int {
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
private fun compareSemverComponent(
    sa: String,
    sb: String,
): Int {
    val na = sa.toIntOrNull()
    val nb = sb.toIntOrNull()
    if (na != null && nb != null) return na - nb
    return sa.compareTo(sb).let {
        if (it == 0) {
            0
        } else if (it < 0) {
            -1
        } else {
            1
        }
    }
}

// ---- Internal wire structs (flat / snake_case tolerances) ------------------

@Serializable
private data class BlobWire(
    @SerialName("blob_id") val blobId: String,
    val size: Long,
)

@Serializable
private data class BlobsWire(
    val blobs: List<BlobWire>,
)

@Serializable
private data class DeleteBlobWire(
    @SerialName("blob_id") val blobId: String,
    val deleted: Boolean,
)

@Serializable
private data class ListPackagesWire(
    val packages: List<String>? = null,
    val data: ListPackagesResponseData? = null,
)

@Serializable
private data class ListVersionsWire(
    val versions: List<String>? = null,
    val data: ListVersionsResponseData? = null,
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
private data class TeeAdmissionEnv(
    val data: GetTeeAdmissionPolicyResponseData? = null,
)

@Serializable
private data class MetadataEnv(
    val data: MetadataRecord? = null,
)

@Serializable
private data class ReparentEnv(
    val data: ReparentGroupResponseData? = null,
    val reparented: Boolean? = null,
)

@Serializable
private data class SubgroupsWire(
    val subgroups: List<SubgroupEntry>? = null,
    val data: List<SubgroupEntry>? = null,
)

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
@Suppress("TooManyFunctions")
class AdminApi(
    private val http: HttpClient,
) : AdminSurface {
    // ---- Health and Status (public, no auth) -------------------------------

    override suspend fun healthCheck(): HealthStatus =
        http.getJson<ApiEnvelope<HealthStatus>>("/admin-api/health").data ?: error("healthCheck")

    /** NOTE: not unwrapped — returns the whole `{ data: { status } }` envelope. */
    override suspend fun isAuthed(): AdminAuthStatus =
        http.getJson("/admin-api/is-authed")

    /**
     * Readiness, as distinct from liveness: `health` says the process is up,
     * this says it can serve.
     */
    override suspend fun isReady(): HealthStatus =
        http.getJson<ApiEnvelope<HealthStatus>>("/admin-api/ready").data ?: error("isReady")

    /**
     * Who this node is — the account it writes as, the device it is, and the keys
     * that identify both.
     *
     * This replaces `getNamespaceIdentity`, which asked a *namespace* who the node
     * was. Core deleted that route in rc.23 (core#3522) and it has answered 404
     * ever since.
     *
     * `404` here is a real answer: a node holding neither a usable device nor an
     * account root has taken part in nothing at all.
     */
    override suspend fun getNodeIdentity(): NodeIdentity =
        http.getJson<ApiEnvelope<NodeIdentity>>("/admin-api/identity").data ?: error("getNodeIdentity")

    // ---- Application Management --------------------------------------------

    /**
     * Install by registry coordinates. The node fetches the bundle from the
     * registry it is configured with (`[registry]` in `config.toml`) — a client
     * cannot name a URL any more (core#3652, rc.31).
     *
     * A `502` here is neither the node's fault nor the caller's: *"the configured
     * Http source has no application published at `<package>@<version>`"*.
     */
    override suspend fun installApplication(request: InstallApplicationRequest): InstallApplicationResponseData =
        http
            .postJson<InstallApplicationRequest, ApiEnvelope<InstallApplicationResponseData>>(
                "/admin-api/install-application", request,
            ).data ?: error("installApplication")

    /**
     * Install `package@version` from the node's registry.
     *
     * This used to fetch the bundle manifest itself, derive an `.mpk` artifact URL
     * and hand the node that URL. rc.31 removed URL installs — the request now
     * carries `deny_unknown_fields`, so the old body is **refused**, not ignored:
     *
     * ```
     * 400 unknown field `url`, expected `package` or `version`
     * ```
     *
     * `registryUrl` is gone with it: which registry a node trusts is the node's
     * configuration (`[registry]` in `config.toml`), not a caller's argument.
     *
     * ⚠️ To discover a version to install, use [getRegistryVersions] — the
     * registry read. [listPackageVersions] and [getLatestPackageVersion] ask the
     * *node*, and a node reports only what it has **installed**: for a package it
     * has never seen they answer `{"versions":[]}` and `{"version":null}`, not
     * "here is what you could install". The node exposes no route naming its own
     * registry, so point [getRegistryVersions] at the same one it is configured
     * with; a mismatch surfaces as a `502` from the install.
     */
    override suspend fun installFromRegistry(
        packageName: String,
        version: String,
    ): InstallApplicationResponseData = installApplication(InstallApplicationRequest(packageName, version))

    /**
     * List a package's published versions from the registry, newest-first by semver.
     * Reads `GET {registry}/api/v2/bundles?package={package}` and takes each bundle's
     * `appVersion`. Registry-side data — distinct from the node's installed-version list.
     */
    override suspend fun getRegistryVersions(
        registryUrl: String,
        packageName: String,
    ): List<String> {
        val base = origin(registryUrl)
        val url = "$base/api/v2/bundles?package=${encodeComponent(packageName)}"
        val body = fetchExternal(url, "registry versions fetch failed for $packageName")
        val bundles = http.json.decodeFromString<List<RegistryBundleManifest>>(body)
        return bundles.map { it.appVersion }.sortedWith { x, y -> -compareSemver(x, y) }
    }

    /**
     * The newest version of [packageName] the registry publishes that a node on
     * [runtimeVersion] can install: not yanked, and its bundle's `minRuntimeVersion` no newer
     * than the runtime. `null` when there is none.
     *
     * Without a runtime this is the newest non-yanked version, whatever it needs. With one, it
     * walks the package's version list (`GET {registry}/api/v2/packages/{package}`) newest
     * first and reads each bundle (`GET {registry}/api/v2/bundles/{package}/{version}`) until
     * one fits, because the bundle listing names only the latest version.
     */
    suspend fun resolveRegistryVersion(
        registryUrl: String,
        packageName: String,
        runtimeVersion: String? = null,
    ): String? {
        val base = origin(registryUrl)
        val pkg = encodeComponent(packageName)
        val bundles =
            ApplicationIds.parseBundles(
                fetchExternal("$base/api/v2/bundles?package=$pkg", "registry versions fetch failed for $packageName"),
            )
        ApplicationIds.selectLatestBundle(bundles, runtimeVersion)?.let { return it.appVersion }
        if (runtimeVersion.isNullOrEmpty()) return null

        val pkgBody = fetchExternal("$base/api/v2/packages/$pkg", "registry package fetch failed for $packageName")
        val listing = http.json.parseToJsonElement(pkgBody) as? JsonObject ?: return null
        val strings = { key: String -> (listing[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty() }
        return ApplicationIds.newestCompatible(
            versions = strings("versions"),
            yanked = strings("yankedVersions"),
            skipping = bundles.mapNotNull { it.appVersion },
            runtimeVersion = runtimeVersion,
        ) { version ->
            val body =
                fetchExternal(
                    "$base/api/v2/bundles/$pkg/${encodeComponent(version)}",
                    "registry bundle fetch failed for $packageName@$version",
                )
            ApplicationIds.parseBundle(body) ?: throw MeroStateException("the registry's $packageName@$version bundle does not decode")
        }
    }

    /**
     * Install the newest version of [packageName] this node can run.
     *
     * Pass the node's [runtimeVersion] when it is known. When it is not, the newest version is
     * tried first; a node too old for it refuses naming its own runtime ("… but current runtime
     * is 0.11.0"), and the install is retried once with the newest version that runtime
     * accepts. A registry publishing a release ahead of the node no longer breaks the install.
     */
    suspend fun installLatestFromRegistry(
        registryUrl: String,
        packageName: String,
        runtimeVersion: String? = null,
    ): RegistryInstall {
        var runtime = runtimeVersion
        while (true) {
            val version =
                resolveRegistryVersion(registryUrl, packageName, runtime)
                    ?: throw MeroStateException(
                        "the registry lists no version of $packageName" + (runtime?.let { " that runtime $it can install" } ?: ""),
                    )
            try {
                return RegistryInstall(installFromRegistry(packageName, version).applicationId, version)
            } catch (e: HttpException) {
                val learned = ApplicationIds.runtimeVersionRefusedBy(e)
                if (runtime != null || learned == null) throw e
                runtime = learned
            }
        }
    }

    override suspend fun installDevApplication(request: InstallDevApplicationRequest): InstallApplicationResponseData =
        http
            .postJson<InstallDevApplicationRequest, ApiEnvelope<InstallApplicationResponseData>>(
                "/admin-api/install-dev-application", request,
            ).data ?: error("installDevApplication")

    override suspend fun uninstallApplication(appId: String): UninstallApplicationResponseData =
        http.deleteJson<ApiEnvelope<UninstallApplicationResponseData>>("/admin-api/applications/${pathSegment(appId)}").data
            ?: error("uninstallApplication")

    override suspend fun listApplications(): ListApplicationsResponseData =
        http.getJson<ApiEnvelope<ListApplicationsResponseData>>("/admin-api/applications").data
            ?: error("listApplications")

    override suspend fun getApplication(appId: String): GetApplicationResponseData =
        http.getJson<ApiEnvelope<GetApplicationResponseData>>("/admin-api/applications/${pathSegment(appId)}").data
            ?: error("getApplication")

    /** Installed-blob inventory for an application — one entry per locally installed version. */
    override suspend fun listApplicationVersions(applicationId: String): List<ApplicationVersionEntry> =
        http.getJson<ApiEnvelope<List<ApplicationVersionEntry>>>("/admin-api/applications/${pathSegment(applicationId)}/versions").data
            ?: emptyList()

    // ---- Package Management ------------------------------------------------

    /** Packages this node has **installed** — not the registry's catalogue. */
    override suspend fun listPackages(): ListPackagesResponseData {
        // Core returns this flat ({ packages: [...] }), not under `data`; tolerate both.
        val res = http.execute("GET", "/admin-api/packages").ensureSuccessful()
        val wire = http.json.decodeFromString<ListPackagesWire>(res.body)
        return wire.data ?: ListPackagesResponseData(packages = wire.packages ?: emptyList())
    }

    /**
     * Versions of a package this node has **installed**. An empty list means
     * "not installed here", never "not published" — use [getRegistryVersions] for
     * what is available to install.
     */
    override suspend fun listPackageVersions(packageName: String): ListVersionsResponseData {
        // Core returns this flat ({ versions: [...] }), not under `data`; tolerate both.
        val res = http.execute("GET", "/admin-api/packages/${pathSegment(packageName)}/versions").ensureSuccessful()
        val wire = http.json.decodeFromString<ListVersionsWire>(res.body)
        return wire.data ?: ListVersionsResponseData(versions = wire.versions ?: emptyList())
    }

    /** Newest **installed** version of a package. Both fields are `null` if none is. */
    override suspend fun getLatestPackageVersion(packageName: String): GetLatestVersionResponseData =
        http.getJson("/admin-api/packages/${pathSegment(packageName)}/latest")

    // ---- Context Management ------------------------------------------------

    override suspend fun createContext(request: CreateContextRequest): CreateContextResponseData {
        // Core requires `initializationParams`; default it to an empty byte array.
        val body = if (request.initializationParams == null) request.copy(initializationParams = emptyList()) else request
        return http
            .postJson<CreateContextRequest, ApiEnvelope<CreateContextResponseData>>(
                "/admin-api/contexts", body,
            ).data ?: error("createContext")
    }

    override suspend fun deleteContext(
        contextId: String,
        request: DeleteContextRequest?,
    ): DeleteContextResponseData {
        val body = request?.let { http.json.encodeToString(it) }
        val res = http.execute("DELETE", "/admin-api/contexts/${pathSegment(contextId)}", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteContextResponseData>>(res.body).data
            ?: error("deleteContext")
    }

    override suspend fun getContexts(): GetContextsResponseData =
        http.getJson<ApiEnvelope<GetContextsResponseData>>("/admin-api/contexts").data ?: error("getContexts")

    override suspend fun getContext(contextId: String): Context =
        http.getJson<ApiEnvelope<Context>>("/admin-api/contexts/${pathSegment(contextId)}").data ?: error("getContext")

    override suspend fun getContextsForApplication(applicationId: String): GetContextsResponseData =
        http.getJson<ApiEnvelope<GetContextsResponseData>>("/admin-api/contexts/for-application/${pathSegment(applicationId)}").data
            ?: error("getContextsForApplication")

    // ---- Context Identity --------------------------------------------------

    override suspend fun generateContextIdentity(): GenerateContextIdentityResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<GenerateContextIdentityResponseData>>(
                "/admin-api/identity/context", JsonObject(emptyMap()),
            ).data ?: error("generateContextIdentity")

    override suspend fun getContextIdentities(contextId: String): GetContextIdentitiesResponseData =
        http.getJson<ApiEnvelope<GetContextIdentitiesResponseData>>("/admin-api/contexts/${pathSegment(contextId)}/identities").data
            ?: error("getContextIdentities")

    override suspend fun getContextIdentitiesOwned(contextId: String): GetContextIdentitiesResponseData =
        http
            .getJson<ApiEnvelope<GetContextIdentitiesResponseData>>(
                "/admin-api/contexts/${pathSegment(contextId)}/identities-owned",
            ).data ?: error("getContextIdentitiesOwned")

    // ---- Context join (group membership) -----------------------------------

    override suspend fun joinContext(contextId: String): JoinContextResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<JoinContextResponseData>>(
                "/admin-api/contexts/${pathSegment(contextId)}/join", JsonObject(emptyMap()),
            ).data ?: error("joinContext")

    // ---- Context group / storage / sync ------------------------------------

    /** Value is `string | null`; returns the optional directly (does NOT throw on null). */
    override suspend fun getContextGroup(contextId: String): ContextGroupResponseData =
        http.getJson<ApiEnvelope<String>>("/admin-api/contexts/${pathSegment(contextId)}/group").data

    override suspend fun getContextStorage(contextId: String): ContextStorageResponseData =
        http.getJson<ApiEnvelope<ContextStorageResponseData>>("/admin-api/contexts/${pathSegment(contextId)}/storage").data
            ?: error("getContextStorage")

    /**
     * Sync one context, or every context when [contextId] is null.
     *
     * ⚠️ The two are **different paths**, and must be built separately. This used
     * to interpolate `${contextId ?: ""}`, which for the sync-everything case
     * produced a trailing slash — and axum 0.8 (core 0.11.0-rc.30, #3744) stopped
     * matching a trailing slash to its route:
     *
     * ```
     * POST /admin-api/contexts/sync       → 200
     * POST /admin-api/contexts/sync/      → 404
     * POST /admin-api/contexts/sync/{id}  → 200
     * ```
     *
     * So "sync everything" silently became a 404 and this threw, on a call that
     * had worked for every release before rc.30.
     */
    override suspend fun syncContext(contextId: String?) {
        val path = if (contextId == null) "/admin-api/contexts/sync" else "/admin-api/contexts/sync/${pathSegment(contextId)}"
        http.execute("POST", path, "{}").ensureSuccessful()
    }

    /**
     * Kick off a full state re-pull for a context (operator recovery for a stranded
     * context). `force` re-pulls even when the node does not flag the context as stranded.
     */
    override suspend fun resyncContext(
        contextId: String,
        request: ResyncContextRequest,
    ): ResyncContextResponseData {
        // Core's `ResyncContextApiResponse` is a flat payload (no inner `data`), so parse
        // directly. An empty 2xx body means accepted; synthesize a typed result.
        val res =
            http
                .execute("POST", "/admin-api/contexts/${pathSegment(contextId)}/resync", http.json.encodeToString(request))
                .ensureSuccessful()
        return runCatching { http.json.decodeFromString<ResyncContextResponseData>(res.body) }.getOrNull()
            ?: ResyncContextResponseData(contextId = contextId, resyncStarted = true)
    }

    override suspend fun updateContextApplication(
        contextId: String,
        request: UpdateContextApplicationRequest,
    ) {
        http
            .execute("POST", "/admin-api/contexts/${pathSegment(contextId)}/application", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    override suspend fun getContextsWithExecutorsForApplication(applicationId: String): ContextsWithExecutorsResponseData {
        // Core returns this flat as { contexts: [...] }; tolerate a bare array and { data } too.
        val res =
            http
                .execute("GET", "/admin-api/contexts/with-executors/for-application/${pathSegment(applicationId)}")
                .ensureSuccessful()
        runCatching { http.json.decodeFromString<List<ContextWithExecutors>>(res.body) }.getOrNull()?.let { return it }
        val wrap = runCatching { http.json.decodeFromString<ContextsWithExecutorsWrap>(res.body) }.getOrNull()
        return wrap?.contexts ?: wrap?.data ?: emptyList()
    }

    // ---- Blob Management ---------------------------------------------------

    override suspend fun uploadBlob(request: UploadBlobRequest): UploadBlobResponseData {
        // Core streams the raw request body into blob storage (no JSON) and takes its
        // params from the query string (`hash`, `context_id` — snake_case).
        val query =
            buildList {
                request.hash?.let { add("hash=${encodeComponent(it)}") }
                request.contextId?.let { add("context_id=${encodeComponent(it)}") }
            }
        val path = if (query.isEmpty()) "/admin-api/blobs" else "/admin-api/blobs?" + query.joinToString("&")
        // Body sent as raw octet-stream bytes, never through a String (a charset round-trip
        // rewrites every byte >= 0x80). Core's BlobInfo is snake_case (`blob_id`).
        val res = http.executeBytes("PUT", path, request.data).ensureSuccessful()
        val inner = http.json.decodeFromString<ApiEnvelope<BlobWire>>(res.bodyText).data ?: error("uploadBlob")
        return BlobInfo(blobId = inner.blobId, size = inner.size)
    }

    override suspend fun deleteBlob(blobId: String): DeleteBlobResponseData {
        // Core's `BlobDeleteResponse` is flat, snake_case (`{ blob_id, deleted }`).
        val wire = http.deleteJson<DeleteBlobWire>("/admin-api/blobs/${pathSegment(blobId)}")
        return DeleteBlobResponseData(blobId = wire.blobId, deleted = wire.deleted)
    }

    override suspend fun listBlobs(): ListBlobsResponseData {
        // Core's BlobInfo is snake_case (`blob_id`); map to camelCase.
        val inner = http.getJson<ApiEnvelope<BlobsWire>>("/admin-api/blobs").data ?: error("listBlobs")
        return ListBlobsResponseData(blobs = inner.blobs.map { BlobInfo(blobId = it.blobId, size = it.size) })
    }

    /**
     * Download a blob's raw bytes. `GET /admin-api/blobs/:id` streams the blob content
     * (e.g. `application/gzip`), NOT JSON. Use [listBlobs] for `{ blobId, size }` metadata.
     *
     * [contextId] (sent as `context_id`) names the context the blob belongs to, so an
     * account-scoped caller is judged against that context's membership rather than
     * refused outright.
     */
    override suspend fun getBlob(
        blobId: String,
        contextId: String?,
    ): ByteArray =
        http
            .executeBytes("GET", blobPath(blobId, contextId))
            .ensureSuccessful()
            .body

    /**
     * Fetch a blob's metadata without downloading it. `HEAD /admin-api/blobs/:id` returns
     * the info in response headers (size via `content-length`, plus `x-blob-*`). Header
     * names are matched case-insensitively. [contextId] as for [getBlob].
     */
    override suspend fun getBlobInfo(
        blobId: String,
        contextId: String?,
    ): GetBlobInfoResponseData {
        val res = http.execute("HEAD", blobPath(blobId, contextId)).ensureSuccessful()
        val headers = res.headers.entries.associate { it.key.lowercase() to it.value }
        return GetBlobInfoResponseData(
            blobId = headers["x-blob-id"] ?: blobId,
            size = headers["content-length"]?.toLongOrNull() ?: 0,
            hash = headers["x-blob-hash"],
            mimeType = headers["x-blob-mime-type"],
        )
    }

    // ---- Alias Management --------------------------------------------------

    // The alias create/delete routes answer core's `Empty`, which is `{"data": null}` on the wire:
    // success with nothing to return, the same as Swift's `emptyOK`.
    override suspend fun createContextAlias(request: CreateContextAliasRequest): CreateAliasResponseData =
        http
            .postJson<CreateContextAliasRequest, ApiEnvelope<CreateAliasResponseData>>(
                "/admin-api/alias/create/context", request,
            ).data ?: Empty()

    override suspend fun createApplicationAlias(request: CreateApplicationAliasRequest): CreateAliasResponseData =
        http
            .postJson<CreateApplicationAliasRequest, ApiEnvelope<CreateAliasResponseData>>(
                "/admin-api/alias/create/application", request,
            ).data ?: Empty()

    override suspend fun lookupContextAlias(name: String): LookupAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<LookupAliasResponseData>>(
                "/admin-api/alias/lookup/context/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: error("lookupContextAlias")

    override suspend fun lookupApplicationAlias(name: String): LookupAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<LookupAliasResponseData>>(
                "/admin-api/alias/lookup/application/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: error("lookupApplicationAlias")

    override suspend fun deleteContextAlias(name: String): DeleteAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<DeleteAliasResponseData>>(
                "/admin-api/alias/delete/context/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: Empty()

    override suspend fun deleteApplicationAlias(name: String): DeleteAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<DeleteAliasResponseData>>(
                "/admin-api/alias/delete/application/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: Empty()

    /**
     * ⚠️ The list routes answer a **map** — `{"data": {"<alias>": "<value>"}}`, and
     * `{"data": {}}` when empty — not `{aliases: […]}`. Decoded as a list this
     * threw `MissingFieldException: Field 'aliases' is required` on every call,
     * so all three list methods had never returned anything.
     */
    override suspend fun listContextAliases(): ListAliasesResponseData = listAliases("context")

    override suspend fun listApplicationAliases(): ListAliasesResponseData = listAliases("application")

    // ---- Device aliases ----------------------------------------------------
    //
    // `device` is the third alias scope core actually serves. The SDK used to
    // offer an `identity/{contextId}` scope instead, which does not exist —
    // every create/lookup/delete/list under it answered 404.

    override suspend fun listDeviceAliases(): ListAliasesResponseData = listAliases("device")

    override suspend fun createDeviceAlias(request: CreateDeviceAliasRequest): CreateAliasResponseData =
        http
            .postJson<CreateDeviceAliasRequest, ApiEnvelope<CreateAliasResponseData>>(
                "/admin-api/alias/create/device", request,
            ).data ?: Empty()

    override suspend fun lookupDeviceAlias(name: String): LookupAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<LookupAliasResponseData>>(
                "/admin-api/alias/lookup/device/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: LookupAliasResponseData()

    override suspend fun deleteDeviceAlias(name: String): DeleteAliasResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<DeleteAliasResponseData>>(
                "/admin-api/alias/delete/device/${pathSegment(name)}", JsonObject(emptyMap()),
            ).data ?: Empty()

    private suspend fun listAliases(scope: String): ListAliasesResponseData {
        val res = http.execute("GET", "/admin-api/alias/list/$scope").ensureSuccessful()
        val map =
            http.json
                .decodeFromString<ApiEnvelope<Map<String, String>>>(res.body)
                .data
                .orEmpty()
        return map.map { (name, value) -> AliasEntry(name = name, value = value) }
    }

    // ---- Namespace Management ----------------------------------------------

    override suspend fun listNamespaces(): ListNamespacesResponseData =
        http.getJson<ApiEnvelope<ListNamespacesResponseData>>("/admin-api/namespaces").data ?: emptyList()

    override suspend fun getNamespace(namespaceId: String): Namespace =
        http.getJson<ApiEnvelope<Namespace>>("/admin-api/namespaces/${pathSegment(namespaceId)}").data ?: error("getNamespace")

    override suspend fun listNamespacesForApplication(applicationId: String): ListNamespacesResponseData =
        http
            .getJson<ApiEnvelope<ListNamespacesResponseData>>(
                "/admin-api/namespaces/for-application/${pathSegment(applicationId)}",
            ).data ?: emptyList()

    override suspend fun createNamespace(request: CreateNamespaceRequest): CreateNamespaceResponseData =
        http
            .postJson<CreateNamespaceRequest, ApiEnvelope<CreateNamespaceResponseData>>(
                "/admin-api/namespaces", request,
            ).data ?: error("createNamespace")

    override suspend fun deleteNamespace(
        namespaceId: String,
        request: DeleteNamespaceRequest?,
    ): DeleteNamespaceResponseData {
        // Core requires `Content-Type: application/json` even with an empty body.
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("DELETE", "/admin-api/namespaces/${pathSegment(namespaceId)}", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteNamespaceResponseData>>(res.body).data
            ?: error("deleteNamespace")
    }

    /**
     * `GET /admin-api/namespaces/{namespaceId}/outbound-approvals`: what the namespace approves
     * for outbound requests, one entry per publisher (bundle signer).
     */
    override suspend fun listOutboundApprovals(namespaceId: String): List<OutboundApproval> =
        http
            .getJson<ApiEnvelope<List<OutboundApproval>>>(
                "/admin-api/namespaces/${pathSegment(namespaceId)}/outbound-approvals",
            ).data ?: emptyList()

    /**
     * `PUT /admin-api/namespaces/{namespaceId}/outbound-approvals`, body `{applicationId, approve}`
     * and nothing else. Sets the publisher's list exactly — `PUT`, not `POST`, because it replaces
     * rather than adds — and `OutboundSelection.Prefixes(emptyList())` revokes everything. It takes
     * effect at the app's next run; [SetOutboundApprovalResponseData.published] is `false` when
     * nothing changed.
     */
    override suspend fun setOutboundApproval(
        namespaceId: String,
        request: SetOutboundApprovalRequest,
    ): SetOutboundApprovalResponseData =
        http
            .putJson<SetOutboundApprovalRequest, ApiEnvelope<SetOutboundApprovalResponseData>>(
                "/admin-api/namespaces/${pathSegment(namespaceId)}/outbound-approvals", request,
            ).data ?: error("setOutboundApproval")

    override suspend fun createNamespaceInvitation(
        namespaceId: String,
        request: CreateNamespaceInvitationRequest?,
    ): CreateNamespaceInvitationResult {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/namespaces/${pathSegment(namespaceId)}/invite", body).ensureSuccessful()
        val data =
            http.json.decodeFromString<ApiEnvelope<JsonElement>>(res.body).data
                ?: error("createNamespaceInvitation")
        val isRecursive = (data as? JsonObject)?.containsKey("invitations") == true
        return if (isRecursive) {
            CreateNamespaceInvitationResult.Recursive(http.json.decodeFromJsonElement(data))
        } else {
            CreateNamespaceInvitationResult.Single(http.json.decodeFromJsonElement(data))
        }
    }

    override suspend fun joinNamespace(
        namespaceId: String,
        request: JoinNamespaceRequest,
    ): JoinNamespaceResponseData {
        // Join can be slow (network sync); the Swift/TS set a 65s timeout — the OkHttp
        // client's configured timeouts apply here.
        val res =
            http
                .execute("POST", "/admin-api/namespaces/${pathSegment(namespaceId)}/join", http.json.encodeToString(request))
                .ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<JoinNamespaceResponseData>>(res.body).data
            ?: error("joinNamespace")
    }

    /**
     * [joinNamespace], reported by whether it worked rather than by whether its request
     * returned — mero-js `AdminApiClient.redeemInvitation`. The join is sent once; a failed
     * request whose namespace [listNamespaces] then lists is [RedeemOutcome.AlreadyMember]
     * (a proxy timed out a join that landed, or the link was followed twice). See
     * [com.calimero.mero.admin.redeemInvitation].
     */
    override suspend fun redeemInvitation(
        namespaceId: String,
        invitation: SignedGroupOpenInvitation,
        groupName: String?,
        teamName: String?,
    ): RedeemOutcome =
        com.calimero.mero.admin
            .redeemInvitation(namespaceId, invitation, adminRedeemer(this, groupName), teamName)

    /**
     * Create a subgroup in a namespace.
     *
     * ⚠️ Always sends a `visibility`. Core 0.11.0-rc.83 flipped what an absent one
     * means — *restricted* before, **open** now — so leaving it to the node would
     * make the same call create different subgroups on different nodes. A `null`
     * request, or a `null` [CreateGroupInNamespaceRequest.visibility], is sent as
     * [CreateGroupInNamespaceRequest.VISIBILITY_OPEN], rc.83's own default.
     */
    override suspend fun createGroupInNamespace(
        namespaceId: String,
        request: CreateGroupInNamespaceRequest?,
    ): CreateGroupInNamespaceResponseData {
        val explicit =
            (request ?: CreateGroupInNamespaceRequest()).let {
                if (it.visibility == null) it.copy(visibility = CreateGroupInNamespaceRequest.VISIBILITY_OPEN) else it
            }
        val body = http.json.encodeToString(explicit)
        val res = http.execute("POST", "/admin-api/namespaces/${pathSegment(namespaceId)}/groups", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<CreateGroupInNamespaceResponseData>>(res.body).data
            ?: error("createGroupInNamespace")
    }

    override suspend fun listNamespaceGroups(namespaceId: String): List<SubgroupEntry> =
        http.getJson<ApiEnvelope<List<SubgroupEntry>>>("/admin-api/namespaces/${pathSegment(namespaceId)}/groups").data
            ?: emptyList()

    // ---- Group Management --------------------------------------------------

    override suspend fun getGroupInfo(groupId: String): GroupInfoResponseData =
        http.getJson<ApiEnvelope<GroupInfoResponseData>>("/admin-api/groups/${pathSegment(groupId)}").data ?: error("getGroupInfo")

    /** Thin wrapper over [getGroupInfo]: returns the group's `defaultCapabilities` bitmask. */
    override suspend fun getDefaultCapabilities(groupId: String): Int =
        getGroupInfo(groupId).defaultCapabilities

    /** Thin wrapper over [getGroupInfo]: returns the group's `subgroupVisibility`. */
    override suspend fun getSubgroupVisibility(groupId: String): String =
        getGroupInfo(groupId).subgroupVisibility

    override suspend fun deleteGroup(
        groupId: String,
        request: DeleteGroupRequest?,
    ): DeleteGroupResponseData {
        // Core requires `Content-Type: application/json` even with an empty body.
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("DELETE", "/admin-api/groups/${pathSegment(groupId)}", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<DeleteGroupResponseData>>(res.body).data ?: error("deleteGroup")
    }

    override suspend fun listGroupMembers(groupId: String): ListGroupMembersResponseData {
        // Response is un-enveloped ({ members, selfIdentity }). Validate the non-optional
        // `members` field so a contract-violating response surfaces as a clear error.
        val res = http.execute("GET", "/admin-api/groups/${pathSegment(groupId)}/members").ensureSuccessful()
        val lenient = http.json.decodeFromString<ListGroupMembersLenient>(res.body)
        val members =
            lenient.members ?: run {
                val safeId = groupId.filterNot { it.isWhitespace() }.take(64)
                error("Invalid listGroupMembers response for group $safeId: missing or non-array `members` field")
            }
        return ListGroupMembersResponseData(members = members, selfIdentity = lenient.selfIdentity)
    }

    override suspend fun listGroupContexts(groupId: String): ListGroupContextsResponseData =
        http.getJson<ApiEnvelope<ListGroupContextsResponseData>>("/admin-api/groups/${pathSegment(groupId)}/contexts").data
            ?: emptyList()

    override suspend fun addGroupMembers(
        groupId: String,
        request: AddGroupMembersRequest,
    ) {
        http.execute("POST", "/admin-api/groups/${pathSegment(groupId)}/members", http.json.encodeToString(request)).ensureSuccessful()
    }

    override suspend fun removeGroupMembers(
        groupId: String,
        request: RemoveGroupMembersRequest,
    ) {
        http
            .execute("POST", "/admin-api/groups/${pathSegment(groupId)}/members/remove", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    override suspend fun updateMemberRole(
        groupId: String,
        identity: String,
        request: UpdateMemberRoleRequest,
    ) {
        http
            .execute("PUT", "/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/role", http.json.encodeToString(request))
            .ensureSuccessful()
    }

    override suspend fun getMemberCapabilities(
        groupId: String,
        identity: String,
    ): MemberCapabilities =
        http
            .getJson<ApiEnvelope<MemberCapabilities>>(
                "/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/capabilities",
            ).data ?: error("getMemberCapabilities")

    override suspend fun setMemberCapabilities(
        groupId: String,
        identity: String,
        request: SetMemberCapabilitiesRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/capabilities", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    override suspend fun setDefaultCapabilities(
        groupId: String,
        request: SetDefaultCapabilitiesRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/settings/default-capabilities", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    override suspend fun setSubgroupVisibility(
        groupId: String,
        request: SetSubgroupVisibilityRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/settings/subgroup-visibility", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/settings/tee-admission-policy", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    /**
     * [setTeeAdmissionPolicy] with the `rootProof` minted here by the signing admin's account
     * [root]: the op the node will build from [request] is encoded, the group's
     * `namespaceId` and `ownerOpCounter` are read ([getGroupInfo]) and the root signs
     * ([com.calimero.mero.ownerop.signOwnerOpProof]). Any `rootProof` already on [request]
     * is replaced. [keyEpoch] / [chain] are for a root that was handed off.
     */
    override suspend fun setTeeAdmissionPolicy(
        groupId: String,
        request: SetTeeAdmissionPolicyRequest,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(groupId, teeAdmissionPolicyOpOf(request), root, keyEpoch, chain)
        setTeeAdmissionPolicy(groupId, request.copy(rootProof = proof))
    }

    override suspend fun getTeeAdmissionPolicy(groupId: String): GetTeeAdmissionPolicyResponseData {
        // `data` is optional so a flat (un-enveloped) response cleanly falls back.
        val res = http.execute("GET", "/admin-api/groups/${pathSegment(groupId)}/settings/tee-admission-policy").ensureSuccessful()
        runCatching { http.json.decodeFromString<TeeAdmissionEnv>(res.body).data }.getOrNull()?.let { return it }
        return http.json.decodeFromString(res.body)
    }

    // ---- Group / member / context metadata ---------------------------------

    override suspend fun setGroupMetadata(
        groupId: String,
        request: SetGroupMetadataRequest,
    ) {
        http.execute("PUT", "/admin-api/groups/${pathSegment(groupId)}/metadata", http.json.encodeToString(request)).ensureSuccessful()
    }

    override suspend fun getGroupMetadata(groupId: String): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/${pathSegment(groupId)}/metadata")

    override suspend fun setMemberMetadata(
        groupId: String,
        identity: String,
        request: SetMemberMetadataRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/metadata", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    override suspend fun getMemberMetadata(
        groupId: String,
        identity: String,
    ): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/metadata")

    override suspend fun setContextMetadata(
        groupId: String,
        contextId: String,
        request: SetContextMetadataRequest,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/contexts/${pathSegment(contextId)}/metadata", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    override suspend fun getContextMetadata(
        groupId: String,
        contextId: String,
    ): MetadataRecord? =
        getMetadataRecord("/admin-api/groups/${pathSegment(groupId)}/contexts/${pathSegment(contextId)}/metadata")

    override suspend fun syncGroup(
        groupId: String,
        request: SyncGroupRequest?,
    ): SyncGroupResponseData {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/${pathSegment(groupId)}/sync", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<SyncGroupResponseData>>(res.body).data ?: error("syncGroup")
    }

    /**
     * Join + state-pull every context in a group, so a freshly joined group's contexts initialize
     * instead of staying uninitialized (the "1111…" root hash). Sync/join failures per context are
     * tolerated — cross-node sync is asynchronous, so callers normally retry this.
     */
    override suspend fun syncGroupContexts(groupId: String): ListGroupContextsResponseData {
        runCatching { syncGroup(groupId) }
        val contexts = listGroupContexts(groupId)
        for (ctx in contexts) {
            runCatching { joinContext(ctx.contextId) }
            runCatching { syncContext(ctx.contextId) }
        }
        return contexts
    }

    override suspend fun upgradeGroup(
        groupId: String,
        request: UpgradeGroupRequest,
    ): UpgradeGroupResponseData =
        http
            .postJson<UpgradeGroupRequest, ApiEnvelope<UpgradeGroupResponseData>>(
                "/admin-api/groups/${pathSegment(groupId)}/upgrade", request,
            ).data ?: error("upgradeGroup")

    /** Value is `GroupUpgradeStatus | null`; returns the optional directly. */
    override suspend fun getGroupUpgradeStatus(groupId: String): GroupUpgradeStatusResponseData =
        http.getJson<ApiEnvelope<GroupUpgradeStatus>>("/admin-api/groups/${pathSegment(groupId)}/upgrade/status").data

    /**
     * The operator-facing "have all peers migrated?" rollup for a namespace. The handler
     * serializes the payload directly, so there is no `{ data }` envelope to unwrap here.
     */
    override suspend fun getMigrationStatus(namespaceId: String): MigrationStatus =
        http.getJson("/admin-api/groups/${pathSegment(namespaceId)}/migration-status")

    /** Per-group cascade-migration snapshots for a namespace. */
    override suspend fun getCascadeStatus(namespaceId: String): List<CascadeStatusEntry> =
        http
            .getJson<ApiEnvelope<List<CascadeStatusEntry>>>(
                "/admin-api/groups/${pathSegment(namespaceId)}/cascade-status",
            ).data ?: emptyList()

    override suspend fun retryGroupUpgrade(
        groupId: String,
        request: RetryGroupUpgradeRequest?,
    ): RetryGroupUpgradeResponseData {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/${pathSegment(groupId)}/upgrade/retry", body).ensureSuccessful()
        return http.json.decodeFromString<ApiEnvelope<RetryGroupUpgradeResponseData>>(res.body).data
            ?: error("retryGroupUpgrade")
    }

    /** Move `childGroupId` under `request.newParentId`. */
    override suspend fun reparentGroup(
        childGroupId: String,
        request: ReparentGroupRequest,
    ): ReparentGroupResponseData {
        // Core returns this flat ({ reparented }); tolerate the { data } envelope too.
        val res =
            http
                .execute("POST", "/admin-api/groups/${pathSegment(childGroupId)}/reparent", http.json.encodeToString(request))
                .ensureSuccessful()
        val env = http.json.decodeFromString<ReparentEnv>(res.body)
        return env.data ?: ReparentGroupResponseData(reparented = env.reparented ?: false)
    }

    override suspend fun listSubgroups(groupId: String): List<SubgroupEntry> {
        val res = http.execute("GET", "/admin-api/groups/${pathSegment(groupId)}/subgroups").ensureSuccessful()
        val wire = http.json.decodeFromString<SubgroupsWire>(res.body)
        return wire.subgroups ?: wire.data ?: emptyList()
    }

    override suspend fun detachContextFromGroup(
        groupId: String,
        contextId: String,
        request: DetachContextFromGroupRequest?,
    ) {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        http.execute("POST", "/admin-api/groups/${pathSegment(groupId)}/contexts/${pathSegment(contextId)}/remove", body).ensureSuccessful()
    }

    // ---- Group Invitation & Join -------------------------------------------

    override suspend fun createGroupInvitation(
        groupId: String,
        request: CreateGroupInvitationRequest?,
    ): CreateGroupInvitationResult {
        val body = request?.let { http.json.encodeToString(it) } ?: "{}"
        val res = http.execute("POST", "/admin-api/groups/${pathSegment(groupId)}/invite", body).ensureSuccessful()
        val data = http.json.decodeFromString<ApiEnvelope<JsonElement>>(res.body).data ?: error("createGroupInvitation")
        val isRecursive = (data as? JsonObject)?.containsKey("invitations") == true
        return if (isRecursive) {
            CreateGroupInvitationResult.Recursive(http.json.decodeFromJsonElement(data))
        } else {
            CreateGroupInvitationResult.Single(http.json.decodeFromJsonElement(data))
        }
    }

    override suspend fun joinGroup(request: JoinGroupRequest): JoinGroupResponseData =
        http.postJson<JoinGroupRequest, ApiEnvelope<JoinGroupResponseData>>("/admin-api/groups/join", request).data
            ?: error("joinGroup")

    override suspend fun joinSubgroupInheritance(groupId: String): JoinSubgroupInheritanceResponseData =
        http
            .postJson<JsonObject, ApiEnvelope<JoinSubgroupInheritanceResponseData>>(
                "/admin-api/groups/${pathSegment(groupId)}/join-via-inheritance", JsonObject(emptyMap()),
            ).data ?: error("joinSubgroupInheritance")

    // ---- TEE ---------------------------------------------------------------

    override suspend fun getTeeInfo(): TeeInfoResponseData =
        http.getJson<ApiEnvelope<TeeInfoResponseData>>("/admin-api/tee/info").data ?: error("getTeeInfo")

    override suspend fun teeAttest(request: TeeAttestRequest): TeeAttestResponseData =
        http.postJson<TeeAttestRequest, ApiEnvelope<TeeAttestResponseData>>("/admin-api/tee/attest", request).data
            ?: error("teeAttest")

    /**
     * A quote for fleet registration (core rc.83), `POST /admin-api/tee/registration-attest`.
     * Same response shape as [teeAttest].
     *
     * `teeVerifyQuote` is gone: its route was removed from core before rc.41.
     */
    override suspend fun teeRegistrationAttest(request: TeeRegistrationAttestRequest): TeeAttestResponseData =
        http
            .postJson<TeeRegistrationAttestRequest, ApiEnvelope<TeeAttestResponseData>>(
                "/admin-api/tee/registration-attest", request,
            ).data ?: error("teeRegistrationAttest")

    // ---- Network -----------------------------------------------------------

    override suspend fun getPeersCount(): PeersCountResponseData =
        http.getJson("/admin-api/peers")

    /** Node network status (GET /admin-api/network/status). */
    override suspend fun getNetworkStatus(): JsonElement =
        rawJson("GET", "/admin-api/network/status", null)

    /** Node storage/usage stats (GET /admin-api/usage). */
    override suspend fun getUsage(): JsonElement =
        rawJson("GET", "/admin-api/usage", null)

    // `getCertificate` is gone: core 0.11.0-rc.83 removed `GET /admin-api/certificate`
    // (a928b5ed4, "remove the dead certificate route").

    // ---- Group / context / namespace membership ----------------------------

    /**
     * Create a standalone group (POST /admin-api/groups).
     *
     * ⚠️ Never put a `groupId` in the map: core 0.11.0-rc.83 derives group ids and
     * refuses a body naming one (`deny_unknown_fields`). Prefer the typed overload.
     */
    override suspend fun createGroup(request: Map<String, JsonElement>): CreateGroupResponseData =
        http.postJson<Map<String, JsonElement>, ApiEnvelope<CreateGroupResponseData>>("/admin-api/groups", request).data
            ?: error("createGroup")

    /** Create a standalone group from the typed body. The id is derived by the node. */
    override suspend fun createGroup(request: CreateGroupRequest): CreateGroupResponseData =
        http.postJson<CreateGroupRequest, ApiEnvelope<CreateGroupResponseData>>("/admin-api/groups", request).data
            ?: error("createGroup")

    /** Leave a group (POST /admin-api/groups/:group_id/leave). */
    override suspend fun leaveGroup(
        groupId: String,
        request: Map<String, JsonElement>?,
    ) {
        http
            .execute("POST", "/admin-api/groups/${pathSegment(groupId)}/leave", http.json.encodeToString(request ?: emptyMap()))
            .ensureSuccessful()
    }

    /** Leave a context (POST /admin-api/contexts/:context_id/leave). */
    override suspend fun leaveContext(
        contextId: String,
        request: Map<String, JsonElement>?,
    ) {
        http
            .execute("POST", "/admin-api/contexts/${pathSegment(contextId)}/leave", http.json.encodeToString(request ?: emptyMap()))
            .ensureSuccessful()
    }

    /** Leave a namespace (POST /admin-api/namespaces/:namespace_id/leave). */
    override suspend fun leaveNamespace(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ) {
        http
            .execute(
                "POST", "/admin-api/namespaces/${pathSegment(namespaceId)}/leave", http.json.encodeToString(request ?: emptyMap()),
            ).ensureSuccessful()
    }

    /** Issue a group ownership proof (POST /admin-api/groups/:group_id/issue-ownership-proof). */
    override suspend fun issueOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement =
        rawJson("POST", "/admin-api/groups/${pathSegment(groupId)}/issue-ownership-proof", http.json.encodeToString(request ?: emptyMap()))

    /** Typed [issueOwnershipProof]. The response is flat on the wire; an enveloped one is tolerated. */
    override suspend fun issueOwnershipProof(
        groupId: String,
        request: IssueOwnershipProofRequest,
    ): IssueOwnershipProofResponseData =
        decodeFlatOrEnveloped(
            http
                .execute(
                    "POST", "/admin-api/groups/${pathSegment(groupId)}/issue-ownership-proof", http.json.encodeToString(request),
                ).ensureSuccessful()
                .body,
        )

    /**
     * Typed [issueNamespaceOwnershipProof]. Since core rc.83 the response carries
     * the namespace's `founding` and the signer's `credential`, so a verifier can
     * check the founder without holding namespace state.
     */
    override suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: IssueNamespaceOwnershipProofRequest,
    ): IssueOwnershipProofResponseData =
        decodeFlatOrEnveloped(
            http
                .execute(
                    "POST",
                    "/admin-api/groups/${pathSegment(groupId)}/issue-namespace-ownership-proof",
                    http.json.encodeToString(request),
                ).ensureSuccessful()
                .body,
        )

    /** Issue a namespace ownership proof (POST /admin-api/groups/:group_id/issue-namespace-ownership-proof). */
    override suspend fun issueNamespaceOwnershipProof(
        groupId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement =
        rawJson(
            "POST",
            "/admin-api/groups/${pathSegment(groupId)}/issue-namespace-ownership-proof",
            http.json.encodeToString(request ?: emptyMap()),
        )

    /** Set a member's auto-follow flag (PUT /admin-api/groups/:group_id/members/:identity/auto-follow). */
    override suspend fun setMemberAutoFollow(
        groupId: String,
        identity: String,
        request: Map<String, JsonElement>,
    ) {
        http
            .execute(
                "PUT", "/admin-api/groups/${pathSegment(groupId)}/members/${pathSegment(identity)}/auto-follow", http.json.encodeToString(request),
            ).ensureSuccessful()
    }

    /** Abort a namespace migration (POST /admin-api/groups/:namespace_id/migration/abort). */
    override suspend fun abortMigration(
        namespaceId: String,
        request: Map<String, JsonElement>?,
    ): JsonElement =
        rawJson("POST", "/admin-api/groups/${pathSegment(namespaceId)}/migration/abort", http.json.encodeToString(request ?: emptyMap()))

    // ---- Private helpers ---------------------------------------------------

    private fun blobPath(
        blobId: String,
        contextId: String?,
    ): String =
        if (contextId == null) {
            "/admin-api/blobs/${pathSegment(blobId)}"
        } else {
            "/admin-api/blobs/${pathSegment(blobId)}?context_id=${encodeComponent(contextId)}"
        }

    /** Decode a payload core may send flat or under `{ data }`. */
    private inline fun <reified T> decodeFlatOrEnveloped(body: String): T {
        val element = http.json.parseToJsonElement(body)
        val inner = (element as? JsonObject)?.get("data")?.takeIf { it is JsonObject } ?: element
        return http.json.decodeFromJsonElement(inner)
    }

    /** POST/PUT/DELETE a body whose response is `{}` (or `{data:{}}`) — success is the status. */
    private suspend fun send(
        method: String,
        path: String,
        body: String?,
    ) {
        http.execute(method, path, body).ensureSuccessful()
    }

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
    private suspend fun rawJson(
        method: String,
        path: String,
        body: String?,
    ): JsonElement {
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
    private suspend fun fetchExternal(
        url: String,
        failure: String,
    ): String =
        withContext(Dispatchers.IO) {
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

    // ---- Application ABI (rc.26) -------------------------------------------

    /**
     * The application's WASM ABI: its types, methods and events, as the node
     * resolved them from the installed bundle.
     *
     * [serviceName] selects one service from a multi-service bundle; omit it for
     * a single-service application. Returns `null` on `404` — the application is
     * not installed here.
     */
    override suspend fun getApplicationAbi(
        applicationId: String,
        serviceName: String?,
    ): JsonElement? {
        val query = serviceName?.let { "?service_name=" + encodeComponent(it) }.orEmpty()
        val res = http.execute("GET", "/admin-api/applications/${pathSegment(applicationId)}/abi$query")
        if (res.status == 404) return null
        return http.json.decodeFromString<ApiEnvelope<JsonElement>>(res.ensureSuccessful().body).data
    }

    // ---- Intents (rc.26) ----------------------------------------------------

    /**
     * Execute a method under a warrant, rather than as this node's own identity.
     * The warrant names who authorized the call; `authorProof` binds it to the
     * author.
     */
    override suspend fun performIntent(
        contextId: String,
        request: PerformIntentRequest,
    ): PerformIntentResponseData =
        http
            .postJson<PerformIntentRequest, ApiEnvelope<PerformIntentResponseData>>(
                "/admin-api/contexts/${pathSegment(contextId)}/intents", request,
            ).data ?: error("performIntent")

    // ---- Account: devices, applications, pairing (rc.27 / rc.28) -----------

    /**
     * Every device bound to the account this node speaks for.
     *
     * ⚠️ Flat on the wire — `{"devices":[…]}`, no `data` envelope. So are
     * [listAccountApplications] and [listMemberDevices]. Only a real body settles
     * that; it is not guessable from the neighbouring routes.
     */
    override suspend fun listAccountDevices(): AccountDevices =
        http.getJson("/admin-api/account/devices")

    /** Applications this account speaks in, derived from the namespaces it is in. Flat. */
    override suspend fun listAccountApplications(): AccountApplications =
        http.getJson("/admin-api/account/applications")

    /**
     * Each member of a group and the devices behind it. Flat: `{"members":[…]}`.
     *
     * [offset] and [limit] page through the members; both are left off the wire
     * when `null`, so the node's own defaults (and its clamp on `limit`) apply.
     */
    override suspend fun listMemberDevices(
        groupId: String,
        offset: Int?,
        limit: Int?,
    ): MemberDevices {
        val query =
            buildList {
                offset?.let { add("offset=$it") }
                limit?.let { add("limit=$it") }
            }
        val base = "/admin-api/groups/${pathSegment(groupId)}/member-devices"
        return http.getJson(if (query.isEmpty()) base else base + "?" + query.joinToString("&"))
    }

    /** [listMemberDevices], unwrapped to the member list (mero-js `listGroupMemberDevices`). */
    override suspend fun listGroupMemberDevices(
        groupId: String,
        offset: Int?,
        limit: Int?,
    ): List<MemberDeviceGroup> = listMemberDevices(groupId, offset, limit).members

    /**
     * Begin pairing a device into an account.
     *
     * ⚠️ Pairing is an **account** operation since rc.28 — it used to live under
     * `/namespaces/{id}/account/pair-*`. A device pairs once, then links into
     * namespaces. [revokeAccountDevice] stayed per-namespace, because that is
     * where the group key rotates.
     */
    override suspend fun pairInit(request: PairInitRequest): PairInitResponseData =
        http
            .postJson<PairInitRequest, ApiEnvelope<PairInitResponseData>>(
                "/admin-api/account/pair-init", request,
            ).data ?: error("pairInit")

    /** Complete a pairing begun by [pairInit], after both sides matched the code. */
    override suspend fun pairComplete(request: PairCompleteRequest): PairCompleteResponseData =
        http
            .postJson<PairCompleteRequest, ApiEnvelope<PairCompleteResponseData>>(
                "/admin-api/account/pair-complete", request,
            ).data ?: error("pairComplete")

    /** Re-link an already-certified device, optionally narrowing which apps it may speak for. */
    override suspend fun relinkDevice(
        deviceId: String,
        request: RelinkDeviceRequest,
    ): RelinkDeviceResponseData =
        http
            .postJson<RelinkDeviceRequest, ApiEnvelope<RelinkDeviceResponseData>>(
                "/admin-api/account/devices/${pathSegment(deviceId)}/relink", request,
            ).data ?: error("relinkDevice")

    /**
     * Replace a device's scope outright — narrowing it, which [relinkDevice]
     * cannot do because it is add-only (core rc.41).
     *
     * `PUT /admin-api/account/devices/{deviceId}/scope`, body `{scope}` and
     * nothing else. Pass [DeviceScope.All] for every application, or
     * [DeviceScope.Only] to name them; an empty `Only` is a `400`, deliberately,
     * rather than the widest possible ask.
     *
     * ⚠️ Must run on the node holding the **account root** — only its key can
     * sign the replacement. The device need not be online.
     */
    override suspend fun rescopeDevice(
        deviceId: String,
        request: RescopeDeviceRequest,
    ): RescopeDeviceResponseData =
        http
            .putJson<RescopeDeviceRequest, ApiEnvelope<RescopeDeviceResponseData>>(
                "/admin-api/account/devices/${pathSegment(deviceId)}/scope", request,
            ).data ?: error("rescopeDevice")

    /**
     * Name a device of this account (core rc.41), so a device list renders as
     * something other than 64 hex characters.
     *
     * `PUT /admin-api/account/devices/{deviceId}/label`, body `{label}` and
     * nothing else. The name replicates — it comes back on
     * [AccountDevice.label] for every device of the account — and
     * `labelEpoch` orders it against a rename made elsewhere at the same time.
     */
    override suspend fun labelDevice(
        deviceId: String,
        request: LabelDeviceRequest,
    ): LabelDeviceResponseData =
        http
            .putJson<LabelDeviceRequest, ApiEnvelope<LabelDeviceResponseData>>(
                "/admin-api/account/devices/${pathSegment(deviceId)}/label", request,
            ).data ?: error("labelDevice")

    /** Revoke a device from ONE namespace, rotating that namespace's group key. */
    override suspend fun revokeAccountDevice(
        namespaceId: String,
        request: RevokeDeviceRequest,
    ): RevokeDeviceResponseData =
        http
            .postJson<RevokeDeviceRequest, ApiEnvelope<RevokeDeviceResponseData>>(
                "/admin-api/namespaces/${pathSegment(namespaceId)}/account/revoke", request,
            ).data ?: error("revokeAccountDevice")

    /**
     * The devices revoked in a namespace and the slots their own accounts withdrew
     * (`GET /admin-api/namespaces/{id}/revoked-devices`). A subgroup id resolves to its
     * namespace. `403` unless this node's account is bound in the namespace. The handler
     * serializes the payload directly — no `{ data }` envelope.
     */
    override suspend fun listNamespaceRevokedDevices(namespaceId: String): NamespaceRevokedDevices =
        http.getJson("/admin-api/namespaces/${pathSegment(namespaceId)}/revoked-devices")

    // ---- Direct admission (rc.29) ------------------------------------------

    /**
     * Publish a join on behalf of a keyholder that has no node of its own.
     *
     * This node must appear in the invitation's signed `admitters` list; being
     * designated is what permits it to carry the claim. The response says
     * **`published`** — not *joined*.
     */
    override suspend fun admitJoin(
        namespaceId: String,
        request: AdmitJoinRequest,
    ): AdmitJoinResponseData =
        http
            .postJson<AdmitJoinRequest, ApiEnvelope<AdmitJoinResponseData>>(
                "/admin-api/namespaces/${pathSegment(namespaceId)}/admit", request,
            ).data ?: error("admitJoin")

    // ---- Reads as the account and delegated execution (rc.83) --------------

    /**
     * Read a context as the session's ACCOUNT, without minting a warrant
     * (`POST /admin-api/contexts/{id}/query`).
     *
     * Needs an account-authenticated session; the account must be a member of
     * the owning group (`403`) and the method declared read-only in the ABI
     * (`409` — a write needs [performIntent]). A method that refuses its call is a
     * `400` whose [com.calimero.mero.http.HttpException.errorType] is
     * `FunctionCallError`.
     */
    override suspend fun queryContext(
        contextId: String,
        request: QueryContextRequest,
    ): QueryContextResponseData =
        http
            .postJson<QueryContextRequest, ApiEnvelope<QueryContextResponseData>>(
                "/admin-api/contexts/${pathSegment(contextId)}/query", request,
            ).data ?: QueryContextResponseData()

    /**
     * What this node can do for a member in [contextId], read before signing a
     * warrant: the executor account and key, the release to pin, and whether it
     * holds `CAN_AUTHOR_ON_BEHALF`. `404` when it holds no identity there or the
     * group names no release yet.
     */
    override suspend fun getIntentRelay(contextId: String): IntentRelayInfo =
        http.getJson<ApiEnvelope<IntentRelayInfo>>("/admin-api/contexts/${pathSegment(contextId)}/intents").data
            ?: error("getIntentRelay")

    /**
     * Create a context under a signed creation warrant
     * (`POST /admin-api/groups/{id}/context-intents`). The warrant is opaque here.
     */
    override suspend fun createContextIntent(
        groupId: String,
        request: CreateContextIntentRequest,
    ): CreateContextIntentResponseData =
        http
            .postJson<CreateContextIntentRequest, ApiEnvelope<CreateContextIntentResponseData>>(
                "/admin-api/groups/${pathSegment(groupId)}/context-intents", request,
            ).data ?: error("createContextIntent")

    /** Discovery for [createContextIntent]. [author] (an account id) adds `authorMayCreate`. */
    override suspend fun getContextIntentRelay(
        groupId: String,
        author: String?,
    ): ContextIntentRelayInfo {
        val query = author?.let { "?author=" + encodeComponent(it) }.orEmpty()
        return http.getJson<ApiEnvelope<ContextIntentRelayInfo>>("/admin-api/groups/${pathSegment(groupId)}/context-intents$query").data
            ?: error("getContextIntentRelay")
    }

    /** Apply a governance op under a signed governance warrant (`POST …/governance-intents`). */
    override suspend fun governanceIntent(
        groupId: String,
        request: GovernanceIntentRequest,
    ): GovernanceIntentResponseData =
        http
            .postJson<GovernanceIntentRequest, ApiEnvelope<GovernanceIntentResponseData>>(
                "/admin-api/groups/${pathSegment(groupId)}/governance-intents", request,
            ).data ?: error("governanceIntent")

    /** Discovery for [governanceIntent]. */
    override suspend fun getGovernanceIntentRelay(groupId: String): GovernanceIntentRelayInfo =
        http.getJson<ApiEnvelope<GovernanceIntentRelayInfo>>("/admin-api/groups/${pathSegment(groupId)}/governance-intents").data
            ?: error("getGovernanceIntentRelay")

    /**
     * Carry a signed presence statement (`POST …/presence-intents`). Answers `204`;
     * refusals are `400`, `403`, `413`, `429` or `503`. `state` is always on the
     * wire — as `null` to clear the slice.
     */
    override suspend fun presenceIntent(
        contextId: String,
        request: PresenceIntentRequest,
    ) {
        val body =
            buildJsonObject {
                put("state", request.state?.let { JsonPrimitive(it) } ?: JsonNull)
                put("seq", request.seq)
                put("sentAtMs", request.sentAtMs)
                put("signature", request.signature)
                put("authorProof", request.authorProof)
            }
        send("POST", "/admin-api/contexts/${pathSegment(contextId)}/presence-intents", http.json.encodeToString(JsonObject.serializer(), body))
    }

    // ---- Account root, device links, sealing (rc.83) ------------------------

    /**
     * Have this node's account root sign [AccountSignWithRootRequest.payload] under
     * one of [AccountSignDomains] — e.g. to link the account to Calimero Cloud.
     * Must run on the node holding the root.
     */
    override suspend fun signWithAccountRoot(request: AccountSignWithRootRequest): AccountSignWithRootResponseData =
        http
            .postJson<AccountSignWithRootRequest, ApiEnvelope<AccountSignWithRootResponseData>>(
                "/admin-api/account/sign-with-root", request,
            ).data ?: error("signWithAccountRoot")

    /**
     * Bind a root-certified device into a namespace. `400` when a proof does not
     * verify or the scope does not reach this namespace; `403` when the device was
     * revoked here or the account is a member of nothing in it.
     */
    override suspend fun linkAccountDevice(
        namespaceId: String,
        request: LinkAccountDeviceRequest,
    ): LinkAccountDeviceResponseData =
        http
            .postJson<LinkAccountDeviceRequest, ApiEnvelope<LinkAccountDeviceResponseData>>(
                "/admin-api/namespaces/${pathSegment(namespaceId)}/account/link-device", request,
            ).data ?: error("linkAccountDevice")

    /**
     * [linkAccountDevice] for an account with no node of its own: the account [root] signs
     * the device's scope here ([com.calimero.mero.account.signDeviceScope]) for the device
     * [credential] certifies, and both go to the node. [applications] empty means every
     * application; [scopeEpoch] orders scopes for this device (0 for its first).
     *
     * Not for `rescopeDevice`: core's `PUT /admin-api/account/devices/{id}/scope` runs on the
     * node holding the root, which signs the replacement itself.
     */
    override suspend fun linkAccountDevice(
        namespaceId: String,
        credential: String,
        root: DeviceSigner,
        applications: List<String>,
        scopeEpoch: Long,
    ): LinkAccountDeviceResponseData {
        val device = DeviceCert.parse(credential).device
        val scope = signDeviceScope(DeviceScopeInput(device = device, applications = applications, scopeEpoch = scopeEpoch, signer = root))
        return linkAccountDevice(namespaceId, LinkAccountDeviceRequest(credential = credential, scope = scope))
    }

    /** Seal a small payload to a member account's root key. This node must be a member. */
    override suspend fun sealToAccount(
        groupId: String,
        account: String,
        request: SealToAccountRequest,
    ): SealedEnvelope =
        http
            .postJson<SealToAccountRequest, ApiEnvelope<SealedEnvelope>>(
                "/admin-api/groups/${pathSegment(groupId)}/accounts/${pathSegment(account)}/seal", request,
            ).data ?: error("sealToAccount")

    // ---- Root-guarded owner ops (rc.83) -------------------------------------

    /** Hand [groupId] to an existing admin. Owner-only, root-guarded — see [TransferOwnershipRequest]. */
    override suspend fun transferOwnership(
        groupId: String,
        request: TransferOwnershipRequest,
    ) = send("POST", "/admin-api/groups/${pathSegment(groupId)}/transfer-ownership", http.json.encodeToString(request))

    /** Repoint the namespace's admin pin. Owner-only, root-guarded. */
    override suspend fun changeNamespaceAdmin(
        namespaceId: String,
        request: ChangeNamespaceAdminRequest,
    ) = send("POST", "/admin-api/namespaces/${pathSegment(namespaceId)}/admin", http.json.encodeToString(request))

    /**
     * Delete a group through the owner-only path: it must hold no contexts, and
     * the op is root-guarded. [deleteGroup] is the admin-level cascading delete.
     */
    override suspend fun ownerDeleteGroup(
        groupId: String,
        request: OwnerDeleteGroupRequest,
    ) = send("POST", "/admin-api/groups/${pathSegment(groupId)}/owner-delete", http.json.encodeToString(request))

    /** Which admitted TEEs may author as the TEE authority. [groupId] must be a namespace root. */
    override suspend fun setTeeAuthoringPolicy(
        groupId: String,
        request: SetTeeAuthoringPolicyRequest,
    ) = send("PUT", "/admin-api/groups/${pathSegment(groupId)}/settings/tee-authoring-policy", http.json.encodeToString(request))

    /**
     * Turn TEE authorship off (the same op as an empty [setTeeAuthoringPolicy]).
     * With no `rootProof` the DELETE carries no body and the node signs itself.
     */
    override suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        request: DisableTeeAuthoringPolicyRequest,
    ) {
        val body = request.rootProof?.let { http.json.encodeToString(request) }
        send("DELETE", "/admin-api/groups/${pathSegment(groupId)}/settings/tee-authoring-policy", body)
    }

    // ---- Root-guarded owner ops, signed here by the account root -------------
    //
    // Each reads the group's `namespaceId` and `ownerOpCounter` ([getGroupInfo]), encodes
    // the op the node will build, and has [root] sign the `rootProof`
    // (com.calimero.mero.ownerop, a port of mero-js `owner-op`). Use these when the node
    // does not hold the account root — a nodeless account, or a root kept cold on this
    // device. [keyEpoch] / [chain] are for a root that was handed off. A counter that moved
    // between the read and the send is a `409`: call again.

    /** [transferOwnership], with the root proof signed by [root]. */
    override suspend fun transferOwnership(
        groupId: String,
        newOwner: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(groupId, transferOwnershipOp(newOwner), root, keyEpoch, chain)
        transferOwnership(groupId, TransferOwnershipRequest(newOwner = newOwner, rootProof = proof))
    }

    /** [changeNamespaceAdmin], with the root proof signed by [root] (a root op on the namespace root). */
    override suspend fun changeNamespaceAdmin(
        namespaceId: String,
        newAdmin: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(namespaceId, adminChangedOp(newAdmin), root, keyEpoch, chain)
        changeNamespaceAdmin(namespaceId, ChangeNamespaceAdminRequest(newAdmin = newAdmin, rootProof = proof))
    }

    /** [ownerDeleteGroup], with the root proof signed by [root]. */
    override suspend fun ownerDeleteGroup(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(groupId, groupDeleteOp(), root, keyEpoch, chain)
        ownerDeleteGroup(groupId, OwnerDeleteGroupRequest(rootProof = proof))
    }

    /** [setTeeAuthoringPolicy], with the root proof signed by the signing admin's [root]. */
    override suspend fun setTeeAuthoringPolicy(
        groupId: String,
        allowedMrtd: List<String>,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(groupId, teeAuthoringPolicyOp(allowedMrtd), root, keyEpoch, chain)
        setTeeAuthoringPolicy(groupId, SetTeeAuthoringPolicyRequest(allowedMrtd = allowedMrtd, rootProof = proof))
    }

    /** [disableTeeAuthoringPolicy], with the root proof (for the empty policy) signed by [root]. */
    override suspend fun disableTeeAuthoringPolicy(
        groupId: String,
        root: DeviceSigner,
        keyEpoch: Long,
        chain: List<String>,
    ) {
        val proof = mintRootProof(groupId, teeAuthoringPolicyOp(emptyList()), root, keyEpoch, chain)
        disableTeeAuthoringPolicy(groupId, DisableTeeAuthoringPolicyRequest(rootProof = proof))
    }

    // ---- Delegated authorship capability helpers ----------------------------

    /**
     * Set `CAN_AUTHOR_ON_BEHALF` in [groupId]'s **default** capabilities, so
     * every non-admin member admitted from now on may relay a delegated write.
     *
     * Call it on the namespace root. Not retroactive, and it does not reach
     * admins — use [grantAuthorship] for those. Read-modify-write, so the other
     * default bits (notably `CAN_JOIN_OPEN_SUBGROUPS`) survive; a no-op when the
     * bit is already set.
     */
    override suspend fun openToDelegatedExecution(groupId: String): CapabilityChange {
        val current = getDefaultCapabilities(groupId).toLong() and U32
        if (Capabilities.hasCap(current, Capabilities.CAN_AUTHOR_ON_BEHALF)) return CapabilityChange(false, current)
        val next = Capabilities.withCap(current, Capabilities.CAN_AUTHOR_ON_BEHALF)
        setDefaultCapabilities(groupId, SetDefaultCapabilitiesRequest(defaultCapabilities = next.toInt()))
        return CapabilityChange(true, next)
    }

    /**
     * Grant [account] (a member's ACCOUNT id) `CAN_AUTHOR_ON_BEHALF` on [groupId],
     * leaving its other capabilities alone. A no-op when it already holds it.
     */
    override suspend fun grantAuthorship(
        groupId: String,
        account: String,
    ): CapabilityChange {
        val current = getMemberCapabilities(groupId, account).capabilities.toLong() and U32
        if (Capabilities.hasCap(current, Capabilities.CAN_AUTHOR_ON_BEHALF)) return CapabilityChange(false, current)
        val next = Capabilities.withCap(current, Capabilities.CAN_AUTHOR_ON_BEHALF)
        setMemberCapabilities(groupId, account, SetMemberCapabilitiesRequest(capabilities = next.toInt()))
        return CapabilityChange(true, next)
    }

    private companion object {
        private const val U32 = 0xFFFF_FFFFL

        /** RFC-3986 unreserved set: ALPHA / DIGIT / `-` / `.` / `_` / `~`. */
        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * [value] as exactly one path segment. Every id an admin path carries goes through here: a
         * `/`, `?` or `#` in it is encoded, so an id read from an invite cannot reach another route,
         * and an empty or dot segment, which a URL parser would resolve away, is refused. (SEC-02)
         *
         * @throws IllegalArgumentException for an empty, `.` or `..` value.
         */
        fun pathSegment(value: String): String {
            require(value.isNotEmpty() && value != "." && value != "..") { "Not a path segment: \"$value\"" }
            return encodeComponent(value)
        }

        /** Percent-encode a string for use as a query value or a path segment. */
        fun encodeComponent(value: String): String =
            buildString {
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

/** What [AdminApi.openToDelegatedExecution] / [AdminApi.grantAuthorship] did. */
data class CapabilityChange(
    /** `false` when the bit was already set and nothing was published. */
    val changed: Boolean,
    /** The resulting u32 mask. */
    val capabilities: Long,
)
