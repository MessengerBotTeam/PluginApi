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
    private val delivery = Executors.newSingleThreadExecutor { r -> Thread(r, "loopback-$name").apply { isDaemon = true } }

    override fun send(frame: ByteArray) {
        peer?.deliver(frame)
    }

    override fun setListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun close() {
        delivery.shutdown()
    }

    private fun deliver(frame: ByteArray) {
        // A closed peer drops the frame silently, like a dead Binder.
        try {
            delivery.execute { listener?.invoke(frame) }
        } catch (_: RejectedExecutionException) {
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
