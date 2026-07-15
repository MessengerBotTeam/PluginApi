/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

/**
 * Bidirectional byte-frame channel between host and plugin. The protocol
 * ([PluginProtocol]) and endpoints ([RemoteScriptEngine], [EngineHost]) are transport-agnostic;
 * concrete transports (AIDL, unix socket, in-process loopback) only move frames.
 *
 * Received frames must be delivered on a thread distinct from the one a blocking host-call
 * waits on, otherwise a synchronous capability call would deadlock.
 */
interface PluginTransport {
    fun send(frame: ByteArray)

    fun setListener(listener: (ByteArray) -> Unit)

    fun close()
}
