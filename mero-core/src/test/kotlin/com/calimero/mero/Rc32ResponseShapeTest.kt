package com.calimero.mero

import com.calimero.mero.admin.AccountDevices
import com.calimero.mero.admin.GroupInfo
import com.calimero.mero.admin.MemberDevices
import com.calimero.mero.admin.Namespace
import com.calimero.mero.admin.NodeIdentity
import com.calimero.mero.auth.ApiEnvelope
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode **real captured** `merod 0.11.0-rc.32` bodies with the SDK's own models.
 *
 * `ignoreUnknownKeys` covers a field core ADDS. It does nothing for one core
 * REMOVES: a model that still declares it required throws `MissingFieldException`
 * and the whole call fails. That is the failure mode these fixtures exist to
 * catch, and it is not catchable with a fixture written to match the model.
 *
 * See `fixtures/README.md` for how to re-capture them.
 */
class Rc32ResponseShapeTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun fixture(name: String): String =
        checkNotNull(this::class.java.classLoader?.getResourceAsStream("fixtures/$name.json")) {
            "fixtures/$name.json missing"
        }.bufferedReader().readText()

    /** `upgradePolicy` is gone (core#3485). Declared required, this threw on every list. */
    @Test
    fun `a real namespace list decodes without upgradePolicy`() {
        val body = fixture("namespaces-rc32")
        assertTrue("the fixture must be a body core really sends", !body.contains("upgradePolicy"))

        val namespaces = json.decodeFromString<ApiEnvelope<List<Namespace>>>(body).data
        assertNotNull(namespaces)
        val first = namespaces!!.first()
        assertEquals(64, first.namespaceId.length)
        assertEquals("appVersion arrived where upgradePolicy left", "0.11.0-rc.32", first.appVersion)
    }

    /** Same removal on the group side, plus the new `groupStateHash`. */
    @Test
    fun `a real group info decodes without upgradePolicy`() {
        val body = fixture("group-info-rc32")
        assertTrue(!body.contains("upgradePolicy"))

        val group = json.decodeFromString<ApiEnvelope<GroupInfo>>(body).data
        assertNotNull(group)
        assertEquals(64, group!!.groupId.length)
        assertEquals(64, group.groupStateHash?.length)
    }

    /** The route that replaced the deleted `GET /namespaces/{id}/identity`. */
    @Test
    fun `node identity decodes, including rc32's holdsAccountRoot`() {
        val identity = json.decodeFromString<ApiEnvelope<NodeIdentity>>(fixture("node-identity-rc32")).data
        assertNotNull(identity)
        assertEquals(64, identity!!.accountId.length)
        assertEquals(64, identity.publicKey.length)
        assertEquals(64, identity.accountRootPublicKey.length)
        assertEquals(true, identity.holdsAccountRoot)
    }

    /**
     * ⚠️ These two are FLAT — no `data` envelope — unlike every neighbouring route.
     * Decoding them as `ApiEnvelope` yields `data == null` and reads as "empty",
     * which is why this asserts the flat shape explicitly.
     */
    @Test
    fun `account devices and member devices are flat, not enveloped`() {
        val devicesBody = fixture("account-devices-rc32")
        assertNull(
            "if this ever gains a data envelope, the flat decode below is wrong",
            json.decodeFromString<ApiEnvelope<AccountDevices>>(devicesBody).data,
        )
        assertNotNull(json.decodeFromString<AccountDevices>(devicesBody).devices)

        val members = json.decodeFromString<MemberDevices>(fixture("member-devices-rc32")).members
        assertTrue(members.isNotEmpty())
        assertEquals(64, members.first().account.length)
        assertEquals(
            64,
            members
                .first()
                .devices
                .first()
                .deviceId.length,
        )
    }
}
