package com.calimero.mero

import com.calimero.mero.admin.AccountSignDomains
import com.calimero.mero.admin.AccountSignWithRootRequest
import com.calimero.mero.admin.AccountSignWithRootResponseData
import com.calimero.mero.admin.ChangeNamespaceAdminRequest
import com.calimero.mero.admin.CreateContextIntentRequest
import com.calimero.mero.admin.CreateGroupInNamespaceRequest
import com.calimero.mero.admin.CreateGroupRequest
import com.calimero.mero.admin.CreateNamespaceResponseData
import com.calimero.mero.admin.DisableTeeAuthoringPolicyRequest
import com.calimero.mero.admin.GetTeeAdmissionPolicyResponseData
import com.calimero.mero.admin.GovernanceIntentRequest
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.GroupRoles
import com.calimero.mero.admin.IntentRelayInfo
import com.calimero.mero.admin.IssueOwnershipProofResponseData
import com.calimero.mero.admin.LinkAccountDeviceRequest
import com.calimero.mero.admin.LinkAccountDeviceResponseData
import com.calimero.mero.admin.ListGroupMembersResponseData
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.OwnerDeleteGroupRequest
import com.calimero.mero.admin.PresenceIntentRequest
import com.calimero.mero.admin.QueryContextRequest
import com.calimero.mero.admin.SealToAccountRequest
import com.calimero.mero.admin.SetTeeAdmissionPolicyRequest
import com.calimero.mero.admin.SetTeeAuthoringPolicyRequest
import com.calimero.mero.admin.TeeAdmissionMode
import com.calimero.mero.admin.TeeAttestRequest
import com.calimero.mero.admin.TeeAttestResponseData
import com.calimero.mero.admin.TeeRegistrationAttestRequest
import com.calimero.mero.admin.TransferOwnershipRequest
import com.calimero.mero.admin.WarrantNonceRouteUnavailableException
import com.calimero.mero.admin.WarrantNonceState
import com.calimero.mero.auth.ApiEnvelope
import com.calimero.mero.auth.GenerateClientKeyRequest
import com.calimero.mero.http.HttpException
import com.calimero.mero.rpc.ReadOnlyWriteRefusedException
import com.calimero.mero.rpc.RpcExecution
import com.calimero.mero.sse.ContextEvent
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.util.concurrent.TimeUnit

/**
 * What core moved between 0.11.0-rc.41 and rc.83.
 *
 * Breaking: `GET /certificate` is gone, `groupId` left `CreateGroupRequest`
 * (derived ids, `deny_unknown_fields`), and an absent subgroup `visibility` now
 * means **open** rather than restricted. Additive: root-guarded owner ops, the
 * delegated-execution surface (intent discovery, context / governance /
 * presence intents), account-root signing and device links, the TEE authoring
 * policy and the signed-release admission form, and new response fields.
 *
 * Same discipline as [Rc41WireShapeTest]: request assertions are on the **exact
 * key set**, and response assertions check both directions — the new field
 * decodes, and a body from a node predating it still decodes, to `null`. Where
 * core ships a wire fixture (`crates/server/primitives/fixtures/wire/` at
 * rc.83), the response tests decode those exact bytes from
 * `fixtures/rc83/` instead of a hand-written body.
 */
class Rc83WireShapeTest {
    private lateinit var server: MockWebServer
    private lateinit var mero: Mero

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
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

    private fun capture(
        response: String = "{}",
        block: suspend () -> Unit,
    ): RecordedRequest {
        server.enqueue(MockResponse().setBody(response).setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    private fun bodyOf(req: RecordedRequest): JsonObject {
        val body = req.body.clone().readUtf8()
        return if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
    }

    private fun keysOf(req: RecordedRequest): Set<String> = bodyOf(req).keys

    private fun fixture(name: String): String =
        checkNotNull(this::class.java.classLoader?.getResourceAsStream("fixtures/rc83/$name.json")) {
            "missing fixture $name"
        }.bufferedReader().use { it.readText() }

    private val proof = "ab".repeat(8)

    // ---- Removed / changed routes -----------------------------------------

    /** Ids are derived since rc.83, and the body denies unknown fields: no `groupId`. */
    @Test
    fun `createGroup carries no groupId`() {
        val req = capture { mero.admin.createGroup(CreateGroupRequest(applicationId = "app", name = "g")) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/groups", req.path)
        assertEquals(setOf("applicationId", "name"), keysOf(req))
    }

    /**
     * rc.83 flipped an absent `visibility` from restricted to open, so the SDK
     * names it every time — the same call makes the same subgroup on any node.
     */
    @Test
    fun `createGroupInNamespace always names a visibility, open by default`() {
        val req = capture { mero.admin.createGroupInNamespace("ns-1", CreateGroupInNamespaceRequest(groupName = "room")) }
        assertEquals(setOf("groupName", "visibility"), keysOf(req))
        assertEquals("open", bodyOf(req).getValue("visibility").jsonPrimitive.content)

        val restricted =
            capture {
                mero.admin.createGroupInNamespace(
                    "ns-1",
                    CreateGroupInNamespaceRequest(visibility = CreateGroupInNamespaceRequest.VISIBILITY_RESTRICTED),
                )
            }
        assertEquals("restricted", bodyOf(restricted).getValue("visibility").jsonPrimitive.content)
    }

    @Test
    fun `blob reads carry context_id when given`() {
        var req = capture { mero.admin.getBlob("blob-1", contextId = "ctx-1") }
        assertEquals("/admin-api/blobs/blob-1?context_id=ctx-1", req.path)
        req = capture { mero.admin.getBlobInfo("blob-1", contextId = "ctx-1") }
        assertEquals("HEAD", req.method)
        assertEquals("/admin-api/blobs/blob-1?context_id=ctx-1", req.path)
        req = capture { mero.admin.getBlob("blob-1") }
        assertEquals("/admin-api/blobs/blob-1", req.path)
    }

    @Test
    fun `member devices page with offset and limit only when asked`() {
        var req = capture("""{"members":[]}""") { mero.admin.listMemberDevices("g-1", offset = 10, limit = 5) }
        assertEquals("/admin-api/groups/g-1/member-devices?offset=10&limit=5", req.path)
        req = capture("""{"members":[]}""") { mero.admin.listGroupMemberDevices("g-1") }
        assertEquals("/admin-api/groups/g-1/member-devices", req.path)
    }

    // ---- New request bodies ------------------------------------------------

    @Test
    fun `queryContext posts method and argsJson`() {
        val req = capture { mero.admin.queryContext("ctx-1", QueryContextRequest("get", buildJsonObject { put("k", 1) })) }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/contexts/ctx-1/query", req.path)
        assertEquals(setOf("method", "argsJson"), keysOf(req))
    }

    @Test
    fun `signWithAccountRoot posts domain and payload, matching core's fixture`() {
        val req =
            capture {
                mero.admin.signWithAccountRoot(AccountSignWithRootRequest(AccountSignDomains.ACCOUNT_LOGIN, "00ff"))
            }
        assertEquals("/admin-api/account/sign-with-root", req.path)
        val core = Json.parseToJsonElement(fixture("account_sign_with_root.req")).jsonObject
        assertEquals(core.keys, keysOf(req))
        assertEquals(core.getValue("domain").jsonPrimitive.content, AccountSignDomains.ACCOUNT_LOGIN)
    }

    @Test
    fun `linkAccountDevice posts credential and scope, matching core's fixture`() {
        val req = capture { mero.admin.linkAccountDevice("ns-1", LinkAccountDeviceRequest("02aa", "bb")) }
        assertEquals("/admin-api/namespaces/ns-1/account/link-device", req.path)
        assertEquals(Json.parseToJsonElement(fixture("namespaces_link_device.req")), bodyOf(req))
    }

    @Test
    fun `sealToAccount posts plaintext only`() {
        val req = capture { mero.admin.sealToAccount("g-1", "acct-1", SealToAccountRequest("00")) }
        assertEquals("/admin-api/groups/g-1/accounts/acct-1/seal", req.path)
        assertEquals(setOf("plaintext"), keysOf(req))
    }

    @Test
    fun `transferOwnership matches core's fixture and omits a null rootProof`() {
        val core = Json.parseToJsonElement(fixture("groups_transfer_ownership.req")).jsonObject
        val req =
            capture {
                mero.admin.transferOwnership(
                    "g-1",
                    TransferOwnershipRequest(
                        newOwner = core.getValue("newOwner").jsonPrimitive.content,
                        rootProof = core.getValue("rootProof").jsonPrimitive.content,
                    ),
                )
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/groups/g-1/transfer-ownership", req.path)
        assertEquals(core, bodyOf(req))

        // An empty rootProof is a 400; an absent one lets a root-holding node sign.
        val bare = capture { mero.admin.transferOwnership("g-1", TransferOwnershipRequest(newOwner = "44")) }
        assertEquals(setOf("newOwner"), keysOf(bare))
    }

    @Test
    fun `changeNamespaceAdmin matches core's fixture`() {
        val core = Json.parseToJsonElement(fixture("namespaces_change_admin.req")).jsonObject
        val req =
            capture {
                mero.admin.changeNamespaceAdmin(
                    "ns-1",
                    ChangeNamespaceAdminRequest(
                        newAdmin = core.getValue("newAdmin").jsonPrimitive.content,
                        rootProof = core.getValue("rootProof").jsonPrimitive.content,
                    ),
                )
            }
        assertEquals("/admin-api/namespaces/ns-1/admin", req.path)
        assertEquals(core, bodyOf(req))
    }

    @Test
    fun `ownerDeleteGroup posts rootProof or an empty object`() {
        val core = Json.parseToJsonElement(fixture("groups_owner_delete.req")).jsonObject
        val req =
            capture {
                mero.admin.ownerDeleteGroup("g-1", OwnerDeleteGroupRequest(core.getValue("rootProof").jsonPrimitive.content))
            }
        assertEquals("POST", req.method)
        assertEquals("/admin-api/groups/g-1/owner-delete", req.path)
        assertEquals(core, bodyOf(req))

        val bare = capture { mero.admin.ownerDeleteGroup("g-1") }
        assertEquals(emptySet<String>(), keysOf(bare))
    }

    @Test
    fun `tee authoring policy PUTs allowedMrtd and DELETEs with or without a proof`() {
        val put = capture { mero.admin.setTeeAuthoringPolicy("g-1", SetTeeAuthoringPolicyRequest(listOf("aa"))) }
        assertEquals("PUT", put.method)
        assertEquals("/admin-api/groups/g-1/settings/tee-authoring-policy", put.path)
        assertEquals(setOf("allowedMrtd"), keysOf(put))

        val bare = capture { mero.admin.disableTeeAuthoringPolicy("g-1") }
        assertEquals("DELETE", bare.method)
        assertEquals(0L, bare.bodySize)

        val proven = capture { mero.admin.disableTeeAuthoringPolicy("g-1", DisableTeeAuthoringPolicyRequest(proof)) }
        assertEquals("DELETE", proven.method)
        assertEquals(setOf("rootProof"), keysOf(proven))
    }

    /** The signed-release arm sends no measurement list; core requires them empty. */
    @Test
    fun `tee admission policy signed-release form fits core's fixture`() {
        val req =
            capture {
                mero.admin.setTeeAdmissionPolicy(
                    "g-1",
                    SetTeeAdmissionPolicyRequest.signedRelease(
                        allowedProfiles = listOf("locked-read-only"),
                        minReleaseVersion = "2.3.72",
                        allowedTcbStatuses = listOf("UpToDate"),
                        mode = TeeAdmissionMode.RELAY,
                        rootProof = proof,
                    ),
                )
            }
        val core = Json.parseToJsonElement(fixture("groups_tee_admission_policy.req")).jsonObject
        // Every key sent is one core names; the empty lists and `false` it spells out
        // are `serde(default)`, so leaving them off says the same thing.
        assertTrue(core.keys.containsAll(keysOf(req)))
        assertEquals(setOf("allowedTcbStatuses", "signedRelease", "mode", "rootProof"), keysOf(req))
        assertEquals(core.getValue("signedRelease"), bodyOf(req).getValue("signedRelease"))

        // Core's own request decodes into the model, both arms' fields included.
        val decoded = json.decodeFromString(SetTeeAdmissionPolicyRequest.serializer(), fixture("groups_tee_admission_policy.req"))
        assertEquals("relay", decoded.mode)
        assertEquals(listOf("locked-read-only"), decoded.signedRelease?.allowedProfiles)
    }

    /** The rc.41 positional constructor still compiles and sends the measurement form. */
    @Test
    fun `tee admission policy measurement form sends every list`() {
        val req =
            capture {
                mero.admin.setTeeAdmissionPolicy(
                    "g-1",
                    SetTeeAdmissionPolicyRequest.measurements(
                        allowedMrtd = listOf("m"),
                        allowedRtmr1 = listOf("1"),
                        allowedRtmr2 = listOf("2"),
                        allowedRtmr3 = listOf("3"),
                        acceptMock = true,
                    ),
                )
            }
        assertEquals(setOf("allowedMrtd", "allowedRtmr1", "allowedRtmr2", "allowedRtmr3", "acceptMock"), keysOf(req))
    }

    @Test
    fun `teeAttest sends the new flags only when set`() {
        var req = capture { mero.admin.teeAttest(TeeAttestRequest(nonce = "n")) }
        assertEquals(setOf("nonce"), keysOf(req))
        req = capture { mero.admin.teeAttest(TeeAttestRequest(nonce = "n", bindTransportKey = true, includeCollateral = true)) }
        assertEquals(setOf("nonce", "bindTransportKey", "includeCollateral"), keysOf(req))
    }

    @Test
    fun `registration attest posts a nonce`() {
        val req = capture { mero.admin.teeRegistrationAttest(TeeRegistrationAttestRequest("00")) }
        assertEquals("/admin-api/tee/registration-attest", req.path)
        assertEquals(setOf("nonce"), keysOf(req))
    }

    @Test
    fun `context and governance intents post exactly their fields`() {
        var req =
            capture {
                mero.admin.createContextIntent("g-1", CreateContextIntentRequest("w", "p", JsonObject(emptyMap())))
            }
        assertEquals("/admin-api/groups/g-1/context-intents", req.path)
        assertEquals(setOf("warrant", "authorProof", "initArgs"), keysOf(req))

        req = capture { mero.admin.getContextIntentRelay("g-1", author = "acct") }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/groups/g-1/context-intents?author=acct", req.path)

        req = capture { mero.admin.governanceIntent("g-1", GovernanceIntentRequest("w", "p", "op")) }
        assertEquals("/admin-api/groups/g-1/governance-intents", req.path)
        assertEquals(setOf("warrant", "authorProof", "op"), keysOf(req))
    }

    /** `state` is always on the wire — `null` clears the slice, it is not "unchanged". */
    @Test
    fun `presence intent sends state even when null`() {
        val req =
            capture {
                mero.admin.presenceIntent("ctx-1", PresenceIntentRequest(null, seq = 3, sentAtMs = 9, signature = "s", authorProof = "p"))
            }
        assertEquals("/admin-api/contexts/ctx-1/presence-intents", req.path)
        val body = bodyOf(req)
        assertEquals(setOf("state", "seq", "sentAtMs", "signature", "authorProof"), body.keys)
        assertEquals(JsonNull, body.getValue("state"))
    }

    @Test
    fun `client key carries application_id and ttl_secs`() {
        val encoded =
            Json { explicitNulls = false }.encodeToString(
                GenerateClientKeyRequest.serializer(),
                GenerateClientKeyRequest(permissions = listOf("context:execute"), applicationId = "app", ttlSecs = 3600),
            )
        assertEquals(setOf("permissions", "application_id", "ttl_secs"), Json.parseToJsonElement(encoded).jsonObject.keys)
    }

    // ---- Capability helpers -------------------------------------------------

    @Test
    fun `CAN_AUTHOR_ON_BEHALF is bit 9`() {
        assertEquals(1L shl 9, Capabilities.CAN_AUTHOR_ON_BEHALF)
    }

    /** Read-modify-write: the bit is added, and `CAN_JOIN_OPEN_SUBGROUPS` survives. */
    @Test
    fun `openToDelegatedExecution adds the bit to the default mask`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"groupId":"g","appKey":"a","targetApplicationId":"t","memberCount":1,""" +
                        """"contextCount":0,"defaultCapabilities":4,"subgroupVisibility":"open"}}""",
                ),
            )
            server.enqueue(MockResponse().setBody("{}"))
            val change = mero.admin.openToDelegatedExecution("g")
            assertTrue(change.changed)
            assertEquals(4L or 512L, change.capabilities)
            server.takeRequest()
            val put = server.takeRequest()
            assertEquals("PUT", put.method)
            assertEquals(
                516,
                bodyOf(put)
                    .getValue("defaultCapabilities")
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `grantAuthorship is a no-op when the member holds the bit`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"data":{"capabilities":513}}"""))
            val change = mero.admin.grantAuthorship("g", "acct")
            assertFalse(change.changed)
            assertEquals(1, server.requestCount)
        }

    // ---- New response fields (core's fixtures) -----------------------------

    @Test
    fun `group info carries namespaceId and ownerOpCounter, and older nodes decode`() {
        val info = json.decodeFromString(ApiEnvelope.serializer(GroupInfo.serializer()), fixture("groups_group_info.res")).data!!
        assertEquals("d".repeat(64), info.namespaceId)
        assertEquals(4L, info.ownerOpCounter)

        val older =
            json.decodeFromString(
                GroupInfo.serializer(),
                """{"groupId":"g","appKey":"a","targetApplicationId":"t","memberCount":1,"contextCount":0,""" +
                    """"defaultCapabilities":0,"subgroupVisibility":"open"}""",
            )
        assertNull(older.namespaceId)
        assertNull(older.ownerOpCounter)
    }

    @Test
    fun `namespaces carry founding and heldOps`() {
        val ns = json.decodeFromString(ApiEnvelope.serializer(Namespace.serializer()), fixture("namespaces_get.res")).data!!
        assertEquals("3".repeat(64), ns.founding?.founderAccountId)
        assertEquals(1, ns.heldOps?.ops?.size)
        assertEquals(
            "6".repeat(64),
            ns.heldOps
                ?.ops
                ?.first()
                ?.groupId,
        )

        val list = json.decodeFromString(ApiEnvelope.serializer(kotlinx.serialization.builtins.ListSerializer(Namespace.serializer())), fixture("namespaces_list.res")).data!!
        assertNull("heldOps is skipped when nothing is held", list.single().heldOps)

        val created = json.decodeFromString(CreateNamespaceResponseData.serializer(), """{"namespaceId":"n"}""")
        assertNull(created.founding)
    }

    @Test
    fun `intent relay info decodes core's fixture`() {
        val info = json.decodeFromString(ApiEnvelope.serializer(IntentRelayInfo.serializer()), fixture("contexts_intent_relay.res")).data!!
        assertFalse(info.canAuthorOnBehalf)
        assertEquals("1.2.0", info.releaseVersion)
        assertEquals(64, info.releaseBytecodeId.length)
        assertEquals(64, info.executorKey.length)
        assertEquals("1f2e3d4c5b6a798807162534435261708f9eadbccddeeff00112233445566778", info.grantedOnGroupId)
    }

    @Test
    fun `getIntentRelay GETs the intents route`() {
        val req = capture(fixture("contexts_intent_relay.res")) { mero.admin.getIntentRelay("ctx-1") }
        assertEquals("GET", req.method)
        assertEquals("/admin-api/contexts/ctx-1/intents", req.path)
    }

    /** Core sends this flat; the SDK's existing envelope-or-flat fallback still applies. */
    @Test
    fun `tee admission policy response decodes the signed-release fixture`() =
        runBlocking {
            server.enqueue(MockResponse().setBody(fixture("groups_tee_admission_policy.res")))
            val policy = mero.admin.getTeeAdmissionPolicy("g-1")
            assertEquals(true, policy.enabled)
            assertEquals(TeeAdmissionMode.RELAY, policy.mode)
            assertEquals("2.3.72", policy.signedRelease?.minReleaseVersion)

            val older =
                json.decodeFromString(
                    GetTeeAdmissionPolicyResponseData.serializer(),
                    """{"allowedMrtd":["m"],"allowedRtmr0":[],"allowedRtmr1":[],"allowedRtmr2":[],""" +
                        """"allowedRtmr3":[],"allowedTcbStatuses":[],"acceptMock":false}""",
                )
            assertNull(older.mode)
            assertNull(older.signedRelease)
        }

    @Test
    fun `members may be RelayTee`() {
        val members = json.decodeFromString(ListGroupMembersResponseData.serializer(), fixture("groups_members.res"))
        assertEquals(GroupRoles.RELAY_TEE, members.members.last().role)
    }

    @Test
    fun `sign-with-root and link-device responses decode core's fixtures`() {
        val signed =
            json.decodeFromString(ApiEnvelope.serializer(AccountSignWithRootResponseData.serializer()), fixture("account_sign_with_root.res")).data!!
        assertEquals(64, signed.rootPublicKey.length)
        val linked =
            json.decodeFromString(ApiEnvelope.serializer(LinkAccountDeviceResponseData.serializer()), fixture("namespaces_link_device.res")).data!!
        assertFalse(linked.alreadyBound)
    }

    @Test
    fun `tee attest response carries transport key and collateral, or neither`() {
        val quote =
            """"quote":{"header":{"version":4,"attestationKeyType":2,"teeType":129,"qeVendorId":"q","userData":"u"},""" +
                """"body":{"tdxVersion":"1","teeTcbSvn":"s","mrseam":"a","mrsignerseam":"b","seamattributes":"c",""" +
                """"tdattributes":"d","xfam":"e","mrtd":"f","mrconfigid":"g","mrowner":"h","mrownerconfig":"i",""" +
                """"rtmr0":"0","rtmr1":"1","rtmr2":"2","rtmr3":"3","reportdata":"r"},"signature":"s","attestationKey":"k"}"""
        val full =
            json.decodeFromString(
                TeeAttestResponseData.serializer(),
                """{"quoteB64":"q",$quote,"transportPublicKey":"tk","collateral":{"pck":"x"}}""",
            )
        assertEquals("tk", full.transportPublicKey)
        assertTrue(full.collateral is JsonObject)

        val older = json.decodeFromString(TeeAttestResponseData.serializer(), """{"quoteB64":"q",$quote}""")
        assertNull(older.transportPublicKey)
        assertNull(older.collateral)
    }

    @Test
    fun `typed ownership proof is flat and carries founding and credential`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"signerPublicKey":"pk","signedPayload":"cA==","signature":"cw==",""" +
                        """"founding":{"founderAccountId":"f","salt":"s"},"credential":"02aa"}""",
                ),
            )
            val proofData =
                mero.admin.issueNamespaceOwnershipProof(
                    "g-1",
                    com.calimero.mero.admin
                        .IssueNamespaceOwnershipProofRequest("aud", "sub", "00".repeat(16), 1),
                )
            assertEquals("02aa", proofData.credential)
            assertEquals("f", proofData.founding?.founderAccountId)
            val req = server.takeRequest()
            assertEquals(setOf("audience", "subject", "nonce", "expiresAtMs"), keysOf(req))

            val bare = json.decodeFromString(IssueOwnershipProofResponseData.serializer(), """{"signerPublicKey":"pk","signedPayload":"p","signature":"s"}""")
            assertNull(bare.founding)
        }

    // ---- Warrant nonces ------------------------------------------------------

    /** A u64 past 2^53 must survive: a rounded nonce looks ordinary and is refused forever. */
    @Test
    fun `warrant nonce parses u64 digits exactly`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"data":{"contextId":"c","authorDeviceKey":"k","seen":true,""" +
                        """"highWaterNonce":18446744073709551614,"windowWidth":64,"nextNonce":18446744073709551615}}""",
                ),
            )
            val state = mero.admin.getWarrantNonce("c", "k")
            assertTrue(state is WarrantNonceState.Open)
            assertEquals(ULong.MAX_VALUE, (state as WarrantNonceState.Open).nextNonce)
            assertEquals(ULong.MAX_VALUE - 1uL, state.highWaterNonce)
            assertEquals("/admin-api/contexts/c/warrant-nonce/k", server.takeRequest().path)
        }

    @Test
    fun `an absent nextNonce is exhaustion`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"data":{"contextId":"c","authorDeviceKey":"k","seen":true,"windowWidth":64}}"""))
            val state = mero.admin.getWarrantNonceAsAuthor("c", "proof")
            assertTrue(state is WarrantNonceState.Exhausted)
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals(setOf("authorProof"), keysOf(req))
        }

    /** rc.83 does not serve the route; a 404 says so in its own type, not as "not found". */
    @Test
    fun `a 404 on warrant nonce is route unavailable`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))
            try {
                mero.admin.getWarrantNonce("c", "k")
                fail("expected WarrantNonceRouteUnavailableException")
            } catch (e: WarrantNonceRouteUnavailableException) {
                assertTrue(e.path.endsWith("/warrant-nonce/k"))
            }
        }

    // ---- Errors, RPC, events ------------------------------------------------

    /** Method errors on `/query` and `/intents` carry JSON-RPC's `type` / `data` beside `error`. */
    @Test
    fun `typed refusals surface type and data`() =
        runBlocking {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"function call error: nope","type":"FunctionCallError","data":"nope"}"""),
            )
            try {
                mero.admin.queryContext("c", QueryContextRequest("get", JsonObject(emptyMap())))
                fail("expected HttpException")
            } catch (e: HttpException) {
                assertEquals(400, e.status)
                assertEquals("FunctionCallError", e.errorType)
                assertEquals(JsonPrimitive("nope"), e.errorData)
                assertEquals("function call error: nope", e.errorMessage)
            }
        }

    @Test
    fun `a plain error body has a message and no type`() {
        val e = HttpException(409, """{"error":"stale owner-op counter"}""", emptyMap(), "u")
        assertEquals("stale owner-op counter", e.errorMessage)
        assertNull(e.errorType)
        assertNull(HttpException(502, "<html>", emptyMap(), "u").errorMessage)
    }

    @Test
    fun `ReadOnlyWriteRefused maps to its own exception`() =
        runBlocking {
            server.enqueue(
                MockResponse().setBody(
                    """{"jsonrpc":"2.0","id":1,"error":{"type":"ReadOnlyWriteRefused","data":{"context_id":"ctx-9"}}}""",
                ),
            )
            try {
                mero.rpc.executeRaw("ctx-9", "set")
                fail("expected ReadOnlyWriteRefusedException")
            } catch (e: ReadOnlyWriteRefusedException) {
                assertEquals("ctx-9", e.contextId)
                assertEquals(ReadOnlyWriteRefusedException.TYPE, e.type)
            }
        }

    @Test
    fun `executeWithMetadata names the node transport`() =
        runBlocking {
            server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"output":7}}"""))
            val result = mero.rpc.executeWithMetadata<Int>("c", "get")
            assertEquals(RpcExecution(7, RpcExecution.TRANSPORT_NODE), result)
        }

    @Test
    fun `presence events expose the relayed account`() {
        val relayed =
            ContextEvent(
                "c",
                ContextEvent.KIND_EPHEMERAL,
                Json.parseToJsonElement("""{"type":"Ephemeral","data":{"author":"a","account":"acct"}}"""),
            )
        assertEquals("acct", relayed.presenceAccount)
        val own = ContextEvent("c", ContextEvent.KIND_EPHEMERAL, Json.parseToJsonElement("""{"data":{"author":"a"}}"""))
        assertNull(own.presenceAccount)
    }
}
