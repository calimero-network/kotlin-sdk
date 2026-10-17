package com.calimero.mero.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A parsed `AppVersionChanged` context event: the context's bundle version flipped
 * (core `AppVersionChangedPayload`). Either version may be absent on the wire.
 * Port of mero-js `AppVersionChangedEvent`.
 */
data class AppVersionChangedEvent(
    val contextId: String,
    val fromVersion: String? = null,
    val toVersion: String? = null,
) {
    companion object {
        /** `result.type` of a bundle-version flip. */
        const val KIND = "AppVersionChanged"
    }
}

/**
 * This event as an [AppVersionChangedEvent], or `null` for any other kind. Core flattens
 * the tagged payload: `type` is a sibling of `data`, and `data` is
 * `{ fromVersion?, toVersion? }`.
 */
fun ContextEvent.asAppVersionChanged(): AppVersionChangedEvent? {
    if (kind != AppVersionChangedEvent.KIND) return null
    val data = eventData(payload) as? JsonObject
    return AppVersionChangedEvent(contextId, data.string("fromVersion"), data.string("toVersion"))
}

/**
 * A group-membership change (core's `GroupMembershipEvent`, untagged `NodeEvent`
 * variant), keyed on the group a stream subscribed with. `MemberLeft` and an admin
 * removal both arrive as [MemberRemoved]. Port of mero-js `GroupMembershipEventData`.
 */
sealed class GroupMembershipEvent {
    abstract val groupId: String

    /**
     * The member's ACCOUNT, 64 hex — the principal governance rows name. Not a signing
     * key: core renamed the field from `member` precisely so a stale consumer fails.
     */
    abstract val memberAccount: String

    /**
     * Core's `GroupMemberRole` (`Admin`, `Member`, `ReadOnly`, `ReadOnlyTee`,
     * `RelayTee`). Absent on [MemberRemoved] and on a [MemberJoined] inherited from an
     * Open subgroup.
     */
    abstract val role: String?

    /** Joined by a self-service path (open invitation, inherited Open-subgroup join). */
    data class MemberJoined(
        override val groupId: String,
        override val memberAccount: String,
        override val role: String? = null,
    ) : GroupMembershipEvent()

    /** Added by an admin. */
    data class MemberAdded(
        override val groupId: String,
        override val memberAccount: String,
        override val role: String? = null,
    ) : GroupMembershipEvent()

    /** Removed by an admin, or left. */
    data class MemberRemoved(
        override val groupId: String,
        override val memberAccount: String,
        override val role: String? = null,
    ) : GroupMembershipEvent()

    companion object {
        /** The `result.type` tags of this family. */
        val KINDS: Set<String> = setOf("MemberJoined", "MemberAdded", "MemberRemoved")
    }
}

/**
 * A group-migration phase (core's `GroupMigrationEvent`), keyed on the namespace root a
 * client subscribes with. [MigrationStarted], [MigrationProgress] and
 * [MigrationCompleted] reach every subscribed member; [CascadeProgress] reaches
 * namespace admins only — a non-admin never seeing it is correct, not a gap.
 * Port of mero-js `GroupMigrationEventData`.
 */
sealed class GroupMigrationEvent {
    abstract val groupId: String

    /**
     * A migration was accepted and its target ABI state version resolved.
     * [toStateVersion] is the `#[app::state(version = N)]` number, not the bundle
     * semver ([toVersion]).
     */
    data class MigrationStarted(
        override val groupId: String,
        val fromVersion: String,
        val toVersion: String,
        val toStateVersion: Long,
        /** Contexts the EMITTING NODE enumerated: node-local, never the fleet cohort size. */
        val localContextsTotal: Long,
    ) : GroupMigrationEvent()

    /** Fleet rollup counters, recomputed as peers' heartbeat facts change. */
    data class MigrationProgress(
        override val groupId: String,
        val migrated: Long,
        val inProgress: Long,
        val unknown: Long,
        val failed: Long,
        /** The pinned FLEET COHORT size, unlike [MigrationStarted.localContextsTotal]. */
        val total: Long,
    ) : GroupMigrationEvent()

    /** One subgroup's local context swaps advanced on the emitting node. Admin-only. */
    data class CascadeProgress(
        override val groupId: String,
        /** Hex descendant subgroup id, never the namespace root the event is keyed on. */
        val subgroupId: String,
        val localContextsSwapped: Long,
        /** Contexts the emitting node holds for THIS subgroup. */
        val localContextsTotal: Long,
    ) : GroupMigrationEvent()

    /** Every pinned-cohort member reported the target version. */
    data class MigrationCompleted(
        override val groupId: String,
        val toVersion: String,
        /** FLEET convergence, seconds since the epoch (`MigrationStatus.fleetCompletedAt`). */
        val completedAt: Long,
    ) : GroupMigrationEvent()

    companion object {
        /** The `result.type` tags of this family. */
        val KINDS: Set<String> = setOf("MigrationStarted", "MigrationProgress", "CascadeProgress", "MigrationCompleted")
    }
}

/** This event as a [GroupMembershipEvent], or `null` for any other kind (or a malformed payload). */
fun GroupEvent.asMembershipEvent(): GroupMembershipEvent? {
    val data = eventData(payload) as? JsonObject ?: return null
    val account = data.string("memberAccount") ?: return null
    val role = data.string("role")
    return when (kind) {
        "MemberJoined" -> GroupMembershipEvent.MemberJoined(groupId, account, role)
        "MemberAdded" -> GroupMembershipEvent.MemberAdded(groupId, account, role)
        "MemberRemoved" -> GroupMembershipEvent.MemberRemoved(groupId, account, role)
        else -> null
    }
}

/** This event as a [GroupMigrationEvent], or `null` for any other kind (or a malformed payload). */
fun GroupEvent.asMigrationEvent(): GroupMigrationEvent? {
    if (kind !in GroupMigrationEvent.KINDS) return null
    val d = eventData(payload) as? JsonObject ?: return null
    return runCatching {
        when (kind) {
            "MigrationStarted" ->
                GroupMigrationEvent.MigrationStarted(
                    groupId, d.req("fromVersion"), d.req("toVersion"), d.num("toStateVersion"), d.num("localContextsTotal"),
                )
            "MigrationProgress" ->
                GroupMigrationEvent.MigrationProgress(
                    groupId, d.num("migrated"), d.num("inProgress"), d.num("unknown"), d.num("failed"), d.num("total"),
                )
            "CascadeProgress" ->
                GroupMigrationEvent.CascadeProgress(
                    groupId, d.req("subgroupId"), d.num("localContextsSwapped"), d.num("localContextsTotal"),
                )
            else -> GroupMigrationEvent.MigrationCompleted(groupId, d.req("toVersion"), d.num("completedAt"))
        }
    }.getOrNull()
}

/**
 * The `data` of a raw `result` payload. A byte-array `data` is decoded as UTF-8 JSON when
 * it is one (mero-js does the same); otherwise it is returned as it came.
 */
internal fun eventData(payload: JsonElement): JsonElement? {
    val data = (payload as? JsonObject)?.get("data") ?: return null
    if (data !is JsonArray) return data
    val bytes =
        data
            .map { (it as? JsonPrimitive)?.intOrNull?.takeIf { b -> b in 0..BYTE_MAX } ?: return data }
            .map { it.toByte() }
            .toByteArray()
    return runCatching { Json.parseToJsonElement(bytes.decodeToString()) }.getOrDefault(data)
}

private const val BYTE_MAX = 255

private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.req(key: String): String = string(key) ?: error("missing $key")

private fun JsonObject.num(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: error("missing $key")
