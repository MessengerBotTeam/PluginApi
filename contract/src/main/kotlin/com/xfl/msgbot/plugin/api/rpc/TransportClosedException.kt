/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2026 MessengerBotTeam
 */

package com.xfl.msgbot.plugin.api.rpc

/**
 * Thrown by [PluginTransport.send] when the other side is gone for good, such as a dead process.
 * [RpcPeer] then closes at once, so what waits on that side fails now instead of at its timeout.
 */
class TransportClosedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
