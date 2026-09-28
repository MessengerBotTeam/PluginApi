/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.protocol

/** The wire protocol between host and plugin, checked on binding and again in the `hello` exchange. */
object ProtocolVersion {
    /** 4: one symmetric RPC for every role, schema-described modules, named arguments. */
    const val CURRENT: Int = 4

    /** Oldest plugin protocol this host still accepts. Widen instead of bumping [CURRENT] lockstep. */
    const val MIN_SUPPORTED: Int = 4

    fun isCompatible(pluginProtocol: Int): Boolean = pluginProtocol in MIN_SUPPORTED..CURRENT
}
