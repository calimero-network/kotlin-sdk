package com.calimero.mero.cloud

import com.calimero.mero.account.AccountLinkInput
import com.calimero.mero.account.AccountLoginInput
import com.calimero.mero.account.MemorySecureStore
import com.calimero.mero.account.accountRootFromSecret
import com.calimero.mero.account.signAccountLink
import com.calimero.mero.account.signAccountLogin
import com.calimero.mero.crypto.SeedSigner
import com.calimero.mero.http.HttpException
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.http.NetworkException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The session half of [CloudClient], against a scripted server — the cases of mero-js
 * `cloud-client.test.ts`, `account-login.test.ts` and `account-link-grant.test.ts`.
 */
class CloudSessionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        base = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueue(
        body: String,
        status: Int = 200,
        headers: Map<String, String> = emptyMap(),
    ) {
        val r =
            MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body)
        headers.forEach { (k, v) -> r.setHeader(k, v) }
        server.enqueue(r)
    }

    private fun take(): RecordedRequest = server.takeRequest()

    private fun RecordedRequest.json(): JsonObject = Json.parseToJsonElement(body.readUtf8()).jsonObject

    private fun signedIn(token: String = "session-1") = CloudClient(base, sessionToken = token)

    private val sessionBody =
        """{"session_token":"session-1","expires_at":1800000000,"user":{"email":"someone@example.com","name":"Someone"}}"""

    // ---- sign-in -------------------------------------------------------------------------

    @Test
    fun `defaults to the manager API, not the web app`() {
        assertEquals("https://manager.cloud.calimero.network", CloudClient().baseUrl)
    }

    @Test
    fun `exchanges a Google ID token for a session, sending no bearer`() =
        runBlocking {
            enqueue(sessionBody)
            val cloud = CloudClient("$base/")
            assertFalse(cloud.isSignedIn)
            val session = cloud.signInWithGoogle("google-id-token")
            assertEquals("session-1", session.sessionToken)
            assertEquals(1_800_000_000L, session.expiresAt)
            assertEquals(CloudUser("someone@example.com", "Someone"), session.user)
            assertTrue(cloud.isSignedIn)
            val req = take()
            assertEquals("/api/auth/google", req.path)
            assertNull(req.getHeader("Authorization"))
            assertEquals(buildJsonObject { put("id_token", "google-id-token") }, req.json())
        }

    @Test
    fun `sends the session as a bearer on authenticated reads`() =
        runBlocking {
            enqueue("[]")
            signedIn("stored-token").getMyNamespaces()
            assertEquals("Bearer stored-token", take().getHeader("Authorization"))
        }

    @Test
    fun `refuses an authenticated call before making it when nobody is signed in`() =
        runBlocking {
            try {
                CloudClient(base).getMyNamespaces()
                fail("expected MeroStateException")
            } catch (e: MeroStateException) {
                assertTrue(e.message!!.contains("Not signed in"))
            }
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `adopts a rolling-refreshed session from the response headers and persists it`() =
        runBlocking {
            enqueue("[]", headers = mapOf("x-mdma-session-refresh" to "session-2", "x-mdma-session-expires" to "1900000000"))
            val seen = mutableListOf<String?>()
            val store = CloudSessionStore(MemorySecureStore())
            val cloud = CloudClient(base, sessionToken = "session-1", onSession = { seen += it?.sessionToken }, sessionStore = store)
            cloud.getMyNamespaces()
            assertEquals("session-2", cloud.getSession()?.sessionToken)
            assertEquals(1_900_000_000L, cloud.getSession()?.expiresAt)
            assertEquals(listOf<String?>("session-2"), seen)
            assertEquals("session-2", store.load()?.sessionToken)
            // A later launch resumes it from the store.
            assertEquals("session-2", CloudClient(base, sessionStore = store).getSession()?.sessionToken)
        }

    @Test
    fun `a failing onSession callback does not mask the result`() =
        runBlocking {
            enqueue(sessionBody)
            val session = CloudClient(base, onSession = { error("disk full") }).signInWithGoogle("t")
            assertEquals("session-1", session.sessionToken)
        }

    @Test
    fun `signs out locally even when the server call fails`() =
        runBlocking {
            val store = CloudSessionStore(MemorySecureStore())
            store.save(CloudLoginSession("session-1", 0, CloudUser("")))
            val cloud = CloudClient("http://127.0.0.1:1", sessionStore = store)
            assertTrue(cloud.isSignedIn)
            try {
                cloud.signOut()
                fail("expected the network failure to surface")
            } catch (_: NetworkException) {
            }
            assertFalse(cloud.isSignedIn)
            assertNull(store.load())
        }

    @Test
    fun `signs out on the server with the session`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(204))
            val cloud = signedIn()
            cloud.signOut()
            val req = take()
            assertEquals("/api/auth/logout", req.path)
            assertEquals("Bearer session-1", req.getHeader("Authorization"))
            assertFalse(cloud.isSignedIn)
        }

    @Test
    fun `refreshes the session explicitly`() =
        runBlocking {
            enqueue(sessionBody.replace("session-1", "session-9"))
            val cloud = signedIn()
            assertEquals("session-9", cloud.refreshSession().sessionToken)
            assertEquals("/api/auth/refresh", take().path)
        }

    // ---- account login -------------------------------------------------------------------

    private val loginRoot = "5b6b8a1e9f2c47d3a80e6f14c2b9d75380af4e21c6d3b95f7e08a1c4d2f63b97"
    private val loginRootPk = "a021d221f1e7601e8d280c857f8a667383e3923dda13b55f21ca2d928b79c70c"
    private val loginNonce = "test-login-nonce-abc"
    private val loginSignature = "5Au0t95Ei2dJLBES7w3i6qeH5NN90S2LyzloPIl1spCSBx4MV0M8VUgVaOT6a16SL/HGtj25epGHCPhj5wmeAw=="

    @Test
    fun `signs in with the root, sending only the key, with no bearer on either leg`() =
        runBlocking {
            enqueue("""{"nonce":"$loginNonce","expires_at_ms":1893456000000}""")
            enqueue(sessionBody)
            val cloud = CloudClient(base, sessionToken = "someone.elses.token")
            val session = cloud.signInWithAccount(loginRoot)
            assertEquals("session-1", session.sessionToken)
            val challenge = take()
            assertEquals("/api/auth/account/challenge", challenge.path)
            assertNull(challenge.getHeader("Authorization"))
            val post = take()
            assertEquals("/api/auth/account", post.path)
            assertNull(post.getHeader("Authorization"))
            assertEquals(
                buildJsonObject {
                    put("root_public_key", loginRootPk)
                    put("nonce", loginNonce)
                    put("signature", loginSignature)
                },
                post.json(),
            )
        }

    @Test
    fun `signs in with a root held as a signer`() =
        runBlocking {
            enqueue("""{"nonce":"$loginNonce","expires_at_ms":1}""")
            enqueue(sessionBody)
            CloudClient(base).signInWithAccount(SeedSigner(loginRoot))
            take()
            assertEquals(loginSignature, Json.parseToJsonElement(take().body.readUtf8()).jsonObject["signature"]?.let { (it as JsonPrimitive).content })
        }

    @Test
    fun `surfaces the cloud refusing an unlinked account and stays signed out`() =
        runBlocking {
            enqueue("""{"nonce":"$loginNonce","expires_at_ms":1}""")
            enqueue("""{"detail":"ownership is recorded, but it is not linked"}""", status = 403)
            val cloud = CloudClient(base)
            try {
                cloud.signInWithAccount(loginRoot)
                fail("expected 403")
            } catch (e: HttpException) {
                assertEquals(403, e.status)
            }
            assertFalse(cloud.isSignedIn)
        }

    @Test
    fun `accepts a login proof signed elsewhere`() =
        runBlocking {
            enqueue(sessionBody)
            val challenge = AccountLoginInput(loginNonce, rootSecret = loginRoot)
            val session = CloudClient(base).submitAccountLogin(AccountLoginProof(loginRootPk, loginNonce, signAccountLogin(challenge)))
            assertEquals("someone@example.com", session.user.email)
            assertEquals("/api/auth/account", take().path)
        }

    @Test
    fun `reads the login challenge`() =
        runBlocking {
            enqueue("""{"nonce":"n1","expires_at_ms":1893456000000}""")
            assertEquals(AccountLoginChallenge("n1", 1_893_456_000_000), CloudClient(base).getAccountLoginChallenge())
        }

    // ---- relays, namespaces, machines ----------------------------------------------------

    private val ns = "01".repeat(32)
    private val executor = "4d".repeat(32)

    @Test
    fun `maps snake_case namespace rows`() =
        runBlocking {
            enqueue(
                """[{"group_id":"$ns","namespace_id":"$ns","ha_status":"enabled","ha_enabled_at":"2026-01-01T00:00:00",""" +
                    """"contexts":["ctx-1"],"fleet_replicas":{"current":1,"limit":2}}]""",
            )
            val rows = signedIn().getMyNamespaces()
            assertEquals(
                listOf(
                    CloudNamespace(
                        ns,
                        ns,
                        "enabled",
                        "2026-01-01T00:00:00",
                        listOf("ctx-1"),
                        buildJsonObject {
                            put("current", 1)
                            put("limit", 2)
                        },
                    ),
                ),
                rows,
            )
            assertEquals("/api/cloud/me/namespaces", take().path)
        }

    @Test
    fun `reads a bare namespace row with its defaults`() =
        runBlocking {
            enqueue("""[{"group_id":"$ns"}]""")
            val row = signedIn().getMyNamespaces().single()
            assertEquals(ns, row.namespaceId)
            assertEquals("none", row.haStatus)
            assertTrue(row.contexts.isEmpty())
        }

    private fun relayRow(extra: String = ""): String =
        """{"peer_id":"12D3KooWpeer","relay_url":"https://relay.example","executor_account":"$executor",""" +
            """"status":"active","authorship_ready":true,"tee_role":"RelayTee","can_execute":true$extra}"""

    @Test
    fun `maps the relay wire shape, never substituting peerId for the node key`() =
        runBlocking {
            enqueue("""{"relays":[${relayRow(""","last_seen_at":"t1","confirmed_at":"t0","admission":"confirmed","admission_hint":"in"""")}]}""")
            val relay = signedIn().getNamespaceRelays(ns).single()
            assertEquals("/api/cloud/me/namespaces/$ns/relays", take().path)
            assertEquals(
                CloudRelay(
                    peerId = "12D3KooWpeer",
                    relayUrl = "https://relay.example",
                    executorAccount = executor,
                    nodeKey = null,
                    status = "active",
                    authorshipReady = true,
                    teeRole = "RelayTee",
                    canExecute = true,
                    lastSeenAt = "t1",
                    confirmedAt = "t0",
                    admission = "confirmed",
                    admissionHint = "in",
                    join = null,
                ),
                relay,
            )
        }

    @Test
    fun `reads the node key under either spelling`() =
        runBlocking {
            enqueue("""{"relays":[${relayRow(""","node_public_key":"${"ab".repeat(32)}"""")}]}""")
            assertEquals("ab".repeat(32), signedIn().getNamespaceRelays(ns).single().nodeKey)
        }

    @Test
    fun `carries why a relay is not joining, and reads a malformed join as none`() =
        runBlocking {
            val join =
                ""","join":{"state":"refused","attempts":3,"refusals":[{"peer":"12D3KooWadmin","reason":"was removed from group"}],""" +
                    """"error":null,"reported_at":"2026-01-01T00:00:00"}"""
            enqueue("""{"relays":[${relayRow(join)},${relayRow(""","join":["nope"]""")}]}""")
            val (first, second) = signedIn().getNamespaceRelays(ns)
            assertEquals(
                CloudRelayJoin("refused", 3, listOf(CloudRelayRefusal("12D3KooWadmin", "was removed from group")), null, "2026-01-01T00:00:00"),
                first.join,
            )
            assertNull(second.join)
        }

    @Test
    fun `takes the cloud's can_execute over the grant, and falls back to it on an older cloud`() =
        runBlocking {
            enqueue(
                """{"relays":[{"peer_id":"a","relay_url":"https://r","executor_account":"$executor","authorship_ready":true,"tee_role":"ReadOnlyTee","can_execute":false},""" +
                    """{"peer_id":"b","relay_url":"https://r","executor_account":"$executor","authorship_ready":false,"tee_role":"RelayTee","can_execute":true},""" +
                    """{"peer_id":"c","relay_url":"https://r","executor_account":"$executor","authorship_ready":true},""" +
                    """{"peer_id":"d","relay_url":null,"executor_account":"$executor","authorship_ready":true}]}""",
            )
            val relays = signedIn().getNamespaceRelays(ns)
            assertEquals(listOf(false, true, true, false), relays.map { it.canExecute })
            assertEquals(listOf("ReadOnlyTee", "RelayTee", null, null), relays.map { it.teeRole })
        }

    @Test
    fun `an owned namespace with no relays is an empty list`() =
        runBlocking {
            enqueue("{}")
            assertTrue(signedIn().getNamespaceRelays(ns).isEmpty())
        }

    @Test
    fun `findExecutingRelay skips a relay missing any precondition and picks the usable one`() =
        runBlocking {
            enqueue(
                """{"relays":[{"peer_id":"no-url","relay_url":null,"executor_account":"$executor","can_execute":true},""" +
                    """{"peer_id":"no-exec","relay_url":"https://r","executor_account":null,"can_execute":true},""" +
                    """{"peer_id":"replica","relay_url":"https://r","executor_account":"$executor","can_execute":false},""" +
                    "${relayRow()}]}",
            )
            assertEquals("12D3KooWpeer", signedIn().findExecutingRelay(ns)?.peerId)
            enqueue("""{"relays":[{"peer_id":"x","relay_url":"https://r","executor_account":"$executor","authorship_ready":false}]}""")
            assertNull(signedIn().findExecutingRelay(ns))
        }

    @Test
    fun `surfaces the cloud's 404 for a namespace this login does not own`() =
        runBlocking {
            enqueue("""{"detail":"not found"}""", status = 404)
            try {
                signedIn().getNamespaceRelays(ns)
                fail("expected 404")
            } catch (e: HttpException) {
                assertEquals(404, e.status)
            }
        }

    @Test
    fun `maps the account-wide machine view, keeping a stale or replica machine visible`() =
        runBlocking {
            enqueue(
                """{"machines":[{"peer_id":"12D3KooWpeer","relay_url":"https://relay.example","executor_account":"$executor","can_execute":true,""" +
                    """"namespaces":[{"namespace_id":"$ns","status":"active","authorship_ready":true,"tee_role":"RelayTee","fresh":true,""" +
                    """"confirmed_at":"2026-01-01T00:00:00","last_seen_at":"2026-01-01T00:01:00"}]},""" +
                    """{"peer_id":"p","relay_url":"https://relay.example","executor_account":"$executor","can_execute":false,""" +
                    """"namespaces":[{"namespace_id":"$ns","status":"active","authorship_ready":true,"tee_role":"ReadOnlyTee","fresh":false}]},""" +
                    """{"peer_id":"old","relay_url":"https://relay.example","executor_account":"$executor",""" +
                    """"namespaces":[{"namespace_id":"$ns","status":"active","authorship_ready":true}]}]}""",
            )
            val machines = signedIn().getMyMachines()
            assertEquals("/api/cloud/me/machines", take().path)
            assertEquals(
                CloudMachine(
                    "12D3KooWpeer",
                    "https://relay.example",
                    executor,
                    true,
                    listOf(CloudMachineNamespace(ns, "active", true, "RelayTee", true, "2026-01-01T00:00:00", "2026-01-01T00:01:00")),
                ),
                machines[0],
            )
            assertFalse(machines[1].canExecute)
            assertFalse(machines[1].namespaces[0].fresh)
            // An older cloud: derived from the URL, the account and a granted namespace.
            assertTrue(machines[2].canExecute)
            assertNull(machines[2].namespaces[0].teeRole)
        }

    // ---- HA ------------------------------------------------------------------------------

    @Test
    fun `claimNamespace forwards the proof object verbatim`() =
        runBlocking {
            enqueue("""{"ok":true}""")
            val proof =
                buildJsonObject {
                    put("signerPublicKey", "ab")
                    put("signedPayload", "cd")
                    put("signature", "ef")
                }
            signedIn().claimNamespace(ns, proof)
            val req = take()
            assertEquals("/api/cloud/namespaces/claim", req.path)
            assertEquals(
                buildJsonObject {
                    put("namespace_id", ns)
                    put("ownership_proof", proof)
                },
                req.json(),
            )
        }

    @Test
    fun `enable and disable post to the split endpoints, with admitter addrs only when given`() =
        runBlocking {
            repeat(3) { enqueue("{}") }
            val cloud = signedIn()
            val laptop = "/ip4/63.181.86.34/udp/4001/quic-v1/p2p/12D3KooWRelay/p2p-circuit/p2p/12D3KooWOwner"
            cloud.enableNamespaceHa(ns, listOf(laptop))
            cloud.enableNamespaceHa(ns)
            cloud.disableNamespaceHa(ns)
            val a = take()
            assertEquals("/api/cloud/namespaces/$ns/enable-ha", a.path)
            assertEquals(buildJsonObject { put("admitter_addrs", buildJsonArray { add(JsonPrimitive(laptop)) }) }, a.json())
            assertEquals(JsonObject(emptyMap()), take().json())
            val d = take()
            assertEquals("/api/cloud/namespaces/$ns/disable-ha", d.path)
            assertEquals(JsonObject(emptyMap()), d.json())
        }

    @Test
    fun `enableFoundedNamespaceHa posts the founder's session claim`() =
        runBlocking {
            enqueue("""{"status":"enabled"}""")
            val proof =
                signAccountOwnershipClaim(ns, "bb".repeat(32), "cc".repeat(32), "founder@example.com", "dd".repeat(40), SeedSigner("07".repeat(32)))
            signedIn().enableFoundedNamespaceHa(ns, proof)
            val req = take()
            assertEquals("/api/cloud/me/namespaces/$ns/enable-ha", req.path)
            val body = req.json()
            assertEquals(buildJsonArray { }, body["groups"])
            assertEquals(proof.toJson(), body["ownership_proof"])
        }

    @Test
    fun `builds the enable-ha and disable-ha page URLs`() {
        val cloud = CloudClient("https://cloud.example/")
        assertEquals(
            "https://cloud.example/enable-ha?group_id=g&context_id=c&redirect_url=mero-sample%3A%2F%2Fha",
            cloud.enableHaUrl(HaPageOptions("g", "c", "mero-sample://ha")),
        )
        assertEquals("https://cloud.example/disable-ha?group_id=g&context_id=c", cloud.disableHaUrl(HaPageOptions("g", "c")))
    }

    @Test
    fun `reads the HA page's return only on its redirect URL`() {
        assertNull(readHaPageCallback("mero-sample://other#x=1", "mero-sample://ha"))
        assertEquals(HaPageCallback(emptyMap()), readHaPageCallback("mero-sample://ha", "mero-sample://ha"))
        val back = readHaPageCallback("mero-sample://ha?group_id=g#error=quota", "mero-sample://ha")!!
        assertEquals("quota", back.error)
        assertEquals("g", back.params["group_id"])
    }

    // ---- account linking -----------------------------------------------------------------

    private val linkRoot = "42".repeat(32)
    private val linkAccount = "a71c3dd073939d81f972525e9788c6b958818e33f30674ef43b4184eef40aa50"
    private val linkRootPk = "2152f8d19b791d24453242e15f2eab6cb7cffa7b6a5ed30097960e069881db12"

    @Test
    fun `links an account with a challenge, a root signature and a proof, never the secret`() =
        runBlocking {
            enqueue("""{"nonce":"challenge-1","expires_at_ms":1700000300000}""")
            enqueue("""{"account_id":"$linkAccount","linked_at":"2026-01-01T00:00:00","already_linked":false}""")
            val link = signedIn("stored-token").linkAccount(linkRoot)
            assertEquals(CloudAccountLink(linkAccount, "2026-01-01T00:00:00", false), link)
            val challenge = take()
            assertEquals("/api/cloud/me/accounts/challenge", challenge.path)
            assertEquals("Bearer stored-token", challenge.getHeader("Authorization"))
            val post = take()
            assertEquals("/api/cloud/me/accounts", post.path)
            val raw = post.body.readUtf8()
            assertFalse(raw.contains(linkRoot))
            assertEquals(
                buildJsonObject {
                    put("account_id", linkAccount)
                    put("root_public_key", linkRootPk)
                    put("nonce", "challenge-1")
                    put("signature", signAccountLink(AccountLinkInput("challenge-1", rootSecret = linkRoot)))
                },
                Json.parseToJsonElement(raw),
            )
            assertEquals(linkAccount, accountRootFromSecret(linkRoot).accountId)
        }

    @Test
    fun `reports an idempotent re-link as success`() =
        runBlocking {
            enqueue("""{"nonce":"challenge-2"}""")
            enqueue("""{"account_id":"$linkAccount","linked_at":null,"already_linked":true}""")
            assertTrue(signedIn().linkAccount(SeedSigner(linkRoot)).alreadyLinked)
        }

    @Test
    fun `reads a missing link-challenge expiry as null`() =
        runBlocking {
            enqueue("""{"nonce":"challenge-9","expires_at_ms":1700000300000}""")
            enqueue("""{"nonce":"challenge-10"}""")
            val cloud = signedIn()
            assertEquals(AccountLinkChallenge("challenge-9", 1_700_000_300_000), cloud.getAccountLinkChallenge())
            assertNull(cloud.getAccountLinkChallenge().expiresAtMs)
        }

    @Test
    fun `posts a link proof assembled elsewhere verbatim`() =
        runBlocking {
            enqueue("""{"account_id":"$linkAccount","linked_at":null,"already_linked":false}""")
            val result = signedIn().submitAccountLink(AccountLinkProof(linkAccount, linkRootPk, "challenge-11", "c2lnbmF0dXJl"))
            assertEquals(linkAccount, result.accountId)
            assertEquals(
                buildJsonObject {
                    put("account_id", linkAccount)
                    put("root_public_key", linkRootPk)
                    put("nonce", "challenge-11")
                    put("signature", "c2lnbmF0dXJl")
                },
                take().json(),
            )
        }

    @Test
    fun `lists linked accounts with the plan limit, absent meaning unlimited`() =
        runBlocking {
            enqueue("""{"accounts":[{"account_id":"$linkAccount","linked_at":"2026-01-01T00:00:00","has_recovery_envelope":true}],"limit":3}""")
            enqueue("""{"accounts":[]}""")
            val cloud = signedIn()
            assertEquals(CloudLinkedAccounts(listOf(CloudLinkedAccount(linkAccount, "2026-01-01T00:00:00", true)), 3), cloud.getMyAccounts())
            assertEquals(CloudLinkedAccounts(emptyList(), null), cloud.getMyAccounts())
        }

    @Test
    fun `spends a grant anonymously, signing the grant itself`() =
        runBlocking {
            enqueue("""{"account_id":"$loginAccount","linked_at":"2026-09-17T00:00:00Z","already_linked":false}""")
            enqueue("""{"account_id":"$loginAccount"}""")
            val cloud = CloudClient(base, sessionToken = "someone.elses.token")
            val link = cloud.linkAccountWithGrant("sealed.token", rootSecret = loginRoot)
            assertEquals(loginAccount, link.accountId)
            assertFalse(link.alreadyLinked)
            val first = take()
            assertEquals("/api/cloud/accounts/link", first.path)
            assertNull(first.getHeader("Authorization"))
            val sent = first.json()
            assertEquals(setOf("grant", "root_public_key", "signature"), sent.keys)
            assertEquals(JsonPrimitive(loginRootPk), sent["root_public_key"])
            cloud.linkAccountWithGrant("grant.two", signer = SeedSigner(loginRoot))
            assertTrue(sent["signature"] != take().json()["signature"])
        }

    @Test
    fun `surfaces a refused grant`() =
        runBlocking {
            enqueue("""{"detail":"That grant has already been used."}""", status = 403)
            try {
                CloudClient(base).linkAccountWithGrant("spent.token", rootSecret = loginRoot)
                fail("expected 403")
            } catch (e: HttpException) {
                assertTrue(e.bodyText.contains("already been used"))
            }
        }

    @Test
    fun `refuses a grant spend with both or neither root form`() =
        runBlocking {
            for (args in listOf<Pair<String?, SeedSigner?>>(null to null, loginRoot to SeedSigner(loginRoot))) {
                try {
                    CloudClient(base).linkAccountWithGrant("g", args.first, args.second)
                    fail("accepted $args")
                } catch (_: IllegalArgumentException) {
                }
            }
            assertEquals(0, server.requestCount)
        }

    private val loginAccount = "ca7645ffd4d0621d00c6c88743aeace5797135ab298c49e77de066206090b778"
}
