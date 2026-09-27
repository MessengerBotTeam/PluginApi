/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-side [ScriptEngine] that runs the real engine in a plugin across a [PluginTransport].
 * [load]/[dispatch]/[eval]/[close] serialize to frames; incoming [PluginProtocol.Frame.HostCall]
 * frames are served by the bound [HostBridge] and answered with a Result frame.
 *
 * The transport delivers frames on its own thread, so serving a host-call here never blocks the
 * plugin's engine thread that awaits the result.
 *
 * [onError] carries failures of one-way work (a dispatch, the engine's startup) that the plugin
 * reports with an Error frame; without it they exist only in the plugin process's log.
 */
class RemoteScriptEngine(
    private val transport: PluginTransport,
    override val descriptor: EngineDescriptor,
    private val callTimeoutMs: Long = 30_000,
    private val onError: (String) -> Unit = {},
) : ScriptEngine {
    private var hostBridge: HostBridge? = null
    private val requestSeq = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<CallResult>>()
    private val closed = AtomicBoolean(false)

    init {
        transport.setListener(::onFrame)
    }

    override fun bindHost(bridge: HostBridge) {
        hostBridge = bridge
    }

    /**
     * Waits for the plugin to answer: a script that cannot load must fail here, where the compile
     * that sent it can still say so, not on the first message.
     *
     * @throws IllegalStateException with what the engine said.
     */
    override fun load(
        language: String,
        capabilities: List<String>,
        shim: String,
        userScript: String,
        options: Map<String, String>,
    ) {
        val result =
            request { id -> PluginProtocol.Frame.Load(id, language, capabilities, shim, userScript, options) }
                ?: throw IllegalStateException("The plugin did not answer the load within ${callTimeoutMs}ms")
        if (result is CallResult.Err) throw IllegalStateException("load failed: ${result.message}")
    }

    override fun dispatch(event: Value.VObject) {
        val result = request { id -> PluginProtocol.Frame.Dispatch(event, id) }
            ?: throw IllegalStateException("The plugin did not finish dispatch within ${callTimeoutMs}ms")
        if (result is CallResult.Err) throw IllegalStateException("dispatch failed: ${result.message}")
    }

    /** @throws IllegalStateException with what the engine said. */
    override fun eval(source: String): Value {
        val result =
            request { id -> PluginProtocol.Frame.Eval(id, source) }
                ?: throw IllegalStateException("The plugin did not answer the eval within ${callTimeoutMs}ms")
        return when (result) {
            is CallResult.Ok -> result.value
            is CallResult.Err -> throw IllegalStateException("eval failed: ${result.message}")
        }
    }

    /** Sends the frame [make] builds and waits for its answer; null when none came in time. */
    private fun request(make: (id: Long) -> PluginProtocol.Frame): CallResult? {
        check(!closed.get()) { "Engine connection is closed" }
        val id = requestSeq.incrementAndGet()
        val future = CompletableFuture<CallResult>()
        pending[id] = future
        return try {
            check(!closed.get()) { "Engine connection is closed" }
            transport.send(PluginProtocol.encode(make(id), transport.outbound()))
            future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            null
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Whoever is waiting would otherwise sit out the full timeout against a closed transport.
        pending.values.forEach { it.complete(CallResult.failed("The engine connection was closed")) }
        try {
            transport.send(PluginProtocol.encode(PluginProtocol.Frame.Close, transport.outbound()))
        } finally {
            transport.close()
        }
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed.get()) return
        when (val frame = PluginProtocol.decode(bytes, transport.inbound())) {
            is PluginProtocol.Frame.HostCall -> {
                // No bridge is the host's own bug; "no such capability" would blame the script.
                val result =
                    hostBridge?.let { serve(it, frame) }
                        ?: CallResult.failed("The host is not ready to answer '${frame.method}'")
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.Result(frame.id, result), transport.outbound()))
            }
            is PluginProtocol.Frame.LoadResult -> pending.remove(frame.id)?.complete(frame.result)
            is PluginProtocol.Frame.EvalResult -> pending.remove(frame.id)?.complete(frame.result)
            is PluginProtocol.Frame.DispatchResult -> pending.remove(frame.id)?.complete(frame.result)
            is PluginProtocol.Frame.Error -> onError(frame.message)
            else -> Unit // Load/Dispatch/Eval/Close/Result are inbound only on the plugin side
        }
    }

    /** Nothing else sends the Result frame, so even a throwing bridge must be turned into one. */
    private fun serve(
        bridge: HostBridge,
        frame: PluginProtocol.Frame.HostCall,
    ): CallResult =
        try {
            bridge.call(frame.method, frame.args)
        } catch (e: Exception) {
            CallResult.failed("Capability '${frame.method}' failed: ${e.message ?: e.javaClass.simpleName}")
        }
}
