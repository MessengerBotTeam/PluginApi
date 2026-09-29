/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.SharedMemory
import android.util.Log
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.serialization.BytesChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host end of one plugin session. Frames arriving before [setListener] are buffered, up to
 * [MAX_WAITING].
 */
class HostSessionTransport(private val service: IPluginService) : PluginTransport {
    private val lock = Any()
    private val waiting = ArrayDeque<ByteArray>()
    private var listener: ((ByteArray) -> Unit)? = null
    private var draining = false
    private val closed = AtomicBoolean(false)

    @Volatile private var session: Long = 0

    private val shared = SharedBytes { id, region -> service.sendShared(requireSession(), id, region) }

    override val bytes: BytesChannel get() = shared

    private val callback =
        object : IPluginCallback.Stub() {
            override fun onFrame(frame: ByteArray) {
                val deliver =
                    synchronized(lock) {
                        val current = listener
                        if (current == null || draining) {
                            if (waiting.size < MAX_WAITING) waiting += frame else Log.w(TAG, "Dropping a frame: $MAX_WAITING already wait")
                            return
                        }
                        current
                    }
                // An Error escaping a Binder stub crashes the host process.
                try {
                    deliver(frame)
                } catch (e: Throwable) {
                    Log.e(TAG, "A frame from the plugin could not be handled", e)
                }
            }

            override fun onShared(
                transferId: Long,
                region: SharedMemory,
            ) = shared.receive(transferId, region)
        }

    /** The protocol is negotiated in the first request, `hello`. */
    fun open(
        role: String,
        component: String,
    ) {
        val id = service.open(role, component, callback)
        require(id > 0) { "The plugin returned an invalid session id: $id" }
        session = id
        if (closed.get()) runCatching { service.close(id) }
    }

    override fun send(frame: ByteArray) = service.send(requireSession(), frame)

    override fun setListener(listener: (ByteArray) -> Unit) {
        synchronized(lock) {
            this.listener = listener
            draining = true
        }
        while (true) {
            val next =
                synchronized(lock) {
                    waiting.removeFirstOrNull() ?: run {
                        draining = false
                        null
                    }
                } ?: break
            listener(next)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            listener = null
            draining = false
            waiting.clear()
        }
        shared.clear()
        session.takeIf { it > 0 }?.let { runCatching { service.close(it) } }
    }

    private fun requireSession(): Long =
        session.takeIf { it > 0 && !closed.get() } ?: throw IllegalStateException("The plugin session is not open")

    private companion object {
        const val MAX_WAITING = 64
        const val TAG = "HostSessionTransport"
    }
}
