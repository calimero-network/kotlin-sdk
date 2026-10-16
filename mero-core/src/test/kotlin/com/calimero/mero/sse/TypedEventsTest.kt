package com.calimero.mero.sse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Typed group and context events, decoded from core's wire shapes
 * (`crates/primitives/src/events.rs`: `type` beside `data`, camelCase fields).
 */
class TypedEventsTest {
    private fun result(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun group(
        kind: String,
        data: String,
    ) = GroupEvent("g-1", kind, result("""{"groupId":"g-1","type":"$kind","data":$data}"""))

    @Test
    fun `migration events decode each variant`() {
        assertEquals(
            GroupMigrationEvent.MigrationStarted("g-1", "10.1.3", "10.2.0", 2, 3),
            group("MigrationStarted", """{"fromVersion":"10.1.3","toVersion":"10.2.0","toStateVersion":2,"localContextsTotal":3}""").asMigrationEvent(),
        )
        assertEquals(
            GroupMigrationEvent.MigrationProgress("g-1", 4, 1, 0, 2, 7),
            group("MigrationProgress", """{"migrated":4,"inProgress":1,"unknown":0,"failed":2,"total":7}""").asMigrationEvent(),
        )
        assertEquals(
            GroupMigrationEvent.CascadeProgress("g-1", "ab".repeat(32), 1, 5),
            group("CascadeProgress", """{"subgroupId":"${"ab".repeat(32)}","localContextsSwapped":1,"localContextsTotal":5}""").asMigrationEvent(),
        )
        assertEquals(
            GroupMigrationEvent.MigrationCompleted("g-1", "10.2.0", 1_760_000_000),
            group("MigrationCompleted", """{"toVersion":"10.2.0","completedAt":1760000000}""").asMigrationEvent(),
        )
    }

    @Test
    fun `membership events decode memberAccount and an optional role`() {
        val account = "09".repeat(32)
        assertEquals(
            GroupMembershipEvent.MemberAdded("g-1", account, "RelayTee"),
            group("MemberAdded", """{"memberAccount":"$account","role":"RelayTee"}""").asMembershipEvent(),
        )
        assertEquals(
            GroupMembershipEvent.MemberJoined("g-1", account, null),
            group("MemberJoined", """{"memberAccount":"$account"}""").asMembershipEvent(),
        )
        assertEquals(
            GroupMembershipEvent.MemberRemoved("g-1", account),
            group("MemberRemoved", """{"memberAccount":"$account"}""").asMembershipEvent(),
        )
    }

    @Test
    fun `the families do not cross, and a stale member field is not read as an account`() {
        assertNull(group("MemberAdded", """{"memberAccount":"a"}""").asMigrationEvent())
        assertNull(group("MigrationProgress", """{"migrated":1,"inProgress":0,"unknown":0,"failed":0,"total":1}""").asMembershipEvent())
        assertNull(group("MemberAdded", """{"member":"bs58key"}""").asMembershipEvent())
        assertNull("a malformed payload is not an event", group("MigrationProgress", """{"migrated":1}""").asMigrationEvent())
    }

    @Test
    fun `app version changes parse either version, and only that kind`() {
        val flip =
            ContextEvent("ctx-1", "AppVersionChanged", result("""{"contextId":"ctx-1","type":"AppVersionChanged","data":{"fromVersion":"1.0.0","toVersion":"2.0.0"}}"""))
        assertEquals(AppVersionChangedEvent("ctx-1", "1.0.0", "2.0.0"), flip.asAppVersionChanged())
        val bare = ContextEvent("ctx-1", "AppVersionChanged", result("""{"contextId":"ctx-1","type":"AppVersionChanged","data":{}}"""))
        assertEquals(AppVersionChangedEvent("ctx-1"), bare.asAppVersionChanged())
        assertNull(ContextEvent("ctx-1", "StateMutation", result("""{"data":{}}""")).asAppVersionChanged())
    }

    @Test
    fun `a byte-array data is decoded as JSON, as mero-js does`() {
        val bytes = """{"toVersion":"3.0.0"}""".encodeToByteArray().joinToString(",") { (it.toInt() and 0xFF).toString() }
        val event = ContextEvent("ctx-1", "AppVersionChanged", result("""{"type":"AppVersionChanged","data":[$bytes]}"""))
        assertEquals(AppVersionChangedEvent("ctx-1", null, "3.0.0"), event.asAppVersionChanged())
    }
}
