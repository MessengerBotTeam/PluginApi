/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

import com.xfl.msgbot.plugin.api.call.CallException
import com.xfl.msgbot.plugin.api.call.CallResult
import com.xfl.msgbot.plugin.api.engine.EngineContext
import com.xfl.msgbot.plugin.api.engine.EngineException
import com.xfl.msgbot.plugin.api.engine.EngineScheduler
import com.xfl.msgbot.plugin.api.engine.EngineThread
import com.xfl.msgbot.plugin.api.engine.HostBridge
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.engine.ScriptEngineFactory
import com.xfl.msgbot.plugin.api.engine.ScriptEvent
import com.xfl.msgbot.plugin.api.remote.Wire.map
import com.xfl.msgbot.plugin.api.remote.Wire.string
import com.xfl.msgbot.plugin.api.rpc.PluginTransport
import com.xfl.msgbot.plugin.api.rpc.RpcHandler
import com.xfl.msgbot.plugin.api.rpc.RpcPeer
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plugin side of an engine session: runs the [ScriptEngine] on an [EngineThread] and serves host
 * requests. Created per session by [com.xfl.msgbot.plugin.ipc.PluginService].
 *
 * Events beyond [maxPendingEvents] (including the running one) are rejected as unavailable.
 * [onProtocolError] hears about frames that were lost, such as an answer that could not be sent.
 */
class EngineEndpoint(
    transport: PluginTransport,
    private val factory: ScriptEngineFactory,
    private val callTimeoutMs: Long = 30_000,
    maxPendingEvents: Int = 64,
    private val onProtocolError: (String) -> Unit = {},
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val eventSlots = Semaphore(maxPendingEvents.also { require(it > 0) })
    private val thread: EngineThread = EngineThread("plugin-engine") { message, error -> context.reportError(message, error) }

    @Volatile private var engine: ScriptEngine? = null

    @Volatile private var startupFailure: String? = null

    /** Blocking host calls in flight; an interrupt answers them, or the script would wait out the host first. */
    private val waitingCalls = ConcurrentHashMap.newKeySet<CompletableFuture<CallResult>>()

    private val context: EngineContext =
        object : EngineContext {
            override val host: HostBridge =
                object : HostBridge {
                    override fun call(
                        function: String,
                        args: Map<String, Value>,
                    ): CallResult {
                        val answer = CompletableFuture<CallResult>()
                        waitingCalls += answer
                        try {
                            val params = Wire.obj("function" to function, "args" to args, Wire.deadline(callTimeoutMs))
                            peer.requestAsync(Wire.HOST_CALL, params, callTimeoutMs, answer::complete)
                            return answer.get()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return CallResult.unavailable("Interrupted while waiting for '$function'")
                        } finally {
                            waitingCalls -= answer
                        }
                    }

                    override fun callAsync(
                        function: String,
                        args: Map<String, Value>,
                        onResult: (CallResult) -> Unit,
                    ) = peer.requestAsync(Wire.HOST_CALL, Wire.obj("function" to function, "args" to args, Wire.deadline(callTimeoutMs)), callTimeoutMs) { result ->
                        thread.post { onResult(result) }
                    }
                }

            override val scheduler: EngineScheduler = thread

            override fun reportError(
                message: String,
                error: Throwable?,
            ) = peer.notify(Wire.ENGINE_ERROR, Wire.obj("message" to message))
        }

    private val handler: RpcHandler =
        object : RpcHandler {
            override fun onRequest(
                method: String,
                params: Value,
                reply: (CallResult) -> Unit,
            ) {
                when (method) {
                    Wire.HELLO -> reply(Wire.answerHello(params))
                    Wire.ENGINE_LOAD -> {
                        val request =
                            try {
                                Wire.loadRequestOf(params)
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed load: ${e.message}"))
                            }
                        onEngine(reply) { engine ->
                            engine.load(request)
                            Value.VNull
                        }
                    }
                    Wire.ENGINE_DISPATCH -> {
                        val map =
                            try {
                                params.map()
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed dispatch: ${e.message}"))
                            }
                        val event =
                            try {
                                ScriptEvent(map.string("event"), map.getValue("payload").map())
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed dispatch: ${e.message}"))
                            }
                        if (!eventSlots.tryAcquire()) return reply(CallResult.unavailable("The engine's event queue is full"))
                        onEngine({ result ->
                            eventSlots.release()
                            reply(result)
                        }) { engine ->
                            // Late after a freeze or behind a stuck event: the host has given up on it.
                            if (Wire.expired(map)) throw CallException.unavailable("'${event.name}' waited past the host's deadline and was skipped")
                            engine.dispatch(event)
                            Value.VNull
                        }
                    }
                    Wire.ENGINE_EVAL -> {
                        val source = params.map()["source"]
                        onEngine(reply) { engine -> engine.eval((source as? Value.VString)?.value.orEmpty()) }
                    }
                    else -> reply(CallResult.failed("An engine does not answer '$method'"))
                }
            }

            override fun onNotify(
                method: String,
                params: Value,
            ) {
                when (method) {
                    Wire.CLOSE -> close()
                    Wire.ENGINE_INTERRUPT -> interrupt()
                }
            }
        }

    // Creating the engine as the first thread task queues every request behind it. The latch stops
    // that task from reporting a failure before [peer] is assigned.
    private val constructed = CountDownLatch(1)

    init {
        thread.execute {
            constructed.await()
            try {
                engine = factory.create(context)
            } catch (e: Throwable) {
                val message = "The engine could not start: ${e.message ?: e.javaClass.simpleName}"
                startupFailure = message
                context.reportError(message, e)
            }
        }
    }

    private val peer: RpcPeer = RpcPeer(transport, handler, onProtocolError)

    init {
        constructed.countDown()
    }

    /** Closed, and the engine's thread has stopped: no script is left running in native code. */
    val isStopped: Boolean get() = closed.get() && thread.isTerminated

    /** Catches Throwable so errors like StackOverflowError still reply and release the event slot. */
    private fun onEngine(
        reply: (CallResult) -> Unit,
        block: (ScriptEngine) -> Value,
    ) {
        val task =
            Runnable {
                val result =
                    if (closed.get()) {
                        CallResult.unavailable("The engine session is closed")
                    } else {
                        try {
                            val engine = engine ?: throw EngineException(startupFailure ?: "The engine is not running")
                            CallResult.ok(block(engine))
                        } catch (e: Throwable) {
                            Wire.errorOf(e)
                        }
                    }
                reply(result)
            }
        try {
            thread.execute(task)
        } catch (_: RejectedExecutionException) {
            reply(CallResult.unavailable("The engine session is closed"))
        }
    }

    /** Interrupts the engine first, so a script freed from a host call stops instead of carrying on. */
    private fun interrupt() {
        try {
            engine?.interrupt()
        } catch (e: Throwable) {
            context.reportError("Interrupting the engine failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
        waitingCalls.forEach { it.complete(CallResult.unavailable("The script was interrupted")) }
    }

    /**
     * Idempotent: both the host and the service may close it. Interrupts a running script first,
     * or the close task would queue behind it.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        interrupt()
        try {
            thread.execute { runCatching { engine?.close() } }
        } catch (_: RejectedExecutionException) {
        }
        thread.shutdown()
    }
}
