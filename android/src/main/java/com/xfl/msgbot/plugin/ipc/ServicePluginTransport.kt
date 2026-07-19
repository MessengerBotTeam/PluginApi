/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.os.RemoteException
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.serialization.ValueCodec
import com.xfl.msgbot.plugin.api.transport.PluginTransport

/**
 * Plugin-side [PluginTransport]. Outbound frames go to the host via the AIDL callback; inbound
 * frames arrive through [receive] (called on a Binder thread) and are handed to the listener.
 */
class ServicePluginTransport(private val callback: IPluginCallback) : PluginTransport {
    @Volatile private var listener: ((ByteArray) -> Unit)? = null

    private val blobs = SharedBlobs { id, shm -> callback.onBlob(id, shm) }

    override fun send(frame: ByteArray) {
        try {
            callback.onFrame(frame)
        } catch (e: RemoteException) {
            // Host went away; nothing to do.
        }
    }

    override fun setListener(listener: (ByteArray) -> Unit) {
        this.listener = listener
    }

    override fun outbound(): ValueCodec.BlobHook = blobs.outbound()

    override fun inbound(): ValueCodec.BlobHook = blobs.inbound()

    override fun close() {
        listener = null
        blobs.clear()
    }

    fun receive(frame: ByteArray) {
        listener?.invoke(frame)
    }

    fun receiveBlob(id: Long, shm: SharedMemory) {
        blobs.receive(id, shm)
    }
}
