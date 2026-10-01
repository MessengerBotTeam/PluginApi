/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.RemoteException
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.TransportClosedException
import com.xfl.msgbot.plugin.api.serialization.BytesChannel
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Plugin end of one session. Frames are handled on the session's own thread: every session of a
 * plugin shares one Binder object, whose one-way calls arrive one at a time.
 */
class PluginSessionTransport(private val callback: IPluginCallback) : PluginTransport {
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val delivery = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-session").apply { isDaemon = true } }

    private val shared = SharedBytes { id, region -> deliver { callback.onShared(id, region) } }

    override val bytes: BytesChannel get() = shared

    override fun send(frame: ByteArray) = deliver { callback.onFrame(frame) }

    /** Binder reports a full one-way buffer as a [RemoteException] too; only a dead host closes the transport. */
    private inline fun deliver(call: () -> Unit) {
        try {
            call()
        } catch (e: RemoteException) {
            if (!callback.asBinder().isBinderAlive) throw TransportClosedException("The host is gone", e)
            throw IllegalStateException("The host could not take a frame: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun setListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun close() {
        listener = null
        delivery.shutdown()
        shared.clear()
    }

    internal fun receive(frame: ByteArray) {
        try {
            delivery.execute { listener?.invoke(frame) }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    internal fun receiveShared(
        transferId: Long,
        region: SharedMemory,
    ) = shared.receive(transferId, region)
}
