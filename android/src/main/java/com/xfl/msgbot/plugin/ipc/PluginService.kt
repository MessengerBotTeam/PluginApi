/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.ipc

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import com.xfl.msgbot.plugin.api.discovery.PluginManifestSchema
import com.xfl.msgbot.plugin.api.discovery.PluginRole
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.remote.EngineEndpoint
import com.xfl.msgbot.plugin.api.remote.ProviderEndpoint
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
        /** Only the app that opened a session may use it; IDs are easy to guess. */
        val owner: Int,
    )

    private val sessionIds = AtomicLong()
    private val sessions = ConcurrentHashMap<Long, Session>()

    private val binder =
        object : IPluginService.Stub() {
            override fun binderVersion(): Int = BinderContract.VERSION

            override fun open(
                role: String,
                component: String,
                callback: IPluginCallback,
            ): Long {
                // Enforced here too in case the manifest omits android:permission.
                if (checkCallingOrSelfPermission(PluginManifestSchema.PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("Only MessengerBotR may open plugin sessions")
                }
                BinderContract.verify { callback.binderVersion() }
                val transport = PluginSessionTransport(callback)
                val lost: (String) -> Unit = { message -> Log.w(TAG, "$role '$component': $message") }
                val endpoint =
                    try {
                        when (role) {
                            PluginRole.ENGINE ->
                                EngineEndpoint(
                                    transport,
                                    engines[component] ?: throw IllegalArgumentException("This plugin has no engine '$component'"),
                                    onProtocolError = lost,
                                )
                            PluginRole.PROVIDER ->
                                ProviderEndpoint(
                                    transport,
                                    onProtocolError = lost,
                                    factory = providers[component] ?: throw IllegalArgumentException("This plugin has no provider '$component'"),
                                )
                            else -> throw IllegalArgumentException("Unknown role '$role'")
                        }
                    } catch (e: Throwable) {
                        transport.close()
                        // Binder passes RuntimeExceptions back to the host; any other Throwable kills this process.
                        throw e as? RuntimeException ?: IllegalStateException("The $role '$component' could not start: ${e.message ?: e.javaClass.simpleName}", e)
                    }
                val id = sessionIds.incrementAndGet()
                val death = IBinder.DeathRecipient { close(id) }
                sessions[id] = Session(callback, transport, endpoint, death, Binder.getCallingUid())
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
                ownSession(session)?.transport?.receive(frame)
            }

            override fun sendShared(
                session: Long,
                transferId: Long,
                region: SharedMemory,
            ) {
                ownSession(session)?.transport?.receiveShared(transferId, region) ?: region.close()
            }

            override fun close(session: Long) {
                if (ownSession(session) != null) this@PluginService.close(session)
            }
        }

    private fun ownSession(id: Long): Session? = sessions[id]?.takeIf { it.owner == Binder.getCallingUid() }

    /** Closing runs plugin code, such as an engine's interrupt, so it does not hold up the host's call. */
    private val closer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "plugin-close").apply { isDaemon = true } }

    /**
     * Endpoints closed but still running, typically a script blocked in native code, which no
     * interrupt reaches. Each keeps its thread and memory (a whole JavaScript heap) until the process ends.
     */
    private val stuck = CopyOnWriteArrayList<AutoCloseable>()

    /** How long a closed endpoint may take to stop before it counts as stuck. */
    internal var stuckAfterMs = STUCK_AFTER_MS

    /**
     * Ends this process, the only way to free stuck threads. The app sees the plugin die, fails what
     * waited on it at once, and reconnects the projects that still use it.
     */
    internal var restartProcess: () -> Unit = { Process.killProcess(Process.myPid()) }

    final override fun onBind(intent: Intent?): IBinder = binder

    private fun close(id: Long) {
        val session = sessions.remove(id) ?: return
        runCatching { session.callback.asBinder().unlinkToDeath(session.death, 0) }
        val finish = {
            runCatching { session.endpoint.close() }
            session.transport.close()
            try {
                closer.schedule({ checkStopped(session.endpoint) }, stuckAfterMs, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                // The service is being destroyed.
            }
            Unit
        }
        try {
            closer.execute(finish)
        } catch (_: RejectedExecutionException) {
            finish()
        }
    }

    private fun checkStopped(endpoint: AutoCloseable) {
        if (isStopped(endpoint)) return
        stuck += endpoint
        stuck.removeAll(::isStopped)
        Log.w(TAG, "${stuck.size} closed session(s) still running, likely blocked in native code")
        // Nothing is lost when no session is open; otherwise only once too many have piled up.
        if (sessions.isEmpty() || stuck.size >= MAX_STUCK) {
            Log.e(TAG, "Restarting the plugin process to free ${stuck.size} stuck session(s)")
            restartProcess()
        }
    }

    private fun isStopped(endpoint: AutoCloseable): Boolean =
        when (endpoint) {
            is EngineEndpoint -> endpoint.isStopped
            is ProviderEndpoint -> endpoint.isStopped
            else -> true
        }

    override fun onDestroy() {
        sessions.keys.toList().forEach(::close)
        closer.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PluginService"
        const val STUCK_AFTER_MS = 10_000L
        const val MAX_STUCK = 3
    }
}
