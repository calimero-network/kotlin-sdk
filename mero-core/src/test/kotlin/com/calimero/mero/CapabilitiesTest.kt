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
