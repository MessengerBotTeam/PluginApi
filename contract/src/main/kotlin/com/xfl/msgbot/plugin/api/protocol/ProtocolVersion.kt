/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/**
 * The wire protocol between host and plugin. Each side speaks a range, [MIN_SUPPORTED]..[CURRENT];
 * the host offers its range in `hello` and the plugin answers with the newest version both speak,
 * so either side can move ahead without leaving the other behind.
 *
 * 0 while the contract is unreleased: nothing published speaks it yet, so anything may change.
 */
object ProtocolVersion {
    const val CURRENT: Int = 0

    /** The oldest version this side still speaks. Widen it instead of moving in lockstep. */
    const val MIN_SUPPORTED: Int = 0

    /** The newest version both this side and one speaking [otherMin]..[otherMax] speak, or null when none. */
    fun negotiate(
        otherMin: Int,
        otherMax: Int,
    ): Int? {
        val newest = minOf(CURRENT, otherMax)
        return newest.takeIf { it >= maxOf(MIN_SUPPORTED, otherMin) }
    }

    /**
     * Whether a plugin whose manifest names [declared], the newest version it speaks, can speak one
     * of this side's. A newer plugin usually still speaks older versions, so only one too old to
     * reach [MIN_SUPPORTED] is ruled out before binding; `hello` settles the rest.
     */
    fun mayBeCompatible(declared: Int): Boolean = declared >= MIN_SUPPORTED
}
