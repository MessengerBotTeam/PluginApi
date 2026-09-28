/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.provider.Provider
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderContext
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The plugin side of a provider session: creates the [Provider] and runs it, its handlers
 * included, on one thread. [com.xfl.msgbot.plugin.ipc.PluginService] makes one per session.
 *
 * An event is checked against the provider's own spec before it leaves, so a mistake shows up as
 * an exception at the `emit` that made it, not as a silent drop on the host.
 */
class ProviderEndpoint(
    transport: PluginTransport,
    private val factory: () -> Provider,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread: EngineThread = EngineThread("plugin-provider") { message, _ -> reportError(message) }

    @Volatile private var provider: Provider? = null

    @Volatile private var startupFailure: String? = null

    @Volatile private var running: Running? = null

    private inner class Running(override val projects: Map<String, Map<String, String>>) : ProviderContext {
        @Volatile var active = true

        override fun emit(
            event: String,
            payload: Map<String, Value>,
            projectId: String?,
        ) {
            val spec = requireNotNull(provider).module.spec
            val declared = requireNotNull(spec.event(event)) { "${spec.namespace} declares no event '$event'" }
            declared.checkPayload(payload)?.let { throw IllegalArgumentException("${spec.qualified(event)}: $it") }
            if (active) peer.notify(Wire.PROVIDER_EMIT, Wire.obj("event" to event, "payload" to payload, "project" to projectId))
        }

        override fun reportError(
            message: String,
            error: Throwable?,
        ) = this@ProviderEndpoint.reportError(message)
    }

    private val handler: RpcHandler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) {
                when (method) {
                    Wire.HELLO -> onProvider(reply) { provider -> Wire.hello(provider.module.spec) }
                    Wire.PROVIDER_START -> {
                        val projects = Wire.projectsOf(params.map()["projects"])
                        onProvider(reply) { provider ->
                            stopRunning(provider)
                            val context = Running(projects)
                            running = context
                            provider.start(context)
                            Value.VNull
                        }
                    }
                    Wire.PROVIDER_STOP ->
                        onProvider(reply) { provider ->
                            stopRunning(provider)
                            Value.VNull
                        }
                    Wire.PROVIDER_CALL -> {
                        val call =
                            try {
                                val map = params.map()
                                val project = map.string("project")
                                ProviderCall(project, map.string("function"), Args(map.getValue("args").map()), running?.projects?.get(project).orEmpty())
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed call: ${e.message}"))
                            }
                        onProvider(reply) { provider -> provider.module.call(call) }
                    }
                    else -> reply(CallResult.failed("A provider does not answer '$method'"))
                }
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                if (method == Wire.CLOSE) close()
            }
        }

    private val constructed = CountDownLatch(1)

    init {
        thread.execute {
            constructed.await()
            try {
                provider = factory()
            } catch (e: Exception) {
                startupFailure = "The provider could not start: ${e.message ?: e.javaClass.simpleName}"
                reportError(startupFailure!!)
            }
        }
    }

    private val peer: RpcPeer = RpcPeer(transport, handler)

    init {
        constructed.countDown()
    }

    private fun stopRunning(provider: Provider) {
        val current = running ?: return
        current.active = false
        running = null
        provider.stop()
    }

    private fun reportError(message: String) = peer.notify(Wire.PROVIDER_ERROR, Wire.obj("message" to message))

    private fun onProvider(
        reply: (CallResult) -> Unit,
        block: (Provider) -> Value,
    ) {
        if (thread.isShutdown) return reply(CallResult.unavailable("The provider session is closed"))
        thread.execute {
            val result =
                if (closed.get()) {
                    CallResult.unavailable("The provider session is closed")
                } else {
                    try {
                        val provider = provider ?: throw IllegalStateException(startupFailure ?: "The provider is not running")
                        CallResult.ok(block(provider))
                    } catch (e: Exception) {
                        Wire.errorOf(e)
                    }
                }
            reply(result)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        thread.execute {
            provider?.let { provider ->
                runCatching { stopRunning(provider) }
                runCatching { provider.close() }
            }
        }
        thread.shutdown()
    }
}

