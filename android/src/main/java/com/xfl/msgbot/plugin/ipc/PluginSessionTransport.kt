/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.serialization.BytesChannel

/** The plugin's end of one session: frames out through the host's callback, in through [receive]. */
class PluginSessionTransport(private val callback: IPluginCallback) : PluginTransport {
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val shared = SharedBytes { id, region -> callback.onShared(id, region) }

    override val bytes: BytesChannel get() = shared

    override fun send(frame: ByteArray) {
        try {
            callback.onFrame(frame)
        } catch (_: DeadObjectException) {
            // The host is gone; its death closes this session.
        } catch (e: RemoteException) {
            // Alive but unable to take it (a full transaction buffer): the sender must hear of it.
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
