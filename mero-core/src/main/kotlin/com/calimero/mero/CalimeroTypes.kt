package com.calimero.mero

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

// What a [CalimeroClient] sends and answers (mero-js `cloud-v2/client.ts` types).

/** A signed-in session: a `v2.…` session token (15 minutes) and its `rt_…` refresh token. */
@Serializable
data class CalimeroSession(
    val accountId: String,
    val sessionToken: String,
    /** When the session token expires, as the node said (Unix seconds). */
    val expiresAt: Long = 0,
    val refreshToken: String = "",
)

/** One event a call emitted. */
@Serializable
data class CallEvent(
    val kind: String,
    val data: JsonElement = JsonNull,
)

/** What a call (`/apps/{app}/intents`) answers: its log [index], the method's [result] and its events. */
@Serializable
data class CallResult(
    val index: Long = 0,
    val result: JsonElement = JsonNull,
    val events: List<CallEvent> = emptyList(),
)

/** What a view (`/apps/{app}/views`) answers. */
@Serializable
data class ViewResult(
    val index: Long = 0,
    val result: JsonElement = JsonNull,
)

/** One server-sent event: an app's committed event, or a presence change. [data] is parsed as JSON when it is. */
data class CloudEvent(
    val id: String,
    val kind: String,
    val data: JsonElement,
)

/** What installing a package release answers. */
@Serializable
data class PackageInstalled(
    val `package`: String,
    val version: String,
    val signerId: String = "",
    /** Service name (`""` for a single-service bundle) -> the bundle hash it runs as. */
    val services: Map<String, String> = emptyMap(),
)

/** One installed release of a package. */
@Serializable
data class PackageVersion(
    val version: String,
    val mpk: String = "",
    val size: Long = 0,
    /** `registry` or `upload`. */
    val source: String = "",
    val services: Map<String, String> = emptyMap(),
    val metadata: JsonElement = JsonNull,
    val outbound: List<String> = emptyList(),
    val installedBy: String = "",
    val installedAt: Long = 0,
)

/** An installed package and its releases. */
@Serializable
data class PackageInfo(
    val `package`: String,
    val signerId: String = "",
    val owner: String = "",
    val latest: String? = null,
    val versions: List<PackageVersion> = emptyList(),
)

/** One app instance as `listApps` answers it. */
@Serializable
data class AppInfo(
    val appId: String,
    val name: String = "",
    val owner: String = "",
    val org: String? = null,
    val bundle: String = "",
    val `package`: String? = null,
    val version: String? = null,
)

/** An organization-wide upgrade run, as `upgradeOrg`, `upgradeRun` and `retryUpgradeRun` answer it. */
@Serializable
data class UpgradeRun(
    val runId: String,
    val `package`: String = "",
    val version: String = "",
    val team: String? = null,
    val cascade: Boolean = false,
    /** The account that started it, or `operator`. */
    val by: String = "",
    val startedAt: Long = 0,
    val apps: List<String> = emptyList(),
    val done: List<String> = emptyList(),
    /** App id -> why its upgrade failed. */
    val failed: Map<String, JsonElement> = emptyMap(),
    val finished: Boolean = false,
)

/** One of an app's blobs: [blobId] base58 (what apps print), [id] hex. */
@Serializable
data class BlobInfo(
    val blobId: String,
    val id: String = "",
    val size: Long = 0,
)

/** One device present in an app (`publishPresence`); [state] hex, null once retracted. */
@Serializable
data class PresenceEntry(
    val account: String,
    val device: String = "",
    val state: String? = null,
)

/** What an organization-wide upgrade targets. */
@Serializable
data class OrgUpgradeTarget(
    val `package`: String,
    val version: String,
    /** Only this team's apps (and, with [cascade], those of the teams below it). */
    val team: String? = null,
    val cascade: Boolean? = null,
    val migrate: String? = null,
)

/** The release-deadline stop on an organization's new work. */
@Serializable
data class NewWorkStopped(
    val release: String,
    val after: String,
)

/**
 * `GET /orgs/{org}`: the organization as the caller sees it. [raw] is the whole answer (members,
 * teams, metadata, capabilities, ...); the typed fields are the ones every caller reads.
 */
data class OrgInfo(
    val raw: JsonObject,
    val orgId: String,
    val name: String,
    /** Account -> role (`guest`, `member`, `admin`, `owner`). */
    val members: Map<String, String>,
    /** The teams this caller sees, by id. */
    val teams: Map<String, TeamInfo>,
    val metadata: JsonElement,
    /** Account -> the member's metadata (a display name). */
    val memberMetadata: Map<String, JsonElement>,
    /** Accounts holding `APPROVE_RELEASES` there (owner and admins included). */
    val releaseApprovers: List<String>,
    /** The signer ids (`did:key`) bound to it as publisher. */
    val publishes: List<String>,
    /** Null unless a deadline stopped new work: approve its `release` (or newer) to lift it. */
    val newWorkStopped: NewWorkStopped?,
)

/** A team of an organization (`GET /orgs/{org}/teams/{team}`, or one of [OrgInfo.teams]). */
@Serializable
data class TeamInfo(
    val teamId: String,
    val name: String = "",
    val parent: String? = null,
    /** `everyone` (open) or `invite`. */
    val join: String = "invite",
    /** Account -> whether they lead the team. */
    val members: Map<String, Boolean> = emptyMap(),
    val readOnly: List<String> = emptyList(),
    val metadata: JsonElement = JsonNull,
)

/** An organization the account is in, as `me()` lists it. */
@Serializable
data class OrgMembership(
    val orgId: String,
    val name: String = "",
    val role: String = "",
)

/** One of the account's passkeys, as `me()` lists it. */
@Serializable
data class PasskeyInfo(
    val id: String,
    val signCount: Long = 0,
    val label: String? = null,
)

/** `GET /auth/me`: the signed-in account. [raw] is the whole answer. */
data class AccountInfo(
    val raw: JsonObject,
    val accountId: String,
    val name: String,
    val passkeys: List<PasskeyInfo>,
    val orgs: List<OrgMembership>,
    /** `session` or `apiKey`. */
    val via: String,
    /** The passkey (device) this session was opened with. */
    val passkeyId: String?,
)

/** An API key the account made (never the key itself). */
@Serializable
data class ApiKeyInfo(
    val keyId: String,
    val label: String = "",
    val apps: List<String>? = null,
    val methods: List<String>? = null,
    val readOnly: Boolean = false,
    val allowConfirm: Boolean = false,
    val expiresAt: Long = 0,
)

/** What `createApiKey` takes. */
@Serializable
data class ApiKeyOptions(
    val label: String? = null,
    val apps: List<String>? = null,
    val methods: List<String>? = null,
    val readOnly: Boolean? = null,
    val allowConfirm: Boolean? = null,
    val ttlSecs: Long? = null,
)

/** A new API key: [key] is shown once. */
@Serializable
data class ApiKeyCreated(
    val keyId: String,
    val key: String,
)

/** What `createApp` creates from: a bundle hash (a raw upload, development only), or a package release. */
sealed interface AppSource {
    data class Bundle(
        val hash: String,
    ) : AppSource

    data class Package(
        val `package`: String,
        val version: String? = null,
        val service: String? = null,
    ) : AppSource
}

/** Where `upgradeApp` moves an app: another release of its package, another package, or a bundle hash. */
@Serializable
data class AppUpgradeTarget(
    val version: String? = null,
    val `package`: String? = null,
    val service: String? = null,
    val bundle: String? = null,
    val migrate: String? = null,
)

/** Who may reach an app (`setAccess`, `createApp`). */
sealed interface AppAccess {
    /** Only the owner. */
    data object Owner : AppAccess

    /** Members of the app's organization. */
    data object Org : AppAccess

    /** Anyone, signed in or not. */
    data object Public : AppAccess

    /** The owner and these accounts. */
    data class Accounts(
        val accounts: List<String>,
    ) : AppAccess

    /** Members of one team of the app's organization. */
    data class Team(
        val team: String,
    ) : AppAccess
}

/** An organization role. */
enum class OrgRole(
    val wire: String,
) {
    Guest("guest"),
    Member("member"),
    Admin("admin"),
    Owner("owner"),
}

/** A member's row in a team. */
enum class TeamRole(
    val wire: String,
) {
    Lead("lead"),
    Member("member"),
    ReadOnly("readOnly"),
}

/** Who may join a team: everyone who reaches its parent (open), or invited members only. */
enum class TeamJoin(
    val wire: String,
) {
    Everyone("everyone"),
    Invite("invite"),
}

/**
 * How a call or a view is sent again while the node is unavailable: with the same key, after a
 * backoff that starts at [baseDelayMs] and doubles up to [maxDelayMs], no jitter, at most
 * [retries] times. The defaults are the Rust client's: 6 retries, 200 ms doubling, at most 2 s.
 */
data class CloudRetryOptions(
    val retries: Int = 6,
    val baseDelayMs: Long = 200,
    val maxDelayMs: Long = 2_000,
)

/** The backoff before retry [retry] (0 for the first): `baseDelayMs` doubling, at most `maxDelayMs`. */
fun retryDelayMs(
    retry: Int,
    options: CloudRetryOptions,
): Long = minOf(options.baseDelayMs * (1L shl minOf(retry, 30)), options.maxDelayMs)
