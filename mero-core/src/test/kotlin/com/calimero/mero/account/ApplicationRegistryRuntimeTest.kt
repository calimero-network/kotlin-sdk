package com.calimero.mero.account

import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry publishes bundles ahead of the nodes that run them: on 2026-10-25 every app
 * moved to `minRuntimeVersion: 0.12.0-rc.1` while CI's node was on 0.11.0, and installing
 * "the newest version" answered 500.
 */
class ApplicationRegistryRuntimeTest {
    @Test
    fun compareVersionsOrdersPrereleasesBeforeTheirRelease() {
        val ordered =
            listOf(
                "0.11.0-rc.9", "0.11.0-rc.10", "0.11.0-rc.90", "0.11.0", "0.11.1", "0.12.0-alpha", "0.12.0-rc.1",
                "0.12.0-rc.1.1", "0.12.0-rc.2", "0.12.0", "1.0.0",
            )
        for ((i, a) in ordered.withIndex()) {
            for ((j, b) in ordered.withIndex()) {
                assertEquals("$a vs $b", i.compareTo(j), Integer.signum(ApplicationIds.compareVersions(a, b)))
            }
        }
    }

    @Test
    fun compareVersionsTreatsMissingPartsAsZeroAndIgnoresBuild() {
        assertEquals(0, ApplicationIds.compareVersions("1.2", "1.2.0"))
        assertEquals(0, ApplicationIds.compareVersions("v1.2.0", "1.2.0"))
        assertEquals(0, ApplicationIds.compareVersions("1.2.0+abc", "1.2.0+def"))
        assertTrue(ApplicationIds.compareVersions("0.0.9", "0.0.41") < 0)
        assertTrue(ApplicationIds.compareVersions("3.1.9", "3.1.46") < 0)
    }

    @Test
    fun supportsComparesTheMinimumAgainstTheRuntime() {
        assertFalse(ApplicationIds.supports("0.11.0", "0.12.0-rc.1"))
        assertFalse(ApplicationIds.supports("0.11.0", "0.11.1"))
        assertTrue(ApplicationIds.supports("0.11.0", "0.11.0-rc.90"))
        assertTrue(ApplicationIds.supports("0.11.0", "0.11.0"))
        assertTrue(ApplicationIds.supports("0.12.0", "0.12.0-rc.1"))
        assertTrue(ApplicationIds.supports(null, "9.0.0"))
        assertTrue(ApplicationIds.supports("0.11.0", null))
    }

    @Test
    fun selectLatestHonoursTheRuntimeOnlyWhenGiven() {
        val bundles =
            listOf(
                RegistryBundle(appVersion = "3.1.46", minRuntimeVersion = "0.12.0-rc.1"),
                RegistryBundle(appVersion = "3.1.45", minRuntimeVersion = "0.11.1"),
                RegistryBundle(appVersion = "3.1.44", minRuntimeVersion = "0.11.0-rc.90"),
                RegistryBundle(appVersion = "3.1.50", yanked = true, minRuntimeVersion = "0.11.0"),
            )
        assertEquals("3.1.46", ApplicationIds.selectLatestBundle(bundles)?.appVersion)
        assertEquals("3.1.44", ApplicationIds.selectLatestBundle(bundles, "0.11.0")?.appVersion)
        assertEquals("3.1.45", ApplicationIds.selectLatestBundle(bundles, "0.11.1")?.appVersion)
        assertNull(ApplicationIds.selectLatestBundle(bundles, "0.10.0"))
    }

    @Test
    fun bundleReadsEitherSpellingOfTheMinimum() {
        val camel = ApplicationIds.parseBundle("""{"appVersion":"1.0.0","minRuntimeVersion":"0.12.0-rc.1"}""")
        val snake = ApplicationIds.parseBundle("""{"appVersion":"1.0.0","min_runtime_version":"0.11.1","yanked":true}""")
        assertEquals("0.12.0-rc.1", camel?.minRuntimeVersion)
        assertEquals("0.11.1", snake?.minRuntimeVersion)
        assertEquals(true, snake?.yanked)
        assertEquals(
            listOf("0.11.1"),
            ApplicationIds.parseBundles("""[{"appVersion":"1.0.0","min_runtime_version":"0.11.1"}]""").map { it.minRuntimeVersion },
        )
    }

    @Test
    fun runtimeVersionIsReadFromTheNodesRefusal() {
        val refusal = "bundle requires runtime version 0.12.0-rc.1 but current runtime is 0.11.0"
        assertEquals("0.11.0", ApplicationIds.runtimeVersionFromInstallError(refusal))
        assertEquals(
            "0.11.0-rc.89",
            ApplicationIds.runtimeVersionFromInstallError("""{"error":"… current runtime is 0.11.0-rc.89."}"""),
        )
        assertNull(ApplicationIds.runtimeVersionFromInstallError("HTTP 500 Internal Server Error"))

        val http = HttpException(500, refusal, emptyMap(), "/admin-api/install-application")
        assertEquals("0.11.0", ApplicationIds.runtimeVersionRefusedBy(http))
        assertNull(ApplicationIds.runtimeVersionRefusedBy(MeroStateException("offline")))
    }

    @Test
    fun newestCompatibleWalksNewestFirstSkippingYankedAndChecked() =
        runBlocking {
            val minimums =
                mapOf(
                    "0.0.72" to "0.12.0-rc.1", "0.0.71" to "0.11.1", "0.0.70" to "0.11.0-rc.90", "0.0.69" to "0.11.0-rc.90",
                    "0.0.9" to "0.1.0",
                )
            val read = mutableListOf<String>()
            val picked =
                ApplicationIds.newestCompatible(
                    versions = listOf("0.0.9", "0.0.72", "0.0.71", "0.0.70", "0.0.69"),
                    yanked = listOf("0.0.70"),
                    skipping = listOf("0.0.72"),
                    runtimeVersion = "0.11.0",
                ) { version ->
                    read += version
                    RegistryBundle(appVersion = version, minRuntimeVersion = minimums[version])
                }
            assertEquals("0.0.69", picked)
            assertEquals(listOf("0.0.71", "0.0.69"), read)
        }

    @Test
    fun newestCompatibleStopsAtTheLimit() =
        runBlocking {
            var reads = 0
            val picked =
                ApplicationIds.newestCompatible(
                    versions = (1..10).map { "1.0.$it" },
                    yanked = emptyList(),
                    skipping = emptyList(),
                    runtimeVersion = "0.1.0",
                    limit = 3,
                ) { version ->
                    reads++
                    RegistryBundle(appVersion = version, minRuntimeVersion = "9.0.0")
                }
            assertNull(picked)
            assertEquals(3, reads)
        }
}
