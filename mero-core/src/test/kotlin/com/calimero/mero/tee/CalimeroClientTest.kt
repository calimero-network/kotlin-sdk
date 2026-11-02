package com.calimero.mero.tee

import com.calimero.mero.ApiKeyOptions
import com.calimero.mero.AppError
import com.calimero.mero.AppSource
import com.calimero.mero.CalimeroClient
import com.calimero.mero.CalimeroClientOptions
import com.calimero.mero.CalimeroConnectOptions
import com.calimero.mero.CalimeroSession
import com.calimero.mero.CloudPolicy
import com.calimero.mero.CloudReleasePolicy
import com.calimero.mero.CloudRetryOptions
import com.calimero.mero.Committed
import com.calimero.mero.ConfirmCancelled
import com.calimero.mero.ConsentRequest
import com.calimero.mero.ConsentScope
import com.calimero.mero.Deployer
import com.calimero.mero.DirectoryError
import com.calimero.mero.DirectoryFailure
import com.calimero.mero.DirectoryNode
import com.calimero.mero.LoginRequired
import com.calimero.mero.MOCK_QUOTE_HEADER
import com.calimero.mero.NotLeader
import com.calimero.mero.OpenConsentOptions
import com.calimero.mero.OrgRole
import com.calimero.mero.PasskeyCancelled
import com.calimero.mero.SoftPasskey
import com.calimero.mero.TeamRole
import com.calimero.mero.UnavailableError
import com.calimero.mero.WrongNode
import com.calimero.mero.app.CalimeroAppBackend
import com.calimero.mero.app.OrgInvitation
import com.calimero.mero.app.PasskeyRequired
import com.calimero.mero.confirmationCode
import com.calimero.mero.consentReturn
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.base64
import com.calimero.mero.crypto.concat
import com.calimero.mero.errorFrom
import com.calimero.mero.freshCollateral
import com.calimero.mero.fromB64url
import com.calimero.mero.orderNodes
import com.calimero.mero.pageDeployment
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
        /** Hosts other than the nodes (a node directory, say). */
        val extra: (Request) -> Response? = { null },
    ) {
        val nodes = answers.mapIndexed { i, answer -> StandInNode("https://node$i.example", answer) }
        val net =
            FakeNet { request, body ->
                nodes.firstOrNull { request.url.toString().startsWith(it.base) }?.let { node ->
                    if (request.url.encodedPath == "/attestation") attest(node, request) else node.handle(request, body)
                } ?: extra(request)
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
            val code = confirmationCode("r1")
            val cluster = Cluster({ _, _ -> json(200, """{"requestId":"r1","url":"https://node0.example/consent/r1","expiresAt":9,"code":"$code"}""") })
            var opened: String? = null
            val started =
                cluster.client().openConsentPage(ConsentRequest.Release(ConsentScope("org-1"), "2.3.110", "mero-chat://consent")) { opened = it }
            assertEquals("https://node0.example/consent/r1", opened)
            assertEquals("r1", started.requestId)
            assertEquals("the confirmation code the page shows", code, started.code)
            assertEquals(9L, started.expiresAt)
            val sent = Json.parseToJsonElement(cluster.nodes[0].requests[0].second).jsonObject
            assertEquals("release", sent["kind"]!!.jsonPrimitive.content)
            assertEquals("org-1", sent["scope"]!!.jsonObject["org"]!!.jsonPrimitive.content)
            assertEquals("approved", consentReturn("mero-chat://consent?request_id=r1&result=approved")!!.result)
            assertNull(consentReturn("mero-chat://consent?request_id=r1&result=maybe"))

            // confirm runs with the answer before the page opens; false keeps it closed.
            var shown: String? = null
            opened = null
            cluster.client().openConsentPage(
                ConsentRequest.Release(ConsentScope("org-1"), "2.3.110", "mero-chat://consent"),
                OpenConsentOptions({ opened = it }) { s ->
                    shown = s.code
                    false
                },
            )
            assertEquals(code, shown)
            assertNull(opened)

            // A code that is not the page's for the request: refused, and the page is not opened.
            val lying = Cluster({ _, _ -> json(200, """{"requestId":"r1","url":"https://node0.example/consent/r1","code":"AAA-AAA"}""") })
            try {
                lying.client().openConsentPage(ConsentRequest.Release(ConsentScope("org-1"), "2.3.110", "mero-chat://consent")) { opened = it }
                fail("expected CONSENT_CODE_MISMATCH")
            } catch (e: com.calimero.mero.CalimeroError) {
                assertEquals("CONSENT_CODE_MISMATCH", e.code)
            }
            assertNull(opened)
        }

    @Test
    fun `computes the consent page's confirmation code as core does`() {
        val code = confirmationCode("r1")
        assertTrue(code, Regex("^[0-9A-HJKMNP-TV-Z]{3}-[0-9A-HJKMNP-TV-Z]{3}$").matches(code))
        // SHA-256("abc") starts ba7816bf: its first 30 bits as six Crockford base32 digits (mero-js agrees).
        assertEquals("Q9W-1DF", confirmationCode("abc"))
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
            val passkey = softPasskey()
            val backend = CalimeroAppBackend(cluster.client(), "com.calimero.chat", "acc-1") { passkey }
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

    // ---------------------------------------------------------------- core rc.7+ (mero-js #318–#323)

    private fun sent(
        cluster: Cluster,
        index: Int,
        node: Int = 0,
    ) = Json.parseToJsonElement(cluster.nodes[node].requests[index].second).jsonObject

    private fun header(
        head: JsonObject,
        name: String,
    ): String? =
        (head["headers"] as JsonArray)
            .map { it as JsonArray }
            .firstOrNull { it[0].jsonPrimitive.content == name }
            ?.get(1)
            ?.jsonPrimitive
            ?.content

    /** Answers 412 with a challenge until the request carries `confirm`. */
    private fun stepUp(answer: String = "{}"): (JsonObject, String) -> Answer =
        { _, body ->
            if (body.contains("\"confirm\"")) {
                json(200, answer)
            } else {
                refused(412, "CONFIRM_REQUIRED", """{"challengeId":"c1","challenge":"AQID","rpId":"localhost"}""")
            }
        }

    private suspend fun softPasskey(): SoftPasskey =
        SoftPasskey("localhost", "http://localhost:3000").also { it.create(buildJsonObject { put("challenge", "AAAA") }) }

    @Test
    fun `fails a call over to the next node when one stays unavailable, with the same idempotency key`() =
        runBlocking {
            val cluster = Cluster({ _, _ -> refused(503, "UNAVAILABLE") }, { _, _ -> json(200, """{"index":2,"result":"b"}""") })
            val client = cluster.client()
            assertEquals(
                "b",
                client
                    .call("app-1", "set")
                    .result.jsonPrimitive.content,
            )
            assertEquals(cluster.nodes[1].base, client.nodeUrl)
            assertEquals(7, cluster.nodes[0].requests.size)
            val keys = (cluster.nodes[0].requests + cluster.nodes[1].requests).map { Json.parseToJsonElement(it.second).jsonObject["idempotencyKey"] }
            assertEquals(1, keys.toSet().size)

            val down = Cluster({ _, _ -> refused(503, "UNAVAILABLE") }, { _, _ -> refused(503, "MIGRATING") })
            try {
                down.client().view("app-1", "get")
                fail("expected UnavailableError")
            } catch (e: UnavailableError) {
                assertEquals("migrating", e.reason)
            }
            assertEquals("each node tried once per request", listOf(7, 7), down.nodes.map { it.requests.size })
        }

    @Test
    fun `connects to a cluster by name through the node directory, its zone first`() =
        runBlocking {
            val listing =
                """{"cluster":"eu-1","refreshAfterSeconds":30,"nodes":[
                {"id":"n0","url":"https://node0.example/","zone":"a","weight":1},
                {"id":"bad","url":"ftp://x.example","zone":"b","weight":1},
                {"id":"zero","url":"https://z.example","zone":"b","weight":0},
                {"id":"n1","url":"https://node1.example","zone":"b","weight":3}]}"""
            val cluster =
                Cluster(
                    { _, _ -> json(200, """{"index":1,"result":"a"}""") },
                    { _, _ -> json(200, """{"index":1,"result":"b"}""") },
                    extra = { request ->
                        when (request.url.toString()) {
                            "https://directory.example/v2/eu-1" -> FakeNet.respond(request, 200, listing)
                            "https://directory.example/v2/nowhere" -> FakeNet.respond(request, 404, "{}")
                            else -> null
                        }
                    },
                )
            val client =
                CalimeroClient.connect(
                    CalimeroConnectOptions("eu-1", CloudPolicy.development, directory = "https://directory.example/v2/", zone = "b", httpClient = cluster.net.client),
                )
            assertEquals(listOf("https://node1.example", "https://node0.example"), client.nodes)
            assertEquals(
                "b",
                client
                    .view("app-1", "get")
                    .result.jsonPrimitive.content,
            )
            try {
                CalimeroClient.connect(CalimeroConnectOptions("nowhere", CloudPolicy.development, directory = "https://directory.example/v2", httpClient = cluster.net.client))
                fail("expected DirectoryError")
            } catch (e: DirectoryError) {
                assertEquals(DirectoryFailure.NotFound, e.reason)
                assertEquals("nowhere", e.cluster)
            }
            // A node's weight is its chance to come first: the roll lands past the first node's share.
            val nodes = listOf(DirectoryNode("https://a.example", weight = 1), DirectoryNode("https://b.example", weight = 3))
            assertEquals(listOf("https://b.example", "https://a.example"), orderNodes(nodes, null) { 0.5 })
            assertEquals(listOf("https://a.example", "https://b.example"), orderNodes(nodes, null) { 0.1 })
        }

    @Test
    fun `answers the passkey step-ups on invites, members and API keys`() =
        runBlocking {
            val passkey = softPasskey()
            val cluster = Cluster(stepUp("""{"token":"inv_1","keyId":"k1","key":"cal_x"}"""))
            val client = cluster.client()
            assertEquals("inv_1", client.invite("org-1", OrgRole.Member, passkey, teams = listOf("t1")))
            assertEquals("/orgs/org-1/invites", path(cluster.nodes[0].requests[1].first))
            assertEquals("c1", sent(cluster, 1)["confirm"]!!.jsonObject["challengeId"]!!.jsonPrimitive.content)
            assertEquals("member", sent(cluster, 1)["role"]!!.jsonPrimitive.content)

            client.removeMember("org-1", "acc-2", passkey)
            assertEquals(
                "DELETE",
                cluster.nodes[0]
                    .requests[3]
                    .first["method"]!!
                    .jsonPrimitive.content,
            )
            assertTrue(sent(cluster, 3).containsKey("confirm"))
            assertEquals("cal_x", client.createApiKey(ApiKeyOptions(label = "ci"), passkey).key)
            client.setTeamMember("org-1", "t1", "acc-2", passkey, role = TeamRole.Lead)
            assertEquals("lead", sent(cluster, 7)["role"]!!.jsonPrimitive.content)

            // The app layer without a passkey: nothing is sent.
            val sentBefore = cluster.nodes[0].requests.size
            try {
                CalimeroAppBackend(client, "com.calimero.chat", "acc-1").leaveNamespace("org-1")
                fail("expected PasskeyRequired")
            } catch (e: PasskeyRequired) {
                assertEquals("leaveNamespace", e.method)
            }
            assertEquals(sentBefore, cluster.nodes[0].requests.size)
            CalimeroAppBackend(client, "com.calimero.chat", "acc-1") { passkey }.leaveNamespace("org-1")
            assertTrue(
                Json
                    .parseToJsonElement(
                        cluster.nodes[0]
                            .requests
                            .last()
                            .second,
                    ).jsonObject
                    .containsKey("confirm"),
            )
        }

    @Test
    fun `sends a blob's content type and reads it back, with a grant when given`() =
        runBlocking {
            val cluster =
                Cluster({ head, _ ->
                    if (head["method"]!!.jsonPrimitive.content == "POST") {
                        json(200, """{"blobId":"B58","id":"ab","size":3}""")
                    } else {
                        Answer(200, listOf("content-type" to "image/png"), listOf("png"))
                    }
                })
            val client = cluster.client()
            client.uploadBlob("app-1", byteArrayOf(1, 2, 3), "image/png")
            client.uploadBlob("app-1", byteArrayOf(1))
            assertEquals("image/png", header(cluster.nodes[0].requests[0].first, "content-type"))
            assertEquals("application/octet-stream", header(cluster.nodes[0].requests[1].first, "content-type"))
            val content = client.blobContent("app-1", "B58", grant = "g+1/2")
            assertEquals("image/png", content.contentType)
            assertEquals("png", content.data.decodeToString())
            assertEquals("/apps/app-1/blobs/B58?grant=g%2B1%2F2", path(cluster.nodes[0].requests[2].first))
            assertEquals("png", client.downloadBlobGranted("app-1", "B58", "g").decodeToString())
        }

    @Test
    fun `speaks the deployments, deployers and xcalls routes`() =
        runBlocking {
            val passkey = softPasskey()
            val cluster =
                Cluster({ head, body ->
                    when (path(head)) {
                        "/apps/app-1/xcalls" -> json(200, """{"pending":2,"deadLetters":[{"seq":4,"app":"app-2","method":"ping","reason":"gone","atMs":9}]}""")
                        "/deployments/d1" ->
                            json(200, """{"id":"d1","name":"Chat","owner":{"kind":"org","name":"Acme"},"apps":[{"app":"chat","package":"com.calimero.chat","version":"1.0.0"}]}""")
                        "/deployers" ->
                            if (head["method"]!!.jsonPrimitive.content == "GET") {
                                json(200, """{"deployments":"granted","deployers":"owner","owner":{"account":"acc-1"},"grants":[{"key":"org:o1","deployer":{"org":"o1"},"until":null,"untilSecs":null,"grantedAt":"t","grantedBy":"acc-1"}]}""")
                            } else {
                                stepUp("""{"key":"org:o1","deployer":{"org":"o1"},"grant":{}}""")(head, body)
                            }
                        "/apps" -> json(200, """{"appId":"a1"}""")
                        else -> stepUp("""{"id":"d2"}""")(head, body)
                    }
                })
            val client = cluster.client()
            val xcalls = client.appXcalls("app-1")
            assertEquals(2L, xcalls.pending)
            assertEquals("ping", xcalls.deadLetters.single().method)
            assertEquals("Acme", client.deployment("d1").owner?.name)
            val info = client.deployers()
            assertEquals(Deployer.Org("o1"), info.grants.single().deployer)
            assertEquals(Deployer.Account("acc-1"), info.owner)
            assertEquals("org:o1", client.grantDeployer(Deployer.Org("o1"), passkey).key)
            assertEquals("o1", sent(cluster, 4)["deployer"]!!.jsonObject["org"]!!.jsonPrimitive.content)
            assertEquals("d2", client.createDeployment("Chat", Deployer.Account("acc-1"), passkey))
            assertEquals("acc-1", sent(cluster, 6)["owner"]!!.jsonObject["account"]!!.jsonPrimitive.content)
            client.revokeDeployer(Deployer.Org("o1"), passkey)
            assertEquals("/deployers/org%3Ao1", path(cluster.nodes[0].requests[8].first))
            assertEquals("a1", client.createApp("chat", AppSource.Package("com.calimero.chat", deployment = "d1")))
            assertEquals("d1", sent(cluster, 9)["deployment"]!!.jsonPrimitive.content)
            assertEquals("d1", pageDeployment("https://chat.example/1.0.0/?deployment=d1"))
            assertNull(pageDeployment("https://chat.example/1.0.0/"))
        }

    @Test
    fun `refuses collateral older than maxCollateralAgeDays, and only then`() =
        runBlocking {
            val now =
                java.time.Instant
                    .parse("2026-10-30T12:00:00Z")
                    .toEpochMilli()
            val issued = { iso: String ->
                val fetch: suspend (ByteArray) -> DcapCollateral = {
                    DcapCollateral("", ByteArray(0), ByteArray(0), "", """{"issueDate":"$iso"}""", ByteArray(0), "", "{}", ByteArray(0))
                }
                fetch
            }
            freshCollateral(issued("2026-10-28T00:00:00Z"), 7.0) { now }(ByteArray(0))
            try {
                freshCollateral(issued("2026-10-01T00:00:00Z"), 7.0) { now }(ByteArray(0))
                fail("expected stale collateral to be refused")
            } catch (e: TeeTrustException) {
                assertTrue(e.message, e.message!!.contains("issued 29 days ago, more than the 7 this client accepts"))
            }
            val any = issued("2020-01-01T00:00:00Z")
            assertTrue(freshCollateral(any, null) === any)
            assertEquals(DEFAULT_COLLATERAL_MIRROR, CloudReleasePolicy("2.3.109").collateralSource)
        }
}
