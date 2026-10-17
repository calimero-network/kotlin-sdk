package com.calimero.mero

/**
 * Member capability bitmask constants — mirrors core's `MemberCapabilities`
 * (crates/context/config/src/lib.rs). The per-member value is a u32 bitmask. Core currently assigns
 * bits 0..=9 (the entries below); bits 10 and above are unassigned and may be claimed by a future
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

    /**
     * May relay members' writes under their signed warrants (delegated execution). Implied by
     * nothing — not membership, not admin — so every group is authorship-closed until an admin
     * grants it: per member, or in the group's default mask for members admitted later. See
     * `AdminApi.openToDelegatedExecution` and `AdminApi.grantAuthorship`.
     */
    const val CAN_AUTHOR_ON_BEHALF: Long = 512L // 1 shl 9

    private const val U32_MASK = 0xFFFF_FFFFL

    /** Every assigned bit by its core name, in bit order (mero-js `CAPABILITIES`). */
    val BY_NAME: Map<String, Long> =
        linkedMapOf(
            "CAN_CREATE_CONTEXT" to CAN_CREATE_CONTEXT,
            "CAN_INVITE_MEMBERS" to CAN_INVITE_MEMBERS,
            "CAN_JOIN_OPEN_SUBGROUPS" to CAN_JOIN_OPEN_SUBGROUPS,
            "MANAGE_MEMBERS" to MANAGE_MEMBERS,
            "MANAGE_APPLICATION" to MANAGE_APPLICATION,
            "CAN_CREATE_SUBGROUP" to CAN_CREATE_SUBGROUP,
            "CAN_DELETE_SUBGROUP" to CAN_DELETE_SUBGROUP,
            "CAN_MANAGE_VISIBILITY" to CAN_MANAGE_VISIBILITY,
            "CAN_MANAGE_METADATA" to CAN_MANAGE_METADATA,
            "CAN_AUTHOR_ON_BEHALF" to CAN_AUTHOR_ON_BEHALF,
        )

    /**
     * Ready-made masks for `setDefaultCapabilities` / `setMemberCapabilities` and a namespace's
     * `defaultCapabilities` at founding, so an app names the default it gives members instead of
     * passing a bare number. Port of mero-js `CAPABILITY_PRESETS`.
     *
     * Why this matters: a namespace's default mask is copied into a non-admin member's row when
     * they join, and a namespace starts without [CAN_CREATE_CONTEXT] in it. An app that never
     * sets a default leaves every invited member unable to create a context until an admin
     * grants it by hand.
     *
     * None of these include [CAN_AUTHOR_ON_BEHALF]; grant that deliberately
     * (`AdminApi.grantAuthorship` / `openToDelegatedExecution`).
     */
    object Presets {
        /** No capabilities. */
        const val NONE: Long = 0L

        /** Join Open subgroups only: what a namespace starts with (= 4). */
        const val JOIN_ONLY: Long = CAN_JOIN_OPEN_SUBGROUPS

        /**
         * Create contexts, invite members, join Open subgroups (= 7). A member who can work in
         * the namespace but not reshape it.
         */
        const val CONTRIBUTOR: Long = CAN_CREATE_CONTEXT or CAN_INVITE_MEMBERS or CAN_JOIN_OPEN_SUBGROUPS

        /**
         * [CONTRIBUTOR] plus create and delete subgroups and manage their visibility (= 231).
         * What mero-chat gives members, so they can start and close their own channels.
         */
        const val COLLABORATOR: Long =
            CONTRIBUTOR or CAN_CREATE_SUBGROUP or CAN_DELETE_SUBGROUP or CAN_MANAGE_VISIBILITY
    }

    /** The presets by name, in mero-js's order. */
    val PRESETS: Map<String, Long> =
        linkedMapOf(
            "NONE" to Presets.NONE,
            "JOIN_ONLY" to Presets.JOIN_ONLY,
            "CONTRIBUTOR" to Presets.CONTRIBUTOR,
            "COLLABORATOR" to Presets.COLLABORATOR,
        )

    /**
     * A u32 mask from capability names: `capMask("CAN_CREATE_CONTEXT", "CAN_INVITE_MEMBERS") == 3`.
     * Throws [IllegalArgumentException] on a name core does not assign, so a typo can't
     * silently yield 0.
     */
    fun capMask(vararg names: String): Long = capMask(0L, *names)

    /** [base] (a mask or bit, u32-normalized) with the bits of [names] added. See [capMask]. */
    fun capMask(
        base: Long,
        vararg names: String,
    ): Long {
        var mask = base
        for (name in names) {
            mask = mask or (BY_NAME[name] ?: throw IllegalArgumentException("unknown capability: $name"))
        }
        return mask and U32_MASK
    }

    /** The names of the assigned bits set in [mask], in bit order. Unassigned bits (10+) are not reported. */
    fun capNames(mask: Long): List<String> = BY_NAME.filterValues { hasCap(mask, it) }.keys.toList()

    /** True if [mask] has every bit of [cap] set. */
    fun hasCap(
        mask: Long,
        cap: Long,
    ): Boolean {
        val capU32 = cap and U32_MASK
        return (mask and capU32) == capU32
    }

    /** [mask] with every bit of [cap] set (u32-normalized). */
    fun withCap(
        mask: Long,
        cap: Long,
    ): Long = (mask or cap) and U32_MASK

    /** [mask] with every bit of [cap] cleared (u32-normalized). */
    fun withoutCap(
        mask: Long,
        cap: Long,
    ): Long = (mask and cap.inv()) and U32_MASK
}
