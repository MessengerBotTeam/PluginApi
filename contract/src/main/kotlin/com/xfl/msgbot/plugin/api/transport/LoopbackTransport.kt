/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import java.util.concurrent.Executors

/**
 * In-process [PluginTransport] pair for testing the protocol end-to-end (still crossing the
 * serialization boundary). Each endpoint delivers received frames on its own single thread, so
 * a blocking host-call on one side never deadlocks the other.
 */
class LoopbackTransport private constructor(name: String) : PluginTransport {
    private var peer: LoopbackTransport? = null
    @Volatile private var listener: ((ByteArray) -> Unit)? = null
    private val delivery = Executors.newSingleThreadExecutor { r -> Thread(r, "loopback-$name") }

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
        delivery.submit { listener?.invoke(frame) }
    }

    companion object {
        /** Returns a cross-wired (host, plugin) transport pair. */
        fun pair(): Pair<PluginTransport, PluginTransport> {
            val a = LoopbackTransport("host")
            val b = LoopbackTransport("plugin")
            a.peer = b
            b.peer = a
            return a to b
        }
    }
}
