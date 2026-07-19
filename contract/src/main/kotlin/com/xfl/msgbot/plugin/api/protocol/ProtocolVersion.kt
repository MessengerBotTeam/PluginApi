/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/** Capability contract version, negotiated at the Phase 2 handshake. Bump on breaking changes. */
object ProtocolVersion {
    /**
     * The contract is still being designed and nothing is published: every plugin is built from
     * this source, in lockstep, so there is no older peer for a bump to protect. Breaking changes
     * stay at 1 until the API is stable enough to publish, and the first release is what earns 2.
     */
    const val CURRENT: Int = 1

    /** Oldest plugin protocol this host still accepts. Widen instead of bumping CURRENT lockstep. */
    const val MIN_SUPPORTED: Int = 1

    /** Range check: a plugin is loadable when its protocol falls in [MIN_SUPPORTED, CURRENT]. */
    fun isCompatible(pluginProtocol: Int): Boolean = pluginProtocol in MIN_SUPPORTED..CURRENT
}
