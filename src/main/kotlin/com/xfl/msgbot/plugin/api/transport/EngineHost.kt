/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
     * Supplies this plugin's own shim for an apiLevel, used when the host sends an empty shim
     * (i.e. the host has no shim for this plugin's language). Returning null means "no shim".
     */
    private val shimProvider: (apiLevel: String) -> String? = { null },
) {
    private val engineExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-engine") }
    private val callSeq = AtomicLong(0)
    private val pendingCalls = ConcurrentHashMap<Long, CompletableFuture<Value>>()

    @Volatile private lateinit var engine: ScriptEngine

    private val proxyBridge =
        HostBridge { method, args ->
            val id = callSeq.incrementAndGet()
            val future = CompletableFuture<Value>()
            pendingCalls[id] = future
            transport.send(PluginProtocol.encode(PluginProtocol.Frame.HostCall(id, method, args)))
            try {
                future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                pendingCalls.remove(id)
            }
        }

    init {
        transport.setListener(::onFrame)
        engineExecutor.submit {
            engine = engineFactory()
            engine.bindHost(proxyBridge)
        }
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = PluginProtocol.decode(bytes)) {
            is PluginProtocol.Frame.Load -> engineExecutor.submit {
                // Empty shim => the host has none for our language; fall back to our own.
                val shim = frame.shim.ifEmpty { shimProvider(frame.apiLevel).orEmpty() }
                engine.load(frame.apiLevel, frame.capabilities, shim, frame.userScript)
            }
            is PluginProtocol.Frame.Dispatch -> engineExecutor.submit { engine.dispatch(frame.event) }
            is PluginProtocol.Frame.Eval -> engineExecutor.submit {
                val result = engine.eval(frame.source)
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.EvalResult(frame.id, result)))
            }
            is PluginProtocol.Frame.Close -> engineExecutor.submit {
                engine.close()
                engineExecutor.shutdown()
                transport.close()
            }
            is PluginProtocol.Frame.Result -> pendingCalls.remove(frame.id)?.complete(frame.value)
            else -> Unit // HostCall/EvalResult are outbound only from the plugin side
        }
    }
}
