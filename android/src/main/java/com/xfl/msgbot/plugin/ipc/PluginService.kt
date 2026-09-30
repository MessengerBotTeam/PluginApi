/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SharedMemory
import com.xfl.msgbot.plugin.api.discovery.PluginManifestSchema
import com.xfl.msgbot.plugin.api.discovery.PluginRole
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.remote.EngineEndpoint
import com.xfl.msgbot.plugin.api.remote.ProviderEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The service a plugin APK exposes. Subclasses map manifest IDs to factories; sessions, threads and
 * Binder are handled here.
 *
 * ```kotlin
 * class MyPluginService : PluginService() {
 *     override val engines = mapOf("luaj" to ScriptEngineFactory(::LuaEngine))
 *     override val providers = mapOf("weather" to ::WeatherProvider)
 * }
 * ```
 *
 * A profile-only APK can use this class directly. Each project gets its own session, so keep no
 * state outside the instances the factories create.
 */
open class PluginService : Service() {
    protected open val engines: Map<String, ScriptEngineFactory> = emptyMap()
    protected open val providers: Map<String, () -> Provider> = emptyMap()

    private class Session(
        val callback: IPluginCallback,
        val transport: PluginSessionTransport,
        val endpoint: AutoCloseable,
        val death: IBinder.DeathRecipient,
    )

    private val sessionIds = AtomicLong()
    private val sessions = ConcurrentHashMap<Long, Session>()

    private val binder =
        object : IPluginService.Stub() {
            override fun apiFingerprint(): String = BinderContract.FINGERPRINT

            override fun open(
                role: String,
                component: String,
                callback: IPluginCallback,
            ): Long {
                // Enforced here too in case the manifest omits android:permission.
                if (checkCallingOrSelfPermission(PluginManifestSchema.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Only MessengerBotR may open plugin sessions")
                }
                BinderContract.verify { callback.apiFingerprint() }
                val transport = PluginSessionTransport(callback)
                val endpoint =
                    try {
                        when (role) {
                            PluginRole.ENGINE ->
                                EngineEndpoint(
                                    transport,
                                    engines[component] ?: throw IllegalArgumentException("This plugin has no engine '$component'"),
                                )
                            PluginRole.PROVIDER ->
                                ProviderEndpoint(
                                    transport,
                                    providers[component] ?: throw IllegalArgumentException("This plugin has no provider '$component'"),
                                )
                            else -> throw IllegalArgumentException("Unknown role '$role'")
                        }
                    } catch (e: Throwable) {
                        transport.close()
                        throw e
                    }
                val id = sessionIds.incrementAndGet()
                val death = IBinder.DeathRecipient { close(id) }
                sessions[id] = Session(callback, transport, endpoint, death)
                try {
                    callback.asBinder().linkToDeath(death, 0)
                } catch (e: Exception) {
                    close(id)
                    throw IllegalStateException("The host died while opening the session", e)
                }
                return id
            }

            override fun send(
                session: Long,
                frame: ByteArray,
            ) {
                sessions[session]?.transport?.receive(frame)
            }

            override fun sendShared(
                session: Long,
                transferId: Long,
                region: SharedMemory,
            ) {
                sessions[session]?.transport?.receiveShared(transferId, region) ?: region.close()
            }

            override fun close(session: Long) = this@PluginService.close(session)
        }

    final override fun onBind(intent: Intent?): IBinder = binder

    private fun close(id: Long) {
        val session = sessions.remove(id) ?: return
        runCatching { session.callback.asBinder().unlinkToDeath(session.death, 0) }
        runCatching { session.endpoint.close() }
        session.transport.close()
    }

    override fun onDestroy() {
        sessions.keys.toList().forEach(::close)
        super.onDestroy()
    }
}
