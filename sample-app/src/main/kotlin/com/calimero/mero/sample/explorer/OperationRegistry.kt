package com.calimero.mero.sample.explorer

import com.calimero.mero.ApiKeyOptions
import com.calimero.mero.AppAccess
import com.calimero.mero.AppSource
import com.calimero.mero.AppUpgradeTarget
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentScope
import com.calimero.mero.OrgRole
import com.calimero.mero.TeamJoin
import com.calimero.mero.TeamRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// The SDK surface, one `SDKOperation` per public `CalimeroClient` method (mero-js 27's
// `CalimeroClient`). Each runs on the signed-in session; methods the node asks the account holder
// to confirm use the device's passkey. Arguments are entered as text (JSON where it is JSON);
// results are pretty-printed. Mirrors the Swift sample's operation list.

private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw.trim().ifEmpty { "{}" })

private fun out(value: Any?): String =
    when (value) {
        null, Unit -> "OK"
        is JsonElement -> Fmt.pretty.encodeToString(JsonElement.serializer(), value)
        is ByteArray -> "${value.size} bytes"
        else -> value.toString()
    }

private fun op(
    id: String,
    category: String,
    name: String,
    summary: String,
    vararg fields: OpField,
    run: suspend (OpEnv, Map<String, String>) -> Any?,
) = SDKOperation(id, category, name, summary, fields.toList()) { env, values -> out(run(env, values)) }

private val app = OpField.line("app", "App id", "the app instance's id")
private val org = OpField.line("org", "Organization id")
private val team = OpField.line("team", "Team id")
private val account = OpField.line("account", "Account id")
private val args = OpField.json("args", "Arguments (JSON)")

private val identityOps =
    listOf(
        op("me", "Account", "me", "GET /auth/me: the account, its passkeys and organizations") { e, _ -> e.client.me().raw },
        op("usage", "Account", "usage", "What the account uses, with the cluster's quotas") { e, _ -> e.client.usage() },
        op("refresh", "Account", "refresh", "Swap the refresh token for a new session (single-flight)") { e, _ -> e.client.refresh().accountId },
        op("addPasskey", "Account", "addPasskey", "Add this device's passkey to the account") { e, _ -> e.client.addPasskey(e.passkey) },
        op("labelPasskey", "Account", "labelPasskey", "Name one of the account's passkeys", OpField.line("id", "Credential id"), OpField.line("label", "Label")) { e, v ->
            e.client.labelPasskey(v.v("id"), v.v("label"))
        },
        op("removePasskey", "Account", "removePasskey", "Remove one of the account's passkeys", OpField.line("id", "Credential id")) { e, v -> e.client.removePasskey(v.v("id")) },
        op("attestation", "Account", "attestation", "The node's attestation document (display only)") { e, _ -> e.client.attestation().toString() },
        op("listApiKeys", "Account", "listApiKeys", "The account's API keys") { e, _ -> e.client.listApiKeys().joinToString("\n") },
        op("createApiKey", "Account", "createApiKey", "Create an API key (shown once)", OpField.line("label", "Label"), OpField.line("ttl", "TTL (seconds)", "3600")) { e, v ->
            e.client.createApiKey(ApiKeyOptions(label = v.opt("label"), ttlSecs = v.opt("ttl")?.toLong())).toString()
        },
        op("revokeApiKey", "Account", "revokeApiKey", "Revoke an API key", OpField.line("key", "Key id")) { e, v -> e.client.revokeApiKey(v.v("key")) },
        op("signOutEverywhere", "Account", "signOutEverywhere", "End every session of the account") { e, _ -> e.client.signOutEverywhere() },
    )

private val appOps =
    listOf(
        op("listApps", "Apps", "listApps", "Apps this account reaches", OpField.line("package", "Package (optional)")) { e, v -> e.client.listApps(pkg = v.opt("package")).joinToString("\n") },
        op("getApp", "Apps", "getApp", "One app instance's record", app) { e, v -> e.client.getApp(v.v("app")) },
        op("createApp", "Apps", "createApp", "Create an app instance from a package release", OpField.line("name", "Name"), OpField.line("package", "Package", "com.calimero.chat"), OpField.line("version", "Version (optional)"), OpField.line("org", "Organization (optional)"), OpField.json("initArgs", "init arguments (JSON)")) { e, v ->
            e.client.createApp(v.v("name"), AppSource.Package(v.v("package"), v.opt("version")), org = v.opt("org"), initArgs = json(v.v("initArgs")))
        },
        op("call", "Apps", "call", "POST /apps/{app}/intents: call a method", app, OpField.line("method", "Method"), args) { e, v ->
            e.client.call(v.v("app"), v.v("method"), json(v.v("args"))).toString()
        },
        op("callConfirmed", "Apps", "callConfirmed", "Call a method that needs the passkey", app, OpField.line("method", "Method"), args) { e, v ->
            e.client.callConfirmed(v.v("app"), v.v("method"), json(v.v("args")), e.passkey).toString()
        },
        op("view", "Apps", "view", "POST /apps/{app}/views: run a read", app, OpField.line("method", "Method"), args) { e, v ->
            e.client.view(v.v("app"), v.v("method"), json(v.v("args"))).toString()
        },
        op("deleteApp", "Apps", "deleteApp", "Delete an app instance", app) { e, v -> e.client.deleteApp(v.v("app")) },
        op("setAccess", "Apps", "setAccess", "Who may reach the app: owner, org, public or a team", app, OpField.line("team", "Team id (blank: org)")) { e, v ->
            e.client.setAccess(v.v("app"), v.opt("team")?.let { AppAccess.Team(it) } ?: AppAccess.Org)
        },
        op("setAppMetadata", "Apps", "setAppMetadata", "Set the app instance's metadata", app, OpField.json("metadata", "Metadata (JSON)")) { e, v -> e.client.setAppMetadata(v.v("app"), json(v.v("metadata"))) },
        op("appStorage", "Apps", "appStorage", "What the app's storage holds", app) { e, v -> e.client.appStorage(v.v("app")) },
        op("appAbi", "Apps", "appAbi", "The ABI the app's bundle embeds", app) { e, v -> e.client.appAbi(v.v("app")) },
        op("upgradeApp", "Apps", "upgradeApp", "Move an app to another release (confirmed with the passkey)", app, OpField.line("version", "Version")) { e, v ->
            e.client.upgradeApp(v.v("app"), AppUpgradeTarget(version = v.v("version")), e.passkey)
        },
        op("transferApp", "Apps", "transferApp", "Hand the app to another account", app, OpField.line("to", "Account id")) { e, v -> e.client.transferApp(v.v("app"), v.v("to"), e.passkey) },
        op("presence", "Apps", "presence", "Who is present in the app now", app) { e, v -> e.client.presence(v.v("app")).joinToString("\n") },
        op("publishPresence", "Apps", "publishPresence", "Publish this device's presence", app, OpField.line("seq", "Sequence", "1"), OpField.line("state", "State (text)")) { e, v ->
            e.client.publishPresence(v.v("app"), v.v("seq").toLongOrNull() ?: 1, v.opt("state")?.encodeToByteArray())
        },
        op("listBlobs", "Apps", "listBlobs", "The app's blobs", app) { e, v -> e.client.listBlobs(v.v("app")).joinToString("\n") },
        op("uploadBlob", "Apps", "uploadBlob", "Upload text as a blob", app, OpField.line("text", "Text")) { e, v -> e.client.uploadBlob(v.v("app"), v.v("text").encodeToByteArray()) },
        op("downloadBlob", "Apps", "downloadBlob", "Download a blob", app, OpField.line("id", "Blob id")) { e, v -> e.client.downloadBlob(v.v("app"), v.v("id")).decodeToString() },
        op("blobInfo", "Apps", "blobInfo", "A blob's id and size", app, OpField.line("id", "Blob id")) { e, v -> e.client.blobInfo(v.v("app"), v.v("id")) },
        op("deleteBlob", "Apps", "deleteBlob", "Delete a blob", app, OpField.line("id", "Blob id")) { e, v -> e.client.deleteBlob(v.v("app"), v.v("id")) },
        op("aliases", "Apps", "aliases", "This account's aliases") { e, _ -> e.client.aliases() },
        op("setAlias", "Apps", "setAlias", "Name an app", OpField.line("alias", "Alias"), app) { e, v -> e.client.setAlias(v.v("alias"), v.v("app")) },
        op("alias", "Apps", "alias", "The app an alias names", OpField.line("alias", "Alias")) { e, v -> e.client.alias(v.v("alias")) },
        op("deleteAlias", "Apps", "deleteAlias", "Forget an alias", OpField.line("alias", "Alias")) { e, v -> e.client.deleteAlias(v.v("alias")) },
    )

private val packageOps =
    listOf(
        op("packages", "Packages", "packages", "Installed packages") { e, _ -> e.client.packages().joinToString("\n") },
        op("package", "Packages", "package", "One installed package", OpField.line("package", "Package")) { e, v -> e.client.`package`(v.v("package")) },
        op("installPackage", "Packages", "installPackage", "Install a release from the registry", OpField.line("package", "Package"), OpField.line("version", "Version")) { e, v ->
            e.client.installPackage(v.v("package"), v.v("version"))
        },
        op("uninstallPackage", "Packages", "uninstallPackage", "Uninstall a version or the package", OpField.line("package", "Package"), OpField.line("version", "Version (optional)")) { e, v ->
            e.client.uninstallPackage(v.v("package"), v.opt("version"))
        },
    )

private val orgOps =
    listOf(
        op("createOrg", "Organizations", "createOrg", "Create an organization", OpField.line("name", "Name")) { e, v -> e.client.createOrg(v.v("name")) },
        op("getOrg", "Organizations", "getOrg", "An organization: members, teams, metadata", org) { e, v -> e.client.getOrg(v.v("org")).raw },
        op("deleteOrg", "Organizations", "deleteOrg", "Delete an organization (passkey)", org) { e, v -> e.client.deleteOrg(v.v("org"), e.passkey) },
        op("setOrgMetadata", "Organizations", "setOrgMetadata", "Set the organization's metadata", org, OpField.json("metadata", "Metadata (JSON)")) { e, v -> e.client.setOrgMetadata(v.v("org"), json(v.v("metadata"))) },
        op("setMember", "Organizations", "setMember", "Add a member or change their role", org, account, OpField.line("role", "Role", "member")) { e, v ->
            e.client.setMember(v.v("org"), v.v("account"), OrgRole.entries.first { it.wire == v.v("role") })
        },
        op("removeMember", "Organizations", "removeMember", "Remove a member", org, account) { e, v -> e.client.removeMember(v.v("org"), v.v("account")) },
        op("setMemberMetadata", "Organizations", "setMemberMetadata", "Set a member's metadata", org, account, OpField.json("metadata", "Metadata (JSON)")) { e, v ->
            e.client.setMemberMetadata(v.v("org"), v.v("account"), json(v.v("metadata")))
        },
        op("setCapabilities", "Organizations", "setCapabilities", "Set capability bits", org, OpField.line("caps", "Bits", "4"), OpField.line("account", "Account (optional)")) { e, v ->
            e.client.setCapabilities(v.v("org"), v.v("caps").toInt(), account = v.opt("account"))
        },
        op("invite", "Organizations", "invite", "Mint an invite token", org, OpField.line("role", "Role", "member")) { e, v ->
            e.client.invite(v.v("org"), OrgRole.entries.first { it.wire == v.v("role") })
        },
        op("redeemInvite", "Organizations", "redeemInvite", "Redeem an invite token", OpField.line("token", "Token", "inv_…")) { e, v -> e.client.redeemInvite(v.v("token")) },
        op("createTeam", "Organizations", "createTeam", "Create a team", org, OpField.line("name", "Name"), OpField.line("join", "Join (everyone|invite)", "everyone")) { e, v ->
            e.client.createTeam(v.v("org"), v.v("name"), if (v.v("join") == "invite") TeamJoin.Invite else TeamJoin.Everyone)
        },
        op("getTeam", "Organizations", "getTeam", "A team", org, team) { e, v -> e.client.getTeam(v.v("org"), v.v("team")) },
        op("updateTeam", "Organizations", "updateTeam", "Rename a team", org, team, OpField.line("name", "Name")) { e, v -> e.client.updateTeam(v.v("org"), v.v("team"), name = v.opt("name")) },
        op("deleteTeam", "Organizations", "deleteTeam", "Delete a team and its apps", org, team) { e, v -> e.client.deleteTeam(v.v("org"), v.v("team")) },
        op("setTeamMember", "Organizations", "setTeamMember", "Add a member to a team", org, team, account, OpField.line("role", "Role (lead|member|readOnly)", "member")) { e, v ->
            e.client.setTeamMember(v.v("org"), v.v("team"), v.v("account"), role = TeamRole.entries.firstOrNull { it.wire == v.v("role") })
        },
        op("removeTeamMember", "Organizations", "removeTeamMember", "Remove a member from a team", org, team, account) { e, v ->
            e.client.removeTeamMember(v.v("org"), v.v("team"), v.v("account"))
        },
        op("upgradeRun", "Organizations", "upgradeRun", "An organization-wide upgrade run", org, OpField.line("run", "Run id")) { e, v -> e.client.upgradeRun(v.v("org"), v.v("run")) },
    )

private val approvalOps =
    listOf(
        op("pendingApprovals", "Approvals", "pendingApprovals", "What waits for a decision (blank: your own apps)", OpField.line("org", "Organization (optional)")) { e, v ->
            e.client.pendingApprovals(v.opt("org"))
        },
        op("appsReport", "Approvals", "appsReport", "One page of a scope's apps report", OpField.line("org", "Organization (optional)")) { e, v -> e.client.appsReport(v.opt("org")) },
        op("releaseRules", "Approvals", "releaseRules", "The cluster's governance and release deadlines") { e, _ -> e.client.releaseRules() },
        op("requestConsent", "Approvals", "requestConsent", "Ask for a release approval on the node's consent page", OpField.line("version", "Release"), OpField.line("org", "Organization (optional)")) { e, v ->
            e.client.requestConsent(ConsentRequest.Release(ConsentScope(v.opt("org")), v.v("version"), "mero-sample://consent"))
        },
        op("consentResult", "Approvals", "consentResult", "The decision on a consent request", OpField.line("id", "Request id")) { e, v -> e.client.consentResult(v.v("id")) },
        op("publisherBinding", "Approvals", "publisherBinding", "What a signing key signs to bind itself", OpField.line("signer", "Signer id")) { e, v -> e.client.publisherBinding(v.v("signer")) },
    )

private val rawOps =
    listOf(
        op("get", "Raw", "get", "GET any route (relative, sealed)", OpField.line("path", "Path", "auth/me")) { e, v -> e.client.get(v.v("path")) },
        op("post", "Raw", "post", "POST any route (relative, sealed)", OpField.line("path", "Path"), OpField.json()) { e, v -> e.client.post(v.v("path"), json(v.v("body"))) },
    )

val sdkOperations: List<SDKOperation> = identityOps + appOps + packageOps + orgOps + approvalOps + rawOps

val sdkCategories: List<String> = sdkOperations.map { it.category }.distinct()
