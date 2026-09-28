/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.rpc

import com.xfl.msgbot.plugin.api.serialization.BytesChannel

/**
 * Moves frames between the host and one plugin session: Binder in production, [LoopbackTransport]
 * in tests. It only moves bytes; [RpcPeer] gives them meaning.
 *
 * Frames must be delivered on a thread that never waits on a call of its own, or a blocking call
 * would wait for an answer queued behind itself.
 */
interface PluginTransport {
    fun send(frame: ByteArray)

    fun setListener(listener: (ByteArray) -> Unit)

    fun close()

    /** How large byte payloads travel. Inline unless the transport can do better. */
    val bytes: BytesChannel get() = BytesChannel.INLINE
}
