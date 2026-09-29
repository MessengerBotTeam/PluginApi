/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.RemoteException
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.serialization.BytesChannel

/** Plugin end of one session. */
class PluginSessionTransport(private val callback: IPluginCallback) : PluginTransport {
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val shared = SharedBytes { id, region -> callback.onShared(id, region) }

    override val bytes: BytesChannel get() = shared

    override fun send(frame: ByteArray) {
        try {
            callback.onFrame(frame)
        } catch (e: RemoteException) {
            // Binder reports a full one-way buffer as a dead object too; only a dead host is ignored,
            // since its death recipient closes the session.
            if (!callback.asBinder().isBinderAlive) return
            throw IllegalStateException("The host could not take a frame: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    override fun setListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun close() {
        listener = null
        shared.clear()
    }

    internal fun receive(frame: ByteArray) {
        listener?.invoke(frame)
    }

    internal fun receiveShared(
        transferId: Long,
        region: SharedMemory,
    ) = shared.receive(transferId, region)
}
