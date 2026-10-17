package com.calimero.mero.compose.state

import com.calimero.mero.admin.Application
import com.calimero.mero.admin.ApplicationBlob
import com.calimero.mero.admin.Context
import com.calimero.mero.admin.GetApplicationResponseData
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.GroupUpgradeStatus
import com.calimero.mero.admin.ListNamespacesResponseData
import com.calimero.mero.admin.MigrationStatus
import com.calimero.mero.admin.MigrationStatusRollup
import com.calimero.mero.compose.MeroSession
import com.calimero.mero.invite.InviteCodec
import com.calimero.mero.rpc.MigrateMyEntriesSummary
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.transport.ExecuteResult
import com.calimero.mero.transport.ExecuteTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-react `hooks/index.test.tsx`: migration status, app versions, my authored migration. */
@OptIn(ExperimentalCoroutinesApi::class)
class UpgradeStatesTest {
    private fun status(migrated: Int) =
        MigrationStatus(
            targetVersion = 2,
            expectedMembers = 3,
            rollup = MigrationStatusRollup(migrated, 0, 3 - migrated, failed = 1, total = 3, allMigrated = migrated == 3, membersPendingSignature = 2),
            members = emptyList(),
        )

    private class MigrationAdmin : StateFakeAdmin() {
        var reads = 0
        var migrated = 0

        override suspend fun getMigrationStatus(namespaceId: String): MigrationStatus {
            reads++
            return MigrationStatus(
                2,
                3,
                rollup = MigrationStatusRollup(migrated, 0, 3 - migrated, 1, 3, migrated == 3, 2),
                members = emptyList(),
            )
        }
    }

    private fun progress(ns: String) =
        GroupEvent(
            ns,
            "MigrationProgress",
            buildJsonObject {
                put(
                    "data",
                    buildJsonObject {
                        put("migrated", 3)
                        put("inProgress", 0)
                        put("unknown", 0)
                        put("failed", 0)
                        put("total", 3)
                    },
                )
            },
        )

    @Test
    fun `migration status re-reads on a live event rather than folding its counters in`() =
        runTest {
            val admin = MigrationAdmin()
            val events = StateTestEvents()
            val s = MigrationStatusState(MutableStateFlow(MeroSession(admin = admin, events = events)), backgroundScope, "ns")
            runCurrent()
            assertEquals(1, admin.reads)
            assertTrue(s.live.value)
            assertEquals(listOf("ns"), events.subscribedGroups)
            admin.migrated = 3
            events.events.emit(progress("other-ns"))
            runCurrent()
            assertEquals(1, admin.reads)
            events.events.emit(progress("ns"))
            runCurrent()
            assertEquals(2, admin.reads)
            assertTrue(s.allMigrated)
            // Live: no fallback poll.
            advanceTimeBy(MigrationStatusState.DEFAULT_MIGRATION_POLL_INTERVAL_MS * 3)
            runCurrent()
            assertEquals(2, admin.reads)
            s.close()
        }

    @Test
    fun `migration status falls back to the default poll while the stream is not live`() =
        runTest {
            val admin = MigrationAdmin()
            val events = StateTestEvents().apply { subscribeFails = IllegalStateException("no subscription") }
            val s = MigrationStatusState(MutableStateFlow(MeroSession(admin = admin, events = events)), backgroundScope, "ns")
            runCurrent()
            assertFalse(s.live.value)
            advanceTimeBy(MigrationStatusState.DEFAULT_MIGRATION_POLL_INTERVAL_MS + 1)
            runCurrent()
            assertEquals(2, admin.reads)
            s.close()
        }

    @Test
    fun `an explicit poll interval is kept even while the stream is live`() =
        runTest {
            val admin = MigrationAdmin()
            val s = MigrationStatusState(MutableStateFlow(MeroSession(admin = admin, events = StateTestEvents())), backgroundScope, "ns", pollIntervalMs = 1_000)
            runCurrent()
            assertTrue(s.live.value)
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(4, admin.reads)
            s.close()
        }

    @Test
    fun `migration status exposes the rollup and derived counters, null-safe before a load`() =
        runTest {
            val none = MigrationStatusState(MutableStateFlow(MeroSession()), backgroundScope, null)
            runCurrent()
            assertNull(none.status)
            assertEquals(0, none.failed)
            assertFalse(none.allMigrated)
            assertEquals(emptyList<Any>(), none.members)
            none.close()
            val admin = MigrationAdmin()
            val s = MigrationStatusState(MutableStateFlow(MeroSession(admin = admin)), backgroundScope, "ns")
            runCurrent()
            assertEquals(1, s.failed)
            assertEquals(2, s.membersPendingSignature)
            assertEquals(2, s.status!!.targetVersion)
            s.close()
        }

    // ---- App versions -------------------------------------------------------------------

    private fun contextAt(version: String?) = Context("ctx", "app", contextStateHash = "h", dagHeads = emptyList(), applicationVersion = version)

    @Test
    fun `the app version is stale when it differs from the expected build, and follows a live change`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getContext(contextId: String) = contextAt("1.0.0")
                }
            val events = StateTestEvents()
            val sessions = MutableStateFlow(MeroSession(admin = admin, events = events))
            val stale = AppVersionState(sessions, backgroundScope, "ctx", expected = "1.1.0")
            val fresh = AppVersionState(sessions, backgroundScope, "ctx", expected = "1.0.0")
            runCurrent()
            assertEquals("1.0.0", stale.appVersion)
            assertTrue(stale.isStale)
            assertFalse(fresh.isStale)
            events.events.emit(ContextEvent("ctx", "AppVersionChanged", buildJsonObject { put("data", buildJsonObject { put("toVersion", "1.1.0") }) }))
            runCurrent()
            assertEquals("1.1.0", stale.appVersion)
            assertFalse(stale.isStale)
            stale.close()
            fresh.close()
        }

    @Test
    fun `an update is available only when the registry's newest is strictly greater`() =
        runTest {
            val admin =
                object : StateFakeAdmin() {
                    override suspend fun getRegistryVersions(
                        registryUrl: String,
                        packageName: String,
                    ) = listOf("1.2.0", "1.1.0")
                }
            val sessions = MutableStateFlow(MeroSession(admin = admin))
            val behind = LatestVersionState(sessions, backgroundScope, "https://reg", "pkg", "1.1.0")
            val current = LatestVersionState(sessions, backgroundScope, "https://reg", "pkg", "1.2.0")
            val unset = LatestVersionState(sessions, backgroundScope, null, "pkg", "1.0.0")
            runCurrent()
            assertEquals("1.2.0", behind.latestVersion)
            assertTrue(behind.updateAvailable)
            assertFalse(current.updateAvailable)
            assertEquals(emptyList<String>(), unset.versions)
            assertFalse(unset.updateAvailable)
        }

    private fun installed(bytecodeHex: String) =
        GetApplicationResponseData(
            Application(
                "app",
                ApplicationBlob(InviteCodec.base58Encode(hex(bytecodeHex)), "c"),
                size = 1,
                source = "registry",
                metadata = emptyList(),
                signerId = "s",
                packageName = "pkg",
                version = "2.0.0",
            ),
        )

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private class VersionAdmin(
        val namespaces: List<com.calimero.mero.admin.Namespace>,
        val info: GroupInfo? = null,
        val app: GetApplicationResponseData,
    ) : StateFakeAdmin() {
        var reads = 0

        override suspend fun listNamespaces(): ListNamespacesResponseData {
            reads++
            return namespaces
        }

        override suspend fun getGroupInfo(groupId: String) = info!!

        override suspend fun getApplication(appId: String) = app
    }

    @Test
    fun `a group's pending apply is the targeted blob differing from the installed one`() =
        runTest {
            val blob = "ab".repeat(32)
            val same = VersionAdmin(listOf(namespace("ns", appKey = blob)), app = installed(blob))
            val newer = VersionAdmin(listOf(namespace("ns", appKey = "cd".repeat(32))), app = installed(blob))
            val zero = VersionAdmin(listOf(namespace("ns", appKey = "0".repeat(64))), app = installed(blob))
            val a = GroupAppVersionState(MutableStateFlow(MeroSession(admin = same)), backgroundScope, "ns")
            val b = GroupAppVersionState(MutableStateFlow(MeroSession(admin = newer)), backgroundScope, "ns")
            val c = GroupAppVersionState(MutableStateFlow(MeroSession(admin = zero)), backgroundScope, "ns")
            runCurrent()
            assertEquals(GroupAppVersion("2.0.0", "app", false, null), a.value)
            assertTrue(b.value.pendingApply)
            assertFalse(c.value.pendingApply)
        }

    @Test
    fun `a subgroup falls back to group info and surfaces its active upgrade, and re-reads on a version change`() =
        runTest {
            val upgrade = GroupUpgradeStatus("1.0.0", "2.0.0", 0, "me", "in_progress")
            val admin = VersionAdmin(emptyList(), info = groupInfo("sub", appKey = "ab".repeat(32)).copy(activeUpgrade = upgrade), app = installed("ab".repeat(32)))
            val events = StateTestEvents()
            val s = GroupAppVersionState(MutableStateFlow(MeroSession(admin = admin, events = events)), backgroundScope, "sub")
            runCurrent()
            assertEquals(upgrade, s.value.activeUpgrade)
            assertEquals(1, admin.reads)
            events.events.emit(ContextEvent("any", "AppVersionChanged", JsonObject(emptyMap())))
            runCurrent()
            assertEquals(2, admin.reads)
            s.close()
            assertEquals("00ff", base58ToHex(InviteCodec.base58Encode(byteArrayOf(0, -1))))
        }

    // ---- My authored migration -------------------------------------------------------------

    private class CountingRpc : ExecuteTransport {
        var pending = 2
        override val kind = "node"
        override val canSubscribe = true

        override suspend fun execute(
            contextId: String,
            method: String,
            argsJson: JsonObject,
        ): JsonElement = JsonPrimitive(0)

        override suspend fun executeWithMetadata(
            contextId: String,
            method: String,
            argsJson: JsonObject,
        ) = ExecuteResult(JsonPrimitive(0), kind)

        override suspend fun migrateMyEntries(contextId: String): MigrateMyEntriesSummary {
            pending = 0
            return MigrateMyEntriesSummary(converted = 2, remaining = 0)
        }

        override suspend fun countMyPending(contextId: String) = pending
    }

    @Test
    fun `pending comes from count_my_pending and clears after authorize`() =
        runTest {
            val s = MyAuthoredMigrationState(MutableStateFlow(MeroSession(rpc = CountingRpc())), backgroundScope, "ctx")
            runCurrent()
            assertEquals(2, s.pendingCount.value)
            assertTrue(s.pending)
            assertEquals(MigrateMyEntriesSummary(2, 0), s.authorize())
            assertFalse(s.pending)
            assertEquals(2, s.summary.value!!.converted)
            s.close()
            val none = MyAuthoredMigrationState(MutableStateFlow(MeroSession()), backgroundScope, "ctx")
            runCurrent()
            assertNull(none.authorize())
            assertEquals(0, none.pendingCount.value)
        }

    @Test
    fun `a zeroed or missing appKey carries no pending signal`() {
        assertTrue(isZeroAppKey(null))
        assertTrue(isZeroAppKey("0000"))
        assertFalse(isZeroAppKey("00a0"))
        assertEquals(status(3).rollup.allMigrated, true)
    }
}
