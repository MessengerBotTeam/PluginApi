/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.remote

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The plugin side of an engine session: runs the real [ScriptEngine] on its own [EngineThread]
 * and serves the host's requests. [com.xfl.msgbot.plugin.ipc.PluginService] makes one per session;
 * an engine author never touches it.
 *
 * At most [maxPendingEvents] events (the running one included) wait for the engine; beyond that
 * the host is told the queue is full instead of the plugin running out of memory.
 */
class EngineEndpoint(
    transport: PluginTransport,
    private val factory: ScriptEngineFactory,
    private val callTimeoutMs: Long = 30_000,
    maxPendingEvents: Int = 64,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val eventSlots = Semaphore(maxPendingEvents.also { require(it > 0) })
    private val thread: EngineThread = EngineThread("plugin-engine") { message, error -> context.reportError(message, error) }

    @Volatile private var engine: ScriptEngine? = null

    @Volatile private var startupFailure: String? = null

    private val context: EngineContext =
        object : EngineContext {
            override val host: HostBridge =
                object : HostBridge {
                    override fun call(
                        function: String,
                        args: Map<String, Value>,
                    ): CallResult = peer.request(Wire.HOST_CALL, Wire.obj("function" to function, "args" to args), callTimeoutMs)

                    override fun callAsync(
                        function: String,
                        args: Map<String, Value>,
                        onResult: (CallResult) -> Unit,
                    ) = peer.requestAsync(Wire.HOST_CALL, Wire.obj("function" to function, "args" to args), callTimeoutMs) { result ->
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
                    Wire.HELLO -> reply(CallResult.ok(Wire.hello()))
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
                        val event =
                            try {
                                val map = params.map()
                                ScriptEvent(map.string("event"), map.getValue("payload").map())
                            } catch (e: Exception) {
                                return reply(CallResult.badArgs("Malformed dispatch: ${e.message}"))
                            }
                        if (!eventSlots.tryAcquire()) return reply(CallResult.unavailable("The engine's event queue is full"))
                        onEngine({ result ->
                            eventSlots.release()
                            reply(result)
                        }) { engine ->
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
                if (method == Wire.CLOSE) close()
            }
        }

    // The engine is created by the first task on its thread, so any request that arrives once the
    // peer listens is queued behind it. The latch keeps that task from reporting a failure through
    // a peer that does not exist yet.
    private val constructed = CountDownLatch(1)

    init {
        thread.execute {
            constructed.await()
            try {
                engine = factory.create(context)
            } catch (e: Exception) {
                val message = "The engine could not start: ${e.message ?: e.javaClass.simpleName}"
                startupFailure = message
                context.reportError(message, e)
            }
        }
    }

    private val peer: RpcPeer = RpcPeer(transport, handler)

    init {
        constructed.countDown()
    }

    /** Runs [block] on the engine thread and answers with what it returned or threw. */
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
                        } catch (e: Exception) {
                            Wire.errorOf(e)
                        }
                    }
                reply(result)
            }
        if (thread.isShutdown) reply(CallResult.unavailable("The engine session is closed")) else thread.execute(task)
    }

    /** The host dying never says goodbye, so the service closes this too; closing twice is normal. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peer.close()
        thread.execute { runCatching { engine?.close() } }
        thread.shutdown()
    }
}
