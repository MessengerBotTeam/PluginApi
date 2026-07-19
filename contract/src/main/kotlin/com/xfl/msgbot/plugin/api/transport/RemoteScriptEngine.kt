/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.HostBridge
import com.xfl.msgbot.plugin.api.engine.EngineDescriptor
import com.xfl.msgbot.plugin.api.engine.ScriptEngine
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side [ScriptEngine] that runs the real engine in a plugin across a [PluginTransport].
 * [load]/[dispatch]/[eval]/[close] serialize to frames; incoming [PluginProtocol.Frame.HostCall]
 * frames are served by the bound [HostBridge] and answered with a Result frame.
 *
 * The transport delivers frames on its own thread, so serving a host-call here never blocks the
 * plugin's engine thread that awaits the result.
 */
class RemoteScriptEngine(
    private val transport: PluginTransport,
    override val descriptor: EngineDescriptor,
    private val callTimeoutMs: Long = 30_000,
) : ScriptEngine {
    private var hostBridge: HostBridge? = null
    private val evalSeq = AtomicLong(0)
    private val pendingEvals = ConcurrentHashMap<Long, CompletableFuture<Value>>()

    init {
        transport.setListener(::onFrame)
    }

    override fun bindHost(bridge: HostBridge) {
        hostBridge = bridge
    }

    override fun load(apiLevel: String, capabilities: List<String>, shim: String, userScript: String) {
        transport.send(PluginProtocol.encode(PluginProtocol.Frame.Load(apiLevel, capabilities, shim, userScript), transport.outbound()))
    }

    override fun dispatch(event: Value.VObject) {
        transport.send(PluginProtocol.encode(PluginProtocol.Frame.Dispatch(event), transport.outbound()))
    }

    override fun eval(source: String): Value {
        val id = evalSeq.incrementAndGet()
        val future = CompletableFuture<Value>()
        pendingEvals[id] = future
        transport.send(PluginProtocol.encode(PluginProtocol.Frame.Eval(id, source), transport.outbound()))
        return try {
            future.get(callTimeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pendingEvals.remove(id)
        }
    }

    override fun close() {
        transport.send(PluginProtocol.encode(PluginProtocol.Frame.Close, transport.outbound()))
        transport.close()
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = PluginProtocol.decode(bytes, transport.inbound())) {
            is PluginProtocol.Frame.HostCall -> {
                val result = hostBridge?.call(frame.method, frame.args) ?: Value.VNull
                transport.send(PluginProtocol.encode(PluginProtocol.Frame.Result(frame.id, result), transport.outbound()))
            }
            is PluginProtocol.Frame.EvalResult -> pendingEvals.remove(frame.id)?.complete(frame.value)
            else -> Unit // Load/Dispatch/Eval/Close/Result are inbound only on the plugin side
        }
    }
}
