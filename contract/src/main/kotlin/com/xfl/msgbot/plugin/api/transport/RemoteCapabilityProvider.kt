/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.CapabilityException
import com.xfl.msgbot.plugin.api.provider.ProviderEventSink
import com.xfl.msgbot.plugin.api.provider.ProviderCall
import com.xfl.msgbot.plugin.api.provider.ProviderEvent
import com.xfl.msgbot.plugin.api.provider.CapabilityProvider
import com.xfl.msgbot.plugin.api.provider.ProviderDescriptor
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side [CapabilityProvider] proxy for a source living in another process. The host treats a remote
 * source exactly like a builtin one, which is what lets the notification source stay in-process
 * while an official-API source ships as a plugin.
 *
 * [descriptor] is only known once the source describes itself: call [awaitDescriptor] first, and
 * treat a null answer as "no source", not as one with nothing to offer.
 */
class RemoteCapabilityProvider(
    private val transport: PluginTransport,
    private val onError: (String) -> Unit = {},
) : CapabilityProvider {
    private val describeFuture = CompletableFuture<ProviderDescriptor>()
    private val callSeq = AtomicLong(0)
    private val pendingCalls = ConcurrentHashMap<Long, CompletableFuture<CallResult>>()
    private val closed = AtomicBoolean(false)

    @Volatile private var sink: ProviderEventSink? = null

    @Volatile private var current: ProviderDescriptor? = null

    /** Valid only after [awaitDescriptor] has returned non-null. */
    override val descriptor: ProviderDescriptor
        get() = checkNotNull(current) { "the provider has not described itself yet" }

    init {
        transport.setListener(::onFrame)
    }

    /** Blocks until the provider describes itself, or null on a deadline. An init error is thrown. */
    fun awaitDescriptor(timeoutMs: Long = 5_000): ProviderDescriptor? =
        try {
            describeFuture.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (e: ExecutionException) {
            throw IllegalStateException("Provider failed to initialize: ${e.cause?.message}", e.cause)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }

    override fun bindSink(sink: ProviderEventSink) {
        this.sink = sink
    }

    /** A failure comes back as a [CapabilityException]; the dispatcher turns it into [CallResult.Err]. */
    override fun call(call: ProviderCall): Value {
        val id = callSeq.incrementAndGet()
        val result = request(id, ProviderProtocol.Frame.Call(id, call.projectId, call.method, call.args), call.method)
        return when (result) {
            is CallResult.Ok -> result.value
            is CallResult.Err -> throw CapabilityException(result.code, result.message)
        }
    }

    override fun start() {
        val id = callSeq.incrementAndGet()
        when (val result = request(id, ProviderProtocol.Frame.Start(id), "start")) {
            is CallResult.Ok -> Unit
            is CallResult.Err -> throw IllegalStateException(result.message)
        }
    }

    private fun request(id: Long, frame: ProviderProtocol.Frame, operation: String): CallResult {
        check(!closed.get()) { "Provider connection is closed" }
        val future = CompletableFuture<CallResult>()
        pendingCalls[id] = future
        return try {
            transport.send(ProviderProtocol.encode(frame, transport.outbound()))
            future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            CallResult.failed("Provider did not answer '$operation' within ${CALL_TIMEOUT_MS}ms")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            CallResult.failed("Provider call '$operation' was interrupted")
        } finally {
            pendingCalls.remove(id)
        }
    }

    override fun stop() = transport.send(ProviderProtocol.encode(ProviderProtocol.Frame.Stop, transport.outbound()))

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { stop() }
        pendingCalls.values.forEach { it.complete(CallResult.failed("Provider connection closed")) }
        describeFuture.completeExceptionally(IllegalStateException("Provider connection closed"))
        transport.close()
    }

    private fun onFrame(bytes: ByteArray) {
        if (closed.get()) return
        when (val frame = ProviderProtocol.decode(bytes, transport.inbound())) {
            is ProviderProtocol.Frame.Event -> sink?.emit(ProviderEvent(frame.projectId, frame.event))
            is ProviderProtocol.Frame.CallResult -> pendingCalls.remove(frame.id)?.complete(frame.result)
            is ProviderProtocol.Frame.StartResult -> pendingCalls.remove(frame.id)?.complete(frame.result)
            is ProviderProtocol.Frame.Error -> {
                onError(frame.message)
                describeFuture.completeExceptionally(IllegalStateException(frame.message))
            }
            is ProviderProtocol.Frame.Describe -> {
                current = ProviderDescriptor(frame.providerId, frame.displayName, frame.capabilities, frame.events, frame.namespace)
                describeFuture.complete(current)
            }
            else -> Unit
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
