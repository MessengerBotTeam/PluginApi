/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

import com.xfl.msgbot.plugin.api.bridge.CallResult
import com.xfl.msgbot.plugin.api.bridge.CapabilityException
import com.xfl.msgbot.plugin.api.source.EventSink
import com.xfl.msgbot.plugin.api.source.MessageSource
import com.xfl.msgbot.plugin.api.source.SourceDescriptor
import com.xfl.msgbot.plugin.api.value.Value
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Host-side [MessageSource] proxy for a source living in another process. The host treats a remote
 * source exactly like a builtin one, which is what lets the notification source stay in-process
 * while an official-API source ships as a plugin.
 *
 * [descriptor] is only known once the source describes itself: call [awaitDescriptor] first, and
 * treat a null answer as "no source", not as one with nothing to offer.
 */
class RemoteMessageSource(
    private val transport: PluginTransport,
) : MessageSource {
    private val describeFuture = CompletableFuture<SourceDescriptor>()
    private val callSeq = AtomicLong(0)
    private val pendingCalls = ConcurrentHashMap<Long, CompletableFuture<CallResult>>()

    @Volatile private var sink: EventSink? = null

    @Volatile private var current: SourceDescriptor? = null

    /** Valid only after [awaitDescriptor] has returned non-null. */
    override val descriptor: SourceDescriptor
        get() = checkNotNull(current) { "the source has not described itself yet" }

    init {
        transport.setListener(::onFrame)
    }

    /** Blocks until the source describes itself, or null if it never does. */
    fun awaitDescriptor(timeoutMs: Long = 5_000): SourceDescriptor? =
        runCatching { describeFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

    override fun bindSink(sink: EventSink) {
        this.sink = sink
    }

    /** A failure comes back as a [CapabilityException]; the dispatcher turns it into [CallResult.Err]. */
    override fun call(method: String, args: List<Value>): Value {
        val id = callSeq.incrementAndGet()
        val future = CompletableFuture<CallResult>()
        pendingCalls[id] = future
        transport.send(SourceProtocol.encode(SourceProtocol.Frame.Call(id, method, args), transport.outbound()))
        val result =
            try {
                future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                CallResult.failed("The source did not answer '$method' within ${CALL_TIMEOUT_MS}ms")
            } finally {
                pendingCalls.remove(id)
            }
        return when (result) {
            is CallResult.Ok -> result.value
            is CallResult.Err -> throw CapabilityException(result.code, result.message)
        }
    }

    override fun start() = transport.send(SourceProtocol.encode(SourceProtocol.Frame.Start, transport.outbound()))

    override fun stop() = transport.send(SourceProtocol.encode(SourceProtocol.Frame.Stop, transport.outbound()))

    override fun close() {
        stop()
        transport.close()
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = SourceProtocol.decode(bytes, transport.inbound())) {
            is SourceProtocol.Frame.Event -> sink?.emit(frame.event)
            is SourceProtocol.Frame.CallResult -> pendingCalls.remove(frame.id)?.complete(frame.result)
            is SourceProtocol.Frame.Describe -> {
                current = SourceDescriptor(frame.sourceId, frame.displayName, frame.capabilities, frame.events)
                describeFuture.complete(current)
            }
            else -> Unit // Start/Stop/Call are host-to-source only
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
