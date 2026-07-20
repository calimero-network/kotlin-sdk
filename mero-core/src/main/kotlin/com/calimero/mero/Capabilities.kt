package com.calimero.mero

/**
 * Member capability bitmask constants — mirrors core's `MemberCapabilities`
 * (crates/context/config/src/lib.rs). The per-member value is a u32 bitmask. Core currently assigns
 * bits 0..=8 (the entries below); bits 9 and above are unassigned and may be claimed by a future
 * core release, so applications MUST NOT repurpose them.
 *
 * Pure port of mero-js `capabilities.ts`. The mask is carried as a [Long] to keep the full unsigned
 * 32-bit range positive on the JVM (Kotlin's `Int` is signed).
 */
object Capabilities {
    // Bit values 0..=8 (const val cannot contain a shl expression, so literals are used).
    const val CAN_CREATE_CONTEXT: Long = 1L // 1 shl 0
    const val CAN_INVITE_MEMBERS: Long = 2L // 1 shl 1
    const val CAN_JOIN_OPEN_SUBGROUPS: Long = 4L // 1 shl 2
    const val MANAGE_MEMBERS: Long = 8L // 1 shl 3
    const val MANAGE_APPLICATION: Long = 16L // 1 shl 4
    const val CAN_CREATE_SUBGROUP: Long = 32L // 1 shl 5
    const val CAN_DELETE_SUBGROUP: Long = 64L // 1 shl 6
    const val CAN_MANAGE_VISIBILITY: Long = 128L // 1 shl 7
    const val CAN_MANAGE_METADATA: Long = 256L // 1 shl 8

    private const val U32_MASK = 0xFFFF_FFFFL

    /** True if [mask] has every bit of [cap] set. */
    fun hasCap(mask: Long, cap: Long): Boolean {
        val capU32 = cap and U32_MASK
        return (mask and capU32) == capU32
    }

    /** [mask] with every bit of [cap] set (u32-normalized). */
    fun withCap(mask: Long, cap: Long): Long = (mask or cap) and U32_MASK

    /** [mask] with every bit of [cap] cleared (u32-normalized). */
    fun withoutCap(mask: Long, cap: Long): Long = (mask and cap.inv()) and U32_MASK
}
