/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.protocol.ProtocolVersion
import com.xfl.msgbot.plugin.api.transport.PluginTransport

/**
 * The bound service a plugin exposes to the host. Subclass it and say what to run:
 *
 * ```kotlin
 * class PluginService : PluginServiceBase() {
 *     override fun createHost(transport: PluginTransport) = EngineHost(transport) { MyEngine() }
 * }
 * ```
 *
 * Everything else is the same in every plugin, shared here instead of copied.
 */
abstract class PluginServiceBase : Service() {
    private var transport: ServicePluginTransport? = null
    private var host: AutoCloseable? = null

    /** Builds the endpoint that drives this plugin: an `EngineHost` or a `SourceHost`. */
    protected abstract fun createHost(transport: PluginTransport): AutoCloseable

    private val binder =
        object : IPluginService.Stub() {
            override fun connect(callback: IPluginCallback): Int {
                // A reconnecting host means the old one is gone; a dead connection left running
                // leaks an engine's runtime or keeps a source emitting into nothing.
                host?.close()
                val t = ServicePluginTransport(callback)
                transport = t
                host = createHost(t)
                return ProtocolVersion.CURRENT
            }

            override fun send(frame: ByteArray) {
                transport?.receive(frame)
            }

            override fun sendBlob(
                id: Long,
                shm: SharedMemory,
            ) {
                transport?.receiveBlob(id, shm)
            }
        }

    final override fun onBind(intent: Intent?): IBinder = binder

    /** What the plugin registered on the application context would otherwise outlive the service. */
    final override fun onDestroy() {
        host?.close()
        host = null
        transport = null
        super.onDestroy()
    }
}
