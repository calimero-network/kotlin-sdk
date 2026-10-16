package com.calimero.mero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilitiesTest {
    @Test
    fun `hasCap detects a set bit`() {
        val mask = Capabilities.CAN_INVITE_MEMBERS or Capabilities.MANAGE_MEMBERS
        assertTrue(Capabilities.hasCap(mask, Capabilities.CAN_INVITE_MEMBERS))
        assertTrue(Capabilities.hasCap(mask, Capabilities.MANAGE_MEMBERS))
        assertFalse(Capabilities.hasCap(mask, Capabilities.MANAGE_APPLICATION))
    }

    @Test
    fun `withCap and withoutCap are inverses`() {
        var mask = 0L
        mask = Capabilities.withCap(mask, Capabilities.CAN_CREATE_CONTEXT)
        assertTrue(Capabilities.hasCap(mask, Capabilities.CAN_CREATE_CONTEXT))
        mask = Capabilities.withoutCap(mask, Capabilities.CAN_CREATE_CONTEXT)
        assertFalse(Capabilities.hasCap(mask, Capabilities.CAN_CREATE_CONTEXT))
        assertEquals(0L, mask)
    }

    @Test
    fun `bit values match core assignment`() {
        assertEquals(1L, Capabilities.CAN_CREATE_CONTEXT)
        assertEquals(256L, Capabilities.CAN_MANAGE_METADATA)
    }
}

/** Port of the preset / capMask / capNames cases in mero-js `src/capabilities.test.ts`. */
class CapabilityPresetsTest {
    @Test
    fun `names every assigned bit in bit order`() {
        assertEquals(
            listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L),
            Capabilities.BY_NAME.values.toList(),
        )
        assertEquals("CAN_CREATE_CONTEXT", Capabilities.BY_NAME.keys.first())
        assertEquals("CAN_AUTHOR_ON_BEHALF", Capabilities.BY_NAME.keys.last())
    }

    @Test
    fun `presets are the masks apps already pass by number`() {
        assertEquals(
            mapOf("NONE" to 0L, "JOIN_ONLY" to 4L, "CONTRIBUTOR" to 7L, "COLLABORATOR" to 231L),
            Capabilities.PRESETS,
        )
        for (mask in Capabilities.PRESETS.values) {
            assertFalse(Capabilities.hasCap(mask, Capabilities.CAN_AUTHOR_ON_BEHALF))
        }
    }

    @Test
    fun `capMask combines names and bit values`() {
        assertEquals(0L, Capabilities.capMask())
        assertEquals(3L, Capabilities.capMask("CAN_CREATE_CONTEXT", "CAN_INVITE_MEMBERS"))
        assertEquals(1L, Capabilities.capMask(Capabilities.CAN_CREATE_CONTEXT, "CAN_CREATE_CONTEXT"))
        assertEquals(39L, Capabilities.capMask(Capabilities.Presets.CONTRIBUTOR, "CAN_CREATE_SUBGROUP"))
        assertEquals(0x8000_0000L, Capabilities.capMask(1L shl 31))
        assertEquals(0xFFFF_FFFFL, Capabilities.capMask(-1L))
    }

    @Test
    fun `capMask refuses an unknown name`() {
        for (name in listOf("CAN_CREATE_CONTEXTS", "toString", "")) {
            val e = runCatching { Capabilities.capMask(name) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException && e.message!!.contains("unknown capability"))
        }
    }

    @Test
    fun `capNames lists the assigned bits in a mask, in bit order`() {
        assertEquals(emptyList<String>(), Capabilities.capNames(0))
        assertEquals(
            listOf(
                "CAN_CREATE_CONTEXT",
                "CAN_INVITE_MEMBERS",
                "CAN_JOIN_OPEN_SUBGROUPS",
                "CAN_CREATE_SUBGROUP",
                "CAN_DELETE_SUBGROUP",
                "CAN_MANAGE_VISIBILITY",
            ),
            Capabilities.capNames(Capabilities.Presets.COLLABORATOR),
        )
        assertEquals(
            listOf("CAN_AUTHOR_ON_BEHALF"),
            Capabilities.capNames(Capabilities.withCap(Capabilities.CAN_AUTHOR_ON_BEHALF, 1L shl 31)),
        )
        assertEquals(231L, Capabilities.capMask(*Capabilities.capNames(Capabilities.Presets.COLLABORATOR).toTypedArray()))
    }
}
