package com.calimero.mero

import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Port of the Swift `AdminSyncContextsTests`: `AdminApi.syncGroupContexts` should sync the group,
 * list its contexts, and join + state-pull each one — the anti-"1111…" (uninitialized context)
 * flow the SDK offers so clients joining a namespace/group don't reimplement it and hit the trap.
 */
class AdminSyncContextsTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero
    private val seen: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val method = request.method ?: "GET"
                    val path = request.path ?: ""
                    seen.add("$method $path")
                    val body =
                        if (method == "GET" && path == "/admin-api/groups/g1/contexts") {
                            """{"data":[{"contextId":"c1"},{"contextId":"c2"}]}"""
                        } else {
                            "{}"
                        }
                    return MockResponse().setBody(body).setHeader("Content-Type", "application/json")
                }
            }
        server.start()
        mero =
            Mero(
                MeroConfig(
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    tokenStore = MemoryTokenStore(),
                    timeoutMs = 5_000,
                ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `syncGroupContexts joins and syncs each context`() =
        runBlocking {
            val contexts = mero.admin.syncGroupContexts("g1")

            // It returns the group's contexts…
            assertEquals(listOf("c1", "c2"), contexts.map { it.contextId })
            // …and issued: group sync, list, then join + per-context sync for each.
            assertTrue("seen=$seen", seen.contains("POST /admin-api/groups/g1/sync"))
            assertTrue("seen=$seen", seen.contains("GET /admin-api/groups/g1/contexts"))
            for (ctx in listOf("c1", "c2")) {
                assertTrue("join $ctx; seen=$seen", seen.contains("POST /admin-api/contexts/$ctx/join"))
                assertTrue("sync $ctx; seen=$seen", seen.contains("POST /admin-api/contexts/sync/$ctx"))
            }
        }
}
