/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Plugin-side endpoint. Owns the real [ScriptEngine], created and driven on a single dedicated
 * thread (engine thread-affinity). Feeds the engine a proxy [HostBridge] whose calls are sent
 * to the host as [PluginProtocol.Frame.HostCall] frames, blocking the engine thread until the
 * matching Result arrives (delivered by the transport on a different thread).
 *
 * The engine is provided by a factory so it is instantiated on the engine thread.
 */
class EngineHost(
    private val transport: PluginTransport,
    engineFactory: () -> ScriptEngine,
    private val callTimeoutMs: Long = 30_000,
    /**
     * This plugin's own shim, used when the host sends an empty one. Null means "no shim".
     */
    private val shimProvider: (language: String, apiLevel: String) -> String? = { _, _ -> null },
    /** Told when one-way work (load/dispatch) fails; the exception has nowhere else to surface. */
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-engine") }
    private val callSeq = AtomicLong(0)
    private val pendingCalls = ConcurrentHashMap<Long, CompletableFuture<CallResult>>()
    private val closed = AtomicBoolean(false)

    @Volatile private lateinit var engine: ScriptEngine

    private val proxyBridge =
        HostBridge { method, args ->
            val id = callSeq.incrementAndGet()
            val future = CompletableFuture<CallResult>()
            pendingCalls[id] = future
            transport.send(PluginProtocol.encode(PluginProtocol.Frame.HostCall(id, method, args), transport.outbound()))
            try {
                future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // Shutdown, not a timeout; restore the flag that tells the engine thread to stop.
                Thread.currentThread().interrupt()
                CallResult.failed("'$method' was cancelled while the engine was shutting down")
            } catch (e: Exception) {
                CallResult.failed("The host did not answer '$method' within ${callTimeoutMs}ms")
            } finally {
                pendingCalls.remove(id)
            }
        }

    init {
        transport.setListener(::onFrame)
        engineExecutor.submit {
            // submit files a throw in a Future nobody reads; a runtime that cannot even start
            // (a missing native lib, say) must not vanish into it.
            report("engine init") {
                engine = engineFactory()
                engine.bindHost(proxyBridge)
            }
        }
    }

    private fun onFrame(bytes: ByteArray) {
        // A late frame after close() has no engine left; letting it reach the shut-down executor
        // would throw out of a binder callback and crash the process.
        if (closed.get()) return
        when (val frame = PluginProtocol.decode(bytes, transport.inbound())) {
            is PluginProtocol.Frame.Load -> runOnEngine {
                // The compile that sent this is waiting on the answer, so a throw must become one.
                val answer =
                    try {
                        // Empty shim => the host has none for this language; fall back to our own.
                        val shim = frame.shim.ifEmpty { shimProvider(frame.language, frame.apiLevel).orEmpty() }
                        engine.load(frame.language, frame.apiLevel, frame.capabilities, shim, frame.userScript, frame.options)
                        CallResult.of(Value.VNull)
                    } catch (e: Exception) {
                        val message = "load ${frame.language}/${frame.apiLevel} failed: ${e.message ?: e.javaClass.simpleName}"
                        onError(message)
                        CallResult.failed(message)
                    }
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.LoadResult(frame.id, answer), transport.outbound()))
            }
            is PluginProtocol.Frame.Dispatch -> runOnEngine {
                report("dispatch") { engine.dispatch(frame.event) }
            }
            is PluginProtocol.Frame.Eval -> runOnEngine {
                // The caller is blocked on this, so a throw must come back as an answer.
                val answer =
                    try {
                        CallResult.of(engine.eval(frame.source))
                    } catch (e: Exception) {
                        CallResult.failed(e.message ?: e.javaClass.simpleName)
                    }
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.EvalResult(frame.id, answer), transport.outbound()))
            }
            is PluginProtocol.Frame.Close -> close()
            is PluginProtocol.Frame.Result -> pendingCalls.remove(frame.id)?.complete(frame.result)
            else -> Unit // HostCall/EvalResult are outbound only from the plugin side
        }
    }

    /** Hands work to the engine thread, tolerating a frame that raced [close]. */
    private fun runOnEngine(block: () -> Unit) {
        try {
            engineExecutor.submit(block)
        } catch (_: RejectedExecutionException) {
            // Closed underneath us; nothing to run it against.
        }
    }

    /**
     * One-way work: `submit` files exceptions in a Future nobody reads, so report them here --
     * locally through [onError], and to the host as an Error frame, which is the only place a
     * user can see them.
     */
    private inline fun report(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: Exception) {
            val message = "$what failed: ${e.message ?: e.javaClass.simpleName}"
            onError(message)
            runCatching {
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.Error(message), transport.outbound()))
            }
        }
    }

    /**
     * A host that dies never sends Close, so the plugin must be able to do this itself. Closing
     * twice is the normal path: once for the Close frame, once for the service being destroyed.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        engineExecutor.submit { runCatching { engine.close() } }
        engineExecutor.shutdown()
        transport.close()
    }
}
