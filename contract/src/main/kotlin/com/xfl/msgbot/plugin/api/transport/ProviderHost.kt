/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.CapabilityException
import com.xfl.msgbot.plugin.api.provider.CapabilityProvider
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives one provider on a dedicated thread. The provider and language engine never share state.
 */
class ProviderHost(
    private val transport: PluginTransport,
    providerFactory: () -> CapabilityProvider,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-provider") }
    private val closed = AtomicBoolean(false)
    private val providerClosed = AtomicBoolean(false)

    @Volatile private lateinit var provider: CapabilityProvider

    init {
        transport.setListener(::onFrame)
        executor.execute {
            try {
                provider = providerFactory()
                if (closed.get()) {
                    closeProvider()
                    return@execute
                }
                provider.bindSink { event ->
                    if (!closed.get()) transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.Event(event.projectId, event.payload), transport.outbound()))
                }
                val d = provider.descriptor
                transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.Describe(d.providerId, d.displayName, d.capabilities, d.events, d.namespace), transport.outbound()))
            } catch (e: Exception) {
                runCatching {
                    transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.Error("provider init failed: ${e.message ?: e.javaClass.simpleName}"), transport.outbound()))
                }
            }
        }
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed.get()) return
        when (val frame = ProviderProtocol.decode(bytes, transport.inbound())) {
            is ProviderProtocol.Frame.Start -> submit {
                val result = try {
                    provider.start()
                    CallResult.of(com.xfl.msgbot.plugin.api.value.Value.VNull)
                } catch (e: Exception) {
                    CallResult.failed(e.message ?: e.javaClass.simpleName)
                }
                transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.StartResult(frame.id, result), transport.outbound()))
            }
            is ProviderProtocol.Frame.Stop -> submit {
                runCatching { provider.stop() }.onFailure { e ->
                    transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.Error("provider stop failed: ${e.message ?: e.javaClass.simpleName}"), transport.outbound()))
                }
            }
            is ProviderProtocol.Frame.Call -> submit {
                // The caller is blocked on this, so a throw must come back as an answer.
                val result =
                    try {
                        CallResult.of(provider.call(ProviderCall(frame.projectId, frame.method, frame.args)))
                    } catch (e: CapabilityException) {
                        CallResult.Err(e.code, e.message ?: e.code)
                    } catch (e: Exception) {
                        CallResult.failed(e.message ?: e.javaClass.simpleName)
                    }
                transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.CallResult(frame.id, result), transport.outbound()))
            }
            else -> Unit
        }
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // The session has already closed.
        }
    }

    private fun closeProvider() {
        if (this::provider.isInitialized && providerClosed.compareAndSet(false, true)) {
            runCatching { provider.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        submit(::closeProvider)
        executor.shutdown()
        transport.close()
    }
}
