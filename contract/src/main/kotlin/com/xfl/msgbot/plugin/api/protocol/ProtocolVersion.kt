/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/** Capability contract version, negotiated at the Phase 2 handshake. Bump on breaking changes. */
object ProtocolVersion {
    /** Version 3 requires dispatch completion acknowledgements and bounded execution admission. */
    const val CURRENT: Int = 3

    /** Oldest plugin protocol this host still accepts. Widen instead of bumping CURRENT lockstep. */
    const val MIN_SUPPORTED: Int = 3

    /** Range check: a plugin is loadable when its protocol falls in [MIN_SUPPORTED, CURRENT]. */
    fun isCompatible(pluginProtocol: Int): Boolean = pluginProtocol in MIN_SUPPORTED..CURRENT
}
