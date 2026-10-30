package com.calimero.mero

import com.calimero.mero.tee.compareReleaseVersions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

// Approvals: the scope reports, release deadlines, publishers, and the node's consent page
// (mero-js `cloud-v2/approvals.ts`). Approvals happen only on the node's consent page:
// `POST /consent/requests` -> open the returned `url` -> `GET /consent/{id}/result`.

/** Who holds releases, app upgrades and outbound approvals on the cluster: `owner` or `operator`. */
@Serializable
data class Governance(
    val releases: String = "owner",
    val appUpgrades: String = "owner",
    val outbound: String = "owner",
)

/** Whose report: the account's own apps outside any organization (`kind = "account"`), or an organization's. */
@Serializable
data class ReportScope(
    val kind: String,
    val org: String? = null,
) {
    companion object {
        val Account: ReportScope = ReportScope("account")

        fun org(org: String): ReportScope = ReportScope("org", org)
    }
}

@Serializable
data class ReportedRelease(
    val `package`: String,
    val version: String,
    val service: String? = null,
)

@Serializable
data class ReportedBundle(
    val bundle: String,
    val `package`: String? = null,
    val version: String? = null,
)

@Serializable
data class LastUpgrade(
    val from: ReportedBundle,
    val to: ReportedBundle,
    val at: Long = 0,
    val ok: Boolean = false,
    /** `owner`, `member` or `operator`. */
    val actorKind: String = "",
    val actor: String? = null,
    val error: JsonElement = JsonNull,
    val run: String? = null,
)

@Serializable
data class ReportedReplica(
    val node: Long,
    val release: String? = null,
    val approved: Boolean = false,
)

@Serializable
data class Publisher(
    val signer: String,
)

/** One app in a scope's report. */
@Serializable
data class ReportedApp(
    val app: String,
    val group: Long = 0,
    val scope: ReportScope = ReportScope.Account,
    val bundle: String = "",
    val `package`: ReportedRelease? = null,
    val publisher: Publisher? = null,
    val upgradesHeldBy: String = "owner",
    val lastUpgrade: LastUpgrade? = null,
    val approvedReleases: List<String>? = null,
    val replicas: List<ReportedReplica> = emptyList(),
    val name: String? = null,
    val team: String? = null,
    val availableVersions: List<String>? = null,
)

@Serializable
data class ClusterRelease(
    val release: String,
    val nodes: Long = 0,
    val approved: Boolean = false,
)

/** One page of a scope's apps report (`GET /orgs/{org}/apps`, `GET /auth/apps`). */
@Serializable
data class AppsReport(
    val scope: ReportScope = ReportScope.Account,
    val governance: Governance = Governance(),
    val approvedReleases: List<String>? = null,
    val clusterReleases: List<ClusterRelease> = emptyList(),
    val apps: List<ReportedApp> = emptyList(),
    val next: String? = null,
)

@Serializable
data class OpsAppsReport(
    val fresh: Boolean = false,
    val governance: Governance = Governance(),
    val apps: List<ReportedApp> = emptyList(),
    val next: String? = null,
)

@Serializable
data class OpsOrgReport(
    val org: String,
    val approvedReleases: List<String>? = null,
)

@Serializable
data class OpsOrgsReport(
    val fresh: Boolean = false,
    val governance: Governance = Governance(),
    val orgs: List<OpsOrgReport> = emptyList(),
    val next: String? = null,
)

/** A page of a report: after the cursor a previous page gave, at most [limit] (1 to [MAX_REPORT_PAGE]). */
data class PageOptions(
    val after: String? = null,
    val limit: Int? = null,
)

const val MAX_REPORT_PAGE: Int = 100

/** The query string for [page] (`""`, or `?after=…&limit=…`). */
fun pageQuery(page: PageOptions = PageOptions()): String {
    val parts = mutableListOf<String>()
    page.after?.let { parts += "after=${URLEncoder.encode(it, "UTF-8")}" }
    page.limit?.let {
        require(it in 1..MAX_REPORT_PAGE) { "limit is 1 to $MAX_REPORT_PAGE" }
        parts += "limit=$it"
    }
    return if (parts.isEmpty()) "" else parts.joinToString("&", prefix = "?")
}

/** A release's deadline: from [after] (RFC 3339; [afterSecs] Unix seconds) a scope that has not approved it starts no new work. */
@Serializable
data class ReleaseDeadline(
    val version: String,
    val after: String = "",
    val afterSecs: Long = 0,
)

/** `GET /releases`: the cluster's governance and every release deadline. */
@Serializable
data class ReleaseRules(
    val governance: Governance = Governance(),
    val deadlines: List<ReleaseDeadline> = emptyList(),
)

/** Order two release versions (semver); anything that is not one, by its text. */
fun compareVersions(
    a: String,
    b: String,
): Int =
    try {
        compareReleaseVersions(a, b)
    } catch (_: Exception) {
        a.compareTo(b).coerceIn(-1, 1)
    }

/** The passed deadline that stops a scope approving [approved], or null when none does. */
fun stalledBy(
    approved: List<String>?,
    deadlines: List<ReleaseDeadline>,
    now: Long = System.currentTimeMillis() / 1000,
): ReleaseDeadline? {
    if (approved.isNullOrEmpty()) return null
    val newest = approved.reduce { a, b -> if (compareVersions(a, b) >= 0) a else b }
    var stall: ReleaseDeadline? = null
    for (d in deadlines) {
        if (d.afterSecs <= now && compareVersions(newest, d.version) < 0) {
            if (stall == null || compareVersions(d.version, stall.version) > 0) stall = d
        }
    }
    return stall
}

/** `GET /publishers/{signerId}`: what a key signs to bind itself next. */
@Serializable
data class PublisherBindingInfo(
    val signerId: String,
    val cluster: String = "",
    val generation: Long = 0,
    val bound: Boolean = false,
)

/** The message a package signing key signs to bind itself to [org] as its publisher. */
fun publisherBindingMessage(
    cluster: String,
    org: String,
    signerId: String,
    generation: Long,
): String = "calimero-publisher-binding/v1\n$cluster\n$org\n$signerId\n$generation"

fun publisherBindingBytes(
    cluster: String,
    org: String,
    signerId: String,
    generation: Long,
): ByteArray = publisherBindingMessage(cluster, org, signerId, generation).encodeToByteArray()

/** A key's proof for `bindPublisher`. */
@Serializable
data class PublisherProof(
    val signerId: String,
    val publicKey: String,
    val signature: String,
)

// ---------------------------------------------------------------- consent page

/** Whose approval a release or outbound request is for: the account's own (null org) or an organization's. */
data class ConsentScope(
    val org: String? = null,
) {
    internal fun toJson(): JsonElement =
        if (org == null) {
            kotlinx.serialization.json.JsonPrimitive("account")
        } else {
            buildJsonObject { put("org", org) }
        }

    companion object {
        val Account: ConsentScope = ConsentScope()
    }
}

/** What the app asks its user to approve (`POST /consent/requests`). */
sealed interface ConsentRequest {
    /** Where the page sends the person back: the package's frontend origin, an app link, or one its manifest names. */
    val returnTo: String

    /** Approve (or, with [withdraw], withdraw) a mero-tee release for [scope]. */
    data class Release(
        val scope: ConsentScope,
        val version: String,
        override val returnTo: String,
        val withdraw: Boolean = false,
    ) : ConsentRequest

    /** Upgrade [app] to [toVersion] of its package. */
    data class Upgrade(
        val app: String,
        val toVersion: String,
        override val returnTo: String,
    ) : ConsentRequest

    /** Approve outbound URL prefixes for [scope]; [pkg] a package-bound session's own when null, [prefixes] every one the latest release declares when null. */
    data class Outbound(
        val scope: ConsentScope,
        override val returnTo: String,
        val pkg: String? = null,
        val prefixes: List<String>? = null,
    ) : ConsentRequest

    /** The wire body. */
    fun toJson(): JsonObject =
        when (this) {
            is Release ->
                buildJsonObject {
                    put("kind", "release")
                    put("scope", scope.toJson())
                    put(
                        "subject",
                        buildJsonObject {
                            put("version", version)
                            if (withdraw) put("withdraw", true)
                        },
                    )
                    put("returnTo", returnTo)
                }
            is Upgrade ->
                buildJsonObject {
                    put("kind", "upgrade")
                    put(
                        "subject",
                        buildJsonObject {
                            put("app", app)
                            put("toVersion", toVersion)
                        },
                    )
                    put("returnTo", returnTo)
                }
            is Outbound ->
                buildJsonObject {
                    put("kind", "outbound")
                    put("scope", scope.toJson())
                    put(
                        "subject",
                        buildJsonObject {
                            pkg?.let { put("package", it) }
                            prefixes?.let { list -> put("prefixes", kotlinx.serialization.json.JsonArray(list.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
                        },
                    )
                    put("returnTo", returnTo)
                }
        }
}

/** The node's answer to a consent request: send the person to [url]. */
@Serializable
data class ConsentStarted(
    val requestId: String,
    /** The consent page, on the node's own origin. */
    val url: String,
    /** Unix seconds; a request lives 10 minutes. */
    val expiresAt: Long = 0,
    /**
     * The request's confirmation code (`ABC-123`): the consent page shows the same one, so show it
     * to the person before they open the page, and they can check they approve what the app asked.
     * `""` from a node that predates it.
     */
    val code: String = "",
)

/** `GET /consent/{id}/result`. [status] is `pending`, `approved` or `declined`. */
@Serializable
data class ConsentResult(
    val requestId: String,
    val kind: String = "",
    val status: String = "pending",
    /** Unix seconds, once decided. */
    val decidedAt: Long? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isApproved: Boolean get() = status == "approved"
}

/** What the consent page put on `returnTo` when it sent the person back. */
data class ConsentReturn(
    val requestId: String,
    /** `approved` or `declined`. */
    val result: String,
)

/**
 * What the consent page put on `returnTo` when it sent the person back, or null when [href]
 * carries none. A hint only (anyone can type a URL): confirm with
 * [CalimeroClient.consentResult] before acting on it.
 */
fun consentReturn(href: String): ConsentReturn? {
    val url = href.toHttpUrlOrNull() ?: href.replaceBefore("://", "https").toHttpUrlOrNull() ?: return null
    val requestId = url.queryParameter("request_id")?.takeIf { it.isNotEmpty() } ?: return null
    val result = url.queryParameter("result")?.takeIf { it == "approved" || it == "declined" } ?: return null
    return ConsentReturn(requestId, result)
}

/** The public verifier for mero-tee releases (`attestation-verifier`). */
const val RELEASE_VERIFIER: String = "https://mero-tee.vercel.app"

/** A mero-tee release's notes on GitHub. */
fun releaseNotesUrl(version: String): String =
    "https://github.com/calimero-network/mero-tee/releases/tag/mero-tee-v${URLEncoder.encode(version, "UTF-8")}"

/** The public verifier's page for a mero-tee release. */
fun verifyReleaseUrl(version: String): String = "$RELEASE_VERIFIER/mero-tee?release_tag=${URLEncoder.encode("mero-tee-v$version", "UTF-8")}"

/** A mero-tee release the cluster's nodes run that the scope has not approved. */
data class PendingRelease(
    val version: String,
    /** How many of the cluster's nodes run it. */
    val nodes: Long,
    /** Whether the scope's side holds release approvals (else the operator does). */
    val actionable: Boolean,
    val releaseNotesUrl: String,
    val verifyUrl: String,
    val deadline: ReleaseDeadline?,
)

/** An app with newer releases of its package. */
data class PendingUpgrade(
    val app: String,
    val name: String?,
    val `package`: String,
    val from: String,
    /** The newest it may move to. */
    val to: String,
    /** Every newer release it may move to, oldest first. */
    val available: List<String>,
    val actionable: Boolean,
)

/** What [CalimeroClient.pendingApprovals] answers. */
data class PendingApprovals(
    val scope: ReportScope,
    val governance: Governance,
    val approvedReleases: List<String>?,
    val releases: List<PendingRelease>,
    val upgrades: List<PendingUpgrade>,
    /** A passed deadline has stopped new work for the scope: approve its version (or newer) to lift it. */
    val stalledBy: ReleaseDeadline?,
    /** Whether anything here is the user's to approve. */
    val actionable: Boolean,
)

/** The pending list from a scope's report pages (every page's apps in `report.apps`) and the cluster's release rules. */
fun pendingApprovalsFrom(
    report: AppsReport,
    rules: ReleaseRules,
    now: Long = System.currentTimeMillis() / 1000,
): PendingApprovals {
    val releasesActionable = report.governance.releases == "owner"
    val upgradesActionable = report.governance.appUpgrades == "owner"
    val releases =
        report.clusterReleases
            .filter { !it.approved }
            .sortedWith { a, b -> compareVersions(b.release, a.release) }
            .map { r ->
                PendingRelease(
                    r.release,
                    r.nodes,
                    releasesActionable,
                    releaseNotesUrl(r.release),
                    verifyReleaseUrl(r.release),
                    rules.deadlines.firstOrNull { it.version == r.release },
                )
            }
    val upgrades =
        report.apps.mapNotNull { a ->
            val available = a.availableVersions.orEmpty()
            val pkg = a.`package` ?: return@mapNotNull null
            if (available.isEmpty()) return@mapNotNull null
            PendingUpgrade(a.app, a.name, pkg.`package`, pkg.version, available.last(), available, upgradesActionable)
        }
    return PendingApprovals(
        report.scope,
        report.governance,
        report.approvedReleases,
        releases,
        upgrades,
        stalledBy(report.approvedReleases, rules.deadlines, now),
        releases.any { it.actionable } || upgrades.any { it.actionable },
    )
}

/** The consent request that approves [version] for [scope], or with [withdraw] withdraws its approval. */
fun releaseConsent(
    scope: ReportScope,
    version: String,
    returnTo: String,
    withdraw: Boolean = false,
): ConsentRequest = ConsentRequest.Release(if (scope.kind == "org") ConsentScope(scope.org) else ConsentScope.Account, version, returnTo, withdraw)

/** The consent request that withdraws [version]'s approval for [scope]. */
fun withdrawConsent(
    scope: ReportScope,
    version: String,
    returnTo: String,
): ConsentRequest = releaseConsent(scope, version, returnTo, withdraw = true)

/** The consent request that upgrades [app] to [toVersion]. */
fun upgradeConsent(
    app: String,
    toVersion: String,
    returnTo: String,
): ConsentRequest = ConsentRequest.Upgrade(app, toVersion, returnTo)

/**
 * What a member may do in an organization beyond their role, as core's `caps` bits
 * (`setCapabilities`, `setTeamMember`'s capabilities). A team's lead and an organization's admin
 * hold them all. (mero-js `ORG_CAPABILITIES`.)
 */
object OrgCapabilities {
    /** Create an app in the organization (on its team, or org-wide). */
    const val CREATE_APP: Int = 1

    /** Mint invites (org-wide, or on each team the invite names). */
    const val INVITE_MEMBERS: Int = 1 shl 1

    /** Inherited into open teams; denying it keeps a member out of them. */
    const val JOIN_OPEN_TEAMS: Int = 1 shl 2

    /** Add and remove a team's members. */
    const val MANAGE_MEMBERS: Int = 1 shl 3

    /** Upgrade the organization's apps, start org upgrade runs, approve outbound hosts. */
    const val MANAGE_APPLICATION: Int = 1 shl 4

    /** Create a top-level team without being an admin. */
    const val CREATE_TEAM: Int = 1 shl 5

    /** Delete a team, and what it holds, without being an admin. */
    const val DELETE_TEAM: Int = 1 shl 6

    /** Change who may join a team. */
    const val MANAGE_VISIBILITY: Int = 1 shl 7

    /** Set the organization's and its teams' metadata. */
    const val MANAGE_METADATA: Int = 1 shl 8

    /** Approve (or withdraw) the mero-tee releases that hold the organization's apps; bind a publisher key. Bit 9 is unused. */
    const val APPROVE_RELEASES: Int = 1 shl 10

    /** What a member holds unless an admin says otherwise (mero-js `DEFAULT_ORG_CAPABILITIES`). */
    const val DEFAULT: Int = JOIN_OPEN_TEAMS

    /** Every capability by its mero-js name. */
    val ALL: Map<String, Int> =
        mapOf(
            "CREATE_APP" to CREATE_APP,
            "INVITE_MEMBERS" to INVITE_MEMBERS,
            "JOIN_OPEN_TEAMS" to JOIN_OPEN_TEAMS,
            "MANAGE_MEMBERS" to MANAGE_MEMBERS,
            "MANAGE_APPLICATION" to MANAGE_APPLICATION,
            "CREATE_TEAM" to CREATE_TEAM,
            "DELETE_TEAM" to DELETE_TEAM,
            "MANAGE_VISIBILITY" to MANAGE_VISIBILITY,
            "MANAGE_METADATA" to MANAGE_METADATA,
            "APPROVE_RELEASES" to APPROVE_RELEASES,
        )

    /** The bitmask of [names] (mero-js `orgCapabilities`). */
    fun of(vararg names: String): Int = names.fold(0) { mask, name -> mask or (ALL[name] ?: throw IllegalArgumentException("unknown capability $name")) }
}
