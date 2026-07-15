/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (C) 2025 naijun0403
 */

package com.xfl.msgbot.plugin.api.transport

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
 * [descriptor] is only known after the source describes itself, so callers should await
 * [awaitDescriptor] before advertising this source's capabilities.
 */
class RemoteMessageSource(
    private val transport: PluginTransport,
    fallbackDescriptor: SourceDescriptor,
) : MessageSource {
    private val describeFuture = CompletableFuture<SourceDescriptor>()
    private val callSeq = AtomicLong(0)
    private val pendingCalls = ConcurrentHashMap<Long, CompletableFuture<Value>>()

    @Volatile private var sink: EventSink? = null

    @Volatile private var current: SourceDescriptor = fallbackDescriptor

    override val descriptor: SourceDescriptor get() = current

    init {
        transport.setListener(::onFrame)
    }

    /** Blocks until the source describes itself, falling back to the manifest's view on timeout. */
    fun awaitDescriptor(timeoutMs: Long = 5_000): SourceDescriptor =
        runCatching { describeFuture.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrElse { current }

    override fun bindSink(sink: EventSink) {
        this.sink = sink
    }

    override fun call(method: String, args: List<Value>): Value {
        val id = callSeq.incrementAndGet()
        val future = CompletableFuture<Value>()
        pendingCalls[id] = future
        transport.send(SourceProtocol.encode(SourceProtocol.Frame.Call(id, method, args)))
        return try {
            future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Value.VNull
        } finally {
            pendingCalls.remove(id)
        }
    }

    override fun start() = transport.send(SourceProtocol.encode(SourceProtocol.Frame.Start))

    override fun stop() = transport.send(SourceProtocol.encode(SourceProtocol.Frame.Stop))

    override fun close() {
        stop()
        transport.close()
    }

    private fun onFrame(bytes: ByteArray) {
        when (val frame = SourceProtocol.decode(bytes)) {
            is SourceProtocol.Frame.Event -> sink?.emit(frame.event)
            is SourceProtocol.Frame.CallResult -> pendingCalls.remove(frame.id)?.complete(frame.value)
            is SourceProtocol.Frame.Describe -> {
                current = SourceDescriptor(frame.sourceId, frame.displayName, frame.capabilities)
                describeFuture.complete(current)
            }
            else -> Unit // Start/Stop/Call are host-to-source only
        }
    }

    private companion object {
        const val CALL_TIMEOUT_MS = 15_000L
    }
}
