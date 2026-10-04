/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.Args
import com.xfl.msgbot.plugin.api.call.CallException
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
import com.xfl.msgbot.plugin.api.value.asLongOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin side of a provider session: creates the [Provider] and runs it on one thread. Created per
 * session by [com.xfl.msgbot.plugin.ipc.PluginService].
 *
 * Validates emitted events locally so `emit` throws at the call site instead of the host dropping them.
 * [onProtocolError] hears about frames that were lost, such as an answer that could not be sent.
 */
class ProviderEndpoint(
    transport: PluginTransport,
    private val onProtocolError: (String) -> Unit = {},
    private val factory: () -> Provider,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val thread: EngineThread = EngineThread("plugin-provider") { message, _ -> reportError(message) }

    @Volatile private var provider: Provider? = null

    // Safe to read from requests: they are queued behind provider creation.
    @Volatile private var modules: Map<String, ProviderModule> = emptyMap()

    @Volatile private var startupFailure: String? = null

    @Volatile private var running: Running? = null

    /** One `provider.start`; [generation] tags its emits so the host can drop them after a restart. */
    private inner class Running(
        override val projects: Map<String, Map<String, String>>,
        private val generation: Long?,
    ) : ProviderContext {
        @Volatile var active = true

        /** Answers still owed; stopping gives them, or the host would wait out its timeout. */
        private val owed = ConcurrentHashMap.newKeySet<CompletableFuture<Value>>()

        fun owe(answer: CompletableFuture<Value>): CompletableFuture<Value> {
            val owedAnswer = CompletableFuture<Value>()
            owed += owedAnswer
            owedAnswer.whenComplete { _, _ -> owed -= owedAnswer }
            answer.whenComplete { value, error -> if (error == null) owedAnswer.complete(value) else owedAnswer.completeExceptionally(error) }
            return owedAnswer
        }

        fun end() {
            active = false
            owed.toList().forEach { it.completeExceptionally(CallException.unavailable("The provider stopped before answering")) }
        }

        override fun emit(
            event: String,
            payload: Map<String, Value>,
            projectId: String?,
        ) {
            val (namespace, name) = requireNotNull(Names.split(event)) { "'$event' is not a qualified event name (namespace.event)" }
            val module = requireNotNull(modules[namespace]) { "This provider publishes no '$namespace' module" }
            val declared = requireNotNull(module.spec.event(name)) { "$namespace declares no event '$name'" }
            declared.checkPayload(payload)?.let { throw IllegalArgumentException("$event: $it") }
            if (active) {
                peer.notify(Wire.PROVIDER_EMIT, Wire.obj("event" to event, "payload" to payload, "project" to projectId, Wire.GENERATION to generation))
            } else {
                // Dropped either way; saying so lets a provider find the thread it did not stop.
                this@ProviderEndpoint.reportError("'$event' was emitted after the provider stopped and was dropped")
            }
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
                        val map = params.map()
                        val projects = Wire.projectsOf(map["projects"])
                        val generation = map[Wire.GENERATION]?.asLongOrNull()
                        onProvider(reply) { provider ->
                            stopRunning(provider)
                            val context = Running(projects, generation)
                            running = context
                            try {
                                provider.start(context)
                            } catch (e: Throwable) {
                                // A provider that did not start must not emit as if it had.
                                running = null
                                context.end()
                                throw e
                            }
                            Value.VNull
                        }
                    }
                    Wire.PROVIDER_STOP ->
                        onProvider(reply) { provider ->
                            stopRunning(provider)
                            Value.VNull
                        }
                    Wire.PROVIDER_CALL -> {
                        val map: Map<String, Value>
                        val function: String
                        val module: ProviderModule
                        val project: String
                        val name: String
                        val args: Args
                        try {
                            map = params.map()
                            function = map.string("function")
                            project = map.string("project")
                            val (namespace, member) = Names.split(function) ?: throw IllegalArgumentException("unqualified function")
                            name = member
                            module = modules[namespace] ?: return reply(CallResult.unknownFunction(function))
                            args = Args(map.getValue("args").map())
                        } catch (e: Exception) {
                            return reply(CallResult.badArgs("Malformed call: ${e.message}"))
                        }
                        onProviderAsync(reply) {
                            // Behind a slow call: the host has given up, and running it now could act twice.
                            if (Wire.expired(map)) throw CallException.unavailable("'$function' waited past the host's deadline and was skipped")
                            // Read here, not on arrival: a restart queued ahead of this call changes the options.
                            val current = running ?: throw CallException.unavailable("The provider is not running")
                            current.owe(module.callAsync(ProviderCall(project, name, args, current.projects[project].orEmpty())))
                        }
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

    private val peer: RpcPeer = RpcPeer(transport, handler, onProtocolError)

    init {
        constructed.countDown()
    }

    /** Closed, and the provider's thread has stopped. */
    val isStopped: Boolean get() = closed.get() && thread.isTerminated

    private fun stopRunning(provider: Provider) {
        val current = running ?: return
        running = null
        current.end()
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
                    // A stage from Java may complete with null; anything thrown here would lose the answer.
                    val result =
                        try {
                            if (error == null) CallResult.ok((value as Value?) ?: Value.VNull) else Wire.errorOf(error.unwrapped())
                        } catch (e: Throwable) {
                            Wire.errorOf(e)
                        }
                    reply(result)
                }
            }
        } catch (_: RejectedExecutionException) {
            reply(CallResult.unavailable("The provider session is closed"))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running?.end()
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

