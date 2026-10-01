/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.rpc

import com.xfl.msgbot.plugin.api.serialization.BytesChannel

/**
 * Carries raw frames between the host and one plugin session; [RpcPeer] interprets them.
 *
 * Deliver frames on a thread that never blocks on its own calls, or a blocking call deadlocks on its answer.
 */
interface PluginTransport {
    /**
     * Throws [TransportClosedException] when the other side is gone for good. Any other exception
     * means only this frame was refused, for example because the other side's buffer is full.
     */
    fun send(frame: ByteArray)

    fun setListener(listener: (ByteArray) -> Unit)

    fun close()

    /** Channel for large byte payloads. Defaults to inline. */
    val bytes: BytesChannel get() = BytesChannel.INLINE
}
