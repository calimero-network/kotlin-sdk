package com.calimero.mero.account

import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.readU64le
import com.calimero.mero.http.MeroStateException
import com.calimero.mero.relay.ApplicationTarget
import com.calimero.mero.relay.GovernanceMemberRole
import com.calimero.mero.relay.GovernanceOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** mero-js `create-context.test.ts`, `create-context.found.test.ts` and `govern.ts`. */
class DelegatedGovernanceTest {
    private val rig = DelegatedRig()
    private val account = rig.account
    private val cloud = rig.node.cloud
    private val session = rig.session
    private val ns = DelegatedRig.NS
    private val app = "88".repeat(32)

    @After
    fun tearDown() = rig.close()

    private fun creations() = cloud.recorded("/context-intents").filter { it.method == "POST" }

    private fun governs() = cloud.recorded("/governance-intents").filter { it.method == "POST" }

    /** A warrant's nonce sits 8 + 8 + 64 bytes from its end: nonce ‖ notAfter ‖ signature. */
    private fun nonceOf(warrant: String): ULong {
        val bytes = Hex.decodeUnsized(warrant, "warrant")
        return readU64le(bytes, bytes.size - 80)
    }

    @Test
    fun `creates a context in the named group, spending nonce 0 and remembering the relay`() =
        runBlocking {
            val created =
                account.createDelegatedContext(
                    session,
                    CreateDelegatedContextRequest(ns, app, serviceName = "docs", contextSeed = "5e".repeat(32)),
                )
            val post = creations().single()
            assertEquals("/admin-api/groups/$ns/context-intents", post.path)
            assertEquals(0uL, nonceOf(post.bodyJson!!["warrant"]!!.jsonPrimitive.content))
            assertEquals(buildJsonObject { }, post.bodyJson!!["initArgs"])
            assertEquals(rig.base, account.relayForContext(session.account, created.contextId))
            assertEquals(rig.base, account.readRelayMap(session.account).namespaces[ns])
            // The context's writes start above the creation nonce.
            assertTrue(account.persistedNonces(rig.base).next() >= 1uL)
        }

    @Test
    fun `sends init arguments from the bytes a node request carries`() =
        runBlocking {
            val bytes = """{"a":1}""".encodeToByteArray().map { it.toInt() }
            account.createDelegatedContext(session, CreateDelegatedContextRequest(ns, app, initializationParams = bytes))
            assertEquals(buildJsonObject { put("a", 1) }, creations().single().bodyJson!!["initArgs"])
        }

    @Test
    fun `a private context is a restricted subgroup, its members, and the context inside it`() =
        runBlocking {
            val member = "ab".repeat(32)
            val created = account.createDelegatedPrivateContext(session, CreateDelegatedContextRequest(ns, app), listOf(member))
            val posts = governs()
            assertEquals("/admin-api/groups/$ns/governance-intents", posts[0].path)
            val op = posts[0].bodyJson!!["op"]!!.jsonPrimitive.content
            assertEquals("00", op.substring(0, 2))
            assertEquals(created.groupId, op.substring(2, 66))
            assertEquals("01", op.substring(130, 132)) // restricted
            assertEquals("/admin-api/groups/${created.groupId}/governance-intents", posts[1].path)
            assertEquals(Hex.encode(GovernanceOps.memberAddedOp(member, GovernanceMemberRole.Member).bytes), posts[1].bodyJson!!["op"]!!.jsonPrimitive.content)
            assertEquals("/admin-api/groups/${created.groupId}/context-intents", creations().single().path)
            assertEquals(ns, account.namespaceOfGroup(session, created.groupId))
        }

    @Test
    fun `governance nonces never go backwards and are floored at the clock`() =
        runBlocking {
            val before = System.currentTimeMillis().toULong()
            account.delegatedGovernance(session, ns, ns, GovernanceOps.groupMetadataSetOp(name = "a"))
            account.delegatedGovernance(session, ns, ns, GovernanceOps.groupMetadataSetOp(name = "b"))
            val (n1, n2) = governs().map { nonceOf(it.bodyJson!!["warrant"]!!.jsonPrimitive.content) }
            assertTrue(n1 >= before)
            assertTrue(n2 > n1)
        }

    @Test
    fun `a group whose namespace is unknown is refused before anything is signed`() =
        runBlocking {
            val e = runCatching { account.governGroup(session, "99".repeat(32), GovernanceOps.memberLeftOp(session.account)) }.exceptionOrNull()
            assertTrue(e is MeroStateException)
            assertTrue(governs().isEmpty())
            account.rememberGroupNamespace(session.account, "99".repeat(32), ns)
            account.governRoot(session, "99".repeat(32), GovernanceOps.groupDeletedOp("99".repeat(32)))
            assertEquals("/admin-api/groups/$ns/governance-intents", governs().single().path)
        }

    @Test
    fun `attests the relay at founding even with its key pinned, and remembers the namespace`() =
        runBlocking {
            account.pinRelayNodeKey(rig.base, cloud.nodeKey)
            val founded = account.foundDelegatedNamespace(session, application = ApplicationTarget(app, "com.calimero.chat", "1.0.0"))
            assertEquals(1, cloud.recorded("/admin-api/tee/attest").size)
            assertEquals(rig.base, account.readRelayMap(session.account).namespaces[founded.namespaceId])
            assertTrue(founded.haEnabled)
        }

    @Test
    fun `refuses to found through a relay whose attestation is refused, though its key is pinned`() =
        runBlocking {
            account.pinRelayNodeKey(rig.base, cloud.nodeKey)
            cloud.attestBindsWrongKey = true
            val e = runCatching { account.foundDelegatedNamespace(session) }.exceptionOrNull()!!
            assertTrue(e.message!!.contains("refused"))
            assertTrue(governs().isEmpty())
        }

    @Test
    fun `says to try again when the attestation did not answer`() =
        runBlocking {
            cloud.attestStatus = 503
            assertTrue(runCatching { account.foundDelegatedNamespace(session) }.exceptionOrNull()!!.message!!.contains("try again"))
        }

    @Test
    fun `learns the executor from a namespace it is in when the session names none`() =
        runBlocking {
            val bare = session.copy(executorAccount = null)
            assertTrue(runCatching { account.foundDelegatedNamespace(bare) }.exceptionOrNull()!!.message!!.contains("join a namespace"))
            cloud.executorKey = cloud.nodeKey
            account.rememberRelay(session.account, rig.base, namespaceId = ns)
            account.foundDelegatedNamespace(bare)
            assertEquals(1, cloud.recorded("/admin-api/groups/$ns/governance-intents").count { it.method == "GET" })
        }

    @Test
    fun `reports an unlinked account in mero-js's words and keeps the namespace`() =
        runBlocking {
            cloud.haRefusal = "account_not_linked"
            val founded = account.foundDelegatedNamespace(session)
            assertFalse(founded.haEnabled)
            assertEquals(HA_ACCOUNT_NOT_LINKED_MESSAGE, founded.haError)
            assertEquals(HA_ACCOUNT_NOT_LINKED_MESSAGE, HA_REFUSAL_MESSAGES["account_not_linked"])
        }

    @Test
    fun `the latest published version is the newest live one`() =
        runBlocking {
            fun bundle(
                v: String,
                yanked: Boolean = false,
            ) = buildJsonObject {
                put("package", "com.calimero.chat")
                put("signerId", "s")
                put("appVersion", v)
                put("yanked", yanked)
            }
            cloud.registryBundles += listOf(bundle("1.2.0"), bundle("1.10.0"), bundle("2.0.0", yanked = true))
            assertEquals("1.10.0", latestPublishedVersion(rig.base, "com.calimero.chat"))
            cloud.registryBundles.clear()
            assertNull(runCatching { latestPublishedVersion(rig.base, "com.calimero.chat") }.getOrNull())
        }
}
