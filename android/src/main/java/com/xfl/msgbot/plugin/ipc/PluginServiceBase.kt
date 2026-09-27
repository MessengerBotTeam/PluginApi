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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private class Session(
        val callback: IPluginCallback,
        val transport: ServicePluginTransport,
        val host: AutoCloseable,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val nextSessionId = AtomicLong()
    private val sessions = ConcurrentHashMap<Long, Session>()

    /** Builds the endpoint that drives this plugin: an `EngineHost` or a `ProviderHost`. */
    protected open fun createHost(transport: PluginTransport): AutoCloseable =
        error("Override createHost(action, transport) to provide a plugin endpoint")

    /** The action keeps engine, source, and extension roles independent even in one APK service. */
    protected open fun createHost(action: String, transport: PluginTransport): AutoCloseable = createHost(transport)

    private val binder =
        object : IPluginService.Stub() {
            override fun protocolVersion(): Int = ProtocolVersion.CURRENT

            override fun open(action: String, callback: IPluginCallback): Long {
                val id = nextSessionId.incrementAndGet()
                val transport = ServicePluginTransport(callback)
                val host =
                    try {
                        createHost(action, transport)
                    } catch (e: Exception) {
                        transport.close()
                        throw e
                    }
                val recipient = IBinder.DeathRecipient { closeSession(id) }
                sessions[id] = Session(callback, transport, host, recipient)
                try {
                    callback.asBinder().linkToDeath(recipient, 0)
                } catch (e: Exception) {
                    closeSession(id)
                    throw e
                }
                return id
            }

            override fun send(sessionId: Long, frame: ByteArray) {
                sessions[sessionId]?.transport?.receive(frame)
            }

            override fun sendBlob(
                sessionId: Long,
                blobId: Long,
                shm: SharedMemory,
            ) {
                sessions[sessionId]?.transport?.receiveBlob(blobId, shm)
            }

            override fun close(sessionId: Long) = closeSession(sessionId)
        }

    final override fun onBind(intent: Intent?): IBinder = binder

    private fun closeSession(id: Long) {
        val session = sessions.remove(id) ?: return
        runCatching { session.callback.asBinder().unlinkToDeath(session.deathRecipient, 0) }
        runCatching { session.host.close() }
        session.transport.close()
    }

    final override fun onDestroy() {
        sessions.keys.toList().forEach(::closeSession)
        super.onDestroy()
    }
}
