package com.calimero.mero

import com.calimero.mero.sse.GroupEvent
import com.calimero.mero.sse.SseClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Group subscriptions on the SSE stream (core rc.83): `groupIds` beside
 * `contextIds` in the subscribe body, and group-keyed frames — `result.groupId`
 * rather than `result.contextId` — delivered as [GroupEvent].
 */
class SseGroupEventsTest {
    private lateinit var server: MockWebServer
    private val subscribeBodies = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/sse" ->
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(
                                    "data: {\"type\":\"connect\",\"session_id\":\"s-1\"}\n\n" +
                                        "data: {\"result\":{\"groupId\":\"g-1\",\"type\":\"MemberAdded\"," +
                                        "\"data\":{\"memberAccount\":\"a\",\"role\":\"RelayTee\"}}}\n\n",
                                )
                        "/sse/subscription" -> {
                            subscribeBodies += request.body.readUtf8()
                            MockResponse().setBody("{}")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client() =
        SseClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = { "an-access-token" },
        )

    @Test
    fun `group events arrive as GroupEvent and subscribe names groupIds`() {
        val event: GroupEvent = runBlocking { withTimeout(10_000) { client().groupEvents(listOf("g-1")).first() } }
        assertEquals("g-1", event.groupId)
        assertEquals("MemberAdded", event.kind)

        val params =
            Json
                .parseToJsonElement(subscribeBodies.first())
                .jsonObject
                .getValue("params")
                .jsonObject
        assertEquals(listOf("g-1"), params.getValue("groupIds").jsonArray.map { it.jsonPrimitive.content })
    }

    /** A context-only stream leaves `groupIds` off, so a node predating it is not refused. */
    @Test
    fun `a context-only subscribe carries no groupIds`() {
        runCatching { runBlocking { withTimeout(3_000) { client().events(listOf("ctx-1")).first() } } }
        val params =
            Json
                .parseToJsonElement(subscribeBodies.first())
                .jsonObject
                .getValue("params")
                .jsonObject
        assertFalse(params.containsKey("groupIds"))
        assertEquals(listOf("ctx-1"), params.getValue("contextIds").jsonArray.map { it.jsonPrimitive.content })
    }
}
