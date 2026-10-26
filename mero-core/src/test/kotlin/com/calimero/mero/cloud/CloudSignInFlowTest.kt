package com.calimero.mero.cloud

import com.calimero.mero.account.AccountSwitchException
import com.calimero.mero.account.CloudAccount
import com.calimero.mero.account.DelegatedSession
import com.calimero.mero.account.DeviceCert
import com.calimero.mero.account.Enrolment
import com.calimero.mero.account.EnrolmentException
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.admin.SignedGroupOpenInvitation
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.crypto.readU64le
import com.calimero.mero.relay.DcapRelayKeyVerifier
import com.calimero.mero.relay.IntentRefusedException
import com.calimero.mero.storage.MemoryTokenStore
import com.calimero.mero.testkit.FakeNode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

/**
 * The whole Cloud sign-in against [FakeNode]'s cloud + relay routes: wallet URL →
 * (mock) wallet redirect → verified certificate → routing-proven relay lookup → relay
 * attestation and `account_proof` login → warranted writes and reads by query.
 *
 * Request shapes are asserted on the **exact key set**, in the style of Rc41WireShapeTest:
 * core rejects unknown fields, so a stray key is as fatal as a missing one.
 */
class CloudSignInFlowTest {
    private lateinit var node: FakeNode
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var store: MemorySecureStore
    private lateinit var account: CloudAccount
    private var now = 1_700_000_000_000L

    private val callback = "mero-sample://enrol"
    private val root = SeedSigner("5c".repeat(32))

    @Before
    fun setUp() {
        node = FakeNode()
        server = node.start()
        base = server.url("/").toString().trimEnd('/')
        store = MemorySecureStore()
        account = relaunched()
    }

    /** A fresh [CloudAccount] over the same store, as after process death. */
    private fun relaunched() =
        CloudAccount(
            store,
            relayKeyVerifier = DcapRelayKeyVerifier(allowMock = true),
            cloudBaseUrl = base,
            walletUrl = "https://wallet.example/account-enroll",
            clock = { now },
        )

    private suspend fun assertStateRefused(answer: String) {
        try {
            account.completeEnrolment(answer)
            throw AssertionError("expected EnrolmentException")
        } catch (e: EnrolmentException) {
            assertTrue(e.message!!.contains("state"))
        }
    }

    @After
    fun tearDown() = server.shutdown()

    /** What the wallet does: certify the keys named in the URL and redirect with the fragment. */
    private fun walletApproves(
        walletUrl: String,
        state: String? = null,
    ): String {
        val url = walletUrl.toHttpUrl()
        val signPk = url.queryParameter("enrol-device")!!
        val kemPk = url.queryParameter("enrol-kem")!!
        val back = url.queryParameter("callback-url")!!
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 7 })
        val credential = DeviceCert.sign(root, device, signPk, kemPk)
        val st = state ?: url.queryParameter("state")!!
        return "$back#credential=$credential&account=$acc&device=$device&state=${URLEncoder.encode(st, "UTF-8")}"
    }

    @Test
    fun `builds the wallet URL mero-js builds, with an app-scheme callback`() {
        val url = account.beginEnrolment(callback).toHttpUrl()
        val keys = account.deviceKeys.load()!!
        assertEquals("wallet.example", url.host)
        assertEquals("/account-enroll", url.encodedPath)
        assertEquals(setOf("enrol-device", "enrol-kem", "callback-url", "state"), url.queryParameterNames)
        assertEquals(keys.signPublicKey, url.queryParameter("enrol-device"))
        assertEquals(keys.kemPublicKey, url.queryParameter("enrol-kem"))
        assertEquals(callback, url.queryParameter("callback-url"))
        assertEquals(32, url.queryParameter("state")!!.length)
        // The keys survive: a second sign-in certifies the same device.
        assertEquals(keys, account.deviceKeys.loadOrCreate())
    }

    @Test
    fun `refuses a callback that already carries a fragment`() {
        try {
            account.beginEnrolment("mero-sample://enrol#x")
            throw AssertionError("expected a refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fragment"))
        }
    }

    @Test
    fun `signs in, finds the relay with a routing proof, and logs in with the certificate`() =
        runBlocking {
            val session = account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            assertEquals(DeviceCert.accountForRootPublicKey(root.publicKey), session.account)
            assertEquals(base, session.relayUrl)
            assertEquals(node.cloud.relayAccount, session.executorAccount)

            // Routing proof on the relays read: credential, the nonce the cloud minted, a signature over it.
            val relays = node.cloud.recorded("/relays").single()
            assertEquals(session.credential, relays.headers["X-Calimero-Credential"])
            assertEquals("fake-challenge-1", relays.headers["X-Calimero-Nonce"])
            val keys = account.deviceKeys.load()!!
            assertEquals(
                RoutingProof.signRoutingChallenge("fake-challenge-1", keys.signer()),
                relays.headers["X-Calimero-Signature"],
            )

            val tokens = MemoryTokenStore()
            val connection = account.connect(session, tokens)
            assertTrue(connection.ensureSession())
            assertNotNull(tokens.getTokens())

            // attest: {nonce, bindNodeKey, includeCollateral}
            val attest =
                node.cloud
                    .recorded("/admin-api/tee/attest")
                    .single()
                    .bodyJson!!
            assertEquals(setOf("nonce", "bindNodeKey", "includeCollateral"), attest.keys)
            assertEquals(64, attest["nonce"]!!.jsonPrimitive.content.length)

            // /auth/token: account_proof with a login statement over the challenge.
            val token = Json.parseToJsonElement(node.lastTokenRequestBody!!).jsonObject
            assertEquals(setOf("auth_method", "public_key", "client_name", "timestamp", "provider_data"), token.keys)
            assertEquals("account_proof", token["auth_method"]!!.jsonPrimitive.content)
            val provider = token["provider_data"]!!.jsonObject
            assertEquals(setOf("challenge", "login_statement", "account_proof"), provider.keys)
            assertEquals(session.credential, provider["account_proof"]!!.jsonPrimitive.content)
            val statement = provider["login_statement"]!!.jsonPrimitive.content
            // node ‖ Cli tag ‖ challenge ‖ session key ‖ device key ‖ iat ‖ exp ‖ sig
            assertEquals(node.cloud.nodeKey, statement.substring(0, 64))
            assertEquals("02", statement.substring(64, 66))
            assertEquals(provider["challenge"]!!.jsonPrimitive.content, statement.substring(66, 130))
            assertEquals(token["public_key"]!!.jsonPrimitive.content, statement.substring(130, 194))
            assertEquals(keys.signPublicKey, statement.substring(194, 258))

            // The relay's node key is pinned: a reconnect does not attest again.
            account.connect(session, MemoryTokenStore()).ensureSession()
            assertEquals(1, node.cloud.recorded("/admin-api/tee/attest").size)
        }

    @Test
    fun `writes through a warrant and reads through query, falling back on 409`() =
        runBlocking {
            val session = account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            val connection = account.connect(session, MemoryTokenStore())
            val ctx = "11".repeat(32)
            val args = buildJsonObject { put("text", "hi") }

            node.cloud.outputs["get_messages"] = buildJsonArray { add(JsonPrimitive("hello")) }
            val read = connection.relay.call(ctx, "get_messages")
            assertEquals(buildJsonArray { add(JsonPrimitive("hello")) }, read)
            val query = node.cloud.recorded("/query").single()
            assertEquals(setOf("method", "argsJson"), query.bodyJson!!.keys)
            assertTrue(query.headers["Authorization"]!!.startsWith("Bearer "))
            assertTrue(node.cloud.recorded("/intents").isEmpty())

            // A write: the query answers 409, the call goes out as a warrant.
            connection.relay.call(ctx, "send_message", args)
            val intent = node.cloud.recorded("/intents").last { it.method == "POST" }
            val body = intent.bodyJson!!
            assertEquals(setOf("method", "argsJson", "warrant", "authorProof"), body.keys)
            assertEquals(args, body["argsJson"])
            assertEquals(session.credential, body["authorProof"]!!.jsonPrimitive.content)
            assertNull(intent.headers["Authorization"])
            val warrant = body["warrant"]!!.jsonPrimitive.content
            assertEquals(ctx, warrant.substring(0, 64))
            assertEquals(session.account, warrant.substring(64, 128))
            assertEquals(account.deviceKeys.load()!!.signPublicKey, warrant.substring(128, 192))
            assertEquals(node.cloud.relayAccount, warrant.substring(192, 256))
            assertEquals(node.cloud.executorKey, warrant.substring(256, 320))
            assertEquals(1uL, nonceOf(warrant))

            // Second write: the persisted sequence advances.
            connection.relay.execute(ctx, "send_message", args)
            assertEquals(
                2uL,
                nonceOf(
                    node.cloud
                        .recorded("/intents")
                        .last { it.method == "POST" }
                        .bodyJson!!,
                ),
            )
            // ...and a known write skips the query probe next time.
            assertEquals(1, node.cloud.recorded("/query").count { it.bodyJson!!["method"]!!.jsonPrimitive.content == "send_message" })
        }

    @Test
    fun `a spent-nonce refusal that names no floor surfaces, with no lookup and no retry`() =
        runBlocking {
            val session = account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            val connection = account.connect(session, MemoryTokenStore())
            node.cloud.refuseNextIntent = "this warrant's nonce has already been spent by this author device"
            try {
                connection.relay.execute("11".repeat(32), "set", buildJsonObject { put("k", "v") })
                throw AssertionError("expected IntentRefusedException")
            } catch (e: IntentRefusedException) {
                assertTrue(e.retryable)
                assertEquals(400, e.status)
            }
            assertEquals(1, node.cloud.recorded("/intents").count { it.method == "POST" })
            assertTrue(node.cloud.recorded("warrant-nonce").isEmpty())
        }

    @Test
    fun `after a nonce refusal the next execute signs the next nonce`() =
        runBlocking {
            val session = account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            val connection = account.connect(session, MemoryTokenStore())
            val ctx = "11".repeat(32)
            node.cloud.refuseNextIntent = "this warrant's nonce has already been spent by this author device"
            val refused = runCatching { connection.relay.execute(ctx, "set", buildJsonObject { put("k", "v") }) }.exceptionOrNull()
            assertTrue((refused as IntentRefusedException).retryable)

            connection.relay.execute(ctx, "set", buildJsonObject { put("k", "v") })
            val posts = node.cloud.recorded("/intents").filter { it.method == "POST" }
            assertEquals(2, posts.size)
            val first = nonceOf(posts.first().bodyJson!!)
            assertEquals(first + 1uL, nonceOf(posts.last().bodyJson!!))
        }

    @Test
    fun `a declined enrolment surfaces as cancelled and stores nothing`() =
        runBlocking {
            account.beginEnrolment(callback)
            try {
                account.completeEnrolment("$callback#error=cancelled")
                throw AssertionError("expected EnrolmentException")
            } catch (e: EnrolmentException) {
                assertTrue(e.cancelled)
            }
            assertFalse(account.isSignedIn)
        }

    @Test
    fun `refuses an answer to a request this app did not make`() =
        runBlocking {
            val url = account.beginEnrolment(callback)
            try {
                account.completeEnrolment(walletApproves(url, state = "someone-elses-state"))
                throw AssertionError("expected EnrolmentException")
            } catch (e: EnrolmentException) {
                assertTrue(e.message!!.contains("state"))
            }
            assertFalse(account.isSignedIn)
        }

    @Test
    fun `refuses a credential certifying a different device key`() {
        val keys = account.deviceKeys.loadOrCreate()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 1 })
        val credential = DeviceCert.sign(root, device, SeedSigner("6d".repeat(32)).publicKey, keys.kemPublicKey)
        val cb = Enrolment.readEnrolmentCallback("$callback#credential=$credential&account=$acc&device=$device&state=s1")!!
        try {
            Enrolment.completeDeviceEnrolment(cb, keys.signPublicKey, keys.kemPublicKey, expectState = "s1")
            throw AssertionError("expected EnrolmentException")
        } catch (e: EnrolmentException) {
            assertTrue(e.message!!.contains("certifies device key"))
        }
    }

    @Test
    fun `refuses an answer when no state is pending, however valid its certificate`() {
        val keys = account.deviceKeys.loadOrCreate()
        val acc = DeviceCert.accountForRootPublicKey(root.publicKey)
        val device = DeviceCert.mintDeviceId(acc, ByteArray(16) { 7 })
        val credential = DeviceCert.sign(root, device, keys.signPublicKey, keys.kemPublicKey)
        for (fragment in listOf("&state=s1", "")) {
            val cb = Enrolment.readEnrolmentCallback("$callback#credential=$credential&account=$acc&device=$device$fragment")!!
            try {
                Enrolment.completeDeviceEnrolment(cb, keys.signPublicKey, keys.kemPublicKey, expectState = null)
                throw AssertionError("expected EnrolmentException")
            } catch (e: EnrolmentException) {
                assertTrue(e.message!!.contains("state"))
            }
        }
    }

    /**
     * SEC-01: a credential minted for this device's (public) keys by someone else's passkey,
     * sent as a link, must not sign the app in to their account.
     */
    @Test
    fun `refuses an unsolicited answer and stores nothing`() =
        runBlocking {
            val keys = account.deviceKeys.loadOrCreate()
            val forged =
                Enrolment.deviceEnrolmentUrl(
                    keys.signPublicKey,
                    keys.kemPublicKey,
                    callback,
                    "attacker-chosen",
                    "https://wallet.example/account-enroll",
                )
            assertStateRefused(walletApproves(forged))
            assertFalse(account.isSignedIn)
            assertTrue(node.cloud.recorded("/relays").isEmpty())
        }

    @Test
    fun `an answered state cannot be answered again`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val answer = walletApproves(account.beginEnrolment(callback))
            account.completeEnrolment(answer)!!
            assertTrue(account.isSignedIn)
            assertStateRefused(answer)
        }

    @Test
    fun `an expired state is refused`() =
        runBlocking {
            val answer = walletApproves(account.beginEnrolment(callback))
            now += CloudAccount.ENROLMENT_STATE_LIFETIME_MS + 1
            assertStateRefused(answer)
            assertFalse(account.isSignedIn)
        }

    @Test
    fun `the pending state survives a relaunch`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val answer = walletApproves(account.beginEnrolment(callback))
            val session = relaunched().completeEnrolment(answer)!!
            assertEquals(DeviceCert.accountForRootPublicKey(root.publicKey), session.account)
            assertNull("spent", store.get("calimero.enrol.state"))
        }

    @Test
    fun `switching account needs confirmation`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val other = DelegatedSession("ab".repeat(32), "cd".repeat(32), "00")
            account.saveSession(other)
            val answer = walletApproves(account.beginEnrolment(callback))
            val incoming = DeviceCert.accountForRootPublicKey(root.publicKey)
            try {
                account.completeEnrolment(answer)
                throw AssertionError("expected AccountSwitchException")
            } catch (e: AccountSwitchException) {
                assertEquals("ab".repeat(32), e.currentAccount)
                assertEquals(incoming, e.incomingAccount)
            }
            assertEquals("nothing replaced", other, account.session())

            // Confirmed: the same answer completes, since the refusal did not spend its state.
            val session = account.completeEnrolment(answer, allowAccountSwitch = true)!!
            assertEquals(incoming, session.account)
            assertEquals(incoming, account.session()!!.account)
        }

    @Test
    fun `re-enrolling the same account needs no confirmation`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            assertEquals(DeviceCert.accountForRootPublicKey(root.publicKey), account.session()!!.account)
        }

    @Test
    fun `signed in without a relay is still signed in`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            val session = account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            assertNull(session.relayUrl)
            assertTrue(account.isSignedIn)
            assertNotNull(account.chooseRelay(session).note)
        }

    @Test
    fun `joins a namespace through the admitter the cloud routes to`() =
        runBlocking {
            node.cloud.accountHasRelay = false
            account.completeEnrolment(walletApproves(account.beginEnrolment(callback)))!!
            val ns = "77".repeat(32)
            val result = account.joinAsAccount(ns, invitation(node.cloud.relayAccount))
            assertTrue(result.published)
            assertEquals(base, result.relayUrl)
            assertEquals(base, account.session()!!.relayUrl)

            val routing = node.cloud.recorded("/admitters").single()
            assertNotNull(routing.headers["X-Calimero-Signature"])
            val admit =
                node.cloud
                    .recorded("/admit")
                    .single()
                    .bodyJson!!
            assertEquals(setOf("invitation", "signedOp"), admit.keys)
            // The invitation goes back exactly as core sent it.
            assertEquals(invitation(node.cloud.relayAccount).raw, admit["invitation"])
            val op = admit["signedOp"]!!.jsonPrimitive.content
            assertEquals("1b", op.substring(0, 2)) // schema 27
            assertEquals(ns, op.substring(2, 66))
            assertEquals("00000000", op.substring(66, 74)) // no parents
            assertEquals(account.deviceKeys.load()!!.signPublicKey, op.substring(74, 138))
            assertTrue(op.endsWith("00")) // unendorsed
        }

    private fun nonceOf(body: JsonObject): ULong = nonceOf(body["warrant"]!!.jsonPrimitive.content)

    /** The nonce sits 8 + 8 + 64 bytes from the end of a warrant: nonce ‖ notAfter ‖ signature. */
    private fun nonceOf(warrant: String): ULong {
        val bytes = Hex.decodeUnsized(warrant, "warrant")
        return readU64le(bytes, bytes.size - 80)
    }

    private fun invitation(admitter: String): SignedGroupOpenInvitation {
        fun bytes(v: Int) = buildJsonArray { repeat(32) { add(JsonPrimitive(v)) } }
        return SignedGroupOpenInvitation(
            buildJsonObject {
                put(
                    "invitation",
                    buildJsonObject {
                        put("inviter_identity", bytes(1))
                        put("group_id", bytes(0x77))
                        put("expiration_timestamp", 1_900_000_000)
                        put("secret_salt", bytes(3))
                        put("invited_role", 1)
                        put("admitters", buildJsonArray { add(JsonPrimitive(admitter)) })
                    },
                )
                put("inviter_signature", "ab".repeat(64))
                put("inviter_account", "22".repeat(32))
                put("admitter_addrs", buildJsonArray { add(JsonPrimitive("/ip4/10.0.0.1/tcp/2528/p2p/12D3KooWExample")) })
            },
        )
    }
}
