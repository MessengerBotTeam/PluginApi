/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/**
 * Host-plugin wire protocol version. Each side supports [MIN_SUPPORTED]..[CURRENT]; the host sends
 * its range in `hello` and the plugin picks the newest common version.
 *
 * 0 means unreleased; anything may change.
 */
object ProtocolVersion {
    const val CURRENT: Int = 0

    /** Lower this rather than raising it in lockstep with [CURRENT]. */
    const val MIN_SUPPORTED: Int = 0

    /** Newest version shared with [otherMin]..[otherMax], or null. */
    fun negotiate(
        otherMin: Int,
        otherMax: Int,
    ): Int? {
        val newest = minOf(CURRENT, otherMax)
        return newest.takeIf { it >= maxOf(MIN_SUPPORTED, otherMin) }
    }

    /**
     * Pre-bind check on a manifest's newest protocol [declared]. Only rejects plugins older than
     * [MIN_SUPPORTED]; newer ones may still speak ours, which `hello` decides.
     */
    fun mayBeCompatible(declared: Int): Boolean = declared >= MIN_SUPPORTED
}
