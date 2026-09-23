package com.calimero.mero

import com.calimero.mero.admin.AccountDevices
import com.calimero.mero.admin.DeviceScope
import com.calimero.mero.admin.GetContextIdentitiesResponseData
import com.calimero.mero.admin.LabelDeviceRequest
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.admin.RescopeDeviceRequest
import com.calimero.mero.storage.MemoryTokenStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What core moved between 0.11.0-rc.38 and rc.41.
 *
 * Unlike the rc.38 step, which closed 37 request bodies at once, rc.38 → rc.41 is
 * **additive on the admin surface**: no existing request struct lost, renamed or
 * closed a field. What it added is two routes and three response fields, and the
 * two new routes arrived `deny_unknown_fields` from birth — so the same assertion
 * discipline applies to them from the first commit rather than after the first
 * outage.
 *
 * Every request assertion here is therefore on the **exact key set**, never
 * `contains`. A present-key assertion passes just as happily with a fatal key
 * beside it; that is precisely how `requester` rode eighteen bodies undetected
 * until rc.38 turned it into a 400.
 *
 * The response assertions check both directions of the compatibility promise:
 * a field core added must decode, and a body from a node predating it — which
 * omits the field entirely, because core skips rather than nulls it — must still
 * decode, to `null`.
 */
class Rc41WireShapeTest {
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

    private fun capture(block: suspend () -> Unit): RecordedRequest {
        server.enqueue(MockResponse().setBody("{}").setHeader("Content-Type", "application/json"))
        runCatching { runBlocking { block() } }
        return server.takeRequest(2, TimeUnit.SECONDS) ?: error("no request captured")
    }

    /** Every key actually written to the wire, in the order-independent form worth asserting. */
    private fun keysOf(req: RecordedRequest): Set<String> {
        val body = req.body.readUtf8()
        if (body.isBlank()) return emptySet()
        return Json.parseToJsonElement(body).jsonObject.keys
    }

    // ---- New routes: PUT .../scope and PUT .../label -----------------------

    /**
     * `RescopeDeviceApiRequest` is `{scope}` — and it is a **PUT**, because it
     * replaces rather than accumulates. That is the whole difference from
     * `relink`, which is add-only and can therefore never take an application
     * away.
     */
    @Test
    fun `rescopeDevice PUTs scope and nothing else`() {
        val req =
            capture {
                mero.admin.rescopeDevice("dev-1", RescopeDeviceRequest(scope = DeviceScope.All))
            }
        assertEquals("PUT", req.method)
        assertEquals("/admin-api/account/devices/dev-1/scope", req.path)
        assertEquals(setOf("scope"), keysOf(req))
    }

    /**
     * Serde's externally-tagged enum: a unit variant is the bare **string**
     * `"all"`, not `{"all":null}` and not `{"type":"all"}`. The route denies
     * unknown fields, so a discriminator beside it would be a 422 for the whole
     * call.
     */
    @Test
    fun `an All scope is the bare string all`() {
        val req =
            capture {
                mero.admin.rescopeDevice("dev-1", RescopeDeviceRequest(scope = DeviceScope.All))
            }
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("all", body.getValue("scope").jsonPrimitive.content)
    }

    /** The newtype variant is `{"only":[…]}` — one key, named `only`, holding the ids. */
    @Test
    fun `an Only scope is an object keyed only`() {
        val req =
            capture {
                mero.admin.rescopeDevice(
                    "dev-1",
                    RescopeDeviceRequest(scope = DeviceScope.Only(listOf("app-a", "app-b"))),
                )
            }
        val scope =
            Json
                .parseToJsonElement(req.body.readUtf8())
                .jsonObject
                .getValue("scope")
                .jsonObject
        assertEquals(setOf("only"), scope.keys)
        assertEquals(
            listOf("app-a", "app-b"),
            scope.getValue("only").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    /**
     * An empty `only` is NOT "every application" — the node answers 400
     * `ScopeReplacementEmpty`. It still has to reach the wire as an empty array
     * rather than being quietly dropped into `"all"`, which would be the widest
     * possible ask from the narrowest possible slip.
     */
    @Test
    fun `an empty Only stays an empty list on the wire`() {
        val req =
            capture {
                mero.admin.rescopeDevice("dev-1", RescopeDeviceRequest(scope = DeviceScope.Only(emptyList())))
            }
        val scope =
            Json
                .parseToJsonElement(req.body.readUtf8())
                .jsonObject
                .getValue("scope")
                .jsonObject
        assertEquals(0, scope.getValue("only").jsonArray.size)
    }

    /** `LabelDeviceApiRequest` is `{label}`, on a PUT. Nothing else is accepted. */
    @Test
    fun `labelDevice PUTs label and nothing else`() {
        val req = capture { mero.admin.labelDevice("dev-1", LabelDeviceRequest(label = "My phone")) }
        assertEquals("PUT", req.method)
        assertEquals("/admin-api/account/devices/dev-1/label", req.path)
        assertEquals(setOf("label"), keysOf(req))
    }

    /** A scope round-trips through its own serializer, both variants. */
    @Test
    fun `a device scope round-trips`() {
        for (scope in listOf(DeviceScope.All, DeviceScope.Only(listOf("app-a")))) {
            val encoded = json.encodeToString(RescopeDeviceRequest.serializer(), RescopeDeviceRequest(scope))
            assertEquals(scope, json.decodeFromString(RescopeDeviceRequest.serializer(), encoded).scope)
        }
    }

    // ---- New response fields ----------------------------------------------

    /**
     * `identitiesOf` says which reading `identities-owned` gave, because since
     * rc.41 the same call answers a different question for a node-owner session
     * than for a delegated one. A caller that infers the reading from its own
     * token is guessing.
     */
    @Test
    fun `context identities carry the reading they are`() {
        val decoded =
            json.decodeFromString(
                GetContextIdentitiesResponseData.serializer(),
                """{"identities":["aa"],"identitiesOf":"caller"}""",
            )
        assertEquals(GetContextIdentitiesResponseData.IDENTITIES_OF_CALLER, decoded.identitiesOf)
    }

    /** A node predating the field said nothing about it, and "nothing" must not be a guess. */
    @Test
    fun `context identities from an older node decode with a null reading`() {
        val decoded =
            json.decodeFromString(
                GetContextIdentitiesResponseData.serializer(),
                """{"identities":["aa"]}""",
            )
        assertNull(decoded.identitiesOf)
    }

    /** A reading a later core adds must decode, not throw — hence `String`, not an enum. */
    @Test
    fun `an unknown reading decodes rather than throwing`() {
        val decoded =
            json.decodeFromString(
                GetContextIdentitiesResponseData.serializer(),
                """{"identities":[],"identitiesOf":"somethingNewer"}""",
            )
        assertEquals("somethingNewer", decoded.identitiesOf)
    }

    /**
     * `revokedFrom` on the node identity is how a node learns its own device was
     * disowned. Core skips the key rather than sending null, so both shapes have
     * to decode.
     */
    @Test
    fun `node identity carries revokedFrom, and its absence is null`() {
        val base = """"accountId":"aa","publicKey":"bb","accountRootPublicKey":"cc""""
        val revoked =
            json.decodeFromString(
                NodeIdentity.serializer(),
                """{$base,"revokedFrom":{"accountId":"dd","deviceId":"ee"}}""",
            )
        assertEquals("dd", revoked.revokedFrom?.accountId)
        assertEquals("ee", revoked.revokedFrom?.deviceId)

        assertNull(json.decodeFromString(NodeIdentity.serializer(), """{$base}""").revokedFrom)
    }

    /** A device's replicated name, and a device that has none. */
    @Test
    fun `account devices carry a label, and its absence is null`() {
        val decoded =
            json.decodeFromString(
                AccountDevices.serializer(),
                """
                {"devices":[
                  {"deviceId":"d1","signingKey":"k1","label":"My phone"},
                  {"deviceId":"d2","signingKey":"k2"}
                ]}
                """.trimIndent(),
            )
        assertEquals("My phone", decoded.devices[0].label)
        assertNull(decoded.devices[1].label)
    }
}
