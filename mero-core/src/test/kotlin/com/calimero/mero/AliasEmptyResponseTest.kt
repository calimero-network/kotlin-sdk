package com.calimero.mero

import com.calimero.mero.admin.CreateApplicationAliasRequest
import com.calimero.mero.admin.CreateContextAliasRequest
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Alias create/delete answer core's `Empty` as `{"data": null}`: that is success, not an error. */
class AliasEmptyResponseTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        mero = Mero(MeroConfig(baseUrl = server.url("/").toString().trimEnd('/'), tokenStore = MemoryTokenStore()))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `alias create and delete accept a null data envelope`() =
        runBlocking {
            repeat(4) { server.enqueue(MockResponse().setBody("""{"data":null}""")) }
            mero.admin.createContextAlias(CreateContextAliasRequest(alias = "a", contextId = "ctx"))
            mero.admin.createApplicationAlias(CreateApplicationAliasRequest(alias = "a", applicationId = "app"))
            mero.admin.deleteContextAlias("a")
            mero.admin.deleteApplicationAlias("a")
            val paths = List(4) { server.takeRequest(2, TimeUnit.SECONDS)!!.path }
            assertEquals(
                listOf(
                    "/admin-api/alias/create/context",
                    "/admin-api/alias/create/application",
                    "/admin-api/alias/delete/context/a",
                    "/admin-api/alias/delete/application/a",
                ),
                paths,
            )
        }
}
