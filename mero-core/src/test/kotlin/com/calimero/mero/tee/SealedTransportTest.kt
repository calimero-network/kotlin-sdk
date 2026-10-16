package com.calimero.mero.tee

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.concat
import com.calimero.mero.crypto.randomBytes
import com.calimero.mero.relay.MemoryNonceSource
import com.calimero.mero.relay.RelayClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Pipe
import okio.Source
import okio.Timeout
import okio.buffer
import org.bouncycastle.util.encoders.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Port of mero-js `src/sealed/sealed.test.ts`, pinned to core's published vectors. */
class SealedTransportTest {
    // Core's published vectors (`crates/server/src/sealed/tests.rs` and
    // `crates/tee-attestation/src/generate.rs`), repeated verbatim.
    private val transportPublicVector = "0faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f20"
    private val handshakeRequestVector =
        "020faa684ed28867b97f4a6a2dee5df8ce974e76b7018e3f22a1c4cf2678570f207b0d47d93427f831116078" +
            "1c7c733fd89f88970aef490d8aa0ee19a4cb8a1b1461fba335b4f3e9be8a34a61900e5b4be"
    private val handshakeResponseVector =
        "02ff2ee45601ec1b67310c7790404585ae697331eee1c1f8cf2419731c1fff3e6b7d6339b7296bdc4778248f" +
            "57126a10ee5b7fb39b0cb104dcbfcaaaf44d0e6017379638a4"
    private val sealedRequestVector =
        "025555555555555555555555555555555500000000000000019fa4301264a48bdf6811cfb1d7c3dad713ede5" +
            "ea1820608358ff2779cee1605efc3d97879f24d6cf302a7877beda552ca6714ef9d5496f978fdcdde522435c" +
            "f663e4be5f60170e8eab74161a913608fe268e5352fcd3090d62e2a12c92b06b51885d43c4d83dcfe544"
    private val sealedResponseVector =
        "0000004f4873ec5a589a1e3585360440100bc00d20522fe35bac2221dcfe80341ce3069481d4794b801776f4" +
            "a76188b81887eb83cfccd2981229af48a3820df924261b3ff12d4b29340ca07c392ed4e0ee4bed0000001cfa" +
            "20a7633655179808da19e2fcbc81bc219262fc4f5406582834f549000000111426897864d7a92eec9261fe29" +
            "93415592"
    private val transportBindingVector = "30274595433e8afc5d4035e30a2b599d93caf0d470867527f13dc6af92fa12a8"

    private val prologue = "calimero/sealed-http/v2".encodeToByteArray()
    private val transportKey = Hex.decode(transportPublicVector, "transport", 32)
    private val vectorHead = SealedTransport.head("POST", "/jsonrpc", listOf("content-type" to "application/json"))
    private val anyRequest = Request.Builder().url("https://tee-node.example/node/jsonrpc").build()

    /** The vector session: the handshake with core's fixed client ephemeral key. */
    private fun vectorSession(): Pair<NoiseInitiator, Session> {
        val noise = NoiseInitiator.initiate(transportKey, prologue, ByteArray(32) { 0x33 })
        return noise to sessionFrom(noise, Hex.decodeUnsized(handshakeResponseVector, "response"))
    }

    private fun streamOf(bytes: ByteArray): Response = FakeNet.respondBytes(anyRequest, 200, bytes, SEALED_CONTENT_TYPE)

    // ---- wire format -------------------------------------------------------------------------

    @Test
    fun `opens the handshake exactly as the node expects`() {
        val (noise, _) = vectorSession()
        assertEquals(handshakeRequestVector, Hex.encode(concat(byteArrayOf(2), transportKey, noise.message1)))
    }

    @Test
    fun `seals a request under the session exactly as the node expects`() {
        val (_, session) = vectorSession()
        assertEquals(sealedRequestVector, Hex.encode(sealRequest(session, vectorHead, "{}".encodeToByteArray()).envelope))
    }

    @Test
    fun `opens the frames of a response the node sealed`() {
        val (_, session) = vectorSession()
        val sealed = sealRequest(session, vectorHead, ByteArray(0))
        val response =
            openResponse(streamOf(Hex.decodeUnsized(sealedResponseVector, "r")), session.responseKey, sealed.header, sealed.requestId, anyRequest)
        assertEquals(200, response.code)
        assertEquals("application/json", response.header("content-type"))
        assertEquals("""{"ok":true}""", response.body!!.string())
    }

    @Test
    fun `refuses a handshake reply from anyone but the attested node`() {
        val noise = NoiseInitiator.initiate(transportKey, prologue, ByteArray(32) { 0x33 })
        val forged = Hex.decodeUnsized(handshakeResponseVector, "r")
        forged[forged.size - 1] = (forged.last().toInt() xor 1).toByte()
        assertRefuses("did not come from the attested node") { sessionFrom(noise, forged) }
    }

    @Test
    fun `refuses a response frame that anyone but the node sealed`() {
        val (_, session) = vectorSession()
        val sealed = sealRequest(session, vectorHead, ByteArray(0))
        val forged = Hex.decodeUnsized(sealedResponseVector, "r")
        forged[forged.size - 1] = (forged.last().toInt() xor 1).toByte()
        val response = openResponse(streamOf(forged), session.responseKey, sealed.header, sealed.requestId, anyRequest)
        assertRefuses("did not open") { response.body!!.string() }
    }

    @Test
    fun `notices a response cut short before its end frame`() {
        val (_, session) = vectorSession()
        val sealed = sealRequest(session, vectorHead, ByteArray(0))
        val whole = Hex.decodeUnsized(sealedResponseVector, "r")
        val endFrame = 4 + 1 + 16
        val response = openResponse(streamOf(whole.copyOfRange(0, whole.size - endFrame)), session.responseKey, sealed.header, sealed.requestId, anyRequest)
        assertRefuses("cut short") { response.body!!.string() }
    }

    @Test
    fun `refuses a frame larger than the node ever sends, before buffering it`() {
        val (_, session) = vectorSession()
        val sealed = sealRequest(session, vectorHead, ByteArray(0))
        var pulled = 0
        val endless =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    pulled += 1
                    val chunk = if (pulled == 1) byteArrayOf(-1, -1, -1, -1) else ByteArray(1024)
                    sink.write(chunk)
                    return chunk.size.toLong()
                }

                override fun timeout() = Timeout.NONE

                override fun close() = Unit
            }
        val response =
            Response
                .Builder()
                .request(anyRequest)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .header("content-type", SEALED_CONTENT_TYPE)
                .body(endless.buffer().asResponseBody(SEALED_CONTENT_TYPE.toMediaType()))
                .build()
        assertRefuses("larger than the node sends") { openResponse(response, session.responseKey, sealed.header, sealed.requestId, anyRequest) }
        assertTrue(pulled < 3)
    }

    @Test
    fun `computes the transport binding the node puts in its quote`() {
        assertEquals(transportBindingVector, Hex.encode(transportKeyBinding(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })))
    }

    // ---- a stand-in node ---------------------------------------------------------------------

    private class Answer(
        val status: Int,
        val headers: List<Pair<String, String>>,
        /** Body pieces, each sent as its own frame, [delayMs] apart. */
        val chunks: List<String>,
        val delayMs: Long = 0,
    )

    private fun ok(body: String = "{}") = Answer(200, listOf("content-type" to "application/json"), listOf(body))

    /** Answers the Noise handshake with its transport secret and seals its answers frame by frame, as core does. */
    private class StandInNode(
        val answer: (JsonObject, String) -> Answer,
    ) {
        @Volatile var transport = X25519KeyPair.of()
        var handshakes = 0
        val requests: MutableList<Pair<JsonObject, String>> = java.util.Collections.synchronizedList(mutableListOf())
        private val sessions = ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()

        val transportKey: ByteArray get() = transport.publicKey

        fun restart() {
            transport = X25519KeyPair.of()
            sessions.clear()
        }

        fun forgetSessions() = sessions.clear()

        fun handle(
            request: Request,
            body: ByteArray,
        ): Response? =
            when (request.url.toString()) {
                "$BASE$HANDSHAKE_PATH" -> handshake(request, body)
                "$BASE$SEALED_PATH" -> exchange(request, body)
                else -> null
            }

        @Synchronized
        private fun handshake(
            request: Request,
            body: ByteArray,
        ): Response {
            if (!body.copyOfRange(1, 33).contentEquals(transport.publicKey)) return refusal(request, 409, "stale_transport_key")
            handshakes += 1
            val message1 = body.copyOfRange(33, body.size)
            val state = SymmetricState()
            state.mixHash("calimero/sealed-http/v2".encodeToByteArray())
            state.mixHash(transport.publicKey)
            val remote = message1.copyOfRange(0, 32)
            state.mixHash(remote)
            state.mixKey(transport.dh(remote))
            state.decryptAndHash(message1.copyOfRange(32, message1.size))
            val ephemeral = X25519KeyPair.of()
            state.mixHash(ephemeral.publicKey)
            state.mixKey(ephemeral.dh(remote))
            val sessionId = randomBytes(16)
            val message2 = concat(ephemeral.publicKey, state.encryptAndHash(concat(sessionId, byteArrayOf(0, 0, 0x0e, 0x10))))
            sessions[Hex.encode(sessionId)] = state.split()
            return FakeNet.respondBytes(request, 200, concat(byteArrayOf(2), message2), SEALED_CONTENT_TYPE)
        }

        private fun exchange(
            request: Request,
            envelope: ByteArray,
        ): Response {
            val header = envelope.copyOfRange(0, 25)
            val keys = sessions[Hex.encode(header.copyOfRange(1, 17))] ?: return refusal(request, 409, "unknown_session")
            val requestId = header.copyOfRange(17, 25)
            val iv = { index: Int -> concat(requestId, byteArrayOf(0, 0, 0, index.toByte())) }
            val inner = AesGcm.open(keys.first, iv(0), header, envelope.copyOfRange(25, envelope.size))
            val len = ((inner[0].toInt() and 0xff) shl 24) or ((inner[1].toInt() and 0xff) shl 16) or ((inner[2].toInt() and 0xff) shl 8) or (inner[3].toInt() and 0xff)
            val head = Json.parseToJsonElement(String(inner, 4, len)).jsonObject
            val text = String(inner, 4 + len, inner.size - 4 - len)
            requests += head to text
            val reply = answer(head, text)
            var index = 0
            val frame = { kind: Int, data: ByteArray ->
                val sealed = AesGcm.seal(keys.second, iv(index++), header, concat(byteArrayOf(kind.toByte()), data))
                concat(byteArrayOf((sealed.size ushr 24).toByte(), (sealed.size ushr 16).toByte(), (sealed.size ushr 8).toByte(), sealed.size.toByte()), sealed)
            }
            val headJson =
                buildJsonObject {
                    put("status", reply.status)
                    put("headers", JsonArray(reply.headers.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }))
                }.toString()
            val pipe = Pipe(1 shl 20)
            val sink = pipe.sink.buffer()
            sink.write(frame(0, headJson.encodeToByteArray())).flush()
            thread {
                for (chunk in reply.chunks) {
                    if (reply.delayMs > 0) Thread.sleep(reply.delayMs)
                    sink.write(frame(1, chunk.encodeToByteArray())).flush()
                }
                sink.write(frame(2, ByteArray(0))).flush()
                sink.close()
            }
            return Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("")
                .header("content-type", SEALED_CONTENT_TYPE)
                .body(pipe.source.buffer().asResponseBody(SEALED_CONTENT_TYPE.toMediaType()))
                .build()
        }

        companion object {
            const val BASE = "https://tee-node.example/node"
        }
    }

    private val base = StandInNode.BASE

    private fun get(
        client: OkHttpClient,
        path: String,
    ): Response = client.newCall(Request.Builder().url("$base$path").build()).execute()

    private fun latin1(net: FakeNet) = net.bodies.joinToString("") { String(it, Charsets.ISO_8859_1) }

    // ---- SealedTransport ---------------------------------------------------------------------

    @Test
    fun `delivers the whole request to the node and returns its answer as a Response`() {
        val node = StandInNode { _, _ -> Answer(201, listOf("x-answer" to "yes"), listOf("{\"result\":", "42}")) }
        val net = FakeNet(node::handle)
        val sealed = SealedTransport(base, node.transportKey, net.client)

        val response =
            sealed.client
                .newCall(
                    Request
                        .Builder()
                        .url("$base/jsonrpc?x=1")
                        .header("Authorization", "Bearer secret-token")
                        .post("""{"method":"secret-call"}""".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()

        val (head, body) = node.requests[0]
        assertEquals("POST", head["method"]!!.jsonPrimitive.content)
        assertEquals("/jsonrpc?x=1", head["path"]!!.jsonPrimitive.content)
        val headers = (head["headers"] as JsonArray).map { (it as JsonArray).let { p -> p[0].jsonPrimitive.content to p[1].jsonPrimitive.content } }
        assertTrue(("authorization" to "Bearer secret-token") in headers)
        assertTrue(headers.any { it.first == "content-type" && it.second.startsWith("application/json") })
        assertEquals("""{"method":"secret-call"}""", body)
        assertEquals(201, response.code)
        assertEquals("yes", response.header("x-answer"))
        assertEquals(
            42,
            Json
                .parseToJsonElement(response.body!!.string())
                .jsonObject["result"]!!
                .jsonPrimitive.int,
        )

        val wire = latin1(net)
        assertFalse(wire.contains("secret-token"))
        assertFalse(wire.contains("secret-call"))
        assertFalse(wire.contains("jsonrpc"))
    }

    @Test
    fun `opens one session for many requests, concurrent ones included`() {
        val node = StandInNode { _, _ -> ok() }
        val sealed = SealedTransport(base, node.transportKey, FakeNet(node::handle).client)
        runBlocking {
            (1..3).map { async(kotlinx.coroutines.Dispatchers.IO) { get(sealed.client, "/admin-api/health").use { it.body!!.string() } } }.awaitAll()
        }
        get(sealed.client, "/admin-api/health").close()
        assertEquals(1, node.handshakes)
        assertEquals(4, node.requests.size)
    }

    @Test
    fun `opens a new session when the node dropped the old one, and retries`() {
        val node = StandInNode { _, _ -> ok("\"again\"") }
        val sealed = SealedTransport(base, node.transportKey, FakeNet(node::handle).client)
        get(sealed.client, "/admin-api/health").close()
        node.forgetSessions()
        assertEquals("\"again\"", get(sealed.client, "/admin-api/health").body!!.string())
        assertEquals(2, node.handshakes)
    }

    @Test
    fun `attests again, through the caller, when the node restarted`() {
        val node = StandInNode { _, _ -> ok() }
        var attested = 0
        val sealed =
            SealedTransport(base, FakeNet(node::handle).client) {
                attested++
                node.transportKey
            }
        get(sealed.client, "/admin-api/health").close()
        node.restart()
        assertEquals(200, get(sealed.client, "/admin-api/health").code)
        assertEquals(2, attested)
    }

    @Test
    fun `tells the caller to attest again when given a fixed key and the node restarted`() {
        val node = StandInNode { _, _ -> ok() }
        val sealed = SealedTransport(base, node.transportKey, FakeNet(node::handle).client)
        node.restart()
        val e = runCatching { get(sealed.client, "/admin-api/health") }.exceptionOrNull()
        assertTrue("got $e", e is StaleTransportKeyException)
    }

    @Test
    fun `refuses a handshake reply too long to be one`() {
        val carrier = FakeNet { req, _ -> FakeNet.respondBytes(req, 200, ByteArray(4096), SEALED_CONTENT_TYPE) }
        val sealed = SealedTransport(base, transportKey, carrier.client)
        assertRefuses("too long") { get(sealed.client, "/admin-api/health") }
    }

    @Test
    fun `waits and retries once when the node is too busy to open a session`() {
        val node = StandInNode { _, _ -> ok() }
        var refused = 0
        val carrier =
            FakeNet { req, body ->
                if (req.url.encodedPath.endsWith(HANDSHAKE_PATH) && refused == 0) {
                    refused += 1
                    FakeNet.respond(req, 503, """{"error":{"code":"busy","message":"busy"}}""", headers = mapOf("retry-after" to "0.01"))
                } else {
                    node.handle(req, body)
                }
            }
        val sealed = SealedTransport(base, node.transportKey, carrier.client)
        assertEquals(200, get(sealed.client, "/admin-api/health").code)
        assertEquals(1, node.handshakes)
    }

    @Test
    fun `passes a node refusal on as a SealedTransportException`() {
        val carrier = FakeNet { req, _ -> refusal(req, 400, "malformed") }
        val sealed = SealedTransport(base, transportKey, carrier.client)
        val e = runCatching { get(sealed.client, "/admin-api/health") }.exceptionOrNull()
        assertTrue(e is SealedTransportException)
        assertEquals("malformed", (e as SealedTransportException).code)
        assertEquals(400, e.status)
    }

    @Test
    fun `hands over a streamed body frame by frame, before the stream ends`() {
        val node = StandInNode { _, _ -> Answer(200, listOf("content-type" to "text/event-stream"), listOf("data: one\n\n", "data: two\n\n"), 200) }
        val sealed = SealedTransport(base, node.transportKey, FakeNet(node::handle).client)
        val response =
            sealed.client
                .newCall(
                    Request
                        .Builder()
                        .url("$base/sse")
                        .header("accept", "text/event-stream")
                        .build(),
                ).execute()
        assertEquals("text/event-stream", response.header("content-type"))
        val source = response.body!!.source()
        val started = System.currentTimeMillis()
        assertEquals("data: one", source.readUtf8LineStrict())
        // The first event arrived before the second was even sent.
        assertTrue(System.currentTimeMillis() - started < 350)
        assertEquals("", source.readUtf8LineStrict())
        assertEquals("data: two", source.readUtf8LineStrict())
        assertEquals("", source.readUtf8LineStrict())
        assertTrue(source.exhausted())
    }

    @Test
    fun `never sends a request outside the node in the clear`() {
        val carrier = FakeNet { _, _ -> error("sent") }
        val sealed = SealedTransport(base, transportKey, carrier.client)
        assertRefuses("Refusing") { sealed.client.newCall(Request.Builder().url("https://elsewhere.example/node/jsonrpc").build()).execute() }
        assertRefuses("Refusing") { sealed.client.newCall(Request.Builder().url("https://tee-node.example/other/jsonrpc").build()).execute() }
        assertTrue(carrier.requests.isEmpty())
    }

    // ---- fetchAttestedTransportKey -----------------------------------------------------------

    @Test
    fun `asks the verifier to check the binding of the reported key`() {
        val sent = mutableListOf<com.calimero.mero.admin.TeeAttestRequest>()
        val seen = mutableListOf<TransportQuote>()
        val key =
            runBlocking {
                fetchAttestedTransportKey({ q ->
                    seen += q
                    true
                }) { request ->
                    sent += request
                    TransportAttestation("quote", transportPublicVector)
                }
            }
        assertEquals(transportPublicVector, Hex.encode(key))
        assertEquals(true, sent[0].bindTransportKey)
        assertEquals(null, sent[0].includeCollateral)
        assertEquals(TransportQuote("quote", sent[0].nonce, Hex.encode(transportKeyBinding(ByteArray(32), key))), seen.single())
    }

    @Test
    fun `asks the node for collateral only for a verifier that wants it`() {
        val sent = mutableListOf<com.calimero.mero.admin.TeeAttestRequest>()
        val seen = mutableListOf<TransportQuote>()
        runBlocking {
            fetchAttestedTransportKey(
                quoteVerifier { q ->
                    seen += q
                    true
                },
            ) { request ->
                sent += request
                TransportAttestation("AA==", Hex.encode(ByteArray(32) { 0x44 }), TeeFixtures.COLLATERAL.toJson())
            }
        }
        assertEquals(true, sent[0].includeCollateral)
        assertArrayEquals(TeeFixtures.COLLATERAL.tcbInfoSignature, seen.single().collateral!!.tcbInfoSignature)
    }

    @Test
    fun `refuses a key whose quote did not verify, or a node that reports none`() {
        assertRefuses("did not verify") {
            runBlocking { fetchAttestedTransportKey({ false }) { TransportAttestation("quote", transportPublicVector) } }
        }
        assertRefuses("predates sealed transport") {
            runBlocking { fetchAttestedTransportKey({ true }) { TransportAttestation("quote", null) } }
        }
    }

    // ---- createAttestedSealedFetch -----------------------------------------------------------

    /**
     * The stand-in node, plus the one unsealed route a client needs first:
     * `POST /admin-api/tee/attest`, answered with the node's transport key and a "quote" that
     * is only its report data, so a verifier can check the binding.
     */
    private fun attestingNode(answer: (JsonObject, String) -> Answer): Triple<StandInNode, FakeNet, MutableList<String>> {
        val node = StandInNode(answer)
        val attests = mutableListOf<String>()
        val net =
            FakeNet { req, body ->
                if (req.url.toString() == "$base/admin-api/tee/attest") {
                    val nonce =
                        Json
                            .parseToJsonElement(String(body))
                            .jsonObject["nonce"]!!
                            .jsonPrimitive.content
                    attests += nonce
                    val suffix = Hex.encode(transportKeyBinding(ByteArray(32), node.transportKey))
                    val quote = Hex.decode(nonce + suffix, "report data", 64)
                    FakeNet.respond(req, 200, """{"data":{"quoteB64":"${Base64.toBase64String(quote)}","transportPublicKey":"${Hex.encode(node.transportKey)}"}}""")
                } else {
                    node.handle(req, body)
                }
            }
        return Triple(node, net, attests)
    }

    /** Accepts a "quote" that is exactly `nonce ‖ reportDataSuffix`. */
    private val reportDataVerifier = VerifyTransportQuote { q -> Hex.encode(Base64.decode(q.quoteB64)) == q.nonce + q.reportDataSuffix }

    @Test
    fun `attests the node, then seals everything to the key its quote commits to`() {
        val (node, net, attests) = attestingNode { _, _ -> ok("""{"data":{"alive":true}}""") }
        val sealed = createAttestedSealedFetch(base, reportDataVerifier, httpClient = net.client)
        assertEquals("""{"data":{"alive":true}}""", get(sealed.client, "/admin-api/health").body!!.string())
        get(sealed.client, "/admin-api/health").close()
        assertEquals(1, attests.size)
        assertEquals(1, node.handshakes)
        assertEquals(listOf("/admin-api/health", "/admin-api/health"), node.requests.map { it.first["path"]!!.jsonPrimitive.content })
    }

    @Test
    fun `sends nothing sealed to a node whose quote the verifier refuses`() {
        val (node, net, _) = attestingNode { _, _ -> ok() }
        val sealed = createAttestedSealedFetch(base, { false }, httpClient = net.client)
        val e = assertRefuses("did not verify") { get(sealed.client, "/admin-api/health") }
        assertTrue(e is IOException)
        assertEquals(0, node.handshakes)
        assertTrue(node.requests.isEmpty())
    }

    /**
     * Delegated execution to a TEE relay: the warrant and the method's arguments reach only the
     * relay's TD. Whatever sits in front of it carries opaque envelopes.
     */
    @Test
    fun `carries a RelayClient intent to the relay sealed, warrant and arguments included`() {
        val context = "01".repeat(32)
        val executor = "4d".repeat(32)
        val executorKey = "ec".repeat(32)
        val (node, net, _) =
            attestingNode { head, _ ->
                if (head["method"]!!.jsonPrimitive.content == "GET") {
                    ok(
                        """{"data":{"executorAccount":"$executor","executorKey":"$executorKey","canAuthorOnBehalf":true,""" +
                            """"groupId":"${"ab".repeat(32)}","releaseBytecodeId":"${"b1".repeat(32)}","releaseVersion":"1.2.0"}}""",
                    )
                } else {
                    ok("""{"data":{"rootHash":"root-1","returns":"ok"}}""")
                }
            }
        val relay =
            RelayClient(
                relayUrl = base,
                authorAccount = "0e".repeat(32),
                authorProof = "aa",
                signer = SeedSigner("77".repeat(32)),
                nonces = MemoryNonceSource(1u),
                executorAccount = executor,
                httpClient = createAttestedSealedFetch(base, reportDataVerifier, httpClient = net.client).client,
            )

        val result =
            runBlocking {
                relay.execute(
                    context,
                    "transfer_secret_amount",
                    buildJsonObject {
                        put("to", "bob")
                        put("amount", 4242)
                    },
                )
            }

        assertEquals("root-1", result.rootHash)
        assertEquals(JsonPrimitive("ok"), result.returns)
        val (discovery, intent) = node.requests
        assertEquals("GET", discovery.first["method"]!!.jsonPrimitive.content)
        assertEquals("POST", intent.first["method"]!!.jsonPrimitive.content)
        assertEquals("/admin-api/contexts/$context/intents", intent.first["path"]!!.jsonPrimitive.content)
        val sent = Json.parseToJsonElement(intent.second).jsonObject
        assertEquals("transfer_secret_amount", sent["method"]!!.jsonPrimitive.content)
        assertEquals(4242, sent["argsJson"]!!.jsonObject["amount"]!!.jsonPrimitive.int)
        val warrant = sent["warrant"]!!.jsonPrimitive.content
        assertTrue(warrant.contains(executor) && warrant.contains(executorKey) && warrant.contains("b1".repeat(32)))

        val wire = latin1(net)
        assertFalse(wire.contains("transfer_secret_amount"))
        assertFalse(wire.contains("4242"))
        assertFalse(wire.contains(warrant.substring(0, 32)))
    }

    // ---- createSignedReleaseSealedFetch (release.test.ts) -------------------------------------

    private val relayUrl = "https://relay.example"
    private val releasedImage = "merotee-ubuntu-questing-25-10-locked-read-only-2-3-87"

    private fun signed(version: String) =
        SignedNodeRelease(
            version,
            TeeFixtures.text("sealed/mero-tee-v$version.published-mrtds.json"),
            TeeFixtures.text("sealed/mero-tee-v$version.published-mrtds.json.bundle.json"),
        )

    private fun releaseJson(r: SignedNodeRelease) =
        buildJsonObject {
            put(
                "data",
                buildJsonObject {
                    put("version", r.version)
                    put("publishedMrtds", r.publishedMrtds)
                    put("bundle", r.bundle)
                },
            )
        }.toString()

    /**
     * A relay that names release 2.3.87 in its `/tee/info` and attests with the real sample
     * quote, bound to the transport key; and mirrors serving `{data: SignedNodeRelease}`.
     */
    private fun network(infoRefused: Boolean = false): FakeNet =
        FakeNet { req, body ->
            val url = req.url.toString()
            when {
                url == "$relayUrl/admin-api/tee/info" ->
                    if (infoRefused) {
                        FakeNet.respond(req, 403, """{"error":{"code":"sealed_required","message":"sealed only"}}""")
                    } else {
                        FakeNet.respond(req, 200, """{"data":{"cloudProvider":"gcp","osImage":"$releasedImage","mrtd":""}}""")
                    }
                url == "$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.87" || url == "https://cloud.example/api/tee/node-releases/2.3.87" ->
                    FakeNet.respond(req, 200, releaseJson(signed("2.3.87")))
                url == "$relayUrl/admin-api/tee/attest" -> {
                    val sent = Json.parseToJsonElement(String(body)).jsonObject
                    assertEquals(JsonPrimitive(true), sent["bindTransportKey"])
                    assertEquals(JsonPrimitive(true), sent["includeCollateral"])
                    val data =
                        buildJsonObject {
                            put("quoteB64", TeeFixtures.QUOTE_B64)
                            put("transportPublicKey", Hex.encode(ByteArray(32) { 0x44 }))
                            put("collateral", TeeFixtures.COLLATERAL.toJson())
                        }
                    FakeNet.respond(req, 200, buildJsonObject { put("data", data) }.toString())
                }
                else -> null
            }
        }

    private fun options(
        net: FakeNet,
        minReleaseVersion: String = "2.3.86",
        releaseMirror: String = DEFAULT_RELEASE_MIRROR,
        releaseSource: (suspend (NodeReleaseRequest) -> SignedNodeRelease)? = null,
        releaseVersion: String? = null,
    ) = SignedReleaseSealedFetchOptions(
        baseUrl = relayUrl,
        minReleaseVersion = minReleaseVersion,
        releaseMirror = releaseMirror,
        releaseSource = releaseSource,
        releaseVersion = releaseVersion,
        now = { TeeFixtures.AT },
        httpClient = net.client,
    )

    private fun call(sealed: SealedTransport) {
        sealed.client.newCall(Request.Builder().url("$relayUrl/admin-api/health").build()).execute()
    }

    @Test
    fun `gets the release the relay names from the public mirror, and refuses a quote not of it`() {
        val net = network()
        assertRefuses("are not an image this verifier trusts") { call(createSignedReleaseSealedFetch(options(net))) }
        assertEquals(
            listOf("$relayUrl/admin-api/tee/attest", "$relayUrl/admin-api/tee/info", "$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.87"),
            net.requests,
        )
    }

    @Test
    fun `takes the release from another mirror, or from a function, given the version`() {
        val byUrl = network()
        assertRefuses("are not an image this verifier trusts") {
            call(createSignedReleaseSealedFetch(options(byUrl, releaseMirror = "https://cloud.example")))
        }
        assertTrue("https://cloud.example/api/tee/node-releases/2.3.87" in byUrl.requests)
        assertFalse("$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.87" in byUrl.requests)

        val byFunction = network()
        val asked = mutableListOf<NodeReleaseRequest>()
        assertRefuses("are not an image this verifier trusts") {
            call(
                createSignedReleaseSealedFetch(
                    options(byFunction, releaseSource = { r ->
                        asked += r
                        signed(r.version)
                    }),
                ),
            )
        }
        assertEquals(relayUrl, asked.single().baseUrl)
        assertEquals("2.3.87", asked.single().version)
        assertTrue(asked.single().httpClient === byFunction.client)
    }

    @Test
    fun `takes releaseVersion when a node that requires sealing will not name its release, and only then`() {
        val refusing = network(infoRefused = true)
        assertRefuses("`releaseVersion`") { call(createSignedReleaseSealedFetch(options(refusing))) }

        val pinned = network(infoRefused = true)
        assertRefuses("are not an image this verifier trusts") { call(createSignedReleaseSealedFetch(options(pinned, releaseVersion = "2.3.87"))) }
        assertTrue("$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.87" in pinned.requests)

        // A node that names its release is taken at its word, as untrusted as ever.
        val answering = network()
        assertRefuses("are not an image this verifier trusts") {
            call(createSignedReleaseSealedFetch(options(answering, releaseVersion = "2.3.99")))
        }
        assertTrue("$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.87" in answering.requests)
        assertFalse("$DEFAULT_RELEASE_MIRROR/api/tee/node-releases/2.3.99" in answering.requests)

        assertRefuses("releaseVersion") { createSignedReleaseSealedFetch(options(network(), releaseVersion = "latest")) }
    }

    @Test
    fun `refuses a relay that serves a release below the minimum, before trusting its quote`() {
        assertRefuses("Release 2.3.87 is older than the minimum trusted, 2.3.88") {
            call(createSignedReleaseSealedFetch(options(network(), minReleaseVersion = "2.3.88")))
        }
    }
}

private fun refusal(
    request: Request,
    status: Int,
    code: String,
): Response = FakeNet.respond(request, status, """{"error":{"code":"$code","message":"$code"}}""")
