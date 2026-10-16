package com.calimero.mero.presence

import com.calimero.mero.crypto.Ed25519
import com.calimero.mero.crypto.Hex
import com.calimero.mero.crypto.SeedSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pinned to mero-js `presence/statement.test.ts`, itself pinned to core's vector. */
class PresenceStatementTest {
    private val ctx = "11".repeat(32)
    private val seed = "22".repeat(32)

    @Test
    fun `matches core's pinned bytes`() {
        val author = Hex.encode(Ed25519.publicKey(Hex.decode(seed, "seed", 32)))
        val bytes =
            PresenceStatement.presenceStatementBytes(
                contextId = ctx,
                author = author,
                seq = 7,
                sentAtMs = 1_700_000_000_000,
                state = """{"typing":true}""".encodeToByteArray(),
            )
        assertEquals(CORE_VECTOR, Hex.encode(bytes))
    }

    @Test
    fun `the domain is the 19 raw bytes of calimero-presence-1`() {
        assertEquals("calimero/presence/1", PresenceStatement.PRESENCE_DOMAIN.decodeToString())
        assertEquals(19, PresenceStatement.PRESENCE_DOMAIN.size)
    }

    @Test
    fun `hashes a retract differently from an empty state`() {
        assertNotEquals(Hex.encode(PresenceStatement.stateHash(null)), Hex.encode(PresenceStatement.stateHash(ByteArray(0))))
    }

    @Test
    fun `sign produces a signature the device key verifies over the statement`() {
        val signer = SeedSigner(seed)
        val state = byteArrayOf(1, 2, 3)
        val sig = PresenceStatement.sign(signer, ctx, 9, 1_000, state)
        val statement = PresenceStatement.presenceStatementBytes(ctx, signer.publicKey, 9, 1_000, state)
        assertTrue(Ed25519.verify(Hex.decode(signer.publicKey, "pk", 32), Hex.decode(sig, "sig", 64), statement))
    }

    @Test
    fun `signedRequest fills a presence-intents body, null state for a retract`() {
        val signer = SeedSigner(seed)
        val set = PresenceStatement.signedRequest(signer, "aa", ctx, byteArrayOf(0x0f, 0x10), seq = 5, sentAtMs = 6)
        assertEquals("0f10", set.state)
        assertEquals(5, set.seq)
        assertEquals(6, set.sentAtMs)
        assertEquals("aa", set.authorProof)
        assertEquals(PresenceStatement.sign(signer, ctx, 5, 6, byteArrayOf(0x0f, 0x10)), set.signature)

        val retract = PresenceStatement.signedRequest(signer, "aa", ctx, null, seq = 7, sentAtMs = 8)
        assertNull(retract.state)
        assertEquals(PresenceStatement.sign(signer, ctx, 7, 8, null), retract.signature)
    }

    private companion object {
        /** core's `statement_vector_is_stable` (calimero-node-primitives, presence.rs). */
        const val CORE_VECTOR = "63616c696d65726f2f70726573656e63652f311111111111111111111111111111111111111111111111111111111111111111a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f007000000000000000068e5cf8b010000f25c119b355e48fcfc5c8acd6b7681d0d8dfe33f75ee6408aa162e13b05ca486"
    }
}
