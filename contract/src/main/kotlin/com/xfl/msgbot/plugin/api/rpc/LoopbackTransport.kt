/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.rpc

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** In-process transport pair for tests. Each side delivers on its own thread, like Binder. */
class LoopbackTransport private constructor(name: String) : PluginTransport {
    private var peer: LoopbackTransport? = null

    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    @Volatile private var closed = false
    private val delivery = Executors.newSingleThreadExecutor { r -> Thread(r, "loopback-$name").apply { isDaemon = true } }

    override fun send(frame: ByteArray) {
        if (closed) throw TransportClosedException("This side is closed")
        peer?.deliver(frame)
    }

    override fun setListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun close() {
        closed = true
        delivery.shutdown()
    }

    /** A closed other side refuses the frame, like a dead Binder. */
    private fun deliver(frame: ByteArray) {
        try {
            delivery.execute { listener?.invoke(frame) }
        } catch (e: RejectedExecutionException) {
            throw TransportClosedException("The other side is closed", e)
        }
    }

    companion object {
        /** A cross-wired (host, plugin) pair. */
        fun pair(): Pair<PluginTransport, PluginTransport> {
            val host = LoopbackTransport("host")
            val plugin = LoopbackTransport("plugin")
            host.peer = plugin
            plugin.peer = host
            return host to plugin
        }
    }
}
