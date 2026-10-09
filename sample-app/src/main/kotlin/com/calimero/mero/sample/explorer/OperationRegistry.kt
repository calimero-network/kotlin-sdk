package com.calimero.mero.sample.explorer

import com.calimero.mero.admin.AccountSignWithRootRequest
import com.calimero.mero.admin.AddGroupMembersRequest
import com.calimero.mero.admin.AdmitJoinRequest
import com.calimero.mero.admin.ChangeNamespaceAdminRequest
import com.calimero.mero.admin.CreateApplicationAliasRequest
import com.calimero.mero.admin.CreateContextAliasRequest
import com.calimero.mero.admin.CreateContextIntentRequest
import com.calimero.mero.admin.CreateContextRequest
import com.calimero.mero.admin.CreateDeviceAliasRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupInvitationRequest
import com.calimero.mero.admin.CreateGroupInvitationResult
import com.calimero.mero.admin.CreateNamespaceInvitationRequest
import com.calimero.mero.admin.CreateNamespaceInvitationResult
import com.calimero.mero.admin.CreateNamespaceRequest
import com.calimero.mero.admin.DeleteContextRequest
import com.calimero.mero.admin.DeleteGroupRequest
import com.calimero.mero.admin.DeleteNamespaceRequest
import com.calimero.mero.admin.DetachContextFromGroupRequest
import com.calimero.mero.admin.GovernanceIntentRequest
import com.calimero.mero.admin.InstallApplicationRequest
import com.calimero.mero.admin.InstallDevApplicationRequest
import com.calimero.mero.admin.JoinGroupRequest
import com.calimero.mero.admin.JoinNamespaceRequest
import com.calimero.mero.admin.LabelDeviceRequest
import com.calimero.mero.admin.LinkAccountDeviceRequest
import com.calimero.mero.admin.PairCompleteRequest
import com.calimero.mero.admin.PairInitRequest
import com.calimero.mero.admin.PerformIntentRequest
import com.calimero.mero.admin.PresenceIntentRequest
import com.calimero.mero.admin.QueryContextRequest
import com.calimero.mero.admin.RelinkDeviceRequest
import com.calimero.mero.admin.RemoveGroupMembersRequest
import com.calimero.mero.admin.ReparentGroupRequest
import com.calimero.mero.admin.RescopeDeviceRequest
import com.calimero.mero.admin.ResyncContextRequest
import com.calimero.mero.admin.RetryGroupUpgradeRequest
import com.calimero.mero.admin.RevokeDeviceRequest
import com.calimero.mero.admin.RootGuardedOpRequest
import com.calimero.mero.admin.SealToAccountRequest
import com.calimero.mero.admin.SetContextMetadataRequest
import com.calimero.mero.admin.SetDefaultCapabilitiesRequest
import com.calimero.mero.admin.SetGroupMetadataRequest
import com.calimero.mero.admin.SetMemberCapabilitiesRequest
import com.calimero.mero.admin.SetMemberMetadataRequest
import com.calimero.mero.admin.SetSubgroupVisibilityRequest
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.admin.SetTeeAuthoringPolicyRequest
import com.calimero.mero.admin.SyncGroupRequest
import com.calimero.mero.admin.TeeAttestRequest
import com.calimero.mero.admin.TeeRegistrationAttestRequest
import com.calimero.mero.admin.TransferOwnershipRequest
import com.calimero.mero.admin.UpdateContextApplicationRequest
import com.calimero.mero.admin.UpdateMemberRoleRequest
import com.calimero.mero.admin.UpgradeGroupRequest
import com.calimero.mero.admin.UploadBlobRequest
import com.calimero.mero.admin.compareSemver
import com.calimero.mero.auth.CreateKeyRequest
import com.calimero.mero.auth.GenerateClientKeyRequest
import com.calimero.mero.auth.MockTokenRequest
import com.calimero.mero.auth.RefreshTokenRequest
import com.calimero.mero.auth.RevokeTokenRequest
import com.calimero.mero.auth.TokenRequest
import com.calimero.mero.auth.UpdateKeyPermissionsRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// The SDK surface, one `SDKOperation` per public method the Kotlin SDK exposes. Split into
// per-category lists (mirrors the Swift sample). Request bodies are entered as JSON and decoded
// into the typed request; results are pretty-printed via [Fmt].

private val healthOps =
    listOf(
        SDKOperation("adm.health", "Health & Node", "healthCheck", "Node liveness", emptyList()) { m, _ ->
            Fmt.json(m.admin.healthCheck())
        },
        SDKOperation("adm.isAuthed", "Health & Node", "isAuthed", "Admin auth status", emptyList()) { m, _ ->
            Fmt.json(m.admin.isAuthed())
        },
        SDKOperation("adm.ready", "Health & Node", "isReady", "Readiness (can it serve?)", emptyList()) { m, _ ->
            Fmt.json(m.admin.isReady())
        },
        SDKOperation("adm.identity", "Health & Node", "getNodeIdentity", "Who this node is", emptyList()) { m, _ ->
            Fmt.json(m.admin.getNodeIdentity())
        },
        SDKOperation("adm.peers", "Health & Node", "getPeersCount", "Connected peer count", emptyList()) { m, _ ->
            Fmt.json(m.admin.getPeersCount())
        },
        SDKOperation("adm.net", "Health & Node", "getNetworkStatus", "Network status", emptyList()) { m, _ ->
            Fmt.json(m.admin.getNetworkStatus())
        },
        SDKOperation("adm.usage", "Health & Node", "getUsage", "Storage / usage stats", emptyList()) { m, _ ->
            Fmt.json(m.admin.getUsage())
        },
    )

private val authOps =
    listOf(
        SDKOperation("auth.health", "Auth & Identity", "getHealth", "Auth service health", emptyList()) { m, _ ->
            Fmt.json(m.auth.getHealth())
        },
        SDKOperation("auth.identity", "Auth & Identity", "getIdentity", "Service / version / mode", emptyList()) { m, _ ->
            Fmt.json(m.auth.getIdentity())
        },
        SDKOperation("auth.providers", "Auth & Identity", "getProviders", "Available auth providers", emptyList()) { m, _ ->
            Fmt.json(m.auth.getProviders())
        },
        SDKOperation(
            "auth.gen", "Auth & Identity", "generateTokens", "POST /auth/token (provider handshake)",
            listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.generateTokens(Fmt.decode<TokenRequest>(i.v("body")))) },
        SDKOperation(
            "auth.refresh", "Auth & Identity", "refreshToken", "Refresh an access token", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.refreshToken(Fmt.decode<RefreshTokenRequest>(i.v("body")))) },
        SDKOperation(
            "auth.validate", "Auth & Identity", "validateToken", "HEAD /auth/validate",
            listOf(OpField.line("token", "Access token")),
        ) { m, i ->
            val r = m.auth.validateToken(i.v("token"))
            "valid: ${r.valid}\nstatus: ${r.status}\nheaders:\n${Fmt.json(r.headers)}"
        },
        SDKOperation(
            "auth.revoke", "Auth & Identity", "revokeTokens", "Revoke a client's tokens", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.revokeTokens(Fmt.decode<RevokeTokenRequest>(i.v("body")))) },
        SDKOperation(
            "auth.mock", "Auth & Identity", "generateMockTokens", "Mint mock tokens (dev nodes)", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.generateMockTokens(Fmt.decode<MockTokenRequest>(i.v("body")))) },
    )

private val keyOps =
    listOf(
        SDKOperation("key.rootList", "Root & Client Keys", "listRootKeys", "All root keys", emptyList()) { m, _ ->
            Fmt.json(m.auth.listRootKeys())
        },
        SDKOperation(
            "key.rootCreate", "Root & Client Keys", "createRootKey", "Register a root key", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.createRootKey(Fmt.decode<CreateKeyRequest>(i.v("body")))) },
        SDKOperation(
            "key.rootDelete", "Root & Client Keys", "deleteRootKey", "Delete a root key",
            listOf(OpField.line("keyId", "Key ID")),
        ) { m, i -> Fmt.json(m.auth.deleteRootKey(i.v("keyId"))) },
        SDKOperation("key.clientList", "Root & Client Keys", "listClientKeys", "All client keys", emptyList()) { m, _ ->
            Fmt.json(m.auth.listClientKeys())
        },
        SDKOperation(
            "key.clientGen", "Root & Client Keys", "generateClientKey", "Issue a client key", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.auth.generateClientKey(Fmt.decode<GenerateClientKeyRequest>(i.v("body")))) },
        SDKOperation(
            "key.clientDelete", "Root & Client Keys", "deleteClientKey", "Delete a client key",
            listOf(OpField.line("keyId", "Key ID"), OpField.line("clientId", "Client ID")),
        ) { m, i -> Fmt.json(m.auth.deleteClientKey(i.v("keyId"), i.v("clientId"))) },
        SDKOperation(
            "key.perms", "Root & Client Keys", "getKeyPermissions", "Key permissions",
            listOf(OpField.line("keyId", "Key ID")),
        ) { m, i -> Fmt.json(m.auth.getKeyPermissions(i.v("keyId"))) },
        SDKOperation(
            "key.permsUpdate", "Root & Client Keys", "updateKeyPermissions", "Add/remove key permissions",
            listOf(
                OpField.line("keyId", "Key ID"),
                OpField.json("body", "Changes", """{"add":[],"remove":[]}"""),
            ),
        ) { m, i ->
            Fmt.json(m.auth.updateKeyPermissions(i.v("keyId"), Fmt.decode<UpdateKeyPermissionsRequest>(i.v("body"))))
        },
    )

private val appOps =
    listOf(
        SDKOperation("app.list", "Applications", "listApplications", "Installed applications", emptyList()) { m, _ ->
            Fmt.json(m.admin.listApplications())
        },
        SDKOperation(
            "app.get", "Applications", "getApplication", "One application", listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.getApplication(i.v("appId"))) },
        SDKOperation(
            "app.install", "Applications", "installApplication", "Install by registry coordinates",
            listOf(OpField.json("body", "InstallApplicationRequest", """{"package":"","version":""}""")),
        ) { m, i -> Fmt.json(m.admin.installApplication(Fmt.decode<InstallApplicationRequest>(i.v("body")))) },
        SDKOperation(
            "app.installDev", "Applications", "installDevApplication", "Install a local dev bundle", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.admin.installDevApplication(Fmt.decode<InstallDevApplicationRequest>(i.v("body")))) },
        SDKOperation(
            "app.uninstall", "Applications", "uninstallApplication", "Uninstall",
            listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.uninstallApplication(i.v("appId"))) },
        SDKOperation(
            "app.versions", "Applications", "listApplicationVersions", "Installed versions",
            listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.listApplicationVersions(i.v("appId"))) },
        SDKOperation(
            "app.ctxFor", "Applications", "getContextsForApplication", "Contexts for an app",
            listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.getContextsForApplication(i.v("appId"))) },
        SDKOperation(
            "app.ctxExec", "Applications", "getContextsWithExecutorsForApplication", "Contexts + executors",
            listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.getContextsWithExecutorsForApplication(i.v("appId"))) },
        SDKOperation(
            "app.abi", "Applications", "getApplicationAbi", "The app's WASM ABI",
            listOf(OpField.line("appId", "Application ID"), OpField.line("serviceName", "Service (optional)")),
        ) { m, i -> Fmt.json(m.admin.getApplicationAbi(i.v("appId"), i.opt("serviceName")?.ifBlank { null })) },
        SDKOperation(
            "app.nsFor", "Applications", "listNamespacesForApplication", "Namespaces for an app",
            listOf(OpField.line("appId", "Application ID")),
        ) { m, i -> Fmt.json(m.admin.listNamespacesForApplication(i.v("appId"))) },
    )

private val pkgOps =
    listOf(
        SDKOperation("pkg.list", "Packages & Registry", "listPackages", "Known packages", emptyList()) { m, _ ->
            Fmt.json(m.admin.listPackages())
        },
        SDKOperation(
            "pkg.versions", "Packages & Registry", "listPackageVersions", "Installed versions",
            listOf(OpField.line("packageName", "Package name")),
        ) { m, i -> Fmt.json(m.admin.listPackageVersions(i.v("packageName"))) },
        SDKOperation(
            "pkg.latest", "Packages & Registry", "getLatestPackageVersion", "Latest installed version",
            listOf(OpField.line("packageName", "Package name")),
        ) { m, i -> Fmt.json(m.admin.getLatestPackageVersion(i.v("packageName"))) },
        SDKOperation(
            "pkg.regVersions", "Packages & Registry", "getRegistryVersions", "Registry versions (off-node)",
            listOf(OpField.line("registryUrl", "Registry URL"), OpField.line("packageName", "Package name")),
        ) { m, i -> Fmt.json(m.admin.getRegistryVersions(i.v("registryUrl"), i.v("packageName"))) },
        SDKOperation(
            "pkg.install", "Packages & Registry", "installFromRegistry", "Install by coordinates",
            listOf(OpField.line("packageName", "Package"), OpField.line("version", "Version")),
        ) { m, i -> Fmt.json(m.admin.installFromRegistry(i.v("packageName"), i.v("version"))) },
        SDKOperation(
            "pkg.semver", "Packages & Registry", "compareSemver", "Compare two versions",
            listOf(OpField.line("a", "Version A"), OpField.line("b", "Version B")),
        ) { _, i -> "compareSemver(${i.v("a")}, ${i.v("b")}) = ${compareSemver(i.v("a"), i.v("b"))}" },
    )

private val ctxOps =
    listOf(
        SDKOperation("ctx.list", "Contexts", "getContexts", "All contexts", emptyList()) { m, _ ->
            Fmt.json(m.admin.getContexts())
        },
        SDKOperation(
            "ctx.get", "Contexts", "getContext", "One context", listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContext(i.v("contextId"))) },
        SDKOperation(
            "ctx.create", "Contexts", "createContext", "Create a context", listOf(OpField.json("body", "CreateContextRequest")),
        ) { m, i -> Fmt.json(m.admin.createContext(Fmt.decode<CreateContextRequest>(i.v("body")))) },
        SDKOperation(
            "ctx.delete", "Contexts", "deleteContext", "Delete a context",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<DeleteContextRequest>(it) }
            Fmt.json(m.admin.deleteContext(i.v("contextId"), req))
        },
        SDKOperation(
            "ctx.join", "Contexts", "joinContext", "Join a context", listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.joinContext(i.v("contextId"))) },
        SDKOperation(
            "ctx.leave", "Contexts", "leaveContext", "Leave a context",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            m.admin.leaveContext(i.v("contextId"), req)
            "left context"
        },
        SDKOperation(
            "ctx.group", "Contexts", "getContextGroup", "Owning group (nullable)",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContextGroup(i.v("contextId"))) },
        SDKOperation(
            "ctx.intent", "Contexts", "performIntent", "Execute under a warrant",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "PerformIntentRequest")),
        ) { m, i -> Fmt.json(m.admin.performIntent(i.v("contextId"), Fmt.decode<PerformIntentRequest>(i.v("body")))) },
        SDKOperation(
            "ctx.storage", "Contexts", "getContextStorage", "Context storage stats",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContextStorage(i.v("contextId"))) },
        SDKOperation(
            "ctx.sync", "Contexts", "syncContext", "Sync one/all contexts",
            listOf(OpField.line("contextId", "Context ID (blank = all)")),
        ) { m, i ->
            m.admin.syncContext(i.opt("contextId"))
            "sync requested"
        },
        SDKOperation(
            "ctx.resync", "Contexts", "resyncContext", "Force a full re-pull",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "ResyncContextRequest", "{}")),
        ) { m, i -> Fmt.json(m.admin.resyncContext(i.v("contextId"), Fmt.decode<ResyncContextRequest>(i.v("body")))) },
        SDKOperation(
            "ctx.updateApp", "Contexts", "updateContextApplication", "Point a context at a new app",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "UpdateContextApplicationRequest")),
        ) { m, i ->
            m.admin.updateContextApplication(i.v("contextId"), Fmt.decode<UpdateContextApplicationRequest>(i.v("body")))
            "application updated"
        },
    )

private val ctxIdOps =
    listOf(
        SDKOperation(
            "cid.gen", "Context Identity", "generateContextIdentity", "Generate a context identity", emptyList(),
        ) { m, _ -> Fmt.json(m.admin.generateContextIdentity()) },
        SDKOperation(
            "cid.list", "Context Identity", "getContextIdentities", "All identities in a context",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContextIdentities(i.v("contextId"))) },
        SDKOperation(
            "cid.owned", "Context Identity", "getContextIdentitiesOwned", "Owned identities in a context",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContextIdentitiesOwned(i.v("contextId"))) },
    )

private val aliasOps =
    listOf(
        SDKOperation("al.ctxCreate", "Aliases", "createContextAlias", "Create context alias", listOf(OpField.json())) { m, i ->
            Fmt.json(m.admin.createContextAlias(Fmt.decode<CreateContextAliasRequest>(i.v("body"))))
        },
        SDKOperation(
            "al.appCreate", "Aliases", "createApplicationAlias", "Create application alias", listOf(OpField.json()),
        ) { m, i -> Fmt.json(m.admin.createApplicationAlias(Fmt.decode<CreateApplicationAliasRequest>(i.v("body")))) },
        SDKOperation(
            "al.ctxLookup", "Aliases", "lookupContextAlias", "Resolve context alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.lookupContextAlias(i.v("name"))) },
        SDKOperation(
            "al.appLookup", "Aliases", "lookupApplicationAlias", "Resolve application alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.lookupApplicationAlias(i.v("name"))) },
        SDKOperation(
            "al.ctxDelete", "Aliases", "deleteContextAlias", "Delete context alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.deleteContextAlias(i.v("name"))) },
        SDKOperation(
            "al.appDelete", "Aliases", "deleteApplicationAlias", "Delete application alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.deleteApplicationAlias(i.v("name"))) },
        SDKOperation("al.ctxList", "Aliases", "listContextAliases", "All context aliases", emptyList()) { m, _ ->
            Fmt.json(m.admin.listContextAliases())
        },
        SDKOperation("al.appList", "Aliases", "listApplicationAliases", "All application aliases", emptyList()) { m, _ ->
            Fmt.json(m.admin.listApplicationAliases())
        },
        SDKOperation("al.devList", "Aliases", "listDeviceAliases", "All device aliases", emptyList()) { m, _ ->
            Fmt.json(m.admin.listDeviceAliases())
        },
        SDKOperation("al.devCreate", "Aliases", "createDeviceAlias", "Create device alias", listOf(OpField.json())) { m, i ->
            Fmt.json(m.admin.createDeviceAlias(Fmt.decode<CreateDeviceAliasRequest>(i.v("body"))))
        },
        SDKOperation(
            "al.devLookup", "Aliases", "lookupDeviceAlias", "Resolve device alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.lookupDeviceAlias(i.v("name"))) },
        SDKOperation(
            "al.devDelete", "Aliases", "deleteDeviceAlias", "Delete device alias",
            listOf(OpField.line("name", "Alias name")),
        ) { m, i -> Fmt.json(m.admin.deleteDeviceAlias(i.v("name"))) },
    )

private val blobOps =
    listOf(
        SDKOperation("blob.list", "Blobs", "listBlobs", "All blobs", emptyList()) { m, _ ->
            Fmt.json(m.admin.listBlobs())
        },
        SDKOperation(
            "blob.upload", "Blobs", "uploadBlob", "Upload text as a blob",
            listOf(OpField.line("text", "Text to upload"), OpField.line("contextId", "Context ID (optional)")),
        ) { m, i ->
            val req = UploadBlobRequest(data = i.v("text").toByteArray(), hash = null, contextId = i.opt("contextId"))
            Fmt.json(m.admin.uploadBlob(req))
        },
        SDKOperation(
            "blob.info", "Blobs", "getBlobInfo", "Blob metadata (HEAD)", listOf(OpField.line("blobId", "Blob ID")),
        ) { m, i -> Fmt.json(m.admin.getBlobInfo(i.v("blobId"))) },
        SDKOperation(
            "blob.get", "Blobs", "getBlob", "Download blob bytes", listOf(OpField.line("blobId", "Blob ID")),
        ) { m, i ->
            val data = m.admin.getBlob(i.v("blobId"))
            val preview =
                runCatching { String(data.copyOfRange(0, minOf(400, data.size))) }.getOrNull()
                    ?: data.take(64).joinToString("") { "%02x".format(it) }
            "${data.size} bytes\n\n$preview"
        },
        SDKOperation(
            "blob.delete", "Blobs", "deleteBlob", "Delete a blob", listOf(OpField.line("blobId", "Blob ID")),
        ) { m, i -> Fmt.json(m.admin.deleteBlob(i.v("blobId"))) },
    )

private val nsOps =
    listOf(
        SDKOperation("ns.list", "Namespaces", "listNamespaces", "All namespaces", emptyList()) { m, _ ->
            Fmt.json(m.admin.listNamespaces())
        },
        SDKOperation(
            "ns.get", "Namespaces", "getNamespace", "One namespace", listOf(OpField.line("namespaceId", "Namespace ID")),
        ) { m, i -> Fmt.json(m.admin.getNamespace(i.v("namespaceId"))) },
        SDKOperation(
            "ns.create", "Namespaces", "createNamespace", "Create a namespace",
            listOf(OpField.json("body", "CreateNamespaceRequest")),
        ) { m, i -> Fmt.json(m.admin.createNamespace(Fmt.decode<CreateNamespaceRequest>(i.v("body")))) },
        SDKOperation(
            "ns.delete", "Namespaces", "deleteNamespace", "Delete a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<DeleteNamespaceRequest>(it) }
            Fmt.json(m.admin.deleteNamespace(i.v("namespaceId"), req))
        },
        SDKOperation(
            "ns.invite", "Namespaces", "createNamespaceInvitation", "Invite to a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<CreateNamespaceInvitationRequest>(it) }
            when (val r = m.admin.createNamespaceInvitation(i.v("namespaceId"), req)) {
                is CreateNamespaceInvitationResult.Single -> Fmt.json(r.data)
                is CreateNamespaceInvitationResult.Recursive -> Fmt.json(r.data)
            }
        },
        SDKOperation(
            "ns.join", "Namespaces", "joinNamespace", "Join a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "JoinNamespaceRequest")),
        ) { m, i -> Fmt.json(m.admin.joinNamespace(i.v("namespaceId"), Fmt.decode<JoinNamespaceRequest>(i.v("body")))) },
        SDKOperation(
            "ns.leave", "Namespaces", "leaveNamespace", "Leave a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            m.admin.leaveNamespace(i.v("namespaceId"), req)
            "left namespace"
        },
        SDKOperation(
            "ns.groupCreate", "Namespaces", "createGroupInNamespace", "Create a group in a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<CreateGroupInNamespaceRequest>(it) }
            Fmt.json(m.admin.createGroupInNamespace(i.v("namespaceId"), req))
        },
        SDKOperation(
            "ns.groups", "Namespaces", "listNamespaceGroups", "Groups in a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID")),
        ) { m, i -> Fmt.json(m.admin.listNamespaceGroups(i.v("namespaceId"))) },
        SDKOperation(
            "ns.migStatus", "Namespaces", "getMigrationStatus", "Migration rollup",
            listOf(OpField.line("namespaceId", "Namespace ID")),
        ) { m, i -> Fmt.json(m.admin.getMigrationStatus(i.v("namespaceId"))) },
        SDKOperation(
            "ns.cascade", "Namespaces", "getCascadeStatus", "Per-group cascade status",
            listOf(OpField.line("namespaceId", "Namespace ID")),
        ) { m, i -> Fmt.json(m.admin.getCascadeStatus(i.v("namespaceId"))) },
        SDKOperation(
            "ns.abort", "Namespaces", "abortMigration", "Abort a migration",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            Fmt.json(m.admin.abortMigration(i.v("namespaceId"), req))
        },
        SDKOperation(
            "ns.nsProof", "Namespaces", "issueNamespaceOwnershipProof", "Namespace ownership proof",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            Fmt.json(m.admin.issueNamespaceOwnershipProof(i.v("groupId"), req))
        },
    )

private val groupOps =
    listOf(
        SDKOperation(
            "grp.create", "Groups", "createGroup", "Create a standalone group",
            listOf(OpField.json("body", "Body (map)")),
        ) { m, i -> Fmt.json(m.admin.createGroup(Fmt.decode<Map<String, JsonElement>>(i.v("body")))) },
        SDKOperation(
            "grp.info", "Groups", "getGroupInfo", "Group info", listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.getGroupInfo(i.v("groupId"))) },
        SDKOperation(
            "grp.delete", "Groups", "deleteGroup", "Delete a group",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<DeleteGroupRequest>(it) }
            Fmt.json(m.admin.deleteGroup(i.v("groupId"), req))
        },
        SDKOperation(
            "grp.contexts", "Groups", "listGroupContexts", "Contexts in a group", listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.listGroupContexts(i.v("groupId"))) },
        SDKOperation(
            "grp.subgroups", "Groups", "listSubgroups", "Child subgroups", listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.listSubgroups(i.v("groupId"))) },
        SDKOperation(
            "grp.leave", "Groups", "leaveGroup", "Leave a group",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            m.admin.leaveGroup(i.v("groupId"), req)
            "left group"
        },
        SDKOperation(
            "grp.defCaps", "Groups", "getDefaultCapabilities", "Default capabilities bitmask",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> "defaultCapabilities = ${m.admin.getDefaultCapabilities(i.v("groupId"))}" },
        SDKOperation(
            "grp.subVis", "Groups", "getSubgroupVisibility", "Subgroup visibility",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> "subgroupVisibility = ${m.admin.getSubgroupVisibility(i.v("groupId"))}" },
        SDKOperation(
            "grp.detach", "Groups", "detachContextFromGroup", "Detach a context",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("contextId", "Context ID"),
                OpField.json("body", "Body (optional)", ""),
            ),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<DetachContextFromGroupRequest>(it) }
            m.admin.detachContextFromGroup(i.v("groupId"), i.v("contextId"), req)
            "detached"
        },
        SDKOperation(
            "grp.reparent", "Groups", "reparentGroup", "Move under a new parent",
            listOf(OpField.line("childGroupId", "Child group ID"), OpField.json("body", "ReparentGroupRequest")),
        ) { m, i -> Fmt.json(m.admin.reparentGroup(i.v("childGroupId"), Fmt.decode<ReparentGroupRequest>(i.v("body")))) },
        SDKOperation(
            "grp.proof", "Groups", "issueOwnershipProof", "Group ownership proof",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<Map<String, JsonElement>>(it) }
            Fmt.json(m.admin.issueOwnershipProof(i.v("groupId"), req))
        },
        SDKOperation(
            "grp.sync", "Groups", "syncGroup", "Sync a group",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<SyncGroupRequest>(it) }
            Fmt.json(m.admin.syncGroup(i.v("groupId"), req))
        },
        SDKOperation(
            "grp.syncContexts", "Groups", "syncGroupContexts", "Sync + join every context in a group",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.syncGroupContexts(i.v("groupId"))) },
    )

private val memberOps =
    listOf(
        SDKOperation(
            "mem.list", "Group Members", "listGroupMembers", "Members of a group", listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.listGroupMembers(i.v("groupId"))) },
        SDKOperation(
            "mem.add", "Group Members", "addGroupMembers", "Add members",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "AddGroupMembersRequest")),
        ) { m, i ->
            m.admin.addGroupMembers(i.v("groupId"), Fmt.decode<AddGroupMembersRequest>(i.v("body")))
            "members added"
        },
        SDKOperation(
            "mem.remove", "Group Members", "removeGroupMembers", "Remove members",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "RemoveGroupMembersRequest")),
        ) { m, i ->
            m.admin.removeGroupMembers(i.v("groupId"), Fmt.decode<RemoveGroupMembersRequest>(i.v("body")))
            "members removed"
        },
        SDKOperation(
            "mem.role", "Group Members", "updateMemberRole", "Change a member's role",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("identity", "Identity"),
                OpField.json("body", "UpdateMemberRoleRequest"),
            ),
        ) { m, i ->
            m.admin.updateMemberRole(i.v("groupId"), i.v("identity"), Fmt.decode<UpdateMemberRoleRequest>(i.v("body")))
            "role updated"
        },
        SDKOperation(
            "mem.getCaps", "Group Members", "getMemberCapabilities", "Member capabilities",
            listOf(OpField.line("groupId", "Group ID"), OpField.line("identity", "Identity")),
        ) { m, i -> Fmt.json(m.admin.getMemberCapabilities(i.v("groupId"), i.v("identity"))) },
        SDKOperation(
            "mem.setCaps", "Group Members", "setMemberCapabilities", "Set member capabilities",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("identity", "Identity"),
                OpField.json("body", "Request"),
            ),
        ) { m, i ->
            m.admin.setMemberCapabilities(i.v("groupId"), i.v("identity"), Fmt.decode<SetMemberCapabilitiesRequest>(i.v("body")))
            "capabilities set"
        },
        SDKOperation(
            "mem.autoFollow", "Group Members", "setMemberAutoFollow", "Set auto-follow",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("identity", "Identity"),
                OpField.json("body", "Body", "{\"autoFollow\":true}"),
            ),
        ) { m, i ->
            m.admin.setMemberAutoFollow(i.v("groupId"), i.v("identity"), Fmt.decode<Map<String, JsonElement>>(i.v("body")))
            "auto-follow set"
        },
        SDKOperation(
            "mem.getMeta", "Group Members", "getMemberMetadata", "Member metadata",
            listOf(OpField.line("groupId", "Group ID"), OpField.line("identity", "Identity")),
        ) { m, i -> Fmt.json(m.admin.getMemberMetadata(i.v("groupId"), i.v("identity"))) },
        SDKOperation(
            "mem.setMeta", "Group Members", "setMemberMetadata", "Set member metadata",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("identity", "Identity"),
                OpField.json("body", "Request"),
            ),
        ) { m, i ->
            m.admin.setMemberMetadata(i.v("groupId"), i.v("identity"), Fmt.decode<SetMemberMetadataRequest>(i.v("body")))
            "metadata set"
        },
    )

private val settingsOps =
    listOf(
        SDKOperation(
            "set.defCaps", "Group Settings", "setDefaultCapabilities", "Set default capabilities",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Request")),
        ) { m, i ->
            m.admin.setDefaultCapabilities(i.v("groupId"), Fmt.decode<SetDefaultCapabilitiesRequest>(i.v("body")))
            "set"
        },
        SDKOperation(
            "set.subVis", "Group Settings", "setSubgroupVisibility", "Set subgroup visibility",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Request")),
        ) { m, i ->
            m.admin.setSubgroupVisibility(i.v("groupId"), Fmt.decode<SetSubgroupVisibilityRequest>(i.v("body")))
            "set"
        },
        SDKOperation(
            "set.grpMetaSet", "Group Settings", "setGroupMetadata", "Set group metadata",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Request")),
        ) { m, i ->
            m.admin.setGroupMetadata(i.v("groupId"), Fmt.decode<SetGroupMetadataRequest>(i.v("body")))
            "set"
        },
        SDKOperation(
            "set.grpMetaGet", "Group Settings", "getGroupMetadata", "Get group metadata",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.getGroupMetadata(i.v("groupId"))) },
        SDKOperation(
            "set.ctxMetaSet", "Group Settings", "setContextMetadata", "Set context metadata",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("contextId", "Context ID"),
                OpField.json("body", "Request"),
            ),
        ) { m, i ->
            m.admin.setContextMetadata(
                i.v("groupId"),
                i.v("contextId"),
                Fmt.decode<SetContextMetadataRequest>(i.v("body")),
            )
            "set"
        },
        SDKOperation(
            "set.ctxMetaGet", "Group Settings", "getContextMetadata", "Get context metadata",
            listOf(OpField.line("groupId", "Group ID"), OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getContextMetadata(i.v("groupId"), i.v("contextId"))) },
        SDKOperation(
            "set.teeGet", "Group Settings", "getTeeAdmissionPolicy", "Get TEE admission policy",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.getTeeAdmissionPolicy(i.v("groupId"))) },
        SDKOperation(
            "set.teeSet", "Group Settings", "setTeeAdmissionPolicy", "Set TEE admission policy",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Request")),
        ) { m, i ->
            m.admin.setTeeAdmissionPolicy(i.v("groupId"), Fmt.decode<SetTeeAdmissionPolicyRequest>(i.v("body")))
            "set"
        },
    )

private val upgradeOps =
    listOf(
        SDKOperation(
            "up.upgrade", "Upgrade & Invites", "upgradeGroup", "Upgrade a group's app",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "UpgradeGroupRequest")),
        ) { m, i -> Fmt.json(m.admin.upgradeGroup(i.v("groupId"), Fmt.decode<UpgradeGroupRequest>(i.v("body")))) },
        SDKOperation(
            "up.status", "Upgrade & Invites", "getGroupUpgradeStatus", "Upgrade status (nullable)",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.getGroupUpgradeStatus(i.v("groupId"))) },
        SDKOperation(
            "up.retry", "Upgrade & Invites", "retryGroupUpgrade", "Retry an upgrade",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<RetryGroupUpgradeRequest>(it) }
            Fmt.json(m.admin.retryGroupUpgrade(i.v("groupId"), req))
        },
        SDKOperation(
            "up.invite", "Upgrade & Invites", "createGroupInvitation", "Invite to a group",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "Body (optional)", "")),
        ) { m, i ->
            val req = i.opt("body")?.let { Fmt.decode<CreateGroupInvitationRequest>(it) }
            when (val r = m.admin.createGroupInvitation(i.v("groupId"), req)) {
                is CreateGroupInvitationResult.Single -> Fmt.json(r.data)
                is CreateGroupInvitationResult.Recursive -> Fmt.json(r.data)
            }
        },
        SDKOperation(
            "up.join", "Upgrade & Invites", "joinGroup", "Join a group via invitation",
            listOf(OpField.json("body", "JoinGroupRequest")),
        ) { m, i -> Fmt.json(m.admin.joinGroup(Fmt.decode<JoinGroupRequest>(i.v("body")))) },
        SDKOperation(
            "up.joinInherit", "Upgrade & Invites", "joinSubgroupInheritance", "Join a subgroup via inheritance",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.joinSubgroupInheritance(i.v("groupId"))) },
    )

private val teeOps =
    listOf(
        SDKOperation("tee.info", "TEE", "getTeeInfo", "TEE info", emptyList()) { m, _ ->
            Fmt.json(m.admin.getTeeInfo())
        },
        SDKOperation("tee.attest", "TEE", "teeAttest", "Request an attestation quote", listOf(OpField.json())) { m, i ->
            Fmt.json(m.admin.teeAttest(Fmt.decode<TeeAttestRequest>(i.v("body"))))
        },
    )

private val accountOps =
    listOf(
        SDKOperation(
            "acc.devices", "Account & Devices", "listAccountDevices", "Devices on this account", emptyList(),
        ) { m, _ -> Fmt.json(m.admin.listAccountDevices()) },
        SDKOperation(
            "acc.apps", "Account & Devices", "listAccountApplications", "Applications this account speaks in",
            emptyList(),
        ) { m, _ -> Fmt.json(m.admin.listAccountApplications()) },
        SDKOperation(
            "acc.memberDevices", "Account & Devices", "listMemberDevices", "A group's members and their devices",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.listMemberDevices(i.v("groupId"))) },
        SDKOperation(
            "acc.pairInit", "Account & Devices", "pairInit", "Begin pairing a device",
            listOf(OpField.json("body", "PairInitRequest")),
        ) { m, i -> Fmt.json(m.admin.pairInit(Fmt.decode<PairInitRequest>(i.v("body")))) },
        SDKOperation(
            "acc.pairComplete", "Account & Devices", "pairComplete", "Complete a pairing",
            listOf(OpField.json("body", "PairCompleteRequest")),
        ) { m, i -> Fmt.json(m.admin.pairComplete(Fmt.decode<PairCompleteRequest>(i.v("body")))) },
        SDKOperation(
            "acc.relink", "Account & Devices", "relinkDevice", "Re-link a certified device",
            listOf(OpField.line("deviceId", "Device ID"), OpField.json("body", "RelinkDeviceRequest", """{}""")),
        ) { m, i -> Fmt.json(m.admin.relinkDevice(i.v("deviceId"), Fmt.decode<RelinkDeviceRequest>(i.v("body")))) },
        SDKOperation(
            "acc.rescope", "Account & Devices", "rescopeDevice", "Replace a device's scope (rc.41)",
            listOf(
                OpField.line("deviceId", "Device ID"),
                OpField.json("body", "RescopeDeviceRequest", """{"scope":"all"}"""),
            ),
        ) { m, i -> Fmt.json(m.admin.rescopeDevice(i.v("deviceId"), Fmt.decode<RescopeDeviceRequest>(i.v("body")))) },
        SDKOperation(
            "acc.label", "Account & Devices", "labelDevice", "Name a device (rc.41)",
            listOf(
                OpField.line("deviceId", "Device ID"),
                OpField.json("body", "LabelDeviceRequest", """{"label":"My phone"}"""),
            ),
        ) { m, i -> Fmt.json(m.admin.labelDevice(i.v("deviceId"), Fmt.decode<LabelDeviceRequest>(i.v("body")))) },
        SDKOperation(
            "acc.revoke", "Account & Devices", "revokeAccountDevice", "Revoke a device (rotates the group key)",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "RevokeDeviceRequest")),
        ) { m, i -> Fmt.json(m.admin.revokeAccountDevice(i.v("namespaceId"), Fmt.decode<RevokeDeviceRequest>(i.v("body")))) },
        SDKOperation(
            "acc.admit", "Account & Devices", "admitJoin", "Publish a keyholder's join",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "AdmitJoinRequest")),
        ) { m, i -> Fmt.json(m.admin.admitJoin(i.v("namespaceId"), Fmt.decode<AdmitJoinRequest>(i.v("body")))) },
    )

// Core 0.11.0-rc.83: root-guarded owner ops, account-root signing, and the delegated-execution
// surface (intent discovery and raw intent calls — warrants are minted elsewhere).
private val rc83Ops =
    listOf(
        SDKOperation(
            "ctx.query", "Delegated & Owner Ops", "queryContext", "Read a context as the session's account",
            listOf(OpField.line("contextId", "Context ID"), OpField.json("body", "QueryContextRequest", """{"method":"get","argsJson":{}}""")),
        ) { m, i -> Fmt.json(m.admin.queryContext(i.v("contextId"), Fmt.decode<QueryContextRequest>(i.v("body")))) },
        SDKOperation(
            "ctx.intentRelay", "Delegated & Owner Ops", "getIntentRelay", "What this node can relay in a context",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.admin.getIntentRelay(i.v("contextId"))) },
        SDKOperation(
            "ctx.warrantNonce", "Delegated & Owner Ops", "getWarrantNonce", "An author device's next warrant nonce",
            listOf(OpField.line("contextId", "Context ID"), OpField.line("deviceKey", "Author device key")),
        ) { m, i -> m.admin.getWarrantNonce(i.v("contextId"), i.v("deviceKey")).toString() },
        SDKOperation(
            "ctx.warrantNonceAuthor", "Delegated & Owner Ops", "getWarrantNonceAsAuthor", "Warrant nonce, asked with the author's proof",
            listOf(OpField.line("contextId", "Context ID"), OpField.line("authorProof", "Author proof (hex)")),
        ) { m, i -> m.admin.getWarrantNonceAsAuthor(i.v("contextId"), i.v("authorProof")).toString() },
        SDKOperation(
            "ctx.presenceIntent", "Delegated & Owner Ops", "presenceIntent", "Carry a signed presence statement",
            listOf(
                OpField.line("contextId", "Context ID"),
                OpField.line("state", "State (hex, empty to clear)"),
                OpField.line("seq", "Sequence"),
                OpField.line("sentAtMs", "Sent at (ms)"),
                OpField.line("signature", "Signature (hex)"),
                OpField.line("authorProof", "Author proof (hex)"),
            ),
        ) { m, i ->
            m.admin.presenceIntent(
                i.v("contextId"),
                PresenceIntentRequest(
                    state = i.opt("state"),
                    seq = i.v("seq").toLong(),
                    sentAtMs = i.v("sentAtMs").toLong(),
                    signature = i.v("signature"),
                    authorProof = i.v("authorProof"),
                ),
            )
            "presence carried"
        },
        SDKOperation(
            "grp.contextIntent", "Delegated & Owner Ops", "createContextIntent", "Create a context under a creation warrant",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "CreateContextIntentRequest")),
        ) { m, i -> Fmt.json(m.admin.createContextIntent(i.v("groupId"), Fmt.decode<CreateContextIntentRequest>(i.v("body")))) },
        SDKOperation(
            "grp.contextIntentRelay", "Delegated & Owner Ops", "getContextIntentRelay", "Discovery for delegated context creation",
            listOf(OpField.line("groupId", "Group ID"), OpField.line("author", "Author account (optional)")),
        ) { m, i -> Fmt.json(m.admin.getContextIntentRelay(i.v("groupId"), i.opt("author"))) },
        SDKOperation(
            "grp.governanceIntent", "Delegated & Owner Ops", "governanceIntent", "Apply a governance op under a warrant",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "GovernanceIntentRequest")),
        ) { m, i -> Fmt.json(m.admin.governanceIntent(i.v("groupId"), Fmt.decode<GovernanceIntentRequest>(i.v("body")))) },
        SDKOperation(
            "grp.governanceIntentRelay", "Delegated & Owner Ops", "getGovernanceIntentRelay", "Discovery for delegated governance",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.getGovernanceIntentRelay(i.v("groupId"))) },
        SDKOperation(
            "grp.openDelegated", "Delegated & Owner Ops", "openToDelegatedExecution", "Grant CAN_AUTHOR_ON_BEHALF by default",
            listOf(OpField.line("groupId", "Group ID (namespace root)")),
        ) { m, i -> m.admin.openToDelegatedExecution(i.v("groupId")).toString() },
        SDKOperation(
            "grp.grantAuthorship", "Delegated & Owner Ops", "grantAuthorship", "Grant one member CAN_AUTHOR_ON_BEHALF",
            listOf(OpField.line("groupId", "Group ID"), OpField.line("account", "Member account")),
        ) { m, i -> m.admin.grantAuthorship(i.v("groupId"), i.v("account")).toString() },
        SDKOperation(
            "grp.transferOwnership", "Delegated & Owner Ops", "transferOwnership", "Hand a group to another admin",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "TransferOwnershipRequest")),
        ) { m, i ->
            m.admin.transferOwnership(i.v("groupId"), Fmt.decode<TransferOwnershipRequest>(i.v("body")))
            "ownership transferred"
        },
        SDKOperation(
            "ns.changeAdmin", "Delegated & Owner Ops", "changeNamespaceAdmin", "Repoint the namespace admin",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "ChangeNamespaceAdminRequest")),
        ) { m, i ->
            m.admin.changeNamespaceAdmin(i.v("namespaceId"), Fmt.decode<ChangeNamespaceAdminRequest>(i.v("body")))
            "namespace admin changed"
        },
        SDKOperation(
            "grp.ownerDelete", "Delegated & Owner Ops", "ownerDeleteGroup", "Owner-only delete of an empty group",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "RootGuardedOpRequest (optional)", "")),
        ) { m, i ->
            m.admin.ownerDeleteGroup(i.v("groupId"), Fmt.decode<RootGuardedOpRequest>(i.v("body")))
            "group deleted"
        },
        SDKOperation(
            "grp.teeAuthoring", "Delegated & Owner Ops", "setTeeAuthoringPolicy", "Which TEEs may author",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "SetTeeAuthoringPolicyRequest", """{"allowedMrtd":[]}""")),
        ) { m, i ->
            m.admin.setTeeAuthoringPolicy(i.v("groupId"), Fmt.decode<SetTeeAuthoringPolicyRequest>(i.v("body")))
            "authoring policy set"
        },
        SDKOperation(
            "grp.teeAuthoringOff", "Delegated & Owner Ops", "disableTeeAuthoringPolicy", "Turn TEE authorship off",
            listOf(OpField.line("groupId", "Group ID"), OpField.json("body", "RootGuardedOpRequest (optional)", "")),
        ) { m, i ->
            m.admin.disableTeeAuthoringPolicy(i.v("groupId"), Fmt.decode<RootGuardedOpRequest>(i.v("body")))
            "authoring policy disabled"
        },
        SDKOperation(
            "acc.signWithRoot", "Delegated & Owner Ops", "signWithAccountRoot", "Sign a payload with the account root",
            listOf(OpField.json("body", "AccountSignWithRootRequest", """{"domain":"mdma.account-login","payload":""}""")),
        ) { m, i -> Fmt.json(m.admin.signWithAccountRoot(Fmt.decode<AccountSignWithRootRequest>(i.v("body")))) },
        SDKOperation(
            "acc.linkDevice", "Delegated & Owner Ops", "linkAccountDevice", "Bind a certified device into a namespace",
            listOf(OpField.line("namespaceId", "Namespace ID"), OpField.json("body", "LinkAccountDeviceRequest")),
        ) { m, i -> Fmt.json(m.admin.linkAccountDevice(i.v("namespaceId"), Fmt.decode<LinkAccountDeviceRequest>(i.v("body")))) },
        SDKOperation(
            "acc.seal", "Delegated & Owner Ops", "sealToAccount", "Seal bytes to a member's root key",
            listOf(
                OpField.line("groupId", "Group ID"),
                OpField.line("account", "Member account"),
                OpField.json("body", "SealToAccountRequest", """{"plaintext":""}"""),
            ),
        ) { m, i -> Fmt.json(m.admin.sealToAccount(i.v("groupId"), i.v("account"), Fmt.decode<SealToAccountRequest>(i.v("body")))) },
        SDKOperation(
            "acc.groupMemberDevices", "Delegated & Owner Ops", "listGroupMemberDevices", "Member accounts and their devices",
            listOf(OpField.line("groupId", "Group ID")),
        ) { m, i -> Fmt.json(m.admin.listGroupMemberDevices(i.v("groupId"))) },
        SDKOperation(
            "tee.registrationAttest", "TEE", "teeRegistrationAttest", "Attestation quote for fleet registration",
            listOf(OpField.json("body", "TeeRegistrationAttestRequest", """{"nonce":""}""")),
        ) { m, i -> Fmt.json(m.admin.teeRegistrationAttest(Fmt.decode<TeeRegistrationAttestRequest>(i.v("body")))) },
    )

private val rpcOps =
    listOf(
        SDKOperation(
            "rpc.execute", "RPC", "rpc.execute", "Call a contract method",
            listOf(
                OpField.line("contextId", "Context ID"),
                OpField.line("method", "Method"),
                OpField.json("args", "argsJson", "{}"),
            ),
        ) { m, i ->
            val args: JsonObject = i.opt("args")?.let { Fmt.pretty.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
            Fmt.json(m.rpc.executeRaw(contextId = i.v("contextId"), method = i.v("method"), argsJson = args))
        },
        SDKOperation(
            "rpc.migrate", "RPC", "rpc.migrateMyEntries", "Re-sign my entries to current schema",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> Fmt.json(m.rpc.migrateMyEntries(i.v("contextId"))) },
        SDKOperation(
            "rpc.pending", "RPC", "rpc.countMyPending", "Count my pending entries",
            listOf(OpField.line("contextId", "Context ID")),
        ) { m, i -> "pending = ${m.rpc.countMyPending(i.v("contextId"))}" },
    )

/** The full registry, in display order. */
val sdkOperations: List<SDKOperation> =
    healthOps + authOps + keyOps + appOps + pkgOps + ctxOps + ctxIdOps +
        aliasOps + blobOps + nsOps + groupOps + memberOps + settingsOps + upgradeOps +
        accountOps + teeOps + rc83Ops + rpcOps

/** Categories in display order (as first seen in [sdkOperations]). */
val sdkCategories: List<String> = sdkOperations.map { it.category }.distinct()
