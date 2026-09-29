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
import com.xfl.msgbot.plugin.api.provider.ProviderModule
import com.xfl.msgbot.plugin.api.provider.modulesByNamespace
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.remote.Wire.unwrapped
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.schema.Names
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin side of a provider session: creates the [Provider] and runs it on one thread. Created per
 * session by [com.xfl.msgbot.plugin.ipc.PluginService].
 *
 * Validates emitted events locally so `emit` throws at the call site instead of the host dropping them.
 */
class ProviderEndpoint(
    transport: PluginTransport,
    private val factory: () -> Provider,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread: EngineThread = EngineThread("plugin-provider") { message, _ -> reportError(message) }

    @Volatile private var provider: Provider? = null

    // Safe to read from requests: they are queued behind provider creation.
    @Volatile private var modules: Map<String, ProviderModule> = emptyMap()

    @Volatile private var startupFailure: String? = null

    @Volatile private var running: Running? = null

    private inner class Running(override val projects: Map<String, Map<String, String>>) : ProviderContext {
        @Volatile var active = true

        override fun emit(
            event: String,
            payload: Map<String, Value>,
            projectId: String?,
        ) {
            val (namespace, name) = requireNotNull(Names.split(event)) { "'$event' is not a qualified event name (namespace.event)" }
            val module = requireNotNull(modules[namespace]) { "This provider publishes no '$namespace' module" }
            val declared = requireNotNull(module.spec.event(name)) { "$namespace declares no event '$name'" }
            declared.checkPayload(payload)?.let { throw IllegalArgumentException("$event: $it") }
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
                    Wire.HELLO -> onProvider(reply) { Wire.answerHello(params) { modules.values.map { it.spec } }.getOrThrow() }
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
                        val (module, call) =
                            try {
                                val map = params.map()
                                val project = map.string("project")
                                val (namespace, name) =
                                    Names.split(map.string("function")) ?: throw IllegalArgumentException("unqualified function")
                                val module = modules[namespace] ?: return reply(CallResult.unknownFunction(map.string("function")))
                                val options = running?.projects?.get(project).orEmpty()
                                module to ProviderCall(project, name, Args(map.getValue("args").map()), options)
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed call: ${e.message}"))
                            }
                        onProviderAsync(reply) { module.callAsync(call) }
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
                provider = factory().also { modules = it.modulesByNamespace() }
            } catch (e: Throwable) {
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

    /** Catches Throwable so every request gets a reply. */
    private fun onProvider(
        reply: (CallResult) -> Unit,
        block: (Provider) -> Value,
    ) = onProviderAsync(reply) { provider -> CompletableFuture.completedFuture(block(provider)) }

    /** Starts [block] on the provider's thread and answers when its stage completes, from whichever thread that is. */
    private fun onProviderAsync(
        reply: (CallResult) -> Unit,
        block: (Provider) -> CompletableFuture<Value>,
    ) {
        try {
            thread.execute {
                if (closed.get()) return@execute reply(CallResult.unavailable("The provider session is closed"))
                val answer =
                    try {
                        val provider = provider ?: throw IllegalStateException(startupFailure ?: "The provider is not running")
                        block(provider)
                    } catch (e: Throwable) {
                        return@execute reply(Wire.errorOf(e))
                    }
                answer.whenComplete { value, error ->
                    reply(if (error == null) CallResult.ok(value) else Wire.errorOf(error.unwrapped()))
                }
            }
        } catch (_: RejectedExecutionException) {
            reply(CallResult.unavailable("The provider session is closed"))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        try {
            thread.execute {
                provider?.let { provider ->
                    runCatching { stopRunning(provider) }
                    runCatching { provider.close() }
                }
            }
        } catch (_: RejectedExecutionException) {
        }
        thread.shutdown()
    }
}

