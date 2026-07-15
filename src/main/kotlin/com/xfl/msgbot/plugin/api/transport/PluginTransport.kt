/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.serialization.ValueCodec

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

    /**
     * Blob rewriting for this transport. A frame is a byte array, so a transport that can carry
     * bytes another way (shared memory over Binder) moves large ones out of the frame in
     * [outbound] and restores them in [inbound]. Defaults keep everything inline, which is what an
     * in-process transport wants.
     */
    fun outbound(): ValueCodec.BlobHook = ValueCodec.BlobHook { it }

    fun inbound(): ValueCodec.BlobHook = ValueCodec.BlobHook { it }
}
