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

/** Plugin end of one session. */
class PluginSessionTransport(private val callback: IPluginCallback) : PluginTransport {
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val shared = SharedBytes { id, region -> callback.onShared(id, region) }

    override val bytes: BytesChannel get() = shared

    override fun send(frame: ByteArray) {
        try {
            callback.onFrame(frame)
        } catch (_: DeadObjectException) {
            // Host died; the death recipient closes the session.
        } catch (e: RemoteException) {
            // Host alive but rejected the frame (e.g. full transaction buffer); surface it to the sender.
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
