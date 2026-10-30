package com.calimero.mero.tee

import com.calimero.mero.AppError
import com.calimero.mero.CalimeroClient
import com.calimero.mero.CalimeroClientOptions
import com.calimero.mero.CalimeroSession
import com.calimero.mero.CloudPolicy
import com.calimero.mero.CloudRetryOptions
import com.calimero.mero.Committed
import com.calimero.mero.ConfirmCancelled
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentScope
import com.calimero.mero.LoginRequired
import com.calimero.mero.MOCK_QUOTE_HEADER
import com.calimero.mero.NotLeader
import com.calimero.mero.PasskeyCancelled
import com.calimero.mero.SoftPasskey
import com.calimero.mero.UnavailableError
import com.calimero.mero.WrongNode
import com.calimero.mero.app.CalimeroAppBackend
import com.calimero.mero.app.OrgInvitation
import com.calimero.mero.consentReturn
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.concat
import com.calimero.mero.errorFrom
import com.calimero.mero.fromB64url
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger

/**
 * [CalimeroClient] against stand-in cloud nodes, through the real sealed channel and a mock
 * attestation (mero-js `client-routes.test.ts`, `session-recovery.test.ts`). Synthetic data only.
 */
class CalimeroClientTest {
    private val old = CalimeroSession("acc-1", "v2.old.sig", 0, "rt_old")
    private val new = CalimeroSession("acc-1", "v2.new.sig", 0, "rt_new")

    private fun json(
        status: Int,
        body: String,
    ) = Answer(status, listOf("content-type" to "application/json"), listOf(body))

    private fun refused(
        status: Int,
        code: String,
        details: String = "{}",
    ) = json(status, """{"error":"$code","message":"${code.lowercase()} (stand-in)","details":$details}""")

    private fun bearer(head: JsonObject): String? =
        (head["headers"] as JsonArray)
            .map { it as JsonArray }
            .firstOrNull { it[0].jsonPrimitive.content == "authorization" }
            ?.get(1)
            ?.jsonPrimitive
            ?.content

    private fun path(head: JsonObject) = head["path"]!!.jsonPrimitive.content

    /** A cluster of stand-in nodes on one fake network; each attests with a mock quote. */
    private class Cluster(
        vararg answers: (JsonObject, String) -> Answer,
    ) {
        val nodes = answers.mapIndexed { i, answer -> StandInNode("https://node$i.example", answer) }
        val net =
            FakeNet { request, body ->
                nodes.firstOrNull { request.url.toString().startsWith(it.base) }?.let { node ->
                    if (request.url.encodedPath == "/attestation") attest(node, request) else node.handle(request, body)
                }
            }

        private fun attest(
            node: StandInNode,
            request: Request,
        ): Response {
            val nonce = Hex.decode(request.url.queryParameter("nonce")!!, "nonce", 32)
            val binding = transportKeyBinding(ByteArray(32), node.transportKey)
            val quote = concat(MOCK_QUOTE_HEADER.encodeToByteArray(), nonce, binding)
            return FakeNet.respond(
                request,
                200,
                """{"quote":"${base64(quote)}","transportKey":"${Hex.encode(node.transportKey)}","mock":true,"release":{"version":"0.0.0","profile":"dev"}}""",
            )
        }

        fun client(
            allowMock: Boolean = true,
            onSession: ((CalimeroSession?) -> Unit)? = null,
        ) = CalimeroClient(
            CalimeroClientOptions(
                baseUrl = nodes[0].base,
                nodes = nodes.drop(1).map { it.base },
                policy = CloudPolicy(allowMock = allowMock),
                httpClient = net.client,
                retry = CloudRetryOptions(baseDelayMs = 1),
                onSession = onSession,
            ),
        )
    }

    @Test
    fun `refuses a mock quote unless the client allowed mock attestation`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> json(200, """{"index":1,"result":"v","events":[]}""") })
            try {
                cluster.client(allowMock = false).view("app-1", "get")
                fail("expected the mock quote to be refused")
            } catch (e: TeeTrustException) {
                assertTrue(e.message, e.message!!.contains("mock quote refused"))
            }
            assertEquals(0, cluster.nodes[0].requests.size)
            assertEquals(
                "v",
                cluster
                    .client()
                    .view("app-1", "get")
                    .result.jsonPrimitive.content,
            )
        }

    @Test
    fun `calls an app's intents with a minted idempotency key, and views with strong consistency`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> json(200, """{"index":7,"result":{"ok":true},"events":[{"kind":"Set","data":1}]}""") })
            val client = cluster.client()
            client.useSession(old)
            val call = client.call("app 1", "set", buildJsonObject { put("key", "a") })
            client.view("app 1", "get", stale = true)
            assertEquals(7, call.index)
            assertEquals("Set", call.events.single().kind)
            val (callHead, callBody) = cluster.nodes[0].requests[0]
            assertEquals("/apps/app%201/intents", path(callHead))
            assertEquals("Bearer v2.old.sig", bearer(callHead))
            val sent = Json.parseToJsonElement(callBody).jsonObject
            assertTrue(sent["idempotencyKey"]!!.jsonPrimitive.content.startsWith("node:c-"))
            val view = Json.parseToJsonElement(cluster.nodes[0].requests[1].second).jsonObject
            assertEquals("stale", view["consistency"]!!.jsonPrimitive.content)
        }

    @Test
    fun `retries a 503 with the same idempotency key, then gives up as UnavailableError`() =
        runBlocking {
            val tries = AtomicInteger()
            val cluster =
                Cluster({ _, _ ->
                    if (tries.incrementAndGet() < 3) refused(503, "TIMEOUT") else json(200, """{"index":1,"result":null}""")
                })
            val client = cluster.client()
            client.call("app-1", "set")
            val keys = cluster.nodes[0].requests.map { Json.parseToJsonElement(it.second).jsonObject["idempotencyKey"] }
            assertEquals(3, keys.size)
            assertEquals(1, keys.toSet().size)

            val down = Cluster({ _, _ -> refused(503, "UNAVAILABLE") })
            try {
                down.client().call("app-1", "set")
                fail("expected UnavailableError")
            } catch (e: UnavailableError) {
                assertEquals("unavailable", e.reason)
                assertEquals(Committed.Unknown, e.committed)
            }
            assertEquals(7, down.nodes[0].requests.size)
        }

    @Test
    fun `refreshes once on a 401, shared by concurrent calls, and repeats each call`() =
        runBlocking {
            val refreshes = AtomicInteger()
            val cluster =
                Cluster({ head, body ->
                    if (path(head) == "/auth/refresh") {
                        refreshes.incrementAndGet()
                        Thread.sleep(50)
                        if (body.contains("rt_old")) {
                            json(200, """{"accountId":"acc-1","sessionToken":"v2.new.sig","expiresAt":0,"refreshToken":"rt_new"}""")
                        } else {
                            refused(401, "UNAUTHENTICATED")
                        }
                    } else if (bearer(head) == "Bearer v2.new.sig") {
                        json(200, """{"index":3,"result":"v1"}""")
                    } else {
                        refused(401, "UNAUTHENTICATED")
                    }
                })
            val seen = mutableListOf<CalimeroSession?>()
            val client = cluster.client(onSession = { synchronized(seen) { seen += it } })
            client.useSession(old)
            val answers =
                List(4) {
                    async {
                        client
                            .call("app-1", "set")
                            .result.jsonPrimitive.content
                    }
                }.awaitAll()
            assertEquals(List(4) { "v1" }, answers)
            assertEquals(1, refreshes.get())
            assertEquals(new, client.session)
            assertEquals(listOf<CalimeroSession?>(new), seen)
        }

    @Test
    fun `surfaces LoginRequired when the refresh is refused`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> refused(401, "UNAUTHENTICATED") })
            val client = cluster.client()
            client.useSession(old)
            try {
                client.view("app-1", "get")
                fail("expected LoginRequired")
            } catch (_: LoginRequired) {
            }
            assertEquals(listOf("/apps/app-1/views", "/auth/refresh"), cluster.nodes[0].requests.map { path(it.first) })
        }

    @Test
    fun `moves to the next node on 421 WRONG_NODE, but not on NOT_LEADER`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> refused(421, "WRONG_NODE") }, { _, _ -> json(200, """{"index":1,"result":"b"}""") })
            val client = cluster.client()
            assertEquals(
                "b",
                client
                    .view("app-1", "get")
                    .result.jsonPrimitive.content,
            )
            assertEquals(cluster.nodes[1].base, client.nodeUrl)

            val lone = Cluster({ _, _ -> refused(421, "WRONG_NODE") })
            try {
                lone.client().view("app-1", "get")
                fail("expected WrongNode")
            } catch (_: WrongNode) {
            }

            val follower = Cluster({ _, _ -> refused(421, "NOT_LEADER", """{"leaderUrl":"https://leader.example"}""") }, { _, _ -> json(200, "{}") })
            try {
                follower.client().get("ops/status")
                fail("expected NotLeader")
            } catch (e: NotLeader) {
                assertEquals("https://leader.example", e.leaderUrl)
            }
            assertEquals(0, follower.nodes[1].requests.size)
        }

    @Test
    fun `answers 412 CONFIRM_REQUIRED with the passkey, and a declined prompt as ConfirmCancelled`() =
        runBlocking {
            val passkey = SoftPasskey("localhost", "http://localhost:3000")
            passkey.create(buildJsonObject { put("challenge", "AAAA") })
            val cluster =
                Cluster({ _, body ->
                    if (body.contains("confirm")) {
                        json(200, "{}")
                    } else {
                        refused(412, "CONFIRM_REQUIRED", """{"challengeId":"c1","challenge":"AQID","rpId":"localhost"}""")
                    }
                })
            val client = cluster.client()
            client.deleteOrg("org-1", passkey)
            val confirm = Json.parseToJsonElement(cluster.nodes[0].requests[1].second).jsonObject["confirm"]!!.jsonObject
            assertEquals("c1", confirm["challengeId"]!!.jsonPrimitive.content)
            val assertion = confirm["assertion"]!!.jsonObject
            assertEquals(passkey.id, assertion["credentialId"]!!.jsonPrimitive.content)

            val declining =
                object : com.calimero.mero.PasskeyProvider by passkey {
                    override suspend fun get(
                        challenge: ByteArray,
                        rpId: String,
                        allowCredentials: List<String>?,
                    ) = throw PasskeyCancelled()
                }
            try {
                client.deleteOrg("org-1", declining)
                fail("expected ConfirmCancelled")
            } catch (_: ConfirmCancelled) {
            }
        }

    @Test
    fun `streams an app's events as sealed server-sent events`() =
        runBlocking {
            val cluster =
                Cluster({ _, _ ->
                    Answer(
                        200,
                        listOf("content-type" to "text/event-stream"),
                        listOf("id: 1\nevent: Posted\ndata: {\"n\":1}\n\n", ": keep-alive\n\nid: 2\nevent: Posted\ndata: plain\n\n"),
                    )
                })
            val events =
                withTimeout(5_000) {
                    cluster
                        .client()
                        .events("app-1", from = 4)
                        .take(2)
                        .toList()
                }
            assertEquals(listOf("1", "2"), events.map { it.id })
            assertEquals(
                1,
                events[0]
                    .data.jsonObject["n"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
            assertEquals(JsonPrimitive("plain"), events[1].data)
            assertEquals("/apps/app-1/events?from=4", path(cluster.nodes[0].requests[0].first))
        }

    @Test
    fun `asks for consent and reads where the page sent the person back`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> json(200, """{"requestId":"r1","url":"https://node0.example/consent/r1","expiresAt":9}""") })
            var opened: String? = null
            val started =
                cluster.client().openConsentPage(ConsentRequest.Release(ConsentScope("org-1"), "2.3.110", "mero-chat://consent")) { opened = it }
            assertEquals("https://node0.example/consent/r1", opened)
            assertEquals("r1", started.requestId)
            val sent = Json.parseToJsonElement(cluster.nodes[0].requests[0].second).jsonObject
            assertEquals("release", sent["kind"]!!.jsonPrimitive.content)
            assertEquals("org-1", sent["scope"]!!.jsonObject["org"]!!.jsonPrimitive.content)
            assertEquals("approved", consentReturn("mero-chat://consent?request_id=r1&result=approved")!!.result)
            assertNull(consentReturn("mero-chat://consent?request_id=r1&result=maybe"))
        }

    @Test
    fun `maps the app backend onto orgs, teams and app instances`() =
        runBlocking {
            val cluster =
                Cluster({ head, body ->
                    when (path(head)) {
                        "/orgs/org-1/invites" -> json(200, """{"token":"inv_abc"}""")
                        "/orgs/org-1" -> json(200, """{"orgId":"org-1","name":"Acme","members":{"acc-1":"owner"},"teams":{"t1":{"teamId":"t1","name":"general","join":"everyone"}}}""")
                        "/orgs/org-1/apps" ->
                            json(200, """{"apps":[{"app":"a1","team":"t1","name":"chat","package":{"package":"com.calimero.chat","version":"1.0.0"}},{"app":"a2","package":{"package":"other","version":"1"}}],"next":null}""")
                        "/apps" -> json(200, """{"appId":"a3"}""").also { assertTrue(body, body.contains(""""access":{"kind":"team","team":"t1"}""")) }
                        "/invites/inv_abc/redeem" -> json(200, """{"org":"org-1"}""")
                        else -> refused(404, "NOT_FOUND")
                    }
                })
            val backend = CalimeroAppBackend(cluster.client(), "com.calimero.chat", "acc-1")
            val subgroups = backend.listSubgroups("org-1")
            assertEquals("org-1/t1", subgroups.single().groupId)
            assertEquals(listOf("a1"), subgroups.single().contextIds)
            assertTrue(subgroups.single().isPublic)
            assertEquals("a3", backend.createContext("org-1/t1", "com.calimero.chat", "random", JsonObject(emptyMap())))
            val invitation = backend.createInvitation("org-1")
            assertEquals(OrgInvitation("org-1", "inv_abc", name = "Acme"), OrgInvitation.decode(invitation.encoded()))
            assertEquals("org-1", backend.joinNamespace("org-1", invitation).namespaceId)
            assertTrue(!backend.isMember("missing"))
        }

    @Test
    fun `reads the app's own error code and named fields`() {
        val error =
            errorFrom(422, Json.parseToJsonElement("""{"error":"APP_ERROR","message":"taken","details":{"code":"Taken","fields":{"name":"ada"}}}"""))
        assertTrue(error is AppError)
        assertEquals("Taken", (error as AppError).appCode)
        assertEquals("ada", error.fields["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a soft passkey signs assertions its registered key verifies`() =
        runBlocking {
            val passkey = SoftPasskey("localhost", "http://localhost:3000")
            passkey.create(buildJsonObject { put("challenge", "AQID") })
            val restored = SoftPasskey.restore(passkey.export())
            val assertion = restored.get(byteArrayOf(9, 9), "localhost")
            val exported = Json.parseToJsonElement(passkey.export()).jsonObject
            val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(fromB64url(exported["publicKey"]!!.jsonPrimitive.content)))
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(public) }
            verifier.update(
                concat(
                    fromB64url(assertion.authenticatorData),
                    com.calimero.mero.crypto
                        .sha256(fromB64url(assertion.clientDataJson)),
                ),
            )
            assertTrue(verifier.verify(fromB64url(assertion.signature)))
            assertEquals(passkey.id, assertion.credentialId)
        }
}
