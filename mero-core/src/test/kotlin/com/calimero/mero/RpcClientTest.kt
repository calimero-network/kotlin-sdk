package com.calimero.mero

import com.calimero.mero.rpc.RpcException
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class RpcClientTest {
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
    fun `execute unwraps result output into a typed value`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"output":{"converted":3,"remaining":5}}}""",
                ),
            )
            val summary = mero.rpc.migrateMyEntries("ctx-1")
            assertEquals(3, summary.converted)
            assertEquals(5, summary.remaining)

            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/jsonrpc", request.path)
            val body = request.body.readUtf8()
            assertEquals(true, body.contains("\"method\":\"execute\""))
            assertEquals(true, body.contains("\"contextId\":\"ctx-1\""))
        }

    @Test
    fun `execute maps a jsonrpc error into RpcException`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32000,"message":"boom","type":"ContractError"}}""",
                ),
            )
            try {
                mero.rpc.executeRaw("ctx", "explode")
                fail("expected RpcException")
            } catch (e: RpcException) {
                assertEquals(-32000, e.code)
                assertEquals("boom", e.message)
                assertEquals("ContractError", e.type)
            }
        }

    @Test
    fun `a method returning nothing decodes to null, with output absent or null`() =
        runBlocking {
            for (result in listOf("""{}""", """{"output":null}""")) {
                server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}"""))
                assertEquals(JsonNull, mero.rpc.executeRaw("ctx", "set"))
                server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}"""))
                assertNull(mero.rpc.execute<String?>("ctx", "set"))
            }
        }

    @Test
    fun `no output still fails for a non-nullable type`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
            try {
                mero.rpc.execute<String>("ctx", "get")
                fail("expected a decode failure")
            } catch (e: SerializationException) {
                assertTrue(e.message.orEmpty().isNotEmpty())
            }
        }
}
